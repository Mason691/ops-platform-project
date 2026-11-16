package com.aip.ops.service.impl;

import com.aip.sdk.exception.BusinessException;
import com.aip.ops.entity.OpsPaymentQrConfig;
import com.aip.ops.mapper.OpsPaymentQrConfigMapper;
import com.aip.ops.service.PaymentQrService;
import com.aip.sdk.security.LoginUser;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 收款码配置：按运营商组织保存唯一图片，登记 SHA-256；
 * 每次读取重新计算哈希，发现服务器文件被直接替换时拒绝返回并告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentQrServiceImpl implements PaymentQrService {

    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTS = Set.of("jpg", "jpeg", "png");

    private final OpsPaymentQrConfigMapper configMapper;

    /**
     * 收款码私有目录：不能放在 /uploads 静态映射下，否则知道路径即可绕过
     * 鉴权接口与哈希校验直接取图。生产通过 PAYMENT_QR_BASE_DIR 指向持久卷。
     */
    @Value("${app.payment-qr.base-dir:${user.home}/data/payment-qr}")
    private String paymentQrBaseDir;

    @Override
    public Map<String, Object> metadata(Long corpId) {
        requireCorp(corpId);
        OpsPaymentQrConfig config = configMapper.selectByCorp(corpId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (config == null) {
            result.put("configured", false);
            result.put("message", "收款码未配置，请在“收款码配置”中上传");
            return result;
        }
        result.put("configured", true);
        result.put("fileName", config.getFileName());
        result.put("fileHash", config.getFileHash());
        result.put("fileSize", config.getFileSize());
        result.put("uploadTime", config.getUploadTime());
        result.put("uploaderName", config.getUploaderName());
        result.put("status", config.getStatus());
        return result;
    }

    @Override
    public Map<String, Object> upload(MultipartFile file, Long corpId) {
        requireCorp(corpId);
        if (file == null || file.isEmpty()) throw BusinessException.of("请选择收款码图片");
        if (file.getSize() > MAX_FILE_BYTES) throw BusinessException.of("收款码图片不能超过5MB");
        String ext = extension(file.getOriginalFilename());
        if (!ALLOWED_EXTS.contains(ext)) {
            throw BusinessException.of("收款码仅支持 JPG、PNG 图片");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BusinessException.of("读取收款码图片失败，请重试");
        }
        validateImage(bytes);
        String hash = sha256(bytes);
        String relative = "qrcode/" + corpId + "/payment_qr." + ext;
        Path base = Paths.get(paymentQrBaseDir).toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            throw BusinessException.of("收款码存储路径不合法");
        }
        Path tmp = target.resolveSibling(".payment_qr_" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            log.error("收款码保存失败 corpId={}", corpId, e);
            throw BusinessException.of("收款码保存失败，请重试");
        }
        // 清理同目录下旧扩展名的收款码，避免残留文件被误用
        cleanupOtherQrFiles(target);

        LoginUser user = SecurityUtils.getCurrentUser();
        OpsPaymentQrConfig existing = configMapper.selectByCorpIncludingDeleted(corpId);
        OpsPaymentQrConfig config = existing != null ? existing : new OpsPaymentQrConfig();
        config.setCorpId(corpId);
        config.setFileName(sanitizeFileName(file.getOriginalFilename()));
        config.setFilePath(relative);
        config.setFileHash(hash);
        config.setFileSize((long) bytes.length);
        config.setContentType("png".equals(ext) ? "image/png" : "image/jpeg");
        config.setUploaderUserId(user != null ? user.getUserId() : null);
        config.setUploaderName(user != null ? user.getUsername() : null);
        config.setUploadTime(LocalDateTime.now());
        config.setStatus(1);
        config.setDeleteFlag(0);
        if (existing != null) {
            configMapper.updateExisting(config);
        } else {
            configMapper.insert(config);
        }
        log.info("收款码已更新 corpId={} size={} sha256={}", corpId, bytes.length, hash);
        return metadata(corpId);
    }

    @Override
    public VerifiedQr loadVerified(Long corpId) {
        requireCorp(corpId);
        OpsPaymentQrConfig config = configMapper.selectByCorp(corpId);
        if (config == null) {
            throw BusinessException.of("收款码未配置，请联系管理员");
        }
        if (!Integer.valueOf(1).equals(config.getStatus())) {
            throw BusinessException.of("收款码未启用，请联系管理员");
        }
        Path base = Paths.get(paymentQrBaseDir).toAbsolutePath().normalize();
        Path file = base.resolve(config.getFilePath()).normalize();
        if (!file.startsWith(base)) {
            log.error("收款码路径越界 corpId={} path={}", corpId, config.getFilePath());
            throw BusinessException.of("收款码文件校验失败，请联系管理员");
        }
        if (!Files.isRegularFile(file)) {
            log.error("收款码文件不存在 corpId={} path={}", corpId, config.getFilePath());
            throw BusinessException.of("收款码文件不存在，请联系管理员");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            log.error("收款码读取失败 corpId={}", corpId, e);
            throw BusinessException.of("收款码文件校验失败，请联系管理员");
        }
        String actual = sha256(bytes);
        if (!actual.equalsIgnoreCase(config.getFileHash())) {
            log.error("收款码文件哈希校验失败 corpId={} expected={} actual={} path={}",
                    corpId, config.getFileHash(), actual, config.getFilePath());
            throw BusinessException.of("收款码文件校验失败，请联系管理员");
        }
        validateImage(bytes);
        return new VerifiedQr(bytes, config.getContentType() == null
                ? "image/jpeg" : config.getContentType());
    }

    private void validateImage(byte[] bytes) {
        try {
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw BusinessException.of("收款码文件不是有效图片");
            }
        } catch (IOException e) {
            throw BusinessException.of("收款码文件读取失败");
        }
    }

    private void cleanupOtherQrFiles(Path target) {
        Path dir = target.getParent();
        if (dir == null) return;
        try (var stream = Files.list(dir)) {
            stream.filter(path -> !path.equals(target))
                    .filter(path -> path.getFileName().toString().startsWith("payment_qr."))
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    });
        } catch (IOException ignored) {
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void requireCorp(Long corpId) {
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请切换到具体运营商组织后维护收款码");
        }
    }

    private static String extension(String name) {
        String value = name == null ? "" : name.trim();
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String sanitizeFileName(String name) {
        String value = name == null ? "收款码" : name.trim();
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        if (value.isEmpty()) value = "收款码";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }
}
