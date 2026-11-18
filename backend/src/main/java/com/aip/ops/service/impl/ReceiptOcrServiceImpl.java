package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.ops.service.ReceiptOcrService;
import com.aip.ops.util.ReceiptNoParser;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 收据号 OCR：服务端调用 Tesseract（镜像内安装 chi_sim+eng），再按
 * 「入账日期 + NO. + 7 位数字」规则解析。
 *
 * <p>平台 AI 目前只有文本对话/ASR/TTS，无视觉 OCR 技能；按需求允许自行安装
 * 组件，故 OCR 在 ops 后端本地完成，识别接口与实现隔离，后续可平滑替换为
 * 平台视觉服务。超时 15s；任何失败都返回手填提示，不阻塞制单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptOcrServiceImpl implements ReceiptOcrService {

    private static final long TIMEOUT_SECONDS = 15;

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

        boolean acquired;
        try {
            acquired = ocrSlots.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.of("识别任务繁忙，请稍后重试");
        }
        if (!acquired) {
            throw BusinessException.of("识别任务繁忙，请稍后重试");
        }
        String text;
        try {
            text = runTesseract(path, "chi_sim+eng");
            if (text == null || text.isBlank()) {
                text = runTesseract(path, "eng");
            }
        } finally {
            ocrSlots.release();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            result.put("receiptNo", null);
            result.put("message", "未能识别收据号，请手动填写");
            return result;
        }
        ReceiptNoParser.Result parsed = ReceiptNoParser.parse(text);
        if (parsed == null) {
            result.put("receiptNo", null);
            result.put("message", "未能识别收据号，请手动填写");
            return result;
        }
        result.put("receiptNo", parsed.receiptNo());
        result.put("exact", parsed.exact());
        result.put("multiple", parsed.multiple());
        result.put("confidence", parsed.exact()
                ? (parsed.multiple() ? "MEDIUM" : "HIGH") : "LOW");
        if (!parsed.exact()) {
            result.put("message", "识别结果可能不准确，请核对后修改");
        } else if (parsed.multiple()) {
            result.put("message", "识别到多个候选，已填入第一个，请核对");
        } else {
            result.put("message", "已从收据图片自动识别，请核对");
        }
        return result;
    }

    /**
     * 执行 tesseract；返回 OCR 文本。
     * 二进制不存在/语言包缺失/超时都只记日志并返回 null，由上层给手填提示。
     */
    private String runTesseract(Path path, String languages) {
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tesseract", path.toString(), "stdout",
                    "-l", languages, "--psm", "6");
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (IOException e) {
            // 与“没识别到号码”区分仅用于日志；前端统一走手填兜底
            log.warn("Tesseract 不可用（未安装或不在 PATH）：{}", e.getMessage());
            return null;
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("收据 OCR 超时 path={} lang={}", path.getFileName(), languages);
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                log.warn("收据 OCR 退出码={} lang={} outputLen={}", process.exitValue(),
                        languages, output.length());
                return null;
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        } catch (IOException e) {
            log.warn("读取 OCR 输出失败: {}", e.getMessage());
            return null;
        }
    }
}
