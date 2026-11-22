package com.aip.ops.service.construct;

import com.aip.sdk.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 客户手写签字图片落盘。
 *
 * <p>只接受 PNG（Canvas 导出），限制 2MB，校验文件魔数；文件名使用 UUID，
 * 目录按组织/订单隔离。返回根相对 URL + 原始字节 SHA-256，供告知书防篡改核验。</p>
 */
@Slf4j
@Component
public class ConstructSignatureStorage {

    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final int MAX_SIGNATURE_BYTES = 2 * 1024 * 1024;

    @Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    public StoredSignature store(Long corpId, Long orderId, String imageBase64) {
        byte[] bytes = decode(imageBase64);
        if (bytes.length > MAX_SIGNATURE_BYTES) {
            throw BusinessException.of("SIGN_IMAGE_REQUIRED：签字图片不能超过2MB，请清除后重签");
        }
        if (!isPng(bytes)) {
            throw BusinessException.of("SIGN_IMAGE_REQUIRED：签字图片格式不正确，请重新签字");
        }
        String relative = relativePath(corpId, orderId);
        Path target = Paths.get(uploadBaseDir, relative).toAbsolutePath().normalize();
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("签字图片保存失败 corpId={} orderId={}", corpId, orderId, e);
            throw BusinessException.of("签字图片保存失败，请重试");
        }
        // 与业务附件一致使用根相对路径：H5 经 Vite/Nginx 代理，小程序端由 assetUrl 补域名
        String url = "/uploads/" + relative;
        return new StoredSignature(url, sha256(bytes), bytes.length, target);
    }

    /**
     * 复算已归档签字图片的 SHA-256（防篡改核验）。
     * 仅允许读取上传根目录下 ops/construct 子目录，防止 URL 被伪造后读取任意文件。
     */
    public String hashOf(String url) {
        String marker = "/uploads/";
        int index = url == null ? -1 : url.indexOf(marker);
        if (index < 0) {
            throw BusinessException.of("签字图片地址不合法");
        }
        String relative = url.substring(index + marker.length());
        Path base = Paths.get(uploadBaseDir).toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base.resolve("ops/construct"))) {
            throw BusinessException.of("签字图片地址不合法");
        }
        try {
            return sha256(Files.readAllBytes(target));
        } catch (IOException e) {
            log.warn("读取签字图片失败 path={}", target, e);
            throw BusinessException.of("签字图片不存在或无法读取");
        }
    }

    /** 幂等命中（同一签名重复提交）时清理刚落盘的重复文件。 */
    public void delete(StoredSignature stored) {
        if (stored == null || stored.path() == null) return;
        try {
            Files.deleteIfExists(stored.path());
        } catch (IOException e) {
            log.warn("清理重复签字图片失败 path={}", stored.path(), e);
        }
    }

    private static byte[] decode(String imageBase64) {
        String raw = imageBase64 == null ? "" : imageBase64.trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        if (raw.isEmpty()) {
            throw BusinessException.of("SIGN_IMAGE_REQUIRED：请客户完成手写签字");
        }
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw BusinessException.of("SIGN_IMAGE_REQUIRED：签字图片解析失败，请重新签字");
        }
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_MAGIC.length) return false;
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (bytes[i] != PNG_MAGIC[i]) return false;
        }
        return true;
    }

    private static String relativePath(Long corpId, Long orderId) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "ops/construct/" + corpId + "/" + orderId + "/" + date + "/"
                + UUID.randomUUID().toString().replace("-", "") + ".png";
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 落盘结果：根相对 URL + 原始字节哈希 + 大小 + 本地路径（幂等清理用）。 */
    public record StoredSignature(String url, String sha256, int size, Path path) {
    }
}
