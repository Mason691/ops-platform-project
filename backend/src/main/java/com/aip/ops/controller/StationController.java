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
 * H5公众号端 — 站长申请与站长中心。
 * 所有数据操作代理至底座平台内部 API（X-Internal-Token 认证）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/station")
@RequiredArgsConstructor
public class StationController {

    private final RestTemplate restTemplate;

    @Value("${platform.base-url}")
    private String platformBaseUrl;

    @Value("${platform.internal-token}")
    private String internalToken;

    /** 提交站长申请 */
    @PostMapping("/apply")
    @SuppressWarnings("unchecked")
    public R<?> apply(@RequestBody Map<String, Object> body) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long memberId = SecurityUtils.getCurrentUserId();
        try {
            body.put("corpId", corpId);
            body.put("platformMemberId", memberId);
            String url = platformBaseUrl + "/api/internal/station/apply";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return R.ok(resp.getBody().get("data"));
        } catch (Exception e) {
            log.error("提交站长申请失败: corpId={}, memberId={}", corpId, memberId, e);
            return R.fail("提交失败: " + e.getMessage());
        }
    }

    /** 查询当前会员的申请状态 */
    @GetMapping("/apply/status")
    @SuppressWarnings("unchecked")
    public R<?> applyStatus() {
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long memberId = SecurityUtils.getCurrentUserId();
        try {
            String url = platformBaseUrl + "/api/internal/station/apply/status"
                    + "?corpId=" + corpId + "&memberId=" + memberId;
            HttpEntity<Void> entity = new HttpEntity<>(internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return R.ok(resp.getBody().get("data"));
        } catch (Exception e) {
            log.error("查询申请状态失败: corpId={}, memberId={}", corpId, memberId, e);
            return R.fail("查询失败: " + e.getMessage());
        }
    }

    /** 查询当前会员的站长信息（如已是站长） */
    @GetMapping("/info")
    @SuppressWarnings("unchecked")
    public R<?> info() {
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long memberId = SecurityUtils.getCurrentUserId();
        try {
            String url = platformBaseUrl + "/api/internal/station/master/by-member"
                    + "?corpId=" + corpId + "&memberId=" + memberId;
            HttpEntity<Void> entity = new HttpEntity<>(internalHeaders());
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return R.ok(resp.getBody().get("data"));
        } catch (Exception e) {
            log.error("查询站长信息失败: corpId={}, memberId={}", corpId, memberId, e);
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
