package com.aip.ops.service.impl;

import com.aip.ops.dto.EmployeeLoginRequest;
import com.aip.ops.service.CaptchaService;
import com.aip.ops.service.EmployeeAuthService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.security.LoginUser;
import com.aip.sdk.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 员工认证服务 — 委托底座平台 sys_user 表进行身份验证。
 * <p>
 * 本服务仅负责验证码校验、IP 频率限制、输入格式校验等前置安全层。
 * 实际的身份认证（密码验证、账号状态检查、JWT 签发）由底座平台
 * {@code POST /api/auth/employee/login} 接口统一处理。
 * <p>
 * 不再使用本地 ops_employee 表。
 */
@Slf4j
@Service
public class EmployeeAuthServiceImpl implements EmployeeAuthService {

    private static final int RATE_LIMIT_PER_MINUTE = 10;

    private final CaptchaService captchaService;
    private final PlatformInternalClient platformInternalClient;
    private final long jwtExpiration;

    /** IP-based rate limiting: ip → sliding window counter */
    private final ConcurrentHashMap<String, RateWindow> rateLimiter = new ConcurrentHashMap<>();

    public EmployeeAuthServiceImpl(
            CaptchaService captchaService,
            PlatformInternalClient platformInternalClient,
            @Value("${platform.jwt-expiration:43200}") long jwtExpiration) {
        this.captchaService = captchaService;
        this.platformInternalClient = platformInternalClient;
        this.jwtExpiration = jwtExpiration;
    }

    @Override
    public Map<String, String> getCaptcha() {
        return captchaService.generate();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> login(EmployeeLoginRequest req, String clientIp) {
        // ── 1. Rate limit ──
        if (!checkRateLimit(clientIp)) {
            log.warn("员工登录频率限制触发: ip={}", clientIp);
            throw new IllegalArgumentException("登录请求过于频繁，请稍后再试");
        }

        // ── 2. Validate captcha ──
        if (!captchaService.validate(req.getCaptchaId(), req.getCaptchaCode())) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }

        // ── 3. Validate input ──
        String phone = req.getPhone();
        String password = req.getPassword();
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("请输入正确的手机号");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }

        // ── 4. Delegate to platform employee login ──
        Map<String, Object> platformData;
        try {
            Map<String, Object> empReq = new LinkedHashMap<>();
            empReq.put("phone", phone);
            empReq.put("password", password);

            Map<String, Object> rawResp = platformInternalClient.post(
                    "/api/auth/employee/login", empReq, null);

            // Check for platform-level error response (non-200 code)
            Object code = rawResp.get("code");
            if (code instanceof Number && ((Number) code).intValue() != 200) {
                Object msg = rawResp.get("msg");
                String errMsg = msg instanceof String ? (String) msg : "认证失败";
                throw new IllegalArgumentException(errMsg);
            }

            platformData = (Map<String, Object>) rawResp.get("data");
        } catch (IllegalArgumentException e) {
            // Re-throw user-facing platform error messages
            throw e;
        } catch (RuntimeException e) {
            log.warn("员工登录失败（平台返回错误）phone={}: {}", phone, e.getMessage());
            throw new IllegalArgumentException(extractErrorMessage(e));
        }

        if (platformData == null || platformData.get("token") == null) {
            log.error("员工登录失败（平台返回空数据）phone={}", phone);
            throw new IllegalArgumentException("认证服务异常，请稍后重试");
        }

        // ── 5. Map platform response to frontend format ──
        log.info("员工登录成功: phone={}, userId={}", phone, platformData.get("userId"));
        return mapLoginResult(platformData, phone);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> refreshToken() {
        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        LoginUser loginUser = SecurityUtils.getCurrentUser();

        // Resolve phone from the current JWT context
        String phone = null;

        // Try member_id claim first (linked via sys_user.platformMemberId)
        Long memberId = loginUser.getMemberId();
        if (memberId != null) {
            try {
                // Query platform for employee info by member ID
                Map<String, Object> resp = platformInternalClient.get(
                        "/api/internal/user/employee-info?platformMemberId=" + memberId, null);
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                if (data != null && Boolean.TRUE.equals(data.get("isEmployee"))) {
                    // Use the username (phone) from the current JWT — it's set from sys_user.username
                    phone = loginUser.getUsername();
                }
            } catch (Exception e) {
                log.debug("refreshToken — 通过 member_id 查询员工信息失败: {}", e.getMessage());
            }
        }

        // Fallback: use username from JWT (which is sys_user.username, typically the phone)
        if (phone == null) {
            phone = loginUser.getUsername();
        }

        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("无法获取员工身份信息");
        }

