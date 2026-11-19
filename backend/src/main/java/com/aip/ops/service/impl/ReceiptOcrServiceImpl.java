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
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final long OSD_TIMEOUT_SECONDS = 5;
    private static final long COLOR_TIMEOUT_SECONDS = 15;
    private static final long FULL_TIMEOUT_SECONDS = 30;
    /** 多方向彩色扫描总预算，避免模糊图片在 4 个方向上来回超时。 */
    private static final long COLOR_SCAN_BUDGET_SECONDS = 30;
    /** 方向检测失败/无候选时的兜底旋转顺序（先 OSD 角度，再依次尝试）。 */
    private static final int[] FALLBACK_ROTATIONS = {0, 90, 270, 180};

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
        // 手机竖拍/横拍会导致票面旋转：先由 Tesseract OSD 检测纠正角度，
        // OSD 失败或无候选时再按 0/90/270/180 回退尝试。
        Scan scan = scanRotations(source, detectRotation(path));
        ColorScan colors = scan.colors();
        // psm 11 与 psm 7 对同一 7 位数字给出不同结果时，绝不自动采用
        if (colors.uncertain()) {
            ReceiptOcrResult result = reviewResult(colors);
            logCandidates(attachmentId, corpId,
                    colors.redCandidates(), colors.blueCandidates(), result);
            return result;
        }
        Path rotatedFile = null;
        try {
            // 只有红/蓝都没候选、需要全图 OCR 时才落盘旋转图，避免每张图都写大 PNG
            if (colors.redCandidates().isEmpty() && colors.blueCandidates().isEmpty()
                    && scan.rotation() != 0) {
                rotatedFile = writeTemp(scan.image(), "receipt-rot-");
            }
            Path fullPath = rotatedFile != null ? rotatedFile : path;
            ReceiptOcrResult result = buildResult(fullPath,
                    colors.redCandidates(), colors.blueCandidates());
            logCandidates(attachmentId, corpId,
                    colors.redCandidates(), colors.blueCandidates(), result);
            return result;
        } finally {
            deleteTemp(rotatedFile);
        }
    }

    /** 方向检测：Tesseract OSD 输出 Rotate: 0/90/180/270；失败返回 0。 */
    private int detectRotation(Path path) {
        String text = runTesseract(path, "osd", OSD_TIMEOUT_SECONDS, "--psm", "0");
        if (text == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("Rotate:\\s*(\\d+)").matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        int degrees = Integer.parseInt(matcher.group(1));
        return switch (degrees) {
            case 0, 90, 180, 270 -> degrees;
            default -> 0;
        };
    }

    /** 优先 OSD 角度；该角度无高置信 7 位候选时依次尝试其它方向。 */
    private Scan scanRotations(BufferedImage source, int initial) {
        Scan firstWeak = null;
        Set<Integer> tried = new HashSet<>();
        int[] order = rotationOrder(initial);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COLOR_SCAN_BUDGET_SECONDS);
        for (int angle : order) {
            if (System.nanoTime() > deadline || !tried.add(angle)) {
                continue;
            }
            BufferedImage image = rotate(source, angle);
            ColorScan scan = scanColors(image);
            if (scan.hasConfident()) {
                return new Scan(scan, image, angle);
            }
            if (firstWeak == null && scan.hasAny()) {
                firstWeak = new Scan(scan, image, angle);
            }
        }
        return firstWeak != null ? firstWeak
                : new Scan(ColorScan.empty(), rotate(source, initial), initial);
    }

    private static int[] rotationOrder(int initial) {
        int[] order = new int[FALLBACK_ROTATIONS.length + 1];
        order[0] = initial;
        System.arraycopy(FALLBACK_ROTATIONS, 0, order, 1, FALLBACK_ROTATIONS.length);
        return order;
    }

    private ColorScan scanColors(BufferedImage source) {
        return new ColorScan(
                recognizeColor(source, ReceiptColorDigitExtractor.Color.RED),
                recognizeColor(source, ReceiptColorDigitExtractor.Color.BLUE));
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

    /**
     * 单一颜色：彩色掩膜 → psm 11（稀疏文本）+ psm 7（单行）双模式。
     * 两种模式都命中的最佳 7 位数字不一致时返回 uncertain，由上层清空自动填入
     * 并提示手工核对（现场红票 0002304 曾被 psm 11 读成 0002306）。
     */
    private ColorAnalysis recognizeColor(
            BufferedImage source, ReceiptColorDigitExtractor.Color color) {
        Path maskFile = null;
        try {
            BufferedImage mask = ReceiptColorDigitExtractor.buildMask(source, color);
            if (mask == null) {
                return ColorAnalysis.empty();
            }
            maskFile = Files.createTempFile("receipt-" + color.name().toLowerCase(Locale.ROOT) + "-", ".png");
            if (!ImageIO.write(mask, "png", maskFile.toFile())) {
                return ColorAnalysis.empty();
            }
            List<ReceiptColorDigitExtractor.Candidate> psm11 =
                    recognizeMask(maskFile, source.getHeight(), "11");
            List<ReceiptColorDigitExtractor.Candidate> psm7 =
                    recognizeMask(maskFile, source.getHeight(), "7");
            List<ReceiptColorDigitExtractor.Candidate> all = new ArrayList<>(psm11);
            all.addAll(psm7);
            String best11 = bestSeven(psm11);
            String best7 = bestSeven(psm7);
            boolean uncertain = best11 != null && best7 != null && !best11.equals(best7);
            String review = uncertain ? colorLabel(color) + "收据号识别不一致（"
                    + best11 + " / " + best7 + "），请对照收据手工填写" : null;
            return new ColorAnalysis(all, uncertain, review);
        } catch (Exception e) {
            // 彩色提取是增强能力：任何异常都降级到全图 OCR，不影响制单
            log.warn("收据{}数字识别失败，回退全图 OCR：{}", colorLabel(color), e.getMessage());
            return ColorAnalysis.empty();
        } finally {
            deleteTemp(maskFile);
        }
    }

    private static String bestSeven(List<ReceiptColorDigitExtractor.Candidate> candidates) {
        ReceiptColorDigitExtractor.Candidate best = ReceiptColorDigitExtractor.choose(candidates);
        return best != null && best.digits().length() == 7 ? best.digits() : null;
    }

    private static String colorLabel(ReceiptColorDigitExtractor.Color color) {
        return color == ReceiptColorDigitExtractor.Color.RED ? "红色" : "蓝色";
    }

    private static boolean hasConfidentSeven(
            List<ReceiptColorDigitExtractor.Candidate> candidates) {
        return candidates.stream().anyMatch(c -> c.digits().length() == 7 && c.upper()
                && c.confidence() >= ReceiptOcrResultBuilder.MIN_CONFIDENCE);
    }

    /** 双模式不一致：返回空 receiptNo，前端保留候选提示并强制手工填写。 */
    private ReceiptOcrResult reviewResult(ColorScan colors) {
        return new ReceiptOcrResult(null, null, null, preferredColor(),
                "识别结果不一致，请手工核对", false, false, true, "LOW", "REVIEW",
                reviewMessage(colors), reviewCandidates(colors));
    }

    private static String reviewMessage(ColorScan colors) {
        List<String> parts = new ArrayList<>();
        if (colors.red().reviewMessage() != null) {
            parts.add(colors.red().reviewMessage());
        }
        if (colors.blue().reviewMessage() != null) {
            parts.add(colors.blue().reviewMessage());
        }
        return String.join("；", parts);
    }

    private static List<ReceiptOcrResult.Candidate> reviewCandidates(ColorScan colors) {
        List<ReceiptOcrResult.Candidate> result = new ArrayList<>();
        appendReview(result, colors.redCandidates(), "RED");
        appendReview(result, colors.blueCandidates(), "BLUE");
        return result;
    }

    private static void appendReview(List<ReceiptOcrResult.Candidate> result,
                                     List<ReceiptColorDigitExtractor.Candidate> candidates,
                                     String color) {
        Set<String> seen = new HashSet<>();
        for (ReceiptColorDigitExtractor.Candidate candidate
                : ReceiptColorDigitExtractor.ranked(candidates)) {
            if (!seen.add(candidate.digits())) {
                continue;
            }
            if (seen.size() > 3) {
                break;
            }
            result.add(new ReceiptOcrResult.Candidate(color, candidate.digits(),
                    "识别模式不一致", candidate.confidence(), false));
        }
    }

    /** 顺时针旋转；90/270 交换宽高，白底填充。 */
    private static BufferedImage rotate(BufferedImage source, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized == 0) {
            return source;
        }
        boolean swap = normalized % 180 != 0;
        int width = swap ? source.getHeight() : source.getWidth();
        int height = swap ? source.getWidth() : source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.translate(width / 2.0, height / 2.0);
        g.rotate(Math.toRadians(normalized));
        g.translate(-source.getWidth() / 2.0, -source.getHeight() / 2.0);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return out;
    }

    private static Path writeTemp(BufferedImage image, String prefix) {
        try {
            Path file = Files.createTempFile(prefix, ".png");
            if (ImageIO.write(image, "png", file.toFile())) {
                return file;
            }
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("收据旋转临时文件写入失败：{}", e.getMessage());
        }
        return null;
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

    private record ColorAnalysis(List<ReceiptColorDigitExtractor.Candidate> candidates,
                                 boolean uncertain, String reviewMessage) {
        static ColorAnalysis empty() {
            return new ColorAnalysis(List.of(), false, null);
        }
    }

    private record ColorScan(ColorAnalysis red, ColorAnalysis blue) {
        static ColorScan empty() {
            return new ColorScan(ColorAnalysis.empty(), ColorAnalysis.empty());
        }

        List<ReceiptColorDigitExtractor.Candidate> redCandidates() {
            return red.candidates();
        }

        List<ReceiptColorDigitExtractor.Candidate> blueCandidates() {
            return blue.candidates();
        }

        boolean hasAny() {
            return !redCandidates().isEmpty() || !blueCandidates().isEmpty();
        }

        boolean uncertain() {
            return red.uncertain() || blue.uncertain();
        }

        boolean hasConfident() {
            return !uncertain() && (confident(red) || confident(blue));
        }

        private static boolean confident(ColorAnalysis analysis) {
            return !analysis.uncertain() && hasConfidentSeven(analysis.candidates());
        }
    }

    private record Scan(ColorScan colors, BufferedImage image, int rotation) {
    }
}
