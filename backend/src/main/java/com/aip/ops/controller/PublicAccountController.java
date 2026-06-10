package com.aip.ops.controller;

import com.aip.ops.entity.PublicAccountConfig;
import com.aip.ops.service.impl.PublicAccountConfigServiceImpl;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 多公众号运营配置管理 + H5公开查询接口
 */
@RestController @RequiredArgsConstructor
public class PublicAccountController {

    private final PublicAccountConfigServiceImpl configService;
    private final RestTemplate restTemplate;

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

    @PostMapping("/api/ops/public-account-config")
    public R<?> create(@RequestBody PublicAccountConfig config) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        // 超管可指定组织，普通用户强制使用自身组织
        if (config.getCorpId() == null || config.getCorpId() == 0) config.setCorpId(corpId);
        configService.save(config);
        return R.ok();
    }

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

    @DeleteMapping("/api/ops/public-account-config/{id}")
    public R<?> delete(@PathVariable Long id) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        LambdaQueryWrapper<PublicAccountConfig> qw = new LambdaQueryWrapper<PublicAccountConfig>()
                .eq(PublicAccountConfig::getId, id);
        if (corpId != null && corpId != 0) qw.eq(PublicAccountConfig::getCorpId, corpId);
        configService.remove(qw);
        return R.ok();
    }

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
                             @RequestParam(required = false) Long corpId) {
        PublicAccountConfig cfg;
        if (appId != null && !appId.isEmpty()) {
            cfg = configService.getByAppId(appId, corpId);
        } else {
            cfg = configService.getOne(new LambdaQueryWrapper<PublicAccountConfig>()
                    .eq(corpId != null, PublicAccountConfig::getCorpId, corpId)
                    .eq(PublicAccountConfig::getIsDefault, 1)
                    .eq(PublicAccountConfig::getStatus, 1).last("LIMIT 1"));
        }
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("appId", cfg != null ? cfg.getAppId() : "wx095b91409f8ecbfe");
        r.put("jsDomain", cfg != null && cfg.getJsDomain() != null
                ? cfg.getJsDomain() : "a.15891589.xyz");
        r.put("name", cfg != null && cfg.getName() != null ? cfg.getName() : "金地燃气");
        r.put("debug", cfg == null);
        return R.ok(r);
    }
}