        // Obtain a fresh SSO token from platform
        try {
            Map<String, Object> ssoReq = new LinkedHashMap<>();
            ssoReq.put("identifierType", "PHONE");
            ssoReq.put("identifier", phone);
            ssoReq.put("subsystem", "ops-platform");
            ssoReq.put("corpId", corpId);

            Map<String, Object> ssoResp = platformInternalClient.post(
                    "/api/auth/sso/token", ssoReq, null);
            Map<String, Object> data = (Map<String, Object>) ssoResp.get("data");
            String token = data != null && data.get("token") != null
                    ? data.get("token").toString() : null;

            if (token == null) {
                throw new RuntimeException("SSO 令牌响应缺少 token 字段");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("expiresIn", jwtExpiration);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("员工刷新令牌失败: userId={}, error={}", userId, e.getMessage());
            throw new RuntimeException("认证服务异常，请稍后重试", e);
        }
    }

    // ═══ Helpers ═══

    /** Simple sliding-window rate limiter (1-minute window, per IP). */
    private boolean checkRateLimit(String ip) {
        if (ip == null || ip.isEmpty()) return true; // skip if IP unknown
        long now = System.currentTimeMillis();
        RateWindow w = rateLimiter.computeIfAbsent(ip, k -> new RateWindow(now, 0));
        synchronized (w) {
            if (now - w.windowStart > 60_000) {
                w.windowStart = now;
                w.count = 0;
            }
            if (w.count >= RATE_LIMIT_PER_MINUTE) return false;
            w.count++;
            return true;
        }
    }

    /**
     * Map platform employee login response to the frontend format.
     * <p>
     * Platform returns: {token, userId, username, realName, phone, corpId,
     *                     isAdmin, permissions, roles, corpName, expiresIn}
     * <p>
     * Frontend expects: {token, employeeId, name, phone, role, corpId, expiresIn}
     */
    private Map<String, Object> mapLoginResult(Map<String, Object> data, String phone) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", data.get("token"));
        result.put("employeeId", data.get("userId"));             // userId → employeeId
        result.put("name", data.getOrDefault("realName", ""));    // realName → name
        result.put("phone", phone);                                // from request
        result.put("role", extractRole(data));                     // first role name
        result.put("corpId", data.get("corpId"));
        result.put("expiresIn", data.getOrDefault("expiresIn", jwtExpiration));
        return result;
    }

    /** Extract primary role from platform roles list. */
    @SuppressWarnings("unchecked")
    private String extractRole(Map<String, Object> data) {
        List<String> roles = (List<String>) data.get("roles");
        if (roles != null && !roles.isEmpty()) {
            return roles.get(0);
        }
        return "employee"; // default fallback
    }

    /**
     * Extract a user-facing error message from a platform exception.
     * <p>
     * Platform returns error messages in Chinese via {@code R.fail(msg)}.
     * The message may be wrapped in a RuntimeException from PlatformInternalClient.
     * We pass through known messages and provide a generic fallback for unknown errors.
     */
    private String extractErrorMessage(RuntimeException e) {
        String msg = e.getMessage();
        if (msg == null) return "认证服务异常，请稍后重试";

        // Pass through known platform error messages
        if (msg.contains("手机号或密码错误")) return "手机号或密码错误";
        if (msg.contains("账号已被禁用")) return "账号已被禁用，请联系管理员";

        // Generic fallback — don't leak internal details
        log.error("员工登录异常（未识别的平台错误）: {}", msg);
        return "认证服务异常，请稍后重试";
    }

    // ═══ Inner types ═══

    private static class RateWindow {
        long windowStart;
        int count;
        RateWindow(long ws, int c) { this.windowStart = ws; this.count = c; }
    }
}
