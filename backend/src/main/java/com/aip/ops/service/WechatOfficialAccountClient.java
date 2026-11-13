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
 * 公众号服务端调用（access_token 缓存 + 模板消息发送）。
 *
 * <p>统一收口 access_token，避免多个控制器各自缓存导致互相刷新。使用
 * {@code wechatRestTemplate}（不挂日志拦截器），防止异常时 URI 中的 appSecret 落入日志。</p>
 */
@Slf4j
@Service
public class WechatOfficialAccountClient {

    private static final String TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}";
    private static final String TEMPLATE_SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token={token}";

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

    /**
     * 发送模板消息。
     *
     * @param data 微信模板字段 -> 值（如 thing1 -> "张三"）
     * @return 发送结果；失败时带可读原因
     */
    @SuppressWarnings("unchecked")
    public SendResult sendTemplateMessage(PublicAccountConfig cfg, String openid,
                                          String templateId, String url,
                                          Map<String, String> data) {
        if (cfg == null) return SendResult.fail("未配置公众号");
        if (isBlank(openid)) return SendResult.fail("员工未绑定微信");
        if (isBlank(templateId)) return SendResult.fail("未配置公众号消息模板");
        String token = accessToken(cfg);
        if (token == null) return SendResult.fail("获取 access_token 失败");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", openid);
        body.put("template_id", templateId);
        if (!isBlank(url)) body.put("url", url);
        Map<String, Object> wxData = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (isBlank(key)) return;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", value == null ? "" : value);
            wxData.put(key, item);
        });
        body.put("data", wxData);

        try {
            Map<String, Object> resp = restTemplate.postForObject(
                    TEMPLATE_SEND_URL, body, Map.class, token);
            if (resp == null) return SendResult.fail("微信接口无响应");
            Object errcode = resp.get("errcode");
            if (errcode instanceof Number n && n.intValue() == 0) {
                return SendResult.ok();
            }
            int code = errcode instanceof Number n ? n.intValue() : -1;
            if (code == 40001 || code == 42001) {
                // access_token 失效：清缓存，下次发送重新获取
                tokenCache.remove(cfg.getAppId());
                tokenExpire.remove(cfg.getAppId());
            }
            String errmsg = String.valueOf(resp.getOrDefault("errmsg", "未知错误"));
            return SendResult.fail("微信推送失败(" + code + ")：" + errmsg
                    + describeErrcode(code));
        } catch (Exception e) {
            log.warn("发送公众号模板消息异常 openid={}: {}", openid, e.getMessage());
            return SendResult.fail("微信推送异常：" + e.getMessage());
        }
    }

    private static String describeErrcode(int code) {
        return switch (code) {
            case 43004 -> "（员工未关注公众号）";
            case 40003 -> "（openid 无效）";
            case 40037 -> "（模板ID无效）";
            case 45009 -> "（接口调用超过限制）";
            default -> "";
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SendResult(boolean success, String error) {
        static SendResult ok() {
            return new SendResult(true, null);
        }

        static SendResult fail(String error) {
            return new SendResult(false, error);
        }
    }
}
