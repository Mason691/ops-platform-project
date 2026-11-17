package com.aip.ops.security.qr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.time.Duration;
import java.time.Instant;

/**
 * 收款码完整性校验。
 *
 * <p>安全边界（按攻击路径逐条说明）：</p>
 * <ul>
 *   <li><b>改图片文件</b>：图片打包在 jar 的 {@code secure/qr/} 下（不是 static 目录，
 *       Spring Boot 不会静态暴露），运行时读取后必须通过 ECDSA 验签，失败拒绝返回；</li>
 *   <li><b>改 .sig 文件</b>：签名用开发侧离线私钥生成，攻击者没有私钥无法伪造；</li>
 *   <li><b>改公钥</b>：公钥硬编码在编译产物中，修改必须重新发布代码，留痕明显；</li>
 *   <li><b>绕过接口直连静态文件</b>：资源不在 static/，Nginx 无对应路由，
 *       前端只能经鉴权接口取图，校验逻辑无法绕过。</li>
 * </ul>
 *
 * <p>校验缓存：jar 内资源在运行期不可变，以“图片长度 + 签名长度 + lastModified”为
 * 指纹缓存 5 分钟，避免每次请求重复做 ECDSA 验签；失败缓存 30 秒，防止异常时打爆 CPU。
 * 发布新 jar（替换图片/签名）后必须重启应用，缓存随进程重建。</p>
 */
@Slf4j
@Service
public class QrCodeIntegrityService {

    /** 公钥硬编码：开发侧私钥离线保管，绝不入库/入服务器；轮换密钥必须走代码发布。 */
    static final String PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEuS0yrUKNvIWDhmv/YL7oRTeF+sFsMs6WuEPc3inMx8TutPDjKe91HZQHIsIS7Ls2/fehe3vl9OIZmKNatmcxSw==";

    private static final String IMAGE_RESOURCE = "secure/qr/payment-qr.png";
    private static final String SIGNATURE_RESOURCE = "secure/qr/payment-qr.png.sig";
    private static final String CONTENT_TYPE = "image/png";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);
    private static final String PUBLIC_KEY_FINGERPRINT =
            QrCodeCrypto.sha256Hex(PUBLIC_KEY_BASE64.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    .substring(0, 16);

    private volatile CacheEntry cache;

    /** 每次展示前校验（带 5 分钟成功缓存 / 30 秒失败缓存）。 */
    public Verification loadVerified() {
        return verify(true);
    }

    /** 启动校验：绕过缓存，确保启动时对磁盘/jar 当前内容验签。 */
    public Verification verifyNow() {
        return verify(false);
    }

    private synchronized Verification verify(boolean useCache) {
        byte[] image = read(IMAGE_RESOURCE);
        byte[] signature = read(SIGNATURE_RESOURCE);
        if (image == null || image.length == 0 || signature == null || signature.length == 0) {
            String reason = image == null ? "IMAGE_MISSING" : "SIGNATURE_MISSING";
            log.error("PAYMENT_QR_INTEGRITY_FAILED reason={} imagePresent={} signaturePresent={}",
                    reason, image != null, signature != null);
            Verification invalid = Verification.invalid(reason, null);
            cache = new CacheEntry("missing", invalid, Instant.now().plus(FAILURE_TTL));
            return invalid;
        }

        String fingerprint = image.length + ":" + signature.length
                + ":" + lastModified(IMAGE_RESOURCE) + ":" + lastModified(SIGNATURE_RESOURCE);
        CacheEntry current = cache;
        if (useCache && current != null && current.fingerprint.equals(fingerprint)
                && Instant.now().isBefore(current.expiresAt)) {
            return current.verification;
        }

        String sha256 = QrCodeCrypto.sha256Hex(image);
        boolean valid = QrCodeCrypto.verify(image, signature, PUBLIC_KEY_BASE64);
        Verification verification = valid
                ? new Verification(true, image, CONTENT_TYPE, sha256, null)
                : Verification.invalid("ECDSA_VERIFY_FAILED", sha256);
        if (!valid) {
            // 校验失败绝不返回图片：这是“可能已被替换为攻击者收款码”的安全事件
            log.error("PAYMENT_QR_INTEGRITY_FAILED reason=ECDSA_VERIFY_FAILED imageSha256={} "
                            + "signatureBytes={} publicKeyFingerprint={}",
                    sha256, signature.length, PUBLIC_KEY_FINGERPRINT);
        }
        cache = new CacheEntry(fingerprint, verification,
                Instant.now().plus(valid ? CACHE_TTL : FAILURE_TTL));
        return verification;
    }

    private byte[] read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.error("PAYMENT_QR_RESOURCE_READ_FAILED path={} reason={}", path, e.getMessage());
            return null;
        }
    }

    private long lastModified(String path) {
        try {
            URLConnection connection = new ClassPathResource(path).getURL().openConnection();
            connection.setUseCaches(false);
            return connection.getLastModified();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 校验结果。失败时 bytes 为空，调用方必须拒绝返回图片。 */
    public record Verification(boolean valid, byte[] bytes, String contentType,
                               String sha256, String reason) {
        public static Verification invalid(String reason, String sha256) {
            return new Verification(false, new byte[0], "application/json", sha256, reason);
        }
    }

    private record CacheEntry(String fingerprint, Verification verification, Instant expiresAt) {
    }
}
