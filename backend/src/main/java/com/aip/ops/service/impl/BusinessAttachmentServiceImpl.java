package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.mapper.OpsBusinessAttachmentMapper;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.ops.service.EmployeeService;
import com.aip.ops.util.VideoDurationValidator;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 业务订单附件实现。
 *
 * <p>收据图片（RECEIPT）原图保存、不压缩，保证红字 OCR 清晰度；支付凭证与合同图片
 * 仍重编码 JPEG（最长边 2000px / 质量 0.85），PDF 只做魔数与大小校验、内容原样。
 * 路径固定为 {@code /uploads/ops/business/{corpId}/{type}/{yyyyMMdd}/...}，不暴露服务器路径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessAttachmentServiceImpl implements BusinessAttachmentService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** 验收短视频上限；nginx / spring multipart 均为 50MB。 */
    private static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;
    /** 验收短视频硬性时长上限（秒）。 */
    private static final int MAX_VIDEO_SECONDS = 30;
    /** 收据需原图保存，手机原图可达十几 MB，单独放宽；nginx 上限 50MB。 */
    private static final long MAX_RECEIPT_BYTES = 20L * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 2000;
    /** 解码前限制像素数，防压缩炸弹（16MP 已高于手机相机常见尺寸）。 */
    private static final long MAX_IMAGE_PIXELS = 16_000_000L;
    /** 收据原图不缩放，允许 24MP 以内（覆盖主流手机 12MP/24MP 模式）。 */
    private static final long MAX_RECEIPT_PIXELS = 24_000_000L;
    /** 单员工 24h 内未绑定附件上限，防止恶意占用共享上传卷。 */
    private static final int MAX_UNBOUND_PER_DAY = 100;
    private static final float JPEG_QUALITY = 0.85f;
    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png");
    private static final Set<String> VIDEO_EXTS = Set.of("mp4", "mov");
    private static final Set<String> ALLOWED_TYPES =
            Set.of(OpsBusinessAttachment.TYPE_RECEIPT,
                    OpsBusinessAttachment.TYPE_PAYMENT,
                    OpsBusinessAttachment.TYPE_CONTRACT,
                    OpsBusinessAttachment.TYPE_REFUND,
                    OpsBusinessAttachment.TYPE_ACCEPTANCE,
                    OpsBusinessAttachment.TYPE_CONSTRUCT);
    private static final Map<String, Integer> MAX_COUNT = Map.of(
            OpsBusinessAttachment.TYPE_RECEIPT, 1,
            OpsBusinessAttachment.TYPE_PAYMENT, 3,
            OpsBusinessAttachment.TYPE_CONTRACT, 5,
            OpsBusinessAttachment.TYPE_REFUND, 5,
            OpsBusinessAttachment.TYPE_ACCEPTANCE, 10,
            OpsBusinessAttachment.TYPE_CONSTRUCT, 3);

    private final OpsBusinessAttachmentMapper attachmentMapper;
    private final EmployeeService employeeService;
    private final VideoDurationValidator videoDurationValidator;

    @Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    @Override
    public OpsBusinessAttachment upload(MultipartFile file, String fileType) {
        assertEmployee();
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请先切换到具体组织后再上传附件");
        }
        LoginUser user = SecurityUtils.getCurrentUser();
        if (user == null || user.getUserId() == null) {
            throw BusinessException.of("登录状态失效，请重新登录");
        }
        return doUpload(file, fileType, corpId, user.getUserId(), user.getUsername());
    }

    @Override
    public OpsBusinessAttachment uploadForUser(MultipartFile file, String fileType, Long corpId,
                                               Long userId, String userName) {
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请先切换到具体组织后再上传附件");
        }
        if (userId == null) {
            throw BusinessException.of("登录状态失效，请重新登录");
        }
        return doUpload(file, fileType, corpId, userId, userName);
    }

    private OpsBusinessAttachment doUpload(MultipartFile file, String fileType, Long corpId,
                                           Long userId, String userName) {
        String type = normalizeType(fileType);
        validateQuota(corpId, userId);
        if (file == null || file.isEmpty()) {
            throw BusinessException.of("请选择要上传的文件");
        }
        String original = sanitizeFileName(file.getOriginalFilename());
        String ext = extension(original);
        if (!allowedExtension(type, ext)) {
            throw BusinessException.of(invalidFormatMessage(type));
        }
        boolean video = OpsBusinessAttachment.TYPE_ACCEPTANCE.equals(type)
                && VIDEO_EXTS.contains(ext);
        validateSize(file, type, video);
        return video
                ? saveVideo(file, type, corpId, userId, userName, original, ext)
                : saveImageOrPdf(file, type, corpId, userId, userName, original, ext);
    }

    private OpsBusinessAttachment saveVideo(MultipartFile file, String type, Long corpId,
                                            Long userId, String userName, String original,
                                            String ext) {
        if (!isMp4Family(file)) {
            throw BusinessException.of(invalidFormatMessage(type));
        }
        Destination dest = destination(corpId, type, ext);
        try {
            Files.createDirectories(dest.path().getParent());
            file.transferTo(dest.path().toFile());
        } catch (IOException | IllegalStateException e) {
            log.error("验收视频保存失败 corpId={} name={}", corpId, original, e);
            throw BusinessException.of("文件保存失败，请重试");
        }
        int duration;
        try {
            duration = videoDurationValidator.requireDurationSeconds(dest.path());
        } catch (RuntimeException e) {
            deleteQuietly(dest.path());
            throw e;
        }
        if (duration > MAX_VIDEO_SECONDS) {
            deleteQuietly(dest.path());
            throw BusinessException.of("ACCEPTANCE_VIDEO_TOO_LONG：短视频时长不能超过 30 秒");
        }
        OpsBusinessAttachment record = buildRecord(corpId, type, original, dest.relative(),
                file.getSize(), "mov".equals(ext) ? "video/quicktime" : "video/mp4",
                userId, userName);
        record.setDurationSeconds(duration);
        return insertRecord(record, dest.path());
    }

    private OpsBusinessAttachment saveImageOrPdf(MultipartFile file, String type, Long corpId,
                                                 Long userId, String userName, String original,
                                                 String ext) {
        byte[] input;
        try {
            input = file.getBytes();
        } catch (IOException e) {
            throw BusinessException.of("读取文件失败，请重试");
        }
        Stored stored;
        if ("pdf".equals(ext)) {
            stored = validatePdf(input);
        } else if (OpsBusinessAttachment.TYPE_RECEIPT.equals(type)) {
            // 收据号是图片中的红色数字，压缩会损失笔画，必须用原图识别/存档
            stored = validateOriginalImage(input);
        } else {
            stored = compressImage(input);
        }
        Destination dest = destination(corpId, type, stored.ext());
        try {
            Files.createDirectories(dest.path().getParent());
            Files.write(dest.path(), stored.bytes());
        } catch (IOException e) {
            log.error("业务附件保存失败 corpId={} type={} name={}", corpId, type, original, e);
            throw BusinessException.of("文件保存失败，请重试");
        }
        OpsBusinessAttachment record = buildRecord(corpId, type, original, dest.relative(),
                (long) stored.bytes().length, stored.contentType(), userId, userName);
        return insertRecord(record, dest.path());
    }

    private OpsBusinessAttachment buildRecord(Long corpId, String type, String original,
                                              String relative, long size, String contentType,
                                              Long userId, String userName) {
        OpsBusinessAttachment record = new OpsBusinessAttachment();
        record.setCorpId(corpId);
        record.setFileType(type);
        record.setFileName(original);
        record.setFileUrl("/uploads/" + relative);
        record.setFileSize(size);
        record.setContentType(contentType);
        record.setUploaderUserId(userId);
        record.setUploaderName(userName == null ? "" : userName);
        record.setUploadTime(LocalDateTime.now());
        record.setDeleteFlag(0);
        return record;
    }

    private OpsBusinessAttachment insertRecord(OpsBusinessAttachment record, Path dest) {
        try {
            if (attachmentMapper.insert(record) <= 0) {
                throw BusinessException.of("附件保存失败，请重试");
            }
        } catch (RuntimeException e) {
            // 先落盘后写库：写库失败必须回删文件，否则清理任务只扫数据库行，永远清不到
            deleteQuietly(dest);
            throw e;
        }
        log.info("业务附件上传成功 corpId={} type={} id={} name={} size={} duration={}",
                record.getCorpId(), record.getFileType(), record.getId(), record.getFileName(),
                record.getFileSize(), record.getDurationSeconds());
        return record;
    }

    private void validateQuota(Long corpId, Long userId) {
        // 配额：同一员工 24h 内未绑定附件最多 100 个，防止恶意占满共享上传卷
        Long pending = attachmentMapper.selectCount(Wrappers.<OpsBusinessAttachment>lambdaQuery()
                .eq(OpsBusinessAttachment::getCorpId, corpId)
                .eq(OpsBusinessAttachment::getUploaderUserId, userId)
                .isNull(OpsBusinessAttachment::getOrderId)
                .ge(OpsBusinessAttachment::getUploadTime, LocalDateTime.now().minusDays(1)));
        if (pending != null && pending >= MAX_UNBOUND_PER_DAY) {
            throw BusinessException.of("上传过于频繁，请稍后重试");
        }
    }

    private void validateSize(MultipartFile file, String type, boolean video) {
        if (video && file.getSize() > MAX_VIDEO_BYTES) {
            throw BusinessException.of("ACCEPTANCE_FILE_TOO_LARGE：视频不能超过 50MB");
        }
        if (video) {
            return;
        }
        long maxBytes = OpsBusinessAttachment.TYPE_RECEIPT.equals(type)
                ? MAX_RECEIPT_BYTES : MAX_FILE_BYTES;
        if (file.getSize() <= maxBytes) {
            return;
        }
        if (OpsBusinessAttachment.TYPE_ACCEPTANCE.equals(type)) {
            throw BusinessException.of("ACCEPTANCE_FILE_TOO_LARGE：图片不能超过 10MB");
        }
        throw BusinessException.of(OpsBusinessAttachment.TYPE_RECEIPT.equals(type)
                ? "收据图片不能超过20MB" : "单个文件不能超过10MB");
    }

    private Destination destination(Long corpId, String type, String ext) {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String relative = "ops/business/" + corpId + "/" + type.toLowerCase(Locale.ROOT)
                + "/" + datePart + "/" + UUID.randomUUID().toString().replace("-", "")
                + "." + ext;
        Path path = Paths.get(uploadBaseDir, relative).toAbsolutePath().normalize();
        return new Destination(path, relative);
    }

    private static boolean isMp4Family(MultipartFile file) {
        try (java.io.InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(8);
            if (header.length < 8) return false;
            String boxType = new String(header, 4, 4, StandardCharsets.US_ASCII);
            // MP4 通常以 ftyp 开头；部分 MOV 以 moov/mdat/wide/free 开头，
            // 真实结构与时长由 VideoDurationValidator 继续校验。
            return Set.of("ftyp", "moov", "mdat", "wide", "free").contains(boxType);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            log.warn("删除孤儿附件文件失败 path={}", path);
        }
    }

    private record Destination(Path path, String relative) {
    }

    @Override
    public List<OpsBusinessAttachment> requireOwnedList(List<Long> ids, String fileType, Long corpId) {
        return requireOwnedList(ids, fileType, corpId, null);
    }

    @Override
    public List<OpsBusinessAttachment> requireOwnedList(List<Long> ids, String fileType, Long corpId,
                                                        Long allowOrderId) {
        LoginUser current = SecurityUtils.getCurrentUser();
        Long currentUserId = current != null ? current.getUserId() : null;
        return requireOwnedListInternal(ids, fileType, corpId, allowOrderId, currentUserId);
    }

    @Override
    public List<OpsBusinessAttachment> requireOwnedListForUploader(List<Long> ids, String fileType,
                                                                   Long corpId, Long allowOrderId,
                                                                   Long uploaderUserId) {
        return requireOwnedListInternal(ids, fileType, corpId, allowOrderId, uploaderUserId);
    }

    private List<OpsBusinessAttachment> requireOwnedListInternal(List<Long> ids, String fileType,
                                                                 Long corpId, Long allowOrderId,
                                                                 Long uploaderUserId) {
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
        List<OpsBusinessAttachment> ordered = new ArrayList<>();
        for (Long id : distinct) {
            OpsBusinessAttachment row = byId.get(id);
            if (!corpId.equals(row.getCorpId())) {
                throw BusinessException.of("附件不属于当前组织，请重新上传");
            }
            if (!type.equals(row.getFileType())) {
                throw BusinessException.of("附件类型不匹配，请重新上传");
            }
            // 附件只允许上传人本人在制单/OCR 中引用；草稿编辑时允许复用已绑定到
            // 当前订单的附件（订单可能由其他经办人上传）
            boolean boundToAllowedOrder = allowOrderId != null
                    && allowOrderId.equals(row.getOrderId());
            if (!boundToAllowedOrder
                    && (row.getUploaderUserId() == null || uploaderUserId == null
                    || !row.getUploaderUserId().equals(uploaderUserId))) {
                throw BusinessException.of("附件不属于当前员工，请重新上传");
            }
            // 编辑草稿时允许引用已绑定到当前订单的附件；其他订单的附件仍拒绝
            if (row.getOrderId() != null
                    && (allowOrderId == null || !allowOrderId.equals(row.getOrderId()))) {
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
    public void bindToOrder(List<Long> ids, Long orderId, Long corpId) {
        if (ids == null || ids.isEmpty() || orderId == null) return;
        if (corpId == null) throw BusinessException.of("附件归属信息缺失");
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        List<OpsBusinessAttachment> rows = attachmentMapper.selectBatchIds(distinct);
        if (rows.size() != distinct.size()) {
            throw BusinessException.of("附件不存在或已失效，请重新上传");
        }
        for (OpsBusinessAttachment row : rows) {
            if (!corpId.equals(row.getCorpId())) {
                throw BusinessException.of("附件不属于当前组织，请重新上传");
            }
            // 已绑定当前订单的附件允许复用（草稿编辑），其他订单仍拒绝
            if (row.getOrderId() != null && !orderId.equals(row.getOrderId())) {
                throw BusinessException.of("附件已被其他订单占用，请重新上传");
            }
        }
        // 只回填未绑定行；已绑定当前订单的行无需更新
        attachmentMapper.update(null, Wrappers.<OpsBusinessAttachment>lambdaUpdate()
                .in(OpsBusinessAttachment::getId, distinct)
                .eq(OpsBusinessAttachment::getCorpId, corpId)
                .isNull(OpsBusinessAttachment::getOrderId)
                .set(OpsBusinessAttachment::getOrderId, orderId));
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

    @Override
    public List<OpsBusinessAttachment> listByOrder(Long orderId) {
        if (orderId == null) return List.of();
        return attachmentMapper.selectList(Wrappers.<OpsBusinessAttachment>lambdaQuery()
                .eq(OpsBusinessAttachment::getOrderId, orderId)
                .eq(OpsBusinessAttachment::getDeleteFlag, 0)
                .orderByAsc(OpsBusinessAttachment::getId));
    }

    @Override
    public void unbindFromOrder(Long orderId, List<Long> ids) {
        if (orderId == null || ids == null || ids.isEmpty()) return;
        attachmentMapper.update(null, Wrappers.<OpsBusinessAttachment>lambdaUpdate()
                .eq(OpsBusinessAttachment::getOrderId, orderId)
                .in(OpsBusinessAttachment::getId, ids)
                .set(OpsBusinessAttachment::getOrderId, null));
    }

    @Override
    public void deleteUnbound(Long id, Long corpId) {
        if (id == null || corpId == null) return;
        assertEmployee();
        LoginUser current = SecurityUtils.getCurrentUser();
        if (current == null || current.getUserId() == null) {
            throw BusinessException.of("登录状态失效，请重新登录");
        }
        deleteUnboundForUser(id, corpId, current.getUserId());
    }

    @Override
    public void deleteUnboundForUser(Long id, Long corpId, Long userId) {
        if (id == null || corpId == null || userId == null) return;
        OpsBusinessAttachment row = attachmentMapper.selectById(id);
        if (row == null || !corpId.equals(row.getCorpId())
                || row.getOrderId() != null
                || !Objects.equals(row.getUploaderUserId(), userId)) {
            throw BusinessException.of("附件不存在或无权删除");
        }
        try {
            Files.deleteIfExists(resolveLocalPath(row));
        } catch (Exception e) {
            log.warn("删除附件文件失败 id={}：{}", id, e.getMessage());
        }
        attachmentMapper.deleteById(id);
    }

    @Override
    public int purgeUnboundBefore(LocalDateTime cutoff, int limit) {
        if (cutoff == null) return 0;
        int safeLimit = Math.min(Math.max(limit, 1), 1000);
        List<OpsBusinessAttachment> rows = attachmentMapper.selectList(
                Wrappers.<OpsBusinessAttachment>lambdaQuery()
                        .isNull(OpsBusinessAttachment::getOrderId)
                        .lt(OpsBusinessAttachment::getUploadTime, cutoff)
                        .orderByAsc(OpsBusinessAttachment::getId)
                        .last("LIMIT " + safeLimit));
        int count = 0;
        for (OpsBusinessAttachment row : rows) {
            try {
                Files.deleteIfExists(resolveLocalPath(row));
            } catch (Exception e) {
                log.warn("清理附件文件失败 id={}：{}", row.getId(), e.getMessage());
            }
            attachmentMapper.deleteById(row.getId());
            count++;
        }
        return count;
    }

    private void assertEmployee() {
        // Controller 已拒绝 token_type=sso；这里再确认能解析员工手机号，
        // 防止主站管理员令牌（tokenType=null）向组织上传目录写入附件。
        employeeService.getCurrentAgentPhone();
    }

    private static String normalizeType(String fileType) {
        String type = fileType == null ? "" : fileType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw BusinessException.of("不支持的附件类型");
        }
        return type;
    }

    private static boolean allowedExtension(String type, String ext) {
        if (OpsBusinessAttachment.TYPE_ACCEPTANCE.equals(type)) {
            return IMAGE_EXTS.contains(ext) || VIDEO_EXTS.contains(ext);
        }
        if (OpsBusinessAttachment.TYPE_CONTRACT.equals(type)
                || OpsBusinessAttachment.TYPE_REFUND.equals(type)) {
            return IMAGE_EXTS.contains(ext) || "pdf".equals(ext);
        }
        return IMAGE_EXTS.contains(ext);
    }

    private static String invalidFormatMessage(String type) {
        return OpsBusinessAttachment.TYPE_ACCEPTANCE.equals(type)
                ? "ACCEPTANCE_FILE_INVALID：附件格式不支持（仅支持 jpg/png/mp4/mov）"
                : (OpsBusinessAttachment.TYPE_CONTRACT.equals(type)
                ? "合同附件仅支持 JPG、PNG、PDF" : "仅支持 JPG、PNG 图片");
    }

    private static String missingMessage(String type) {
        return switch (type) {
            case OpsBusinessAttachment.TYPE_PAYMENT -> "请上传支付凭证（银行或微信支付凭证）";
            case OpsBusinessAttachment.TYPE_CONTRACT -> "请上传合同附件";
            case OpsBusinessAttachment.TYPE_REFUND -> "请上传退款补充附件";
            case OpsBusinessAttachment.TYPE_ACCEPTANCE ->
                    "ACCEPTANCE_ATTACHMENT_REQUIRED：请至少上传 1 张图片或 1 段视频作为验收凭证";
            case OpsBusinessAttachment.TYPE_CONSTRUCT ->
                    "CONSTRUCT_PHOTO_REQUIRED：请至少上传 1 张现场照片";
            default -> "请上传收据图片";
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case OpsBusinessAttachment.TYPE_PAYMENT -> "支付凭证";
            case OpsBusinessAttachment.TYPE_CONTRACT -> "合同附件";
            case OpsBusinessAttachment.TYPE_REFUND -> "退款补充附件";
            case OpsBusinessAttachment.TYPE_ACCEPTANCE -> "验收凭证";
            case OpsBusinessAttachment.TYPE_CONSTRUCT -> "现场照片";
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
     * 收据图片原图保存：不缩放、不重编码，仅校验真实格式（JPEG/PNG）与像素上限。
     * 以 ImageReader 探测到的真实格式决定扩展名/Content-Type，防止改后缀绕过。
     */
    private static Stored validateOriginalImage(byte[] bytes) {
        if (bytes.length == 0) throw BusinessException.of("图片为空");
        String format;
        int width;
        int height;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) throw BusinessException.of("图片文件格式不合法");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) throw BusinessException.of("图片文件格式不合法");
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                format = reader.getFormatName();
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw BusinessException.of("图片文件读取失败");
        }
        String normalized = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        String storedExt;
        String contentType;
        if ("jpeg".equals(normalized) || "jpg".equals(normalized)) {
            storedExt = "jpg";
            contentType = "image/jpeg";
        } else if ("png".equals(normalized)) {
            storedExt = "png";
            contentType = "image/png";
        } else {
            throw BusinessException.of("收据图片仅支持 JPG、PNG");
        }
        if (width <= 0 || height <= 0 || (long) width * height > MAX_RECEIPT_PIXELS) {
            throw BusinessException.of("收据图片像素过大，请裁剪后重试");
        }
        return new Stored(bytes, storedExt, contentType);
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
