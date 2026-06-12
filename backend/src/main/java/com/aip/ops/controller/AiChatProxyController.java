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

    /** 公众号端 AI 助手配置（昵称、头像、语音开关） */
    @GetMapping("/config")
    public R<Map<String, Object>> aiConfig() {
        return R.ok(Map.of(
                "nickname", "小金",
                "greeting", "您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？",
                "voiceEnabled", true
        ));
    }

    /**
     * AI 助手个性化问候 — 结合会员偏好生成专属问候语。
     * 未登录返回通用问候。
     */
    @GetMapping("/profile")
    public R<Map<String, Object>> aiProfile() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            // 查询会员信息
            // 注意：这里要用 OpsMemberMapper，但为简化暂时返回通用信息
            result.put("nickname", "燃气用户");
            result.put("avatar", null);
            result.put("greeting", "您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？");
        } catch (Exception e) {
            result.put("nickname", "访客");
            result.put("greeting", "你好！我是你的燃气小助手，可以帮你选商品、查积分、找优惠～\n登录后更懂你。");
        }
        return R.ok(result);
    }

    /** 语音识别代理 — 转发音频到底座 AI 引擎识别 */
    @PostMapping("/voice/recognize")
    public R<?> voiceRecognize(@RequestBody Map<String, Object> body) {
        // 语音识别：暂时返回模拟识别结果
        // 后续可对接底座 TTS/ASR 服务
        String base64Audio = (String) body.get("audio");
        if (base64Audio == null || base64Audio.isEmpty()) return R.fail("音频数据为空");
        return R.ok(Map.of("text", "语音识别功能已就绪"));
    }

    /** 语音合成代理 — 转发文本到底座 AI 引擎合成语音 */
    @PostMapping("/voice/synthesize")
    public R<?> voiceSynthesize(@RequestBody Map<String, Object> body) {
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) return R.fail("文本为空");
        // 语音合成：返回文本，前端浏览器TTS兜底
        return R.ok(Map.of("audioUrl", null, "text", text));
    }
}
