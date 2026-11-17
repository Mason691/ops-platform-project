package com.aip.ops.security.qr;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 收款码完整性校验的密码学原语。
 *
 * <p>签名算法 ECDSA P-256 + SHA-256。开发侧私钥离线保管，公钥编译进 jar；
 * 攻击者即使替换图片与 .sig 文件，没有私钥也无法生成可验证的签名。</p>
 */
public final class QrCodeCrypto {

    private QrCodeCrypto() {
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static PublicKey publicKey(String base64X509) {
        try {
            byte[] der = Base64.getDecoder().decode(base64X509);
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("收款码公钥配置非法", e);
        }
    }

    /**
     * 校验 ECDSA 签名。签名由 OpenSSL 对图片字节做 SHA-256 后生成，
     * Java 的 SHA256withECDSA 内部同样先摘要再验签，二者等价。
     */
    public static boolean verify(byte[] data, byte[] signature, String publicKeyBase64) {
        if (data == null || data.length == 0 || signature == null || signature.length == 0) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey(publicKeyBase64));
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalStateException e) {
            return false;
        }
    }
}
