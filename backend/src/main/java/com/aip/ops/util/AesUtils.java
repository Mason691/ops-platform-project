package com.aip.ops.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** AES-128/ECB/PKCS5Padding — 用于解密平台加密存储的配置值。 */
public final class AesUtils {

    private static final String ENCRYPT_SECRET = "aip-default-enc-key";

    private AesUtils() {}

    public static String decrypt(String ciphertext) {
        return decrypt(ciphertext, ENCRYPT_SECRET);
    }

    public static String decrypt(String ciphertext, String secret) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            SecretKeySpec key = toKey(secret);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKeySpec toKey(String secret) {
        byte[] keyBytes = new byte[16];
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(secretBytes, 0, keyBytes, 0, Math.min(secretBytes.length, 16));
        return new SecretKeySpec(keyBytes, "AES");
    }
}
