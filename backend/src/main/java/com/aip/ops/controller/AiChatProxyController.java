package com.aip.ops.controller;

import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * AI 助手代理 — 对接底座平台 AI 引擎（对话、ASR、TTS）。
 * <p>
 * 底座通过内部 API（X-Internal-Token 鉴权）提供语音识别和合成能力。
 * 对话使用 JWT 透传，底座根据 member_id 关联长期记忆。
 * <p>
 * 实现参照：mall-project MiniAiController。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/ai")
@RequiredArgsConstructor
public class AiChatProxyController {

    private final RestTemplate restTemplate;

    @Value("${platform.base-url}")
    private String platformBaseUrl;

    @Value("${platform.internal-token}")
    private String internalToken;

    // ═══ 对话 ═══

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

    // ═══ 配置与问候 ═══

    @GetMapping("/config")
    public R<Map<String, Object>> aiConfig() {
        return R.ok(Map.of(
                "nickname", "小金",
                "greeting", "您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？",
                "voiceEnabled", true
        ));
    }

    @GetMapping("/profile")
    public R<Map<String, Object>> aiProfile() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            result.put("nickname", "燃气用户");
            result.put("avatar", null);
            result.put("greeting", "您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？");
        } catch (Exception e) {
            result.put("nickname", "访客");
            result.put("greeting", "你好！我是你的燃气小助手，可以帮你选商品、查积分、找优惠～\n登录后更懂你。");
        }
        return R.ok(result);
    }

    // ═══ 语音识别（ASR）— 参照 MiniAiController.asr() ═══

    /**
     * 语音识别中转：接收前端上传的音频 → 转发到底座 /api/internal/asr/recognize。
     * 接受 multipart/form-data（与 mall 项目一致）：前端用 FormData 上传 blob。
     */
    @PostMapping("/voice/recognize")
    public R<?> voiceRecognize(@RequestParam("audio") MultipartFile audio,
                                HttpServletRequest request) {
        if (audio.isEmpty()) return R.fail("音频数据为空");
        try {
            Long corpId = resolveCorpId(request);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Token", internalToken);
            headers.set("X-Corp-Id", String.valueOf(corpId));
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            org.springframework.core.io.ByteArrayResource resource =
                    new org.springframework.core.io.ByteArrayResource(audio.getBytes()) {
                        @Override public String getFilename() {
                            return audio.getOriginalFilename() != null
                                    ? audio.getOriginalFilename() : "audio.webm";
                        }
                    };
            org.springframework.util.MultiValueMap<String, Object> parts =
                    new org.springframework.util.LinkedMultiValueMap<>();
            parts.add("audio", resource);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    platformBaseUrl + "/api/internal/asr/recognize",
                    HttpMethod.POST, new HttpEntity<>(parts, headers), Map.class);

            if (resp.getBody() != null) {
                Object data = resp.getBody().get("data");
                if (data instanceof Map) {
                    Object text = ((Map<?, ?>) data).get("text");
                    if (text != null) return R.ok(Map.of("text", text.toString()));
                }
                return R.ok(data);
            }
            return R.fail("语音识别服务返回空结果");
        } catch (Exception e) {
            log.warn("ASR 识别失败 corpId={}: {}", resolveCorpId(request), e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                return R.fail("ASR API Key 无效，请联系管理员配置语音识别服务");
            }
            return R.fail("语音识别服务暂不可用，请稍后重试");
        }
    }

    // ═══ 语音合成（TTS）— 参照 MiniAiController.tts() ═══

    /**
     * TTS 语音合成中转：接收文本 → 转发到底座 /api/internal/tts/synthesize。
     * 返回 base64 data URL 可直接播放。
     */
    @PostMapping("/voice/synthesize")
    public R<?> voiceSynthesize(@RequestBody Map<String, Object> body,
                                 HttpServletRequest request) {
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) return R.fail("合成文本不能为空");

        Long corpId = resolveCorpId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        headers.set("X-Corp-Id", String.valueOf(corpId));
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    platformBaseUrl + "/api/internal/tts/synthesize",
                    HttpMethod.POST, new HttpEntity<>(Map.of("text", text), headers), Map.class);

            if (resp.getBody() != null) {
                Object data = resp.getBody().get("data");
                if (data instanceof Map) {
                    Object audioUrl = ((Map<?, ?>) data).get("audioUrl");
                    if (audioUrl != null) return R.ok(Map.of("audioUrl", audioUrl.toString()));
                }
                // 平台返回了错误信息
                String errMsg = resp.getBody().get("msg") != null
                        ? resp.getBody().get("msg").toString() : "TTS 服务异常";
                return R.fail(errMsg);
            }
            return R.fail("TTS 服务暂不可用");
        } catch (Exception e) {
            log.warn("TTS 合成失败 corpId={}: {}", resolveCorpId(request), e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                return R.fail("TTS API Key 无效，请联系管理员更新语音配置");
            }
            return R.fail("语音合成暂不可用");
        }
    }

    // ═══ 辅助 ═══

    private Long resolveCorpId(HttpServletRequest request) {
        try { return SecurityUtils.getCurrentCorpId(); } catch (Exception e) { /* no JWT */ }
        String headerVal = request.getHeader("X-Corp-Id");
        if (headerVal != null) {
            try { return Long.valueOf(headerVal); } catch (NumberFormatException ignored) {}
        }
        return 4L; // 默认金厨美家运营商
    }
}
