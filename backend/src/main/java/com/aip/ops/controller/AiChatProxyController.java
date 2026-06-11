package com.aip.ops.controller;

import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * AI 助手对话代理 — 将公众号端用户消息转发到底座平台统一 AI 引擎。
 * <p>
 * 底座通过 JWT 中的 member_id 自动关联会员记忆，实现跨子系统一致的 AI 体验。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/ai")
@RequiredArgsConstructor
public class AiChatProxyController {

    private final org.springframework.web.client.RestTemplate restTemplate;

    @Value("${platform.base-url}")
    private String platformBaseUrl;

    /** 公众号端 AI 助手对话（非流式） */
    @PostMapping("/chat")
    public R<?> chat(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String message = (String) body.get("message");
        if (message == null || message.isBlank()) return R.fail("消息不能为空");

        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long convId = body.get("conversationId") != null
                ? Long.valueOf(body.get("conversationId").toString()) : null;

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("message", message);
        reqBody.put("conversationId", convId);
        reqBody.put("corpId", corpId);
        reqBody.put("userId", userId);
        reqBody.put("subsystem", "ops-platform");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", request.getHeader("Authorization"));
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    platformBaseUrl + "/api/ai/chat/send",
                    HttpMethod.POST, new HttpEntity<>(reqBody, headers), Map.class);
            return R.ok(resp.getBody() != null ? resp.getBody().get("data") : null);
        } catch (Exception e) {
            log.warn("AI chat 调用失败: {}", e.getMessage());
            return R.ok(Map.of("reply", "抱歉，AI 助手正在休息，请稍后重试。"));
        }
    }

    /** 公众号端 AI 助手配置（昵称、头像） */
    @GetMapping("/config")
    public R<Map<String, Object>> aiConfig() {
        return R.ok(Map.of(
                "nickname", "小金",
                "greeting", "您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？"
        ));
    }
}
