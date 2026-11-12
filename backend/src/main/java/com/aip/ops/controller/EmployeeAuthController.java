package com.aip.ops.controller;

import com.aip.ops.dto.EmployeeLoginRequest;
import com.aip.ops.dto.EmployeeWechatCodeRequest;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.EmployeeAuthService;
import com.aip.ops.service.WechatOAuthService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Employee login controller — independent from member auth ({@link AuthController}).
 * <p>
 * 两种登录方式并存：
 * <ul>
 *   <li>手机号 + 密码 + 图形验证码（原有方式）</li>
 *   <li>微信公众号网页授权（openid 绑定；首次绑定需先密码登录后在「我的」完成）</li>
 * </ul>
 * 所有端点均在 SecurityConfig 中放行；绑定/状态类端点由控制器手动校验员工 JWT。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/auth/employee")
@RequiredArgsConstructor
public class EmployeeAuthController {

    private final EmployeeAuthService employeeAuthService;
    private final WechatOAuthService wechatOAuthService;
    private final JwtTokenInspector jwtTokenInspector;

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
     * 生成微信授权跳转地址。
     *
     * @param mode   login=微信登录（默认）/ bind=已登录员工绑定微信
     * @param corpId 可选；登录模式由前端传当前组织，绑定模式由 JWT 推导
     */
    @GetMapping("/wechat/authorize-url")
    public R<?> wechatAuthorizeUrl(@RequestParam(defaultValue = "login") String mode,
                                   @RequestParam(required = false) Long corpId) {
        Long userId = null;
        if ("bind".equalsIgnoreCase(mode)) {
            userId = requireEmployeeUserId();
        }
        Long effectiveCorpId = corpId != null ? corpId : SecurityUtils.getCurrentCorpIdOrNull();
        return R.ok(wechatOAuthService.createAuthorizeUrl(mode, effectiveCorpId, userId));
    }

    /** 微信授权登录：code/state 由公众号 OAuth 回调带回。 */
    @PostMapping("/wechat/login")
    public R<?> wechatLogin(@RequestBody EmployeeWechatCodeRequest req, HttpServletRequest request) {
        return R.ok(wechatOAuthService.loginByCode(req.getCode(), req.getState(), getClientIp(request)));
    }

    /** 已登录员工绑定微信：state 必须由同一员工在绑定模式下发起。 */
    @PostMapping("/wechat/bind")
    public R<?> wechatBind(@RequestBody EmployeeWechatCodeRequest req, HttpServletRequest request) {
        Long userId = requireEmployeeUserId();
        return R.ok(wechatOAuthService.bindByCode(
                req.getCode(), req.getState(), userId, getClientIp(request)));
    }

    /** 查询当前员工的微信绑定状态。 */
    @GetMapping("/wechat/bind-status")
    public R<?> wechatBindStatus() {
        Long userId = requireEmployeeUserId();
        return R.ok(wechatOAuthService.getBindStatus(userId, SecurityUtils.getCurrentCorpIdOrNull()));
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

    /**
     * 员工端接口统一身份守卫：拒绝会员 SSO 令牌（token_type=sso）。
     * 无令牌时 SecurityUtils 抛 401，由全局异常处理器返回。
     */
    private Long requireEmployeeUserId() {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            throw new IllegalArgumentException("请使用员工账号登录");
        }
        return SecurityUtils.getCurrentUserId();
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
