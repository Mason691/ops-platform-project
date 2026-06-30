package com.aip.ops.service.impl;

import com.aip.ops.entity.PublicAccountConfig;
import com.aip.ops.mapper.PublicAccountConfigMapper;
import com.aip.ops.service.WechatPhoneService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Base64;
import java.util.Map;

/**
 * 微信手机号解密实现。
 * <ol>
 *   <li>从 PublicAccountConfig 取默认公众号的 appId / appSecret</li>
 *   <li>调用 jscode2session 用 code 换取 session_key + openid</li>
 *   <li>AES/CBC/PKCS5Padding 解密 encryptedData → 手机号</li>
 * </ol>
 */
@Slf4j
@Service
public class WechatPhoneServiceImpl implements WechatPhoneService {

    private static final String JSCODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session"
          + "?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code";

    private final PublicAccountConfigMapper paConfigMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WechatPhoneServiceImpl(PublicAccountConfigMapper paConfigMapper,
                                  RestTemplate restTemplate,
                                  ObjectMapper objectMapper) {
        this.paConfigMapper = paConfigMapper;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public WechatPhoneResult decryptPhone(String code, String encryptedData, String iv) {
        // 1. 取默认公众号配置
        PublicAccountConfig cfg = findDefaultConfig();
        if (cfg == null) {
            log.error("未找到有效的公众号配置，无法解密手机号");
            return null;
        }
        String appId = cfg.getAppId();
        String appSecret = cfg.getAppSecret();
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.error("公众号配置缺少 appId 或 appSecret: name={}", cfg.getName());
            return null;
        }

        // 2. jscode2session 换取 session_key
        String url = String.format(JSCODE2SESSION_URL, appId, appSecret, code);
        log.debug("jscode2session 请求: appId={}", appId);

        String respBody;
        try {
            respBody = restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            log.error("jscode2session 请求失败: {}", e.getMessage());
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resp;
        try {
            resp = objectMapper.readValue(respBody, Map.class);
        } catch (Exception e) {
            log.error("jscode2session 响应解析失败: {}", respBody);
            return null;
        }

        Object errcode = resp.get("errcode");
        if (errcode != null && !"0".equals(String.valueOf(errcode))) {
            log.error("jscode2session 返回错误: errcode={}, errmsg={}",
                    errcode, resp.get("errmsg"));
            return null;
        }

        String sessionKey = (String) resp.get("session_key");
        String openid = (String) resp.get("openid");
        String unionid = (String) resp.get("unionid");

        if (sessionKey == null || sessionKey.isBlank()) {
            log.error("jscode2session 未返回 session_key: {}", respBody);
            return null;
        }

        // 3. AES 解密 encryptedData
        String phone;
        try {
            phone = aesDecrypt(encryptedData, sessionKey, iv);
        } catch (Exception e) {
            log.error("AES 解密手机号失败: {}", e.getMessage());
            return null;
        }

        if (phone == null || phone.isEmpty()) {
            log.error("解密后手机号为空");
            return null;
        }

        log.info("微信手机号解密成功: phone={} openid={}", phone, openid);
        return new WechatPhoneResult(phone, openid, unionid);
    }

    /** 从 PublicAccountConfig 表取默认公众号（is_default=1 & status=1），无则取第一条 */
    private PublicAccountConfig findDefaultConfig() {
        PublicAccountConfig cfg = paConfigMapper.selectOne(
                new LambdaQueryWrapper<PublicAccountConfig>()
                        .eq(PublicAccountConfig::getIsDefault, 1)
                        .eq(PublicAccountConfig::getStatus, 1)
                        .last("LIMIT 1"));
        if (cfg != null) return cfg;
        // fallback: any active config
        return paConfigMapper.selectOne(
                new LambdaQueryWrapper<PublicAccountConfig>()
                        .eq(PublicAccountConfig::getStatus, 1)
                        .last("LIMIT 1"));
    }

    /**
     * AES/CBC/PKCS5Padding 解密（微信使用 PKCS7，Java PKCS5 等价于 PKCS7）。
     * 解密结果为 JSON: {"phoneNumber":"13800138000","purePhoneNumber":"13800138000",...}
     */
    @SuppressWarnings("unchecked")
    private String aesDecrypt(String encryptedData, String sessionKey, String iv) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(sessionKey);
        byte[] ivBytes = Base64.getDecoder().decode(iv);
        byte[] dataBytes = Base64.getDecoder().decode(encryptedData);

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        AlgorithmParameterSpec ivSpec = new IvParameterSpec(ivBytes);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decrypted = cipher.doFinal(dataBytes);

        String json = new String(decrypted, StandardCharsets.UTF_8);
        Map<String, Object> map = objectMapper.readValue(json, Map.class);

        // 优先取 purePhoneNumber（无国家代码），其次 phoneNumber
        Object pure = map.get("purePhoneNumber");
        if (pure != null && !pure.toString().isBlank()) {
            return pure.toString().trim();
        }
        Object full = map.get("phoneNumber");
        return full != null ? full.toString().trim() : null;
    }
}
