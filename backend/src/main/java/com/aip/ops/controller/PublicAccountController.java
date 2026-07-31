package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.entity.PublicAccountConfig;
import com.aip.ops.service.impl.PublicAccountConfigServiceImpl;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多公众号运营配置管理 + H5公开查询接口
 */
@Slf4j
@RestController @RequiredArgsConstructor
public class PublicAccountController {

    private final PublicAccountConfigServiceImpl configService;
    private final RestTemplate restTemplate;

    // ── access_token / jsapi_ticket 缓存 ──
    private final Map<String, String>  tokenCache  = new ConcurrentHashMap<>();
    private final Map<String, Long>    tokenExpire = new ConcurrentHashMap<>();
    private final Map<String, String>  ticketCache = new ConcurrentHashMap<>();
    private final Map<String, Long>    ticketExpire= new ConcurrentHashMap<>();

    private String getAccessToken(PublicAccountConfig cfg) {
        String key = cfg.getAppId();
        Long expire = tokenExpire.getOrDefault(key, 0L);
        if (System.currentTimeMillis() < expire) return tokenCache.get(key);
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"
                + "&appid=" + cfg.getAppId() + "&secret=" + cfg.getAppSecret();
        try {
            var resp = restTemplate.getForObject(url, Map.class);
            if (resp != null && resp.containsKey("access_token")) {
                String token = (String) resp.get("access_token");
                int expiresIn = ((Number) resp.get("expires_in")).intValue();
                tokenCache.put(key, token);
                tokenExpire.put(key, System.currentTimeMillis() + (expiresIn - 300) * 1000L);
                return token;
            }
            log.warn("getAccessToken failed: {}", resp);
        } catch (Exception e) { log.error("getAccessToken error", e); }
        return null;
    }

    private String getJsapiTicket(PublicAccountConfig cfg) {
        String key = cfg.getAppId();
        Long expire = ticketExpire.getOrDefault(key, 0L);
        if (System.currentTimeMillis() < expire) return ticketCache.get(key);
        String token = getAccessToken(cfg);
        if (token == null) return null;
        try {
            var resp = restTemplate.getForObject(
                    "https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token="
                    + token + "&type=jsapi", Map.class);
            if (resp != null && "ok".equals(resp.get("errmsg")) && resp.containsKey("ticket")) {
                String ticket = (String) resp.get("ticket");
                int expiresIn = ((Number) resp.get("expires_in")).intValue();
                ticketCache.put(key, ticket);
                ticketExpire.put(key, System.currentTimeMillis() + (expiresIn - 300) * 1000L);
                return ticket;
            }
        } catch (Exception e) { log.error("getJsapiTicket error", e); }
        return null;
    }

    private String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ═══ 管理后台 CRUD ═══
    @GetMapping("/api/ops/public-account-config")
    public R<?> list() {
        Long corpId = SecurityUtils.getCurrentCorpId();
        LambdaQueryWrapper<PublicAccountConfig> qw = new LambdaQueryWrapper<PublicAccountConfig>()
                .orderByDesc(PublicAccountConfig::getIsDefault)
                .orderByAsc(PublicAccountConfig::getId);
        // 超管可见所有配置，普通用户仅见本组织
        if (corpId != null && corpId != 0) qw.eq(PublicAccountConfig::getCorpId, corpId);
        List<PublicAccountConfig> list = configService.list(qw);
        list.forEach(c -> c.setAppSecret("******"));
        return R.ok(list);
    }

