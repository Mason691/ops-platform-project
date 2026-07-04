package com.aip.ops.service;

import com.aip.ops.dto.EmployeeLoginRequest;

import java.util.Map;

/**
 * Employee authentication — independent from member login flow.
 */
public interface EmployeeAuthService {

    /** Generate captcha image. Returns {captchaId, captchaImage (base64)}. */
    Map<String, String> getCaptcha();

    /** Authenticate employee by phone + password + captcha. Returns token + employee info. */
    Map<String, Object> login(EmployeeLoginRequest req, String clientIp);

    /** Refresh SSO token using current employee context. */
    Map<String, Object> refreshToken();
}
