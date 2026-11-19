package com.aip.ops.service.impl;

import com.aip.ops.dto.ReceiptOcrFeedbackRequest;
import com.aip.ops.dto.ReceiptOcrResult;
import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.ops.service.ReceiptOcrService;
import com.aip.ops.util.ReceiptColorDigitExtractor;
import com.aip.ops.util.ReceiptNoParser;
import com.aip.ops.util.ReceiptOcrResultBuilder;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 收据号 OCR：同时识别红色与蓝色数字，按配置选择默认候选，并记录采用结果。
 *
 * <p>业务背景：收据在「入账日期」后的 NO. 附近可能同时印有红色与蓝色两组
 * 7 位数字。灰度 OCR 对彩色数字对比度不足，因此分别生成红/蓝掩膜后各跑一次
 * Tesseract 数字白名单；两种颜色都存在且不一致时返回两组候选并置
 * {@code needsConfirm=true}，由前端提示员工确认，禁止静默选择。</p>
 *
 * <p>平台 AI 目前只有文本对话/ASR/TTS，无视觉 OCR 技能；按需求允许自行安装
 * 组件，故 OCR 在 ops 后端本地完成，识别接口与实现隔离，后续可平滑替换为
 * 平台视觉服务。收据图片按原图保存/识别（不压缩），彩色识别 15s、全图兜底 30s；
 * 任何失败都返回手填提示，不阻塞制单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptOcrServiceImpl implements ReceiptOcrService {

    private static final long COLOR_TIMEOUT_SECONDS = 15;
    private static final long FULL_TIMEOUT_SECONDS = 30;

    private final BusinessAttachmentService attachmentService;

    /** 并发上限：OCR 为 CPU 密集的外部进程，限制并发避免拖垮请求线程池。 */
    private final Semaphore ocrSlots = new Semaphore(2);

    @Value("${app.receipt-ocr.preferred-color:RED}")
    private String preferredColorRaw;

    @PostConstruct
    void logConfig() {
        log.info("RECEIPT_OCR_CONFIG preferredColor={}", preferredColor());
    }

    @Override
    public ReceiptOcrResult recognize(Long attachmentId) {
        if (attachmentId == null || attachmentId <= 0) {
            throw BusinessException.of("请先上传收据图片");
        }
        Long corpId = requireCorpId();
        OpsBusinessAttachment image = attachmentService.requireOwnedOne(
                attachmentId, OpsBusinessAttachment.TYPE_RECEIPT, corpId);
        Path path = attachmentService.resolveLocalPath(image);
        acquireSlot();
        try {
            return recognizeWithColors(path, attachmentId, corpId);
        } finally {
            ocrSlots.release();
        }
    }

    @Override
    public void logAdoption(ReceiptOcrFeedbackRequest request) {
        Long corpId = requireCorpId();
        if (request == null) {
            log.info("RECEIPT_OCR_ADOPTED corpId={} attachmentId=- red=- blue=- adopted=- manual=false action=-",
                    corpId);
            return;
        }
        Long attachmentId = request.attachmentId();
        if (attachmentId != null && attachmentId > 0) {
            validateFeedbackAttachment(attachmentId, corpId);
        }
        log.info("RECEIPT_OCR_ADOPTED corpId={} attachmentId={} red={} blue={} adopted={} manual={} action={}",
                corpId, attachmentId, safe(request.redReceiptNo()), safe(request.blueReceiptNo()),
                safe(request.adoptedReceiptNo()), Boolean.TRUE.equals(request.manual()),
                safeAction(request.action()));
    }

    private ReceiptOcrResult recognizeWithColors(Path path, Long attachmentId, Long corpId) {
        BufferedImage source = readImage(path);
        if (source == null) {
            return ReceiptOcrResultBuilder.none(preferredColor(), "图片无法识别，请手动填写收据号");
        }
        List<ReceiptColorDigitExtractor.Candidate> red =
                recognizeColor(path, source, ReceiptColorDigitExtractor.Color.RED);
        List<ReceiptColorDigitExtractor.Candidate> blue =
                recognizeColor(path, source, ReceiptColorDigitExtractor.Color.BLUE);
        ReceiptOcrResult result = buildResult(path, red, blue);
        logCandidates(attachmentId, corpId, red, blue, result);
        return result;
    }

    private ReceiptOcrResult buildResult(Path path,
                                         List<ReceiptColorDigitExtractor.Candidate> red,
                                         List<ReceiptColorDigitExtractor.Candidate> blue) {
        if (!red.isEmpty() || !blue.isEmpty()) {
            return ReceiptOcrResultBuilder.build(red, blue, preferredColor());
        }
        String text = recognizeFullText(path);
        if (text == null || text.isBlank()) {
            return ReceiptOcrResultBuilder.none(preferredColor(), "图片无法识别，请手动填写收据号");
        }
        ReceiptNoParser.Result parsed = ReceiptNoParser.parse(text);
        if (parsed == null) {
            return ReceiptOcrResultBuilder.none(preferredColor(),
                    "未定位到 NO. 后的 7 位数字，请手动填写");
        }
        return ReceiptOcrResultBuilder.fallback(parsed, preferredColor());
    }

    /** 单一颜色：彩色掩膜 → Tesseract TSV；psm 7 未命中再回退 psm 11。 */
    private List<ReceiptColorDigitExtractor.Candidate> recognizeColor(
            Path path, BufferedImage source, ReceiptColorDigitExtractor.Color color) {
        Path maskFile = null;
        try {
            BufferedImage mask = ReceiptColorDigitExtractor.buildMask(source, color);
            if (mask == null) {
                return List.of();
            }
            maskFile = Files.createTempFile("receipt-" + color.name().toLowerCase(Locale.ROOT) + "-", ".png");
            if (!ImageIO.write(mask, "png", maskFile.toFile())) {
                return List.of();
            }
            List<ReceiptColorDigitExtractor.Candidate> candidates =
                    recognizeMask(maskFile, source.getHeight(), "7");
            return candidates.isEmpty()
                    ? recognizeMask(maskFile, source.getHeight(), "11") : candidates;
        } catch (Exception e) {
            // 彩色提取是增强能力：任何异常都降级到全图 OCR，不影响制单
            log.warn("收据{}数字识别失败，回退全图 OCR：{}",
                    color == ReceiptColorDigitExtractor.Color.RED ? "红色" : "蓝色", e.getMessage());
            return List.of();
        } finally {
            deleteTemp(maskFile);
        }
    }

    private List<ReceiptColorDigitExtractor.Candidate> recognizeMask(
            Path maskFile, int imageHeight, String psm) {
        String tsv = runTesseract(maskFile, "eng", COLOR_TIMEOUT_SECONDS,
                "--psm", psm,
                "-c", "tessedit_char_whitelist=0123456789", "tsv");
        if (tsv == null || tsv.isBlank()) {
            return List.of();
        }
        return ReceiptColorDigitExtractor.parseTsv(tsv, imageHeight);
    }

    /** 全图兜底：chi_sim+eng 失败后用 eng 再试；仅返回文本，由调用方按 NO. 解析。 */
    private String recognizeFullText(Path path) {
        long started = System.nanoTime();
        String text = runTesseract(path, "chi_sim+eng", FULL_TIMEOUT_SECONDS, "--psm", "6");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        // 慢速失败通常是超时/大图，eng 重跑只会再占一个超时窗口；仅快速失败（如语言包缺失）才降级
        if ((text == null || text.isBlank()) && elapsedMs < 5000) {
            text = runTesseract(path, "eng", FULL_TIMEOUT_SECONDS, "--psm", "6");
        }
        return text;
    }

    private void logCandidates(Long attachmentId, Long corpId,
                               List<ReceiptColorDigitExtractor.Candidate> red,
                               List<ReceiptColorDigitExtractor.Candidate> blue,
                               ReceiptOcrResult result) {
        log.info("RECEIPT_OCR_CANDIDATES corpId={} attachmentId={} red={} blue={} preferred={} "
                        + "recommended={} needsConfirm={} source={}",
                corpId, attachmentId, values(red), values(blue), result.preferredColor(),
                result.receiptNo(), result.needsConfirm(), result.source());
    }

    private void validateFeedbackAttachment(Long attachmentId, Long corpId) {
        try {
            attachmentService.requireOwnedOne(
                    attachmentId, OpsBusinessAttachment.TYPE_RECEIPT, corpId);
        } catch (BusinessException e) {
            // 反馈是审计增强：附件已被删除/更换时不阻塞日志
            log.warn("收据识别反馈附件校验失败 attachmentId={} reason={}",
                    attachmentId, e.getMessage());
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

    private static Long requireCorpId() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请先切换到具体组织");
        }
        return corpId;
    }

    private BufferedImage readImage(Path path) {
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            log.warn("收据图片读取失败 path={}：{}", path.getFileName(), e.getMessage());
            return null;
        }
    }

    private static void deleteTemp(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删除失败不影响识别结果
        }
    }

    private String preferredColor() {
        return "BLUE".equalsIgnoreCase(preferredColorRaw) ? "BLUE" : "RED";
    }

    private static String values(List<ReceiptColorDigitExtractor.Candidate> candidates) {
        String joined = candidates.stream()
                .map(ReceiptColorDigitExtractor.Candidate::digits)
                .distinct().limit(5).collect(Collectors.joining(","));
        return joined.isEmpty() ? "-" : joined;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String clean = value.replaceAll("\\p{Cntrl}", "").trim();
        return clean.length() > 50 ? clean.substring(0, 50) : clean;
    }

    private static String safeAction(String action) {
        if (action == null) {
            return "FINAL";
        }
        String normalized = action.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ACCEPT", "SWITCH", "MANUAL", "FINAL" -> normalized;
            default -> "FINAL";
        };
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
