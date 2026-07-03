package com.aip.ops.controller;

import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 会员资料代理 — 代理到底座平台 /api/member/profile，统一头像昵称。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/member")
public class MemberProfileProxyController {

    private final RestTemplate restTemplate;

    public MemberProfileProxyController(@Qualifier("aiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Value("${platform.base-url}")
    private String platformBaseUrl;

    @Value("${platform.internal-token}")
    private String internalToken;

    @GetMapping("/profile")
    public R<?> getProfile(@RequestHeader("Authorization") String authHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    platformBaseUrl + "/api/member/profile",
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            return R.ok(resp.getBody() != null ? resp.getBody().get("data") : null);
        } catch (Exception e) {
            log.error("Member profile proxy GET failed: {}", e.getMessage());
            return R.fail("服务异常，请稍后重试");
        }
    }

    @PutMapping("/profile")
    public R<?> updateProfile(@RequestHeader("Authorization") String authHeader,
                               @RequestBody Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restTemplate.exchange(
                    platformBaseUrl + "/api/member/profile",
                    HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class);
            return R.ok();
        } catch (Exception e) {
            log.error("Member profile proxy PUT failed: {}", e.getMessage());
            return R.fail("服务异常，请稍后重试");
        }
    }
}
