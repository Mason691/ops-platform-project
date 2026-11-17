package com.aip.ops.service.impl;

import com.aip.ops.entity.PublicAccountConfig;
import com.aip.ops.service.EmployeeLoginResultMapper;
import com.aip.ops.service.IPublicAccountConfigService;
import com.aip.ops.service.WechatOAuthService;
import com.aip.sdk.client.PlatformInternalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 员工公众号网页授权实现。
 * <p>
 * 安全要点：
 * <ul>
 *   <li>state 一次性、5 分钟过期，绑定模式与发起时的员工 ID 绑定（防 CSRF/串号）；</li>
 *   <li>code 只在服务端换取 openid，不把 appSecret 暴露给前端；</li>
 *   <li>openid 匹配、员工账号校验与 JWT 签发全部委托底座，ops 不自行判定员工身份。</li>
 * </ul>
 * state 为单实例内存存储，与现有图形验证码一致；ops 重启后未完成的授权需重新发起。
 */
@Slf4j
@Service
public class WechatOAuthServiceImpl implements WechatOAuthService {

    private static final String AUTHORIZE_BASE = "https://open.weixin.qq.com/connect/oauth2/authorize";
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final int RATE_LIMIT_PER_MINUTE = 10;

    private final IPublicAccountConfigService configService;
    private final PlatformInternalClient platformInternalClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final String redirectUri;
    private final long stateTtlSeconds;
    private final String scope;
    private final long jwtExpiration;

    private final Map<String, OAuthState> states = new ConcurrentHashMap<>();
    private final Map<String, RateWindow> rateLimiter = new ConcurrentHashMap<>();

    public WechatOAuthServiceImpl(
            IPublicAccountConfigService configService,
            PlatformInternalClient platformInternalClient,
            @Qualifier("wechatRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${wechat.oauth.redirect-uri:}") String redirectUri,
            @Value("${wechat.oauth.state-ttl-seconds:300}") long stateTtlSeconds,
            @Value("${wechat.oauth.scope:snsapi_base}") String scope,
            @Value("${platform.jwt-expiration:43200}") long jwtExpiration) {
        this.configService = configService;
        this.platformInternalClient = platformInternalClient;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.redirectUri = redirectUri;
        this.stateTtlSeconds = stateTtlSeconds;
        this.scope = scope;
        this.jwtExpiration = jwtExpiration;
    }

