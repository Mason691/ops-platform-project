package com.aip.ops.controller;

import com.aip.ops.entity.PublicAccountConfig;
import com.aip.ops.kafka.AiChatTaskProducer;
import com.aip.ops.kafka.ChatResultMessage;
import com.aip.ops.service.AiChatTaskService;
import com.aip.ops.service.SseEmitterManager;
import com.aip.ops.service.impl.PublicAccountConfigServiceImpl;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
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
public class AiChatProxyController {

    private final RestTemplate restTemplate;
    private final RestTemplate asrRestTemplate;
    private final PublicAccountConfigServiceImpl publicAccountConfigService;
    private final AiChatTaskService aiChatTaskService;
    private final AiChatTaskProducer aiChatTaskProducer;
    private final SseEmitterManager sseEmitterManager;
    private final ObjectMapper objectMapper;

    public AiChatProxyController(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                                  @Qualifier("asrRestTemplate") RestTemplate asrRestTemplate,
                                  PublicAccountConfigServiceImpl publicAccountConfigService,
                                  AiChatTaskService aiChatTaskService,
                                  AiChatTaskProducer aiChatTaskProducer,
                                  SseEmitterManager sseEmitterManager,
                                  ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.asrRestTemplate = asrRestTemplate;
        this.publicAccountConfigService = publicAccountConfigService;
        this.aiChatTaskService = aiChatTaskService;
        this.aiChatTaskProducer = aiChatTaskProducer;
        this.sseEmitterManager = sseEmitterManager;
        this.objectMapper = objectMapper;
    }

    @Value("${platform.base-url}")
    private String platformBaseUrl;

    @Value("${platform.internal-token}")
    private String internalToken;

