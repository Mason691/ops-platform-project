package com.aip.ops.controller;

import com.aip.ops.dto.EmployeeLoginRequest;
import com.aip.ops.service.EmployeeAuthService;
import com.aip.sdk.result.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Employee login controller — independent from member auth ({@link AuthController}).
 * <p>
 * Endpoints are public (whitelisted in SecurityConfig) so unauthenticated employees
 * can obtain a captcha and submit login credentials directly from the WeChat browser.
 * After login, the returned JWT token is used for all subsequent API calls.
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/auth/employee")
@RequiredArgsConstructor
public class EmployeeAuthController {

    private final EmployeeAuthService employeeAuthService;

    /** Generate captcha image. Returns {captchaId, captchaImage (base64 data URI)}. */
    @GetMapping("/captcha")
    public R<?> captcha() {
        return R.ok(employeeAuthService.getCaptcha());
    }

    /**
     * Employee login with phone + password + captcha.
     * Returns JWT token and employee profile on success.
     * <p>
     * Business validation errors ({@link IllegalArgumentException}) are handled by
     * {@code SdkGlobalExceptionHandler} → code:400 with the actual error message.
     * Unexpected errors (SSO failure, DB) → code:500 "系统内部错误".
     */
    @PostMapping("/login")
    public R<?> login(@RequestBody EmployeeLoginRequest req, HttpServletRequest request) {
        String clientIp = getClientIp(request);
        return R.ok(employeeAuthService.login(req, clientIp));
    }

    /**
     * Refresh JWT token. Requires valid (non-expired) JWT.
     * <p>
     * {@link IllegalArgumentException} → handled by {@code SdkGlobalExceptionHandler} → code:400.
     */
    @PostMapping("/refresh-token")
    public R<?> refreshToken() {
        return R.ok(employeeAuthService.refreshToken());
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }
}
