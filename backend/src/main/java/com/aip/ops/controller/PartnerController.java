package com.aip.ops.controller;

import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 城市合伙人管理 Controller — 运营商管理员管理其下级的城市合伙人组织。
 * 调用底座平台内部 API（X-Internal-Token 认证）完成组织数据操作。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/partners")
@RequiredArgsConstructor
public class PartnerController {

    private final RestTemplate restTemplate;

    @Value("${platform.base-url}")
    private String platformBaseUrl;

    @Value("${platform.internal-token}")
    private String internalToken;

    /** 查询当前运营商下属的所有城市合伙人 */
    @GetMapping
    @SuppressWarnings("unchecked")
    public R<?> list() {
        Long operatorId = SecurityUtils.getCurrentCorpId();
        try {
            // 查所有城市合伙人角色组织
            String url = platformBaseUrl + "/api/internal/corp/list-by-role?roleName=城市合伙人&pageSize=500";
            HttpEntity<Void> entity = new HttpEntity<>(internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            if (data == null) return R.ok(List.of());

            List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
            // 过滤：仅返回 parentCorpId == 当前运营商 ID 的合伙人
            List<Map<String, Object>> partners = records.stream()
                    .filter(r -> {
                        Object pid = r.get("parentCorpId");
                        return pid != null && Long.valueOf(pid.toString()).equals(operatorId);
                    })
                    .toList();
            return R.ok(partners);
        } catch (Exception e) {
            log.error("查询合伙人列表失败: operatorId={}", operatorId, e);
            return R.fail("查询失败: " + e.getMessage());
        }
    }

    /** 新增城市合伙人 */
    @PostMapping
    @SuppressWarnings("unchecked")
    public R<?> create(@RequestBody Map<String, Object> body) {
        Long operatorId = SecurityUtils.getCurrentCorpId();
        try {
            body.put("parentCorpId", operatorId);
            String url = platformBaseUrl + "/api/internal/corp/partner";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return R.ok(resp.getBody().get("data"));
        } catch (Exception e) {
            log.error("创建合伙人失败: operatorId={}", operatorId, e);
            return R.fail("创建失败: " + e.getMessage());
        }
    }

    /** 修改城市合伙人信息 */
    @PutMapping("/{corpId}")
    public R<?> update(@PathVariable Long corpId, @RequestBody Map<String, Object> body) {
        try {
            String url = platformBaseUrl + "/api/internal/corp/partner/" + corpId;
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, internalHeaders());
            restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return R.ok();
        } catch (Exception e) {
            log.error("修改合伙人失败: corpId={}", corpId, e);
            return R.fail("修改失败: " + e.getMessage());
        }
    }

    /** 启用/停用城市合伙人 */
    @PutMapping("/{corpId}/toggle")
    public R<?> toggleStatus(@PathVariable Long corpId, @RequestBody Map<String, Object> body) {
        try {
            String url = platformBaseUrl + "/api/internal/corp/partner/" + corpId + "/status";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, internalHeaders());
            restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return R.ok();
        } catch (Exception e) {
            log.error("切换合伙人状态失败: corpId={}", corpId, e);
            return R.fail("操作失败: " + e.getMessage());
        }
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
