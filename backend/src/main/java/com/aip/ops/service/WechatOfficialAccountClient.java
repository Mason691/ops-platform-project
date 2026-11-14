package com.aip.ops.service;

import com.aip.ops.entity.PublicAccountConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公众号服务端调用（access_token 缓存，供 JSSDK 等使用）。
 *
 * <p>统一收口 access_token，避免多个控制器各自缓存导致互相刷新。使用
 * {@code wechatRestTemplate}（不挂日志拦截器），防止异常时 URI 中的 appSecret 落入日志。
 * 模板消息发送已统一由底座工作流组件承担，本类不再提供推送能力。</p>
 */
@Slf4j
@Service
public class WechatOfficialAccountClient {

    private static final String TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}";

    private final RestTemplate restTemplate;
    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();
    private final Map<String, Long> tokenExpire = new ConcurrentHashMap<>();

    public WechatOfficialAccountClient(
            @Qualifier("wechatRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 获取 access_token（按 appId 缓存，提前 5 分钟过期）；失败返回 null。 */
    @SuppressWarnings("unchecked")
    public String accessToken(PublicAccountConfig cfg) {
        if (cfg == null || isBlank(cfg.getAppId()) || isBlank(cfg.getAppSecret())) {
            return null;
        }
        String key = cfg.getAppId();
        Long expire = tokenExpire.getOrDefault(key, 0L);
        if (System.currentTimeMillis() < expire) {
            return tokenCache.get(key);
        }
        try {
            Map<String, Object> resp = restTemplate.getForObject(TOKEN_URL, Map.class,
                    cfg.getAppId(), cfg.getAppSecret());
            if (resp != null && resp.get("access_token") instanceof String token) {
                int expiresIn = resp.get("expires_in") instanceof Number n ? n.intValue() : 7200;
                tokenCache.put(key, token);
                tokenExpire.put(key, System.currentTimeMillis() + (expiresIn - 300) * 1000L);
                return token;
            }
            log.warn("获取公众号 access_token 失败 appId={}: {}", cfg.getAppId(),
                    resp == null ? "无响应" : resp.get("errmsg"));
        } catch (Exception e) {
            log.warn("获取公众号 access_token 异常 appId={}: {}", cfg.getAppId(), e.getMessage());
        }
        return null;
    }

    /** 主动探测公众号配置（后台「测试连接」用）。 */
    public Map<String, Object> probe(PublicAccountConfig cfg) {
        String token = accessToken(cfg);
        Map<String, Object> result = new LinkedHashMap<>();
        if (token == null) {
            result.put("success", false);
            result.put("errmsg", "获取 access_token 失败，请检查 appId/appSecret");
            return result;
        }
        result.put("success", true);
        result.put("access_token", token.substring(0, Math.min(10, token.length())) + "…");
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
