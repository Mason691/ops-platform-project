package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
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
 * 运营管理后台 — 站长审批与站长列表。
 * 所有数据操作代理至底座平台内部 API（X-Internal-Token 认证）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/station")
@RequiredArgsConstructor
public class AdminStationController {

    private final RestTemplate restTemplate;

    @Value("${platform.base-url}")
    private String platformBaseUrl;

    @Value("${platform.internal-token}")
    private String internalToken;

    /** 分页查询申请列表 */
    @GetMapping("/applies")
    @SuppressWarnings("unchecked")
    public R<?> applies(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long userId = SecurityUtils.getCurrentUserId();
        try {
            String url = platformBaseUrl + "/api/internal/station/apply/page"
                    + "?corpId=" + corpId
                    + (status != null ? "&status=" + status : "")
                    + "&pageNum=" + pageNum + "&pageSize=" + pageSize;
            HttpEntity<Void> entity = new HttpEntity<>(internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return R.ok(resp.getBody().get("data"));
        } catch (Exception e) {
            log.error("查询站长申请列表失败: corpId={}", corpId, e);
            return R.fail("查询失败: " + e.getMessage());
        }
    }

    /** 审批通过 */
    @OperationLog(module = "运营管理", action = "审批通过", targetTable = "sys_station_apply")
    @PostMapping("/applies/{id}/approve")
    @SuppressWarnings("unchecked")
    public R<?> approve(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 从 SecurityUtils 或请求中获取审批人名称
        try {
            body.put("reviewerId", userId);
            if (!body.containsKey("reviewerName")) {
                body.put("reviewerName", "");
            }
            String url = platformBaseUrl + "/api/internal/station/apply/" + id + "/approve";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return R.ok(resp.getBody().get("data"));
        } catch (Exception e) {
            log.error("审批站长申请失败: applyId={}", id, e);
            return R.fail("审批失败: " + e.getMessage());
        }
    }

    /** 审批拒绝 */
    @OperationLog(module = "运营管理", action = "审批拒绝", targetTable = "sys_station_apply")
    @PostMapping("/applies/{id}/reject")
    @SuppressWarnings("unchecked")
    public R<?> reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        try {
            body.put("reviewerId", userId);
            if (!body.containsKey("reviewerName")) {
                body.put("reviewerName", "");
            }
            String url = platformBaseUrl + "/api/internal/station/apply/" + id + "/reject";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, internalHeaders());
            restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            return R.ok();
        } catch (Exception e) {
            log.error("拒绝站长申请失败: applyId={}", id, e);
            return R.fail("操作失败: " + e.getMessage());
        }
    }

    /** 分页查询站长列表 */
    @GetMapping("/masters")
    @SuppressWarnings("unchecked")
    public R<?> masters(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        try {
            String url = platformBaseUrl + "/api/internal/station/master/page"
                    + "?corpId=" + corpId
                    + (keyword != null ? "&keyword=" + keyword : "")
                    + "&pageNum=" + pageNum + "&pageSize=" + pageSize;
            HttpEntity<Void> entity = new HttpEntity<>(internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return R.ok(resp.getBody().get("data"));
        } catch (Exception e) {
            log.error("查询站长列表失败: corpId={}", corpId, e);
            return R.fail("查询失败: " + e.getMessage());
        }
    }

    /** 启用/停用站长 */
    @OperationLog(module = "运营管理", action = "修改", targetTable = "sys_station_master")
    @PutMapping("/masters/{id}/status")
    @SuppressWarnings("unchecked")
    public R<?> toggleStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String url = platformBaseUrl + "/api/internal/station/master/" + id + "/status";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, internalHeaders());
            restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return R.ok();
        } catch (Exception e) {
            log.error("切换站长状态失败: id={}", id, e);
            return R.fail("操作失败: " + e.getMessage());
        }
    }

    /** 站长统计 */
    @GetMapping("/statistics")
    @SuppressWarnings("unchecked")
    public R<?> statistics() {
        Long corpId = SecurityUtils.getCurrentCorpId();
        try {
            String url = platformBaseUrl + "/api/internal/station/statistics?corpId=" + corpId;
            HttpEntity<Void> entity = new HttpEntity<>(internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return R.ok(resp.getBody().get("data"));
        } catch (Exception e) {
            log.error("查询站长统计失败: corpId={}", corpId, e);
            return R.fail("查询失败: " + e.getMessage());
        }
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
