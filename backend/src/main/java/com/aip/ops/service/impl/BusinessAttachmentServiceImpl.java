package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.mapper.OpsBusinessAttachmentMapper;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.LoginUser;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 业务订单附件实现。
 *
 * <p>图片统一重编码 JPEG（最长边 2000px / 质量 0.85）后落盘，兼顾 OCR 文字清晰度与
 * 存储体积；PDF 只做魔数与大小校验，不压缩内容。路径固定为
 * {@code /uploads/ops/business/{corpId}/{type}/{yyyyMMdd}/...}，不暴露服务器路径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessAttachmentServiceImpl implements BusinessAttachmentService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 2000;
    /** 解码前限制像素数，防压缩炸弹。 */
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final float JPEG_QUALITY = 0.85f;
    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png");
    private static final Set<String> ALLOWED_TYPES =
            Set.of(OpsBusinessAttachment.TYPE_RECEIPT,
                    OpsBusinessAttachment.TYPE_PAYMENT,
                    OpsBusinessAttachment.TYPE_CONTRACT);
    private static final Map<String, Integer> MAX_COUNT = Map.of(
            OpsBusinessAttachment.TYPE_RECEIPT, 1,
            OpsBusinessAttachment.TYPE_PAYMENT, 3,
            OpsBusinessAttachment.TYPE_CONTRACT, 5);

    private final OpsBusinessAttachmentMapper attachmentMapper;

    @Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    @Override
    public OpsBusinessAttachment upload(MultipartFile file, String fileType) {
        String type = normalizeType(fileType);
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请先切换到具体组织后再上传附件");
        }
        LoginUser user = SecurityUtils.getCurrentUser();
        if (file == null || file.isEmpty()) throw BusinessException.of("请选择要上传的文件");
        if (file.getSize() > MAX_FILE_BYTES) {
            throw BusinessException.of("单个文件不能超过10MB");
        }
        String original = sanitizeFileName(file.getOriginalFilename());
        String ext = extension(original);
        if (!allowedExtension(type, ext)) {
            throw BusinessException.of(OpsBusinessAttachment.TYPE_CONTRACT.equals(type)
                    ? "合同附件仅支持 JPG、PNG、PDF"
                    : "仅支持 JPG、PNG 图片");
        }
        byte[] input;
        try {
            input = file.getBytes();
        } catch (IOException e) {
            throw BusinessException.of("读取文件失败，请重试");
        }
        Stored stored = "pdf".equals(ext)
                ? validatePdf(input)
                : compressImage(input);

        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String relative = "ops/business/" + corpId + "/" + type.toLowerCase(Locale.ROOT)
                + "/" + datePart + "/" + UUID.randomUUID().toString().replace("-", "")
                + "." + stored.ext();
        Path dest = Paths.get(uploadBaseDir, relative).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dest.getParent());
            Files.write(dest, stored.bytes());
        } catch (IOException e) {
            log.error("业务附件保存失败 corpId={} type={} name={}", corpId, type, original, e);
            throw BusinessException.of("文件保存失败，请重试");
        }

        OpsBusinessAttachment record = new OpsBusinessAttachment();
        record.setCorpId(corpId);
        record.setFileType(type);
        record.setFileName(original);
        record.setFileUrl("/uploads/" + relative);
        record.setFileSize((long) stored.bytes().length);
        record.setContentType(stored.contentType());
        record.setUploaderUserId(user != null ? user.getUserId() : null);
        record.setUploaderName(user != null ? user.getUsername() : "");
        record.setUploadTime(LocalDateTime.now());
        record.setDeleteFlag(0);
        attachmentMapper.insert(record);
        log.info("业务附件上传成功 corpId={} type={} id={} name={} size={}", corpId, type,
                record.getId(), original, stored.bytes().length);
        return record;
    }

    @Override
    public List<OpsBusinessAttachment> requireOwnedList(List<Long> ids, String fileType, Long corpId) {
        String type = normalizeType(fileType);
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && id > 0) distinct.add(id);
            }
        }
        if (distinct.isEmpty()) throw BusinessException.of(missingMessage(type));
        int max = MAX_COUNT.getOrDefault(type, 1);
        if (distinct.size() > max) {
            throw BusinessException.of(typeLabel(type) + "最多" + max + "个");
        }
        if (OpsBusinessAttachment.TYPE_RECEIPT.equals(type) && distinct.size() != 1) {
            throw BusinessException.of("收据图片只能上传1张");
        }
        List<OpsBusinessAttachment> rows = attachmentMapper.selectBatchIds(distinct);
        Map<Long, OpsBusinessAttachment> byId = new LinkedHashMap<>();
        for (OpsBusinessAttachment row : rows) {
            byId.put(row.getId(), row);
        }
        if (byId.size() != distinct.size()) {
            throw BusinessException.of("附件不存在或已失效，请重新上传");
        }
        LoginUser current = SecurityUtils.getCurrentUser();
        Long currentUserId = current != null ? current.getUserId() : null;
        List<OpsBusinessAttachment> ordered = new ArrayList<>();
        for (Long id : distinct) {
            OpsBusinessAttachment row = byId.get(id);
            if (!corpId.equals(row.getCorpId())) {
                throw BusinessException.of("附件不属于当前组织，请重新上传");
            }
            if (!type.equals(row.getFileType())) {
                throw BusinessException.of("附件类型不匹配，请重新上传");
            }
            // 附件只允许上传人本人在制单/OCR 中引用，避免同事间借用附件 ID
            if (row.getUploaderUserId() != null && currentUserId != null
                    && !row.getUploaderUserId().equals(currentUserId)) {
                throw BusinessException.of("附件不属于当前员工，请重新上传");
            }
            if (row.getOrderId() != null) {
                throw BusinessException.of("附件已关联其他订单，请重新上传");
            }
            ordered.add(row);
        }
        return ordered;
    }

    @Override
    public OpsBusinessAttachment requireOwnedOne(Long id, String fileType, Long corpId) {
        if (id == null || id <= 0) throw BusinessException.of(missingMessage(normalizeType(fileType)));
        return requireOwnedList(List.of(id), fileType, corpId).get(0);
    }

    @Override
    public void bindToOrder(List<Long> ids, Long orderId) {
        if (ids == null || ids.isEmpty() || orderId == null) return;
        LinkedHashSet<Long> distinct = new LinkedHashSet<>(ids);
        int updated = attachmentMapper.update(null, Wrappers.<OpsBusinessAttachment>lambdaUpdate()
                .in(OpsBusinessAttachment::getId, distinct)
                .isNull(OpsBusinessAttachment::getOrderId)
                .set(OpsBusinessAttachment::getOrderId, orderId));
        if (updated != distinct.size()) {
            throw BusinessException.of("附件已被其他订单占用，请重新上传");
        }
    }

    @Override
    public String toUrlCsv(List<OpsBusinessAttachment> files) {
        if (files == null || files.isEmpty()) return null;
        List<String> urls = new ArrayList<>();
        for (OpsBusinessAttachment file : files) {
            if (file != null && file.getFileUrl() != null && !file.getFileUrl().isBlank()) {
                urls.add(file.getFileUrl().trim());
            }
        }
        return urls.isEmpty() ? null : String.join(",", urls);
    }

    @Override
    public Path resolveLocalPath(OpsBusinessAttachment file) {
        if (file == null || file.getFileUrl() == null) {
            throw BusinessException.of("收据图片不存在，请重新上传");
        }
        String url = file.getFileUrl().trim();
        String corpSegment = "/business/" + file.getCorpId() + "/";
        if (!url.startsWith("/uploads/ops/business/") || !url.contains(corpSegment)) {
            throw BusinessException.of("收据图片地址不合法");
        }
        String relative = url.substring("/uploads/".length());
        Path base = Paths.get(uploadBaseDir).toAbsolutePath().normalize();
        Path path = base.resolve(relative).normalize();
        if (!path.startsWith(base)) {
            throw BusinessException.of("收据图片地址不合法");
        }
        if (!Files.isRegularFile(path)) {
            throw BusinessException.of("收据图片不存在，请重新上传");
        }
        return path;
    }

    private static String normalizeType(String fileType) {
        String type = fileType == null ? "" : fileType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw BusinessException.of("不支持的附件类型");
        }
        return type;
    }

    private static boolean allowedExtension(String type, String ext) {
        if (OpsBusinessAttachment.TYPE_CONTRACT.equals(type)) {
            return IMAGE_EXTS.contains(ext) || "pdf".equals(ext);
        }
        return IMAGE_EXTS.contains(ext);
    }

    private static String missingMessage(String type) {
        return switch (type) {
            case OpsBusinessAttachment.TYPE_PAYMENT -> "请上传支付凭证（银行或微信支付凭证）";
            case OpsBusinessAttachment.TYPE_CONTRACT -> "请上传合同附件";
            default -> "请上传收据图片";
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case OpsBusinessAttachment.TYPE_PAYMENT -> "支付凭证";
            case OpsBusinessAttachment.TYPE_CONTRACT -> "合同附件";
            default -> "收据图片";
        };
    }

    /** PDF：只校验魔数与大小，内容原样保存。 */
    private static Stored validatePdf(byte[] bytes) {
        if (bytes.length == 0) throw BusinessException.of("文件为空");
        String header = new String(bytes, 0, Math.min(bytes.length, 5), StandardCharsets.US_ASCII);
        if (!"%PDF-".equals(header)) {
            throw BusinessException.of("PDF 文件格式不合法");
        }
        return new Stored(bytes, "pdf", "application/pdf");
    }

    /**
     * 图片：解码 → 限制像素 → 最长边缩放到 2000px → 白底 JPEG(0.85)。
     * 重编码统一格式，避免 iOS HEIC/透明 PNG 在 Web 端不可预览。
     */
    private static Stored compressImage(byte[] bytes) {
        if (bytes.length == 0) throw BusinessException.of("图片为空");
        int width;
        int height;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) throw BusinessException.of("图片文件格式不合法");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) throw BusinessException.of("图片文件格式不合法");
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw BusinessException.of("图片文件读取失败");
        }
        if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
            throw BusinessException.of("图片尺寸过大，请压缩后重试");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw BusinessException.of("图片文件读取失败");
        }
        if (source == null) throw BusinessException.of("图片文件格式不合法");
        double scale = Math.min(1.0, (double) MAX_IMAGE_DIMENSION / Math.max(width, height));
        int targetW = Math.max(1, (int) Math.round(width * scale));
        int targetH = Math.max(1, (int) Math.round(height * scale));
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetW, targetH);
            g.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        byte[] out = writeJpeg(target);
        if (out == null || out.length == 0) {
            throw BusinessException.of("图片处理失败，请重试");
        }
        return new Stored(out, "jpg", "image/jpeg");
    }

    private static byte[] writeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
        } catch (IOException e) {
            log.warn("图片 JPEG 重编码失败: {}", e.getMessage());
            return null;
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String sanitizeFileName(String name) {
        String value = name == null ? "附件" : name.trim();
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        if (value.isEmpty()) value = "附件";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private record Stored(byte[] bytes, String ext, String contentType) {}
}
