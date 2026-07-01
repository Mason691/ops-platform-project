package com.aip.ops.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 底座平台会员同步客户端。
 * 通过底座 internal API 按手机号查找或创建平台统一会员，
 * 返回全局 member_id 供本平台存储关联。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberSyncClient {

    private final RestTemplate restTemplate;

    @Value("${platform.base-url}")
    private String baseUrl;

    @Value("${platform.internal-token}")
    private String internalToken;

    /**
     * 向底座注册或查询会员，返回底座会员信息。
     * 调用失败时抛出异常，由上层决定如何处理。
     * @return map with memberDbId, memberId, nickname, isNew 等
     */
    public Map<String, Object> sync(String phone, String nickname, String avatar) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("identifierType", "PHONE");
        body.put("identifier", phone);
        body.put("nickname", nickname != null ? nickname : "");
        body.put("avatar", avatar != null ? avatar : "");
        body.put("registerSource", "3"); // 3=燃气H5

        HttpEntity<Map<String, String>> req = new HttpEntity<>(body, headers);
        String url = baseUrl + "/api/internal/member/register-or-get";

        log.info("同步会员到底座: phone={}", phone);
        ResponseEntity<Map> resp = restTemplate.postForEntity(url, req, Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody();
        if (data != null && Integer.valueOf(200).equals(data.get("code"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) data.get("data");
            log.info("底座会员同步成功: memberId={}, isNew={}", result.get("memberId"), result.get("isNew"));
            return result;
        }

        log.error("底座会员同步返回非200响应: {}", data);
        throw new RuntimeException("底座会员同步失败: " + (data != null ? data.get("msg") : "未知错误"));
    }
}
