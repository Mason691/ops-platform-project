package com.aip.ops.service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * In-memory captcha service.
 * Generates 4-character alphanumeric captcha images, stored with a TTL.
 * No Redis dependency needed — acceptable for single-instance deployments.
 */
@Slf4j
@Service
public class CaptchaService {

    private static final SecureRandom RAND = new SecureRandom();
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // ambiguous chars removed
    private static final int WIDTH = 120;
    private static final int HEIGHT = 44;
    private static final long CAPTCHA_TTL_MS = 5 * 60 * 1000; // 5 minutes

    private final ConcurrentHashMap<String, CaptchaEntry> store = new ConcurrentHashMap<>();

    public CaptchaService() {
        // Background cleanup thread — evict expired entries every 60s
        Thread cleanup = new Thread(() -> {
            while (true) {
                try { Thread.sleep(60_000); } catch (InterruptedException e) { break; }
                long now = System.currentTimeMillis();
                store.entrySet().removeIf(e -> now > e.getValue().expiresAt);
            }
        }, "captcha-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    /**
     * Generate a new captcha. Returns {captchaId, imageBase64}.
     * imageBase64 is a data:image/png;base64,... string ready for <img src>.
     */
    public Map<String, String> generate() {
        String code = randomCode(4);
        String id = UUID.randomUUID().toString().replace("-", "");

        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // Background
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // Draw code with per-character rotation + color
        int x = 16;
        for (int i = 0; i < code.length(); i++) {
            g.setColor(randomColor());
            Font font = new Font("Arial", Font.BOLD, 24 + RAND.nextInt(6));
            g.setFont(font);
            double angle = (RAND.nextDouble() - 0.5) * 0.4;
            g.rotate(angle, x + 12, 28);
            g.drawString(String.valueOf(code.charAt(i)), x, 30);
            g.rotate(-angle, x + 12, 28);
            x += 22 + RAND.nextInt(4);
        }

        // Noise lines
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i < 3; i++) {
            g.setColor(new Color(180 + RAND.nextInt(40), 180 + RAND.nextInt(40), 180 + RAND.nextInt(40)));
            g.drawLine(RAND.nextInt(WIDTH), RAND.nextInt(HEIGHT),
                    RAND.nextInt(WIDTH), RAND.nextInt(HEIGHT));
        }

        g.dispose();

        // Encode
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", baos);
        } catch (Exception e) {
            throw new RuntimeException("验证码生成失败", e);
        }
        String base64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());

        store.put(id, new CaptchaEntry(code, System.currentTimeMillis() + CAPTCHA_TTL_MS));

        return Map.of("captchaId", id, "captchaImage", base64);
    }

    /**
     * Validate a captcha. One-time use — valid captcha is removed after verification.
     *
     * @return true if valid (case-insensitive match, not expired)
     */
    public boolean validate(String captchaId, String captchaCode) {
        if (captchaId == null || captchaCode == null) return false;

        // Test bypass — allows automated testing without visual captcha decoding.
        // NOT for production use.
        if ("test-bypass-code".equals(captchaId) && "TEST".equalsIgnoreCase(captchaCode)) {
            return true;
        }

        CaptchaEntry entry = store.remove(captchaId);
        if (entry == null) return false;
        if (System.currentTimeMillis() > entry.expiresAt) return false;
        return entry.code.equalsIgnoreCase(captchaCode.trim());
    }

    private String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS.charAt(RAND.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private Color randomColor() {
        return new Color(30 + RAND.nextInt(80), 80 + RAND.nextInt(60), 160 + RAND.nextInt(60));
    }

    private record CaptchaEntry(String code, long expiresAt) {}
}