    @OperationLog(module = "运营管理", action = "新增", targetTable = "public_account_config")
    @PostMapping("/api/ops/public-account-config")
    public R<?> create(@RequestBody PublicAccountConfig config) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        // 超管可指定组织，普通用户强制使用自身组织
        if (config.getCorpId() == null || config.getCorpId() == 0) config.setCorpId(corpId);
        configService.save(config);
        return R.ok();
    }

    @OperationLog(module = "运营管理", action = "修改", targetTable = "public_account_config")
    @PutMapping("/api/ops/public-account-config/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody PublicAccountConfig config) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        LambdaQueryWrapper<PublicAccountConfig> qw = new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getId, id);
        if (corpId != null && corpId != 0) qw.eq(PublicAccountConfig::getCorpId, corpId);
        PublicAccountConfig existing = configService.getOne(qw);
        if (existing == null) return R.fail("配置不存在");
        if (config.getAppSecret() == null || "******".equals(config.getAppSecret()))
            config.setAppSecret(existing.getAppSecret());
        config.setId(id);
        // 超管可修改归属组织
        if (config.getCorpId() == null || config.getCorpId() == 0) config.setCorpId(existing.getCorpId());
        configService.updateById(config);
        return R.ok();
    }

    @OperationLog(module = "运营管理", action = "删除", targetTable = "public_account_config")
    @DeleteMapping("/api/ops/public-account-config/{id}")
    public R<?> delete(@PathVariable Long id) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        LambdaQueryWrapper<PublicAccountConfig> qw = new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getId, id);
        if (corpId != null && corpId != 0) qw.eq(PublicAccountConfig::getCorpId, corpId);
        configService.remove(qw);
        return R.ok();
    }

    @OperationLog(module = "运营管理", action = "修改", targetTable = "public_account_config")
    @PutMapping("/api/ops/public-account-config/{id}/set-default")
    public R<?> setDefault(@PathVariable Long id) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        LambdaQueryWrapper<PublicAccountConfig> qw = new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getId, id);
        if (corpId != null && corpId != 0) qw.eq(PublicAccountConfig::getCorpId, corpId);
        PublicAccountConfig cfg = configService.getOne(qw);
        if (cfg == null) return R.fail("配置不存在");
        configService.update(new LambdaUpdateWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getCorpId, cfg.getCorpId()).set(PublicAccountConfig::getIsDefault, 0));
        cfg.setIsDefault(1); configService.updateById(cfg);
        return R.ok();
    }

    @PostMapping("/api/ops/public-account-config/{id}/test")
    public R<?> testConnection(@PathVariable Long id) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        LambdaQueryWrapper<PublicAccountConfig> qw = new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getId, id);
        if (corpId != null && corpId != 0) qw.eq(PublicAccountConfig::getCorpId, corpId);
        PublicAccountConfig cfg = configService.getOne(qw);
        if (cfg == null) return R.fail("配置不存在");
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"
                + "&appid=" + cfg.getAppId() + "&secret=" + cfg.getAppSecret();
        try {
            var resp = restTemplate.getForObject(url, Map.class);
            Map<String,Object> r = new LinkedHashMap<>();
            if (resp != null && resp.containsKey("access_token")) {
                r.put("success", true);
                r.put("access_token", ((String) resp.get("access_token")).substring(0, 10) + "…");
            } else {
                r.put("success", false);
                r.put("errmsg", resp != null ? resp.get("errmsg") : "无响应");
            }
            return R.ok(r);
        } catch (Exception e) {
            return R.ok(Map.of("success", false, "errmsg", e.getMessage()));
        }
    }

    // ═══ H5 公开查询 (无需 JWT) ═══
    @GetMapping("/api/public-account/config")
    public R<?> publicConfig(@RequestParam(required = false) String appId,
                             @RequestParam(required = false) Long corpId,
                             HttpServletRequest request) {
        PublicAccountConfig cfg;
        if (appId != null && !appId.isEmpty()) {
            cfg = configService.getByAppId(appId, corpId);
        } else {
            cfg = configService.getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                    .eq(corpId != null, PublicAccountConfig::getCorpId, corpId)
                    .eq(PublicAccountConfig::getIsDefault, 1)
                    .eq(PublicAccountConfig::getStatus, 1).last("LIMIT 1"));
        }
        // Derive domain from request Host header; fall back to DB field then default
        String host = request.getHeader("Host");
        String jsDomain = (host != null && !host.isBlank()) ? host
                : (cfg != null && cfg.getJsDomain() != null) ? cfg.getJsDomain()
                : "boss.jdzn99.com";
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("appId", cfg != null ? cfg.getAppId() : "wx095b91409f8ecbfe");
        r.put("jsDomain", jsDomain);
        r.put("name", cfg != null && cfg.getName() != null ? cfg.getName() : "运营平台");
        r.put("debug", cfg == null);
        return R.ok(r);
    }

    // ═══ JSSDK 签名 (公开，H5 前端调用) ═══
    @GetMapping("/api/public-account/js-config")
    public R<?> jsConfig(@RequestParam String url,
                         @RequestParam(required = false) Long corpId) {
        PublicAccountConfig cfg = configService.getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(corpId != null, PublicAccountConfig::getCorpId, corpId)
                .eq(PublicAccountConfig::getIsDefault, 1)
                .eq(PublicAccountConfig::getStatus, 1).last("LIMIT 1"));
        if (cfg == null) cfg = configService.getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(corpId != null, PublicAccountConfig::getCorpId, corpId)
                .eq(PublicAccountConfig::getStatus, 1).last("LIMIT 1"));
        if (cfg == null) return R.fail("无可用公众号配置");

        String ticket = getJsapiTicket(cfg);
        if (ticket == null) return R.fail("获取 jsapi_ticket 失败，请检查 appId/appSecret");

        String nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long timestamp   = System.currentTimeMillis() / 1000;
        String signStr   = "jsapi_ticket=" + ticket + "&noncestr=" + nonceStr
                         + "&timestamp=" + timestamp + "&url=" + url;
        String signature = sha1(signStr);

        Map<String,Object> r = new LinkedHashMap<>();
        r.put("appId",     cfg.getAppId());
        r.put("timestamp", timestamp);
        r.put("nonceStr",  nonceStr);
        r.put("signature", signature);
        return R.ok(r);
    }

    // ═══ 小程序 URL Link 生成 (公开，H5 调用) ═══
    private final Map<String,String> urlLinkCache = new ConcurrentHashMap<>();

    @GetMapping("/api/public-account/mp-url-link")
    public R<?> mpUrlLink(@RequestParam String finderUserName,
                          @RequestParam(required = false) Long corpId) {
        // 缓存命中
        String cached = urlLinkCache.get(finderUserName);
        if (cached != null) return R.ok(Map.of("urlLink", cached));

        // 使用小程序 AppID 对应的配置（wx095b91409f8ecbfe）获取 token
        PublicAccountConfig cfg = configService.getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getAppId, "wx095b91409f8ecbfe")
                .eq(PublicAccountConfig::getStatus, 1).last("LIMIT 1"));
        if (cfg == null) {
            cfg = configService.getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                    .eq(corpId != null, PublicAccountConfig::getCorpId, corpId)
                    .eq(PublicAccountConfig::getIsDefault, 1)
                    .eq(PublicAccountConfig::getStatus, 1).last("LIMIT 1"));
        }
        if (cfg == null) return R.fail("无可用小程序配置");

        // 获取 access_token
        String tokenUrl = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"
                + "&appid=" + cfg.getAppId() + "&secret=" + cfg.getAppSecret();
        try {
            var tokenResp = restTemplate.getForObject(tokenUrl, Map.class);
            if (tokenResp == null || !tokenResp.containsKey("access_token")) {
                return R.fail("获取 access_token 失败: " + tokenResp);
            }
            String accessToken = (String) tokenResp.get("access_token");

            // 调用 generate_urllink
            String url = "https://api.weixin.qq.com/wxa/generate_urllink?access_token=" + accessToken;
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("path", "pages/channels/live?finderUserName=" + finderUserName);
            body.put("env_version", "release");
            body.put("is_expire", false);

            log.info("mp-url-link: calling generate_urllink, body={}", body);
            var resp = restTemplate.postForObject(url, body, Map.class);
            log.info("mp-url-link: response={}", resp);
            if (resp != null && resp.containsKey("url_link")) {
                String urlLink = (String) resp.get("url_link");
                urlLinkCache.put(finderUserName, urlLink);
                return R.ok(Map.of("urlLink", urlLink));
            }
            return R.fail("生成 URL Link 失败: "
                + (resp != null ? resp.getOrDefault("errmsg", resp.getOrDefault("errcode", resp.toString())) : "无响应"));
        } catch (Exception e) {
            return R.fail("生成失败: " + e.getMessage());
        }
    }
}