    @Override
    public Map<String, Object> createAuthorizeUrl(String mode, Long corpId, Long currentUserId) {
        String normalizedMode = mode == null ? "login" : mode.trim().toLowerCase();
        if (!"login".equals(normalizedMode) && !"bind".equals(normalizedMode)) {
            throw new IllegalArgumentException("不支持的微信授权模式");
        }
        if ("bind".equals(normalizedMode) && currentUserId == null) {
            throw new IllegalArgumentException("请先登录后再绑定微信");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("未配置微信授权回调地址，请联系管理员");
        }

        PublicAccountConfig cfg = configService.getDefaultConfig(corpId);
        if (cfg == null || isBlank(cfg.getAppId()) || isBlank(cfg.getAppSecret())) {
            throw new IllegalArgumentException("未配置公众号，暂不支持微信授权登录");
        }

        pruneStates();
        String state = UUID.randomUUID().toString().replace("-", "");
        states.put(state, new OAuthState(
                normalizedMode,
                cfg.getAppId(),
                currentUserId,
                System.currentTimeMillis() + stateTtlSeconds * 1000L));

        String authorizeUrl = AUTHORIZE_BASE
                + "?appid=" + enc(cfg.getAppId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(scope)
                + "&state=" + state
                + "#wechat_redirect";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorizeUrl", authorizeUrl);
        result.put("state", state);
        result.put("mode", normalizedMode);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> loginByCode(String code, String state, String clientIp) {
        if (!checkRateLimit(clientIp)) {
            throw new IllegalArgumentException("操作过于频繁，请稍后再试");
        }
        OAuthState oauthState = takeState(state, "login");
        WechatIdentity identity = exchangeCode(oauthState.appId(), code);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("openid", identity.openid());
        req.put("unionId", identity.unionId());
        req.put("appId", oauthState.appId());
        req.put("clientIp", clientIp);

        Map<String, Object> raw;
        try {
            raw = platformInternalClient.post("/api/auth/employee/wechat/login", req, null);
        } catch (RuntimeException e) {
            log.warn("微信登录调用底座失败: {}", e.getMessage());
            throw new IllegalArgumentException("认证服务异常，请稍后重试");
        }

        Map<String, Object> data = requireData(raw, "微信授权登录失败");
        Map<String, Object> result = EmployeeLoginResultMapper.map(data, null, jwtExpiration);
        result.put("loginMode", "wechat");
        log.info("微信授权登录成功: employeeId={}", data.get("userId"));
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> bindByCode(String code, String state, Long currentUserId, String clientIp) {
        if (!checkRateLimit(clientIp)) {
            throw new IllegalArgumentException("操作过于频繁，请稍后再试");
        }
        OAuthState oauthState = takeState(state, "bind");
        if (currentUserId == null || oauthState.userId() == null
                || !oauthState.userId().equals(currentUserId)) {
            throw new IllegalArgumentException("绑定状态已失效，请重新发起");
        }
        WechatIdentity identity = exchangeCode(oauthState.appId(), code);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("userId", currentUserId);
        req.put("openid", identity.openid());
        req.put("unionId", identity.unionId());
        req.put("appId", oauthState.appId());
        req.put("clientIp", clientIp);

        Map<String, Object> raw;
        try {
            raw = platformInternalClient.post("/api/internal/user/wechat/bind", req, null);
        } catch (RuntimeException e) {
            log.warn("微信绑定调用底座失败: userId={}, error={}", currentUserId, e.getMessage());
            throw new IllegalArgumentException("微信绑定失败，请稍后重试");
        }
        Map<String, Object> data = requireData(raw, "微信绑定失败");
        log.info("员工微信绑定成功: userId={}", currentUserId);
        return data;
    }

    @Override
    public Map<String, Object> getBindStatus(Long currentUserId, Long corpId) {
        Map<String, Object> result = new LinkedHashMap<>();
        PublicAccountConfig cfg = configService.getDefaultConfig(corpId);
        if (cfg == null || isBlank(cfg.getAppId())) {
            result.put("configured", false);
            result.put("bound", false);
            return result;
        }
        result.put("configured", true);
        result.put("appId", cfg.getAppId());
        try {
            Map<String, Object> raw = platformInternalClient.get(
                    "/api/internal/user/wechat/bind-status?userId=" + currentUserId, null);
            Object code = raw.get("code");
            if (code instanceof Number n && n.intValue() != 200) {
                result.put("bound", false);
                result.put("error", "绑定状态查询失败");
                return result;
            }
            Object data = raw.get("data");
            if (data instanceof Map<?, ?> map) {
                // 绑定按 appId 隔离：只展示当前公众号下的绑定，避免换号后误显示“已绑定”
                Object binds = map.get("binds");
                List<Object> matched = new java.util.ArrayList<>();
                if (binds instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> bind && cfg.getAppId().equals(bind.get("appId"))) {
                            matched.add(item);
                        }
                    }
                }
                result.put("bound", !matched.isEmpty());
                result.put("binds", matched);
            } else {
                result.put("bound", false);
            }
        } catch (RuntimeException e) {
            log.warn("查询微信绑定状态失败: userId={}, error={}", currentUserId, e.getMessage());
            result.put("bound", false);
            result.put("error", "绑定状态查询失败");
        }
        return result;
    }

    // ═══ Helpers ═══

    private OAuthState takeState(String state, String expectedMode) {
        if (isBlank(state)) {
            throw new IllegalArgumentException("微信授权 state 不能为空");
        }
        OAuthState oauthState = states.remove(state);
        if (oauthState == null || oauthState.expireAt() < System.currentTimeMillis()) {
            throw new IllegalArgumentException("微信授权已过期，请重新发起");
        }
        if (!expectedMode.equals(oauthState.mode())) {
            throw new IllegalArgumentException("微信授权状态不匹配，请重新发起");
        }
        return oauthState;
    }

    /** 用 code 换取 openid；携带 state 中记录的 appId 对应密钥，避免配置切换后串号。 */
    @SuppressWarnings("unchecked")
    private WechatIdentity exchangeCode(String appId, String code) {
        if (isBlank(code)) {
            throw new IllegalArgumentException("微信授权 code 不能为空");
        }
        PublicAccountConfig cfg = configService.getByAppId(appId, null);
        if (cfg == null || isBlank(cfg.getAppSecret())) {
            throw new IllegalArgumentException("公众号配置已变更，请重新发起微信授权");
        }

        String url = ACCESS_TOKEN_URL
                + "?appid=" + enc(appId)
                + "&secret=" + enc(cfg.getAppSecret())
                + "&code=" + enc(code)
                + "&grant_type=authorization_code";

        String body;
        try {
            body = restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            log.warn("微信 OAuth 换取 openid 请求失败: {}", e.getMessage());
            throw new IllegalArgumentException("微信授权失败，请稍后重试");
        }

        Map<String, Object> resp;
        try {
            resp = objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            log.warn("微信 OAuth 响应解析失败");
            throw new IllegalArgumentException("微信授权失败，请稍后重试");
        }

        Object errcode = resp.get("errcode");
        if (errcode != null && !"0".equals(String.valueOf(errcode))) {
            log.warn("微信 OAuth 换取 openid 失败: errcode={}, errmsg={}",
                    errcode, resp.get("errmsg"));
            throw new IllegalArgumentException("微信授权失败，请重新发起");
        }

        String openid = asString(resp.get("openid"));
        if (isBlank(openid)) {
            log.warn("微信 OAuth 未返回 openid");
            throw new IllegalArgumentException("微信授权失败，请重新发起");
        }
        return new WechatIdentity(openid, asString(resp.get("unionid")));
    }

    /** 校验底座 R 响应并取出 data；业务错误直接转用户可读提示。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> requireData(Map<String, Object> raw, String fallbackMessage) {
        Object code = raw.get("code");
        if (code instanceof Number n && n.intValue() != 200) {
            Object msg = raw.get("msg");
            String message = msg instanceof String s && !s.isBlank() ? s : fallbackMessage;
            throw new IllegalArgumentException(message);
        }
        Object data = raw.get("data");
        if (!(data instanceof Map)) {
            throw new IllegalArgumentException("认证服务异常，请稍后重试");
        }
        return (Map<String, Object>) data;
    }

    private boolean checkRateLimit(String ip) {
        if (ip == null || ip.isBlank()) return true;
        long now = System.currentTimeMillis();
        RateWindow window = rateLimiter.computeIfAbsent(ip, k -> new RateWindow(now));
        synchronized (window) {
            if (now - window.windowStart > 60_000) {
                window.windowStart = now;
                window.count = 0;
            }
            if (window.count >= RATE_LIMIT_PER_MINUTE) return false;
            window.count++;
            return true;
        }
    }

    private void pruneStates() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> e.getValue().expireAt() < now);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record OAuthState(String mode, String appId, Long userId, long expireAt) {}

    private record WechatIdentity(String openid, String unionId) {}

    private static class RateWindow {
        long windowStart;
        int count;
        RateWindow(long windowStart) { this.windowStart = windowStart; }
    }
}
