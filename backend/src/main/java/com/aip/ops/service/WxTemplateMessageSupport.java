package com.aip.ops.service;

import com.aip.ops.entity.OpsWxMsgTemplate;
import com.aip.ops.entity.PublicAccountConfig;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公众号模板消息发送支撑：公众号解析、按公众号取模板ID、字段映射、openid 查询、发送。
 *
 * <p>共享模板可能由运营商创建、被子公司在自己的公众号下使用，因此必须区分：
 * <ul>
 *   <li>公众号：模板指定 appId 时优先使用该公众号（跨组织查找），否则用业务组织默认公众号；</li>
 *   <li>模板ID：优先取 template_id_map 中当前公众号 appId 的映射，其次取默认 template_id；
 *       若使用了不同公众号且无映射，明确失败，避免用错模板ID。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxTemplateMessageSupport {

    private static final String DEFAULT_MAPPING =
            "{\"customerName\":\"thing1\",\"dispatchType\":\"thing2\","
                    + "\"expectTime\":\"time3\",\"dispatchNo\":\"character_string4\"}";

    private final IPublicAccountConfigService accountConfigService;
    private final PlatformInternalClient platformClient;
    private final WechatOfficialAccountClient wechatClient;
    private final ObjectMapper objectMapper;

    /** 公众号：模板指定 appId 时跨组织查找；否则取业务组织默认公众号。 */
    public PublicAccountConfig resolveAccount(Long corpId, String preferredAppId) {
        if (preferredAppId != null && !preferredAppId.isBlank()) {
            PublicAccountConfig cfg = accountConfigService.getByAppId(preferredAppId, corpId);
            if (cfg == null) {
                cfg = accountConfigService.getByAppId(preferredAppId, null);
            }
            if (cfg != null) return cfg;
        }
        return accountConfigService.getDefaultConfig(corpId);
    }

    /**
     * 按实际使用的公众号解析模板ID。
     * template_id_map 为该公众号配置了映射时优先；否则仅当实际公众号与「归属组织默认公众号」
     * （或模板指定 appId）一致时使用默认 template_id；跨组织的不同公众号且无映射时明确失败。
     */
    public String resolveTemplateId(OpsWxMsgTemplate template, String accountAppId) {
        if (template == null || template.getTemplateId() == null
                || template.getTemplateId().isBlank()) {
            throw BusinessException.of("模板未配置微信模板ID");
        }
        String mapped = parseTemplateIdMap(template.getTemplateIdMap()).get(accountAppId);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        if (template.getAppId() != null && !template.getAppId().isBlank()) {
            if (template.getAppId().equals(accountAppId)) {
                return template.getTemplateId();
            }
            throw sharedMappingMissing(accountAppId);
        }
        // appId 为空：默认 template_id 只对「归属组织默认公众号」有效；
        // 子公司使用自有公众号且未配置映射时，模板ID大概率不存在于该公众号，必须明确失败。
        PublicAccountConfig ownerAccount = resolveAccount(template.getOwnerCorpId(), null);
        if (ownerAccount != null && accountAppId.equals(ownerAccount.getAppId())) {
            return template.getTemplateId();
        }
        throw sharedMappingMissing(accountAppId);
    }

    private static BusinessException sharedMappingMissing(String accountAppId) {
        return BusinessException.of("共享模板未配置公众号 " + accountAppId
                + " 的模板ID，请在「按公众号映射模板ID」中补充");
    }

    /** 按字段映射把业务变量转为微信模板 data（微信字段 -> 值）。 */
    public Map<String, String> mapData(OpsWxMsgTemplate template, Map<String, String> vars) {
        Map<String, String> mapping = parseFieldMapping(template.getFieldMapping());
        Map<String, String> data = new LinkedHashMap<>();
        mapping.forEach((logical, wxField) ->
                data.put(wxField, vars.getOrDefault(logical, "")));
        return data;
    }

    /** 查询员工在指定公众号下的 openid；未绑定返回 null。 */
    @SuppressWarnings("unchecked")
    public String fetchOpenid(Long userId, String appId) {
        if (userId == null || appId == null || appId.isBlank()) return null;
        try {
            String path = "/api/internal/user/wechat/openid?userId=" + userId
                    + "&appId=" + URLEncoder.encode(appId, StandardCharsets.UTF_8);
            Map<String, Object> resp = platformClient.get(path, null);
            Object data = resp == null ? null : resp.get("data");
            if (data instanceof Map<?, ?> map) {
                Object openid = map.get("openid");
                return openid instanceof String s && !s.isBlank() ? s : null;
            }
        } catch (Exception e) {
            log.warn("查询员工微信 openid 失败 userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    /** 发送模板消息（业务发送与测试发送共用）。 */
    public SendResult send(OpsWxMsgTemplate template, Long corpId, Long userId,
                           Map<String, String> vars, String url) {
        if (template == null) return SendResult.fail("未配置公众号消息模板");
        PublicAccountConfig account = resolveAccount(corpId, template.getAppId());
        if (account == null) {
            return SendResult.fail("未配置可用公众号");
        }
        String openid = fetchOpenid(userId, account.getAppId());
        if (openid == null) {
            return new SendResult(false, "员工未绑定微信或未关注该公众号",
                    null, account.getAppId(), null);
        }
        String templateId;
        try {
            templateId = resolveTemplateId(template, account.getAppId());
        } catch (BusinessException e) {
            return new SendResult(false, e.getMessage(), null, account.getAppId(), openid);
        }
        Map<String, String> data = mapData(template, vars);
        WechatOfficialAccountClient.SendResult result = wechatClient.sendTemplateMessage(
                account, openid, templateId, url, data);
        if (!result.success()) {
            log.warn("模板消息发送失败 templateId={}, appId={}: {}",
                    templateId, account.getAppId(), result.error());
        }
        return new SendResult(result.success(), result.error(),
                templateId, account.getAppId(), openid);
    }

    private Map<String, String> parseFieldMapping(String json) {
        String source = json == null || json.isBlank() ? DEFAULT_MAPPING : json;
        try {
            return objectMapper.readValue(source, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("消息模板字段映射解析失败，使用默认映射: {}", e.getMessage());
            try {
                return objectMapper.readValue(DEFAULT_MAPPING,
                        new TypeReference<LinkedHashMap<String, String>>() {
                        });
            } catch (Exception ignored) {
                return Map.of();
            }
        }
    }

    private Map<String, String> parseTemplateIdMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("模板ID按公众号映射解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 发送结果；openid 仅用于消息记录落库，对外接口需脱敏。 */
    public record SendResult(boolean success, String error, String templateId,
                             String appId, String openid) {
        static SendResult fail(String error) {
            return new SendResult(false, error, null, null, null);
        }
    }
}
