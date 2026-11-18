package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.ops.service.ReceiptOcrService;
import com.aip.ops.util.ReceiptNoParser;
import com.aip.ops.util.ReceiptRedDigitExtractor;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 收据号 OCR：优先识别收据上的红色数字，失败后回退全图文字识别。
 *
 * <p>业务约定：收据号是收据上红色印刷的数字（通常为右上角 NO. 后的 7 位）。
 * 灰度 OCR 对红字对比度不足，Tesseract 常整段漏识，因此先用
 * {@link ReceiptRedDigitExtractor} 分离红字掩膜，再以 eng + 数字白名单识别；
 * 红字未命中时回退原有「入账日期 + NO. + 7 位数字」全图规则。</p>
 *
 * <p>平台 AI 目前只有文本对话/ASR/TTS，无视觉 OCR 技能；按需求允许自行安装
 * 组件，故 OCR 在 ops 后端本地完成，识别接口与实现隔离，后续可平滑替换为
 * 平台视觉服务。收据图片按原图保存/识别（不压缩），红字识别 20s、全图兜底 30s；
 * 任何失败都返回手填提示，不阻塞制单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptOcrServiceImpl implements ReceiptOcrService {

    private static final long RED_TIMEOUT_SECONDS = 20;
    private static final long FULL_TIMEOUT_SECONDS = 30;
    /** 低于该置信度视为可疑：仍回填，但提示人工核对（OCR 偶发 4/6 混淆）。 */
    private static final double MIN_CONFIDENCE = 60;

    private final BusinessAttachmentService attachmentService;

    /** 并发上限：OCR 为 CPU 密集的外部进程，限制并发避免拖垮请求线程池。 */
    private final Semaphore ocrSlots = new Semaphore(2);

    @Override
    public Map<String, Object> recognize(Long attachmentId) {
        if (attachmentId == null || attachmentId <= 0) {
            throw BusinessException.of("请先上传收据图片");
        }
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请先切换到具体组织后再识别收据");
        }
        OpsBusinessAttachment image = attachmentService.requireOwnedOne(
                attachmentId, OpsBusinessAttachment.TYPE_RECEIPT, corpId);
        Path path = attachmentService.resolveLocalPath(image);

        acquireSlot();
        try {
            Map<String, Object> red = tryRedDigits(path);
            if (red != null) {
                return red;
            }
            return recognizeFullImage(path);
        } finally {
            ocrSlots.release();
        }
    }

    private void acquireSlot() {
        try {
            if (!ocrSlots.tryAcquire(5, TimeUnit.SECONDS)) {
                throw BusinessException.of("识别任务繁忙，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.of("识别任务繁忙，请稍后重试");
        }
    }

    /**
     * 红字优先：红色像素 → 黑字白底掩膜 → Tesseract TSV（数字白名单）。
     * 未识别到候选、图片读取失败或 OCR 异常时返回 null，由调用方回退全图识别。
     */
    private Map<String, Object> tryRedDigits(Path path) {
        Path maskFile = null;
        try {
            BufferedImage source = ImageIO.read(path.toFile());
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return null;
            }
            BufferedImage mask = ReceiptRedDigitExtractor.buildMask(source);
            maskFile = Files.createTempFile("receipt-red-", ".png");
            if (!ImageIO.write(mask, "png", maskFile.toFile())) {
                return null;
            }
            // psm 7（单行）对红字数字最稳；未命中再用 psm 11（稀疏文本）兜底
            List<ReceiptRedDigitExtractor.Candidate> candidates =
                    recognizeRedCandidates(maskFile, source.getHeight());
            ReceiptRedDigitExtractor.Candidate chosen =
                    ReceiptRedDigitExtractor.choose(candidates);
            return chosen == null ? null : buildRedResult(chosen, candidates);
        } catch (Exception e) {
            // 红字提取是增强能力：任何异常都降级到全图 OCR，不影响制单
            log.warn("收据红字识别失败，回退全图 OCR：{}", e.getMessage());
            return null;
        } finally {
            if (maskFile != null) {
                try {
                    Files.deleteIfExists(maskFile);
                } catch (IOException ignored) {
                    // 临时文件删除失败不影响识别结果
                }
            }
        }
    }

    /** psm 7 优先；未命中再回退 psm 11，避免底部红章/多行文字干扰单行模式。 */
    private List<ReceiptRedDigitExtractor.Candidate> recognizeRedCandidates(
            Path maskFile, int imageHeight) {
        List<ReceiptRedDigitExtractor.Candidate> candidates =
                recognizeRedMask(maskFile, imageHeight, "7");
        return candidates.isEmpty()
                ? recognizeRedMask(maskFile, imageHeight, "11") : candidates;
    }

    /** 对红字掩膜执行一次数字白名单 OCR，返回 6~8 位候选。 */
    private List<ReceiptRedDigitExtractor.Candidate> recognizeRedMask(
            Path maskFile, int imageHeight, String psm) {
        String tsv = runTesseract(maskFile, "eng", RED_TIMEOUT_SECONDS,
                "--psm", psm,
                "-c", "tessedit_char_whitelist=0123456789", "tsv");
        if (tsv == null || tsv.isBlank()) {
            return List.of();
        }
        return ReceiptRedDigitExtractor.parseTsv(tsv, imageHeight);
    }

    private Map<String, Object> buildRedResult(ReceiptRedDigitExtractor.Candidate chosen,
                                               List<ReceiptRedDigitExtractor.Candidate> candidates) {
        // 位置可疑（如底部红章流水号）或置信度偏低时降级为 LOW，前端提示人工核对
        boolean exact = chosen.digits().length() == 7 && chosen.upper()
                && chosen.confidence() >= MIN_CONFIDENCE;
        List<ReceiptRedDigitExtractor.Candidate> relevant = candidates.stream()
                .filter(ReceiptRedDigitExtractor.Candidate::upper)
                .toList();
        if (relevant.isEmpty()) {
            relevant = candidates;
        }
        boolean multiple = relevant.stream()
                .map(ReceiptRedDigitExtractor.Candidate::digits)
                .distinct()
                .count() > 1;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("receiptNo", chosen.digits());
        result.put("exact", exact);
        result.put("multiple", multiple);
        result.put("confidence", exact ? (multiple ? "MEDIUM" : "HIGH") : "LOW");
        result.put("source", "RED");
        if (!exact) {
            result.put("message", "识别结果可能不准确，请核对后修改");
        } else if (multiple) {
            result.put("message", "识别到多个红色数字候选，已填入第一个，请核对");
        } else {
            result.put("message", "已从收据图片红色数字识别，请核对");
        }
        return result;
    }

    /** 全图兜底：chi_sim+eng 失败后用 eng 再试，再按「入账日期 + NO.」解析。 */
    private Map<String, Object> recognizeFullImage(Path path) {
        long started = System.nanoTime();
        String text = runTesseract(path, "chi_sim+eng", FULL_TIMEOUT_SECONDS, "--psm", "6");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        // 慢速失败通常是超时/大图，eng 重跑只会再占一个超时窗口；仅快速失败（如语言包缺失）才降级
        if ((text == null || text.isBlank()) && elapsedMs < 5000) {
            text = runTesseract(path, "eng", FULL_TIMEOUT_SECONDS, "--psm", "6");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            result.put("receiptNo", null);
            result.put("message", "未能识别收据号，请手动填写");
            result.put("source", "NONE");
            return result;
        }
        ReceiptNoParser.Result parsed = ReceiptNoParser.parse(text);
        if (parsed == null) {
            result.put("receiptNo", null);
            result.put("message", "未能识别收据号，请手动填写");
            result.put("source", "NONE");
            return result;
        }
        result.put("receiptNo", parsed.receiptNo());
        result.put("exact", parsed.exact());
        result.put("multiple", parsed.multiple());
        result.put("confidence", parsed.exact()
                ? (parsed.multiple() ? "MEDIUM" : "HIGH") : "LOW");
        result.put("source", "FULL");
        if (!parsed.exact()) {
            result.put("message", "识别结果可能不准确，请核对后修改");
        } else if (parsed.multiple()) {
            result.put("message", "识别到多个候选，已填入第一个，请核对");
        } else {
            result.put("message", "已从收据图片自动识别，请核对");
        }
        return result;
    }

    /** 组装 tesseract 命令；参数均为服务端固定值 + 临时文件路径。 */
    private List<String> tesseractCommand(Path path, String languages, String... extraArgs) {
        List<String> command = new ArrayList<>();
        command.add("tesseract");
        command.add(path.toString());
        command.add("stdout");
        command.add("-l");
        command.add(languages);
        if (extraArgs != null) {
            command.addAll(List.of(extraArgs));
        }
        return command;
    }

    /**
     * 执行 tesseract 并返回 stdout；二进制不存在/超时/异常都只记日志并返回 null。
     *
     * <p>输出由独立线程持续读取，避免 OCR 文本超过管道缓冲区时子进程阻塞，
     * 导致 {@code waitFor} 误判超时。</p>
     */
    private String runTesseract(Path path, String languages, long timeoutSeconds,
                                String... extraArgs) {
        Process process = startTesseract(tesseractCommand(path, languages, extraArgs));
        if (process == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread drain = startDrain(process, buffer);
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                log.warn("收据 OCR 超时 path={} lang={}", path.getFileName(), languages);
                return null;
            }
            drain.join(1000);
            if (process.exitValue() != 0) {
                log.warn("收据 OCR 退出码={} lang={} outputLen={}", process.exitValue(),
                        languages, buffer.size());
                return null;
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        }
    }

    private Process startTesseract(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            return pb.start();
        } catch (IOException e) {
            // 与“没识别到号码”区分仅用于日志；前端统一走手填兜底
            log.warn("Tesseract 不可用（未安装或不在 PATH）：{}", e.getMessage());
            return null;
        }
    }

    /** 独立线程持续读取 stdout，避免 OCR 文本超过管道缓冲区时子进程阻塞。 */
    private Thread startDrain(Process process, ByteArrayOutputStream buffer) {
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
                // 进程被强制结束时流会关闭，忽略即可
            }
        }, "receipt-ocr-drain");
        drain.setDaemon(true);
        drain.start();
        return drain;
    }
}
