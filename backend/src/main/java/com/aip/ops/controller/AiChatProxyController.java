package com.aip.ops.controller;

import com.aip.ops.entity.PublicAccountConfig;
import com.aip.ops.service.impl.PublicAccountConfigServiceImpl;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private final PublicAccountConfigServiceImpl publicAccountConfigService;

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

    // ═══ 语音识别（微信 JSSDK 录音）— serverId 模式 ═══

    /** 微信 access_token 缓存（与 PublicAccountController 共享内存缓存） */
    private final Map<String, String>  wxTokenCache  = new ConcurrentHashMap<>();
    private final Map<String, Long>    wxTokenExpire = new ConcurrentHashMap<>();

    /**
     * 微信 JSSDK 语音识别中转：前端通过 wx.startRecord/stopRecord/uploadVoice 获取 serverId，
     * 后端下载微信服务器上的语音文件 → 转发到底座 ASR 服务。
     */
    @PostMapping("/voice/recognize-wechat")
    public R<?> voiceRecognizeWechat(@RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        String serverId = body.get("serverId") != null ? body.get("serverId").toString() : null;
        if (serverId == null || serverId.isBlank()) return R.fail("serverId 不能为空");

        Long corpId = resolveCorpId(request);
        log.info("ASR(WeChat) 开始: corpId={}, serverId={}", corpId, serverId);

        // 1. 获取微信 access_token
        String accessToken = getWxAccessToken(corpId);
        if (accessToken == null) return R.fail("获取微信 access_token 失败，请检查公众号配置");

        // 2. 从微信服务器下载语音
        byte[] audioBytes = downloadWechatMedia(accessToken, serverId);
        if (audioBytes == null || audioBytes.length == 0)
            return R.fail("从微信服务器下载语音失败");

        // 3. 转发到底座 ASR 服务
        return forwardToAsr(audioBytes, "voice.amr", corpId);
    }

    private String getWxAccessToken(Long corpId) {
        // 获取当前组织的公众号配置
        PublicAccountConfig cfg = publicAccountConfigService.getOne(
                new LambdaQueryWrapper<PublicAccountConfig>()
                        .eq(PublicAccountConfig::getCorpId, corpId)
                        .eq(PublicAccountConfig::getIsDefault, 1)
                        .eq(PublicAccountConfig::getStatus, 1)
                        .last("LIMIT 1"));
        if (cfg == null) {
            cfg = publicAccountConfigService.getOne(
                    new LambdaQueryWrapper<PublicAccountConfig>()
                            .eq(PublicAccountConfig::getCorpId, corpId)
                            .eq(PublicAccountConfig::getStatus, 1)
                            .last("LIMIT 1"));
        }
        if (cfg == null) {
            log.warn("ASR(WeChat): 未找到 corpId={} 的公众号配置", corpId);
            return null;
        }

        String key = cfg.getAppId();
        Long expire = wxTokenExpire.getOrDefault(key, 0L);
        if (System.currentTimeMillis() < expire && wxTokenCache.containsKey(key))
            return wxTokenCache.get(key);

        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"
                + "&appid=" + cfg.getAppId() + "&secret=" + cfg.getAppSecret();
        try {
            var resp = restTemplate.getForObject(url, Map.class);
            if (resp != null && resp.containsKey("access_token")) {
                String token = (String) resp.get("access_token");
                int expiresIn = ((Number) resp.get("expires_in")).intValue();
                wxTokenCache.put(key, token);
                wxTokenExpire.put(key, System.currentTimeMillis() + (expiresIn - 300) * 1000L);
                return token;
            }
            log.warn("ASR(WeChat): getAccessToken failed: {}", resp);
        } catch (Exception e) { log.error("ASR(WeChat): getAccessToken error", e); }
        return null;
    }

    private byte[] downloadWechatMedia(String accessToken, String serverId) {
        String url = "https://api.weixin.qq.com/cgi-bin/media/get?access_token="
                + accessToken + "&media_id=" + serverId;
        try {
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null
                    && resp.getBody().length > 0) {
                // 检查是否返回了 JSON 错误（而非二进制音频）
                String ct = resp.getHeaders().getContentType() != null
                        ? String.valueOf(resp.getHeaders().getContentType()) : "";
                if (ct.contains("application/json") || ct.contains("text/plain")) {
                    try {
                        String body = new String(resp.getBody());
                        if (body.contains("errcode")) {
                            log.error("ASR(WeChat): 下载媒体失败: {}", body);
                            return null;
                        }
                    } catch (Exception ignored) {}
                }
                log.info("ASR(WeChat): 下载语音成功, size={}", resp.getBody().length);
                return resp.getBody();
            }
            log.error("ASR(WeChat): 下载媒体异常, HTTP {}", resp.getStatusCode());
        } catch (Exception e) {
            log.error("ASR(WeChat): 下载媒体失败: {}", e.getMessage());
        }
        return null;
    }

    /** 将音频字节转发到底座 ASR 管线 */
    private R<?> forwardToAsr(byte[] audioBytes, String filename, Long corpId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Token", internalToken);
            headers.set("X-Corp-Id", String.valueOf(corpId));
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            org.springframework.core.io.ByteArrayResource resource =
                    new org.springframework.core.io.ByteArrayResource(audioBytes) {
                        @Override public String getFilename() { return filename; }
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
            log.warn("ASR 识别失败 corpId={}: {}", corpId, e.getMessage());
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