    @Value("${spring.kafka.enabled:false}")
    private boolean kafkaEnabled;

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
        } catch (HttpClientErrorException e) {
            String detail = extractErrorDetail(e.getResponseBodyAsString());
            log.error("AI chat 鉴权/客户端错误: status={}, detail={}", e.getStatusCode(), detail, e);
            return R.fail(httpClientErrorMessage(e));
        } catch (HttpServerErrorException e) {
            String detail = extractErrorDetail(e.getResponseBodyAsString());
            log.error("AI chat 底座服务错误: status={}, detail={}", e.getStatusCode(), detail, e);
            return R.fail("AI 服务暂时不可用（HTTP " + e.getStatusCode().value() + "），请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("AI chat 网络异常: {}", e.getMessage(), e);
            return R.fail(resourceAccessErrorMessage(e));
        } catch (Exception e) {
            log.error("AI chat 调用失败: {}", e.getMessage(), e);
            return R.fail("AI 服务异常，请联系管理员");
        }
    }

    // ═══ 异步对话 ═══

    /**
     * 异步 AI 对话：提交任务到 Kafka，立即返回 taskId。
     * Kafka 禁用时降级为同步调用。
     */
    @PostMapping("/chat/async")
    public R<?> chatAsync(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String message = (String) body.get("message");
        if (message == null || message.isBlank()) return R.fail("消息不能为空");

        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long convId = body.get("conversationId") != null
                ? Long.valueOf(body.get("conversationId").toString()) : null;

        // Kafka 禁用时降级为同步调用
        if (!kafkaEnabled) {
            log.debug("Kafka disabled, falling back to sync chat for userId={}", userId);
            return chat(body, request);
        }

        // 异步模式：创建任务 → 发送 Kafka → 立即返回
        String taskId = aiChatTaskProducer.publishTask(
                corpId, userId, null, convId, message,
                "ops-platform", null, null);

        aiChatTaskService.createTask(taskId, corpId, userId, null, convId,
                message, "ops-platform", null);

        return R.ok(Map.of("taskId", taskId, "status", "PROCESSING"));
    }

    /**
     * SSE 流式监听任务状态和结果。
     * 前端连接此端点后，会实时收到 status 和 result 事件。
     */
    @GetMapping("/chat/{taskId}/stream")
    public SseEmitter streamStatus(@PathVariable String taskId) {
        SseEmitter emitter = sseEmitterManager.createEmitter(taskId);

        // 检查是否已有结果（Consumer 已处理完，但 SSE 还没来得及连接）
        try {
            var existingTask = aiChatTaskService.getByTaskId(taskId);
            if (existingTask != null && "COMPLETED".equals(existingTask.getStatus())) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "result");
                result.put("taskId", taskId);
                result.put("status", "COMPLETED");
                result.put("reply", existingTask.getReply());
                result.put("conversationId", existingTask.getConversationId());
                result.put("partialResults", existingTask.getPartial() != null && existingTask.getPartial() == 1);
                emitter.send(SseEmitter.event().name("result").data(objectMapper.writeValueAsString(result)));
                emitter.complete();
                return emitter;
            } else if (existingTask != null && "FAILED".equals(existingTask.getStatus())) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "result");
                result.put("taskId", taskId);
                result.put("status", "FAILED");
                result.put("reply", existingTask.getErrorMsg());
                emitter.send(SseEmitter.event().name("result").data(objectMapper.writeValueAsString(result)));
                emitter.complete();
                return emitter;
            }
        } catch (Exception e) {
            log.debug("Error checking existing task for SSE: {}", e.getMessage());
        }

        return emitter;
    }

    /**
     * 轮询任务状态（SSE 不可用时的兜底方案，如微信小程序）。
     */
    @GetMapping("/chat/{taskId}/status")
    public R<Map<String, Object>> taskStatus(@PathVariable String taskId) {
        var task = aiChatTaskService.getByTaskId(taskId);
        if (task == null) return R.fail("任务不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("status", task.getStatus());
        result.put("phase", task.getPhase());
        result.put("progressMsg", task.getProgressMsg());
        result.put("reply", task.getReply());
        result.put("errorMsg", task.getErrorMsg());
        result.put("partial", task.getPartial() != null && task.getPartial() == 1);
        result.put("conversationId", task.getConversationId());
        return R.ok(result);
    }

    // ═══ 配置与问候 ═══

    /**
     * AI 配置（公开）— 代理到平台 GreetingService，返回动态个性化问候。
     * 尝试转发用户 JWT 以获取个性化数据；无 JWT 时返回通用问候。
     */
    @GetMapping("/config")
    public R<Map<String, Object>> aiConfig(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        // Only try the platform call if the user has a JWT (authenticated),
        // otherwise skip directly to fallback for unauthenticated visitors.
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", authHeader);
                ResponseEntity<Map> resp = restTemplate.exchange(
                        platformBaseUrl + "/api/ai/greeting",
                        HttpMethod.GET, new HttpEntity<>(headers), Map.class);
                if (resp.getBody() != null) {
                    Object data = resp.getBody().get("data");
                    if (data instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> d = (Map<String, Object>) data;
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("nickname", d.getOrDefault("nickname", "小金"));
                        result.put("avatar", d.getOrDefault("avatar", ""));
                        result.put("greeting", d.getOrDefault("greetingText", "您好！有什么可以帮您？"));
                        result.put("hasPendingTasks", d.getOrDefault("hasPendingTasks", false));
                        result.put("pendingTaskCount", d.getOrDefault("pendingTaskCount", 0));
                        result.put("voiceEnabled", true);
                        return R.ok(result);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch greeting from platform, using fallback: {}", e.getMessage());
            }
        }
        // Fallback for unauthenticated visitors
        Map<String, Object> fb = new LinkedHashMap<>();
        fb.put("nickname", "小金");
        fb.put("greeting", "您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？");
        fb.put("voiceEnabled", true);
        return R.ok(fb);
    }

    @GetMapping("/profile")
    public R<Map<String, Object>> aiProfile(HttpServletRequest request) {
        // Delegate to /config for consistency — both return the same greeting data
        return aiConfig(request);
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
            // 不手动 setContentType，让 FormHttpMessageConverter 自动生成带 boundary 的 multipart header

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

            ResponseEntity<Map> resp = asrRestTemplate.exchange(
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
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("ASR API Key 无效 corpId={}", resolveCorpId(request), e);
            return R.fail("ASR API Key 无效，请联系管理员配置语音识别服务");
        } catch (HttpClientErrorException.Forbidden e) {
            log.error("ASR 服务拒绝访问 corpId={}", resolveCorpId(request), e);
            return R.fail("ASR 服务拒绝访问，请检查账号权限");
        } catch (HttpClientErrorException e) {
            log.error("ASR 请求错误 corpId={}: status={}", resolveCorpId(request), e.getStatusCode(), e);
            return R.fail("语音识别请求错误（HTTP " + e.getStatusCode().value() + "）");
        } catch (HttpServerErrorException e) {
            log.error("ASR 服务端错误 corpId={}: status={}", resolveCorpId(request), e.getStatusCode(), e);
            return R.fail("语音识别服务异常，请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("ASR 网络异常 corpId={}: {}", resolveCorpId(request), e.getMessage(), e);
            return R.fail(resourceAccessErrorMessage(e));
        } catch (Exception e) {
            log.error("ASR 识别失败 corpId={}: {}", resolveCorpId(request), e.getMessage(), e);
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
            // 不手动 setContentType，让 FormHttpMessageConverter 自动生成带 boundary 的 multipart header

            org.springframework.core.io.ByteArrayResource resource =
                    new org.springframework.core.io.ByteArrayResource(audioBytes) {
                        @Override public String getFilename() { return filename; }
                    };
            org.springframework.util.MultiValueMap<String, Object> parts =
                    new org.springframework.util.LinkedMultiValueMap<>();
            parts.add("audio", resource);

            ResponseEntity<Map> resp = asrRestTemplate.exchange(
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
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("ASR API Key 无效 corpId={}", corpId, e);
            return R.fail("ASR API Key 无效，请联系管理员配置语音识别服务");
        } catch (HttpClientErrorException.Forbidden e) {
            log.error("ASR 服务拒绝访问 corpId={}", corpId, e);
            return R.fail("ASR 服务拒绝访问，请检查账号权限");
        } catch (HttpClientErrorException e) {
            log.error("ASR 请求错误 corpId={}: status={}", corpId, e.getStatusCode(), e);
            return R.fail("语音识别请求错误（HTTP " + e.getStatusCode().value() + "）");
        } catch (HttpServerErrorException e) {
            log.error("ASR 服务端错误 corpId={}: status={}", corpId, e.getStatusCode(), e);
            return R.fail("语音识别服务异常，请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("ASR 网络异常 corpId={}: {}", corpId, e.getMessage(), e);
            return R.fail(resourceAccessErrorMessage(e));
        } catch (Exception e) {
            log.error("ASR 识别失败 corpId={}: {}", corpId, e.getMessage(), e);
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
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("TTS API Key 无效 corpId={}", resolveCorpId(request), e);
            return R.fail("TTS API Key 无效，请联系管理员更新语音配置");
        } catch (HttpClientErrorException.Forbidden e) {
            log.error("TTS 服务拒绝访问 corpId={}", resolveCorpId(request), e);
            return R.fail("TTS 服务拒绝访问，请检查账号权限");
        } catch (HttpClientErrorException e) {
            log.error("TTS 请求错误 corpId={}: status={}", resolveCorpId(request), e.getStatusCode(), e);
            return R.fail("语音合成请求错误（HTTP " + e.getStatusCode().value() + "）");
        } catch (HttpServerErrorException e) {
            log.error("TTS 服务端错误 corpId={}: status={}", resolveCorpId(request), e.getStatusCode(), e);
            return R.fail("语音合成服务异常，请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("TTS 网络异常 corpId={}: {}", resolveCorpId(request), e.getMessage(), e);
            return R.fail(resourceAccessErrorMessage(e));
        } catch (Exception e) {
            log.error("TTS 合成失败 corpId={}: {}", resolveCorpId(request), e.getMessage(), e);
            return R.fail("语音合成暂不可用");
        }
    }

    // ═══ 会员 AI 设置代理 ═══

    /**
     * 代理会员 AI 设置请求到底座平台。
     * 透传 JWT Authorization，底座通过 member_id 识别会员。
     */
    private R<?> proxyMemberAi(String path, HttpServletRequest request) {
        return proxyMemberAiMethod(path, null, HttpMethod.GET, request);
    }

    private R<?> proxyMemberAiWithBody(String path, Object body, HttpServletRequest request) {
        return proxyMemberAiMethod(path, body, HttpMethod.PUT, request);
    }

    private R<?> proxyMemberAiPut(String path, Object body, HttpServletRequest request) {
        return proxyMemberAiMethod(path, body, HttpMethod.PUT, request);
    }

    private R<?> proxyMemberAiDelete(String path, HttpServletRequest request) {
        return proxyMemberAiMethod(path, null, HttpMethod.DELETE, request);
    }

    private R<?> proxyMemberAiMethod(String path, Object body, HttpMethod method,
                                      HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", request.getHeader("Authorization"));
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            HttpEntity<?> entity = body != null
                    ? new HttpEntity<>(body, headers)
                    : new HttpEntity<>(headers);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    platformBaseUrl + "/api/ai/member" + path,
                    method, entity, Map.class);
            return R.ok(resp.getBody() != null ? resp.getBody().get("data") : null);
        } catch (HttpClientErrorException e) {
            String detail = extractErrorDetail(e.getResponseBodyAsString());
            log.error("Member AI proxy client error: path={} status={}", path, e.getStatusCode(), e);
            return R.fail(detail != null ? detail : httpClientErrorMessage(e));
        } catch (Exception e) {
            log.error("Member AI proxy error: path={}", path, e);
            return R.fail("服务异常，请稍后重试");
        }
    }

    /** 通用 GET 代理到平台 API（JWT 透传） */
    private R<?> proxyPlatformGet(String platformPath, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", request.getHeader("Authorization"));
        try {
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    platformBaseUrl + platformPath,
                    HttpMethod.GET, entity, Map.class);
            Map body = resp.getBody();
            if (body == null) return R.fail("平台无响应");
            // Check business error code before extracting data
            Object code = body.get("code");
            if (code instanceof Number && ((Number) code).intValue() != 200) {
                String msg = body.get("msg") instanceof String ? (String) body.get("msg") : "平台业务错误";
                return R.fail(msg);
            }
            if (body.containsKey("data")) {
                return R.ok(body.get("data"));
            }
            return R.ok(body);
        } catch (HttpClientErrorException e) {
            String detail = extractErrorDetail(e.getResponseBodyAsString());
            log.error("Platform proxy client error: path={} status={}", platformPath, e.getStatusCode(), e);
            return R.fail(detail != null ? detail : httpClientErrorMessage(e));
        } catch (HttpServerErrorException e) {
            log.error("Platform proxy server error: path={} status={}", platformPath, e.getStatusCode(), e);
            String detail = extractErrorDetail(e.getResponseBodyAsString());
            return R.fail(detail != null ? detail : "AI 服务暂时不可用（HTTP " + e.getStatusCode().value() + "）");
        } catch (ResourceAccessException e) {
            log.error("Platform proxy network error: path={}", platformPath, e);
            String detail = e.getMessage();
            if (detail != null && (detail.contains("timeout") || detail.contains("Timeout"))) {
                return R.fail("AI 服务响应超时，请稍后重试");
            }
            return R.fail("AI 服务连接失败，请检查网络");
        } catch (Exception e) {
            log.error("Platform proxy error: path={}", platformPath, e);
            return R.fail("服务异常，请稍后重试");
        }
    }

    @GetMapping("/member/profile")
    public R<?> memberProfile(HttpServletRequest request) {
        return proxyMemberAi("/profile", request);
    }

    @PutMapping("/member/profile/style")
    public R<?> memberUpdateStyle(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return proxyMemberAiWithBody("/profile/style", body, request);
    }

    @PutMapping("/member/profile/persona")
    public R<?> memberUpdatePersona(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return proxyMemberAiWithBody("/profile/persona", body, request);
    }

    @GetMapping("/member/preferences")
    public R<?> memberPreferences(HttpServletRequest request) {
        return proxyMemberAi("/preferences", request);
    }

    @PutMapping("/member/preferences")
    public R<?> memberSetPreference(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return proxyMemberAiWithBody("/preferences", body, request);
    }

    @DeleteMapping("/member/preferences/{key}")
    public R<?> memberDeletePreference(@PathVariable String key, HttpServletRequest request) {
        return proxyMemberAiDelete("/preferences/" + key, request);
    }

    @GetMapping("/member/habits")
    public R<?> memberHabits(HttpServletRequest request) {
        return proxyMemberAi("/habits", request);
    }

    @PutMapping("/member/habits/{key}/acknowledge")
    public R<?> memberAcknowledgeHabit(@PathVariable String key, HttpServletRequest request) {
        return proxyMemberAiPut("/habits/" + key + "/acknowledge", Map.of(), request);
    }

    @DeleteMapping("/member/habits/{key}")
    public R<?> memberDeleteHabit(@PathVariable String key, HttpServletRequest request) {
        return proxyMemberAiDelete("/habits/" + key, request);
    }

    @GetMapping("/member/memories")
    public R<?> memberMemories(HttpServletRequest request) {
        return proxyMemberAi("/memories", request);
    }

    @PutMapping("/member/memories/{id}/importance")
    public R<?> memberUpdateMemoryImportance(@PathVariable Long id,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        return proxyMemberAiWithBody("/memories/" + id + "/importance", body, request);
    }

    // ═══ 技能代理 ═══

    /** 获取当前组织已安装的技能列表（代理到平台 /api/skill/installed） */
    @GetMapping("/skills/installed")
    public R<?> installedSkills(HttpServletRequest request) {
        return proxyPlatformGet("/api/skill/installed", request);
    }

    // ═══ 辅助 ═══

    /** 将 HTTP 客户端错误映射为用户可读的中文提示 */
    private String httpClientErrorMessage(HttpClientErrorException e) {
        return switch (e.getStatusCode().value()) {
            case 401 -> "AI 服务鉴权失败，请联系管理员检查 API Key 配置";
            case 403 -> "AI 服务拒绝访问，请检查账号权限";
            case 404 -> "AI 服务端点不存在，请检查服务配置";
            case 429 -> "AI 服务调用过于频繁，请稍后再试";
            default -> {
                String detail = extractErrorDetail(e.getResponseBodyAsString());
                yield detail != null ? detail : "AI 服务请求错误（HTTP " + e.getStatusCode().value() + "）";
            }
        };
    }

    /** 将网络/IO 异常映射为用户可读的中文提示 */
    private String resourceAccessErrorMessage(ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return "AI 服务响应超时，请稍后重试";
        }
        if (cause instanceof ConnectException) {
            return "AI 服务连接失败，请检查网络";
        }
        return "AI 服务网络异常，请稍后重试";
    }

    /** 尝试从 JSON 响应体中提取 msg 字段 */
    private String extractErrorDetail(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            int idx = body.indexOf("\"msg\"");
            if (idx < 0) return null;
            int start = body.indexOf("\"", idx + 5);
            if (start < 0) return null;
            int end = body.indexOf("\"", start + 1);
            if (end < 0) return null;
            String msg = body.substring(start + 1, end);
            return msg.isEmpty() ? null : msg;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long resolveCorpId(HttpServletRequest request) {
        try { return SecurityUtils.getCurrentCorpId(); } catch (Exception e) { /* no JWT */ }
        String headerVal = request.getHeader("X-Corp-Id");
        if (headerVal != null) {
            try { return Long.valueOf(headerVal); } catch (NumberFormatException ignored) {}
        }
        return 4L; // 默认金厨美家运营商
    }
}
