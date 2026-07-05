package com.aip.ops.controller;

import com.aip.ops.entity.OpsMember;
import com.aip.ops.kafka.AiChatTaskProducer;
import com.aip.ops.mapper.OpsMemberMapper;
import com.aip.ops.service.AiChatTaskService;
import com.aip.ops.service.EmployeeService;
import com.aip.ops.service.SseEmitterManager;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工 AI 助手 — 对接底座平台 Hermes Agent（与会员 AI 共用同一主 Agent）。
 * <p>
 * 注入员工身份上下文（公司、角色、手机号），底座 Agent 据此激活员工专用工具
 * （如 {@code ops_query_employee_business_data}），同时保留全部平台通用能力：
 * 组织知识库搜索、数据分析、技能调用、报告生成。
 * <p>
 * 语音端点（ASR/TTS）与角色无关，统一走 {@link AiChatProxyController}。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/ai/employee")
public class EmployeeAiController {

    private final RestTemplate restTemplate;
    private final EmployeeService employeeService;
    private final OpsMemberMapper memberMapper;
    private final AiChatTaskService aiChatTaskService;
    private final AiChatTaskProducer aiChatTaskProducer;
    private final SseEmitterManager sseEmitterManager;
    private final ObjectMapper objectMapper;

    public EmployeeAiController(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                                 EmployeeService employeeService,
                                 OpsMemberMapper memberMapper,
                                 AiChatTaskService aiChatTaskService,
                                 AiChatTaskProducer aiChatTaskProducer,
                                 SseEmitterManager sseEmitterManager,
                                 ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.employeeService = employeeService;
        this.memberMapper = memberMapper;
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

    // ═══ Chat ═══

    @PostMapping("/chat")
    public R<?> chat(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String message = (String) body.get("message");
        if (message == null || message.isBlank()) return R.fail("消息不能为空");

        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long convId = body.get("conversationId") != null
                ? Long.valueOf(body.get("conversationId").toString()) : null;

        // Enrich with employee context: resolve phone + merge with frontend-supplied context
        @SuppressWarnings("unchecked")
        Map<String, Object> employeeContext = body.get("employeeContext") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) body.get("employeeContext")) : new LinkedHashMap<>();
        employeeContext.putIfAbsent("phone", resolvePhone(userId, corpId));

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("message", message);
        reqBody.put("conversationId", convId);
        reqBody.put("corpId", corpId);
        reqBody.put("userId", userId);
        reqBody.put("subsystem", "ops-platform");
        reqBody.put("employeeContext", employeeContext);

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
            log.error("Employee AI chat 鉴权/客户端错误 userId={}: status={}, detail={}",
                    userId, e.getStatusCode(), detail, e);
            return R.fail(httpClientErrorMessage(e));
        } catch (HttpServerErrorException e) {
            String detail = extractErrorDetail(e.getResponseBodyAsString());
            log.error("Employee AI chat 底座服务错误 userId={}: status={}, detail={}",
                    userId, e.getStatusCode(), detail, e);
            return R.fail("AI 服务暂时不可用（HTTP " + e.getStatusCode().value() + "），请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("Employee AI chat 网络异常 userId={}: {}", userId, e.getMessage(), e);
            return R.fail(resourceAccessErrorMessage(e));
        } catch (Exception e) {
            log.error("Employee AI chat 调用失败 userId={}: {}", userId, e.getMessage(), e);
            return R.fail("AI 服务异常，请联系管理员");
        }
    }

    // ═══ Async Chat ═══

    /**
     * 异步员工 AI 对话：提交任务到 Kafka，立即返回 taskId。
     */
    @PostMapping("/chat/async")
    public R<?> chatAsync(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String message = (String) body.get("message");
        if (message == null || message.isBlank()) return R.fail("消息不能为空");

        Long userId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        Long convId = body.get("conversationId") != null
                ? Long.valueOf(body.get("conversationId").toString()) : null;

        // Enrich with employee context
        @SuppressWarnings("unchecked")
        Map<String, Object> employeeContext = body.get("employeeContext") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) body.get("employeeContext")) : new LinkedHashMap<>();
        employeeContext.putIfAbsent("phone", resolvePhone(userId, corpId));

        // Kafka 禁用时降级为同步调用
        if (!kafkaEnabled) {
            log.debug("Kafka disabled, falling back to sync employee chat for userId={}", userId);
            return chat(body, request);
        }

        String taskId = aiChatTaskProducer.publishTask(
                corpId, userId, null, convId, message,
                "ops-platform", employeeContext, null);

        String empCtxJson = null;
        try { empCtxJson = objectMapper.writeValueAsString(employeeContext); } catch (Exception ignored) {}

        aiChatTaskService.createTask(taskId, corpId, userId, null, convId,
                message, "ops-platform", empCtxJson);

        return R.ok(Map.of("taskId", taskId, "status", "PROCESSING"));
    }

    /**
     * SSE 流式监听员工 AI 对话任务状态。
     */
    @GetMapping("/chat/{taskId}/stream")
    public SseEmitter streamStatus(@PathVariable String taskId) {
        SseEmitter emitter = sseEmitterManager.createEmitter(taskId);

        try {
            var existingTask = aiChatTaskService.getByTaskId(taskId);
            if (existingTask != null && "COMPLETED".equals(existingTask.getStatus())) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "result");
                result.put("taskId", taskId);
                result.put("status", "COMPLETED");
                result.put("reply", existingTask.getReply());
                result.put("partialResults", existingTask.getPartial() != null && existingTask.getPartial() == 1);
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
     * 轮询员工 AI 对话任务状态。
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

    // ═══ Config & Profile ═══

    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        return R.ok(Map.of(
                "nickname", "小金",
                "greeting", "您好！我是您的企业AI助手，对接底座平台智能引擎，"
                        + "支持组织知识库搜索、业务数据分析、技能工具调用和报告生成。",
                "voiceEnabled", true,
                "quickQuestions", List.of(
                        "本月业务数据概览", "帮我分析销售趋势",
                        "搜索公司知识库", "生成业务分析报告"
                )
        ));
    }

    @GetMapping("/profile")
    public R<Map<String, Object>> profile() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Try to personalize with employee identity
            Map<String, Object> identity = employeeService.checkIdentity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> companies = identity.get("companies") instanceof List
                    ? (List<Map<String, Object>>) identity.get("companies") : List.of();

            if (!companies.isEmpty()) {
                Map<String, Object> c = companies.get(0);
                String corpName = c.getOrDefault("corpName", "").toString();
                String roleName = c.getOrDefault("roleName", "").toString();
                String greeting = "您好！我是您的企业AI助手～\n"
                        + "当前公司：" + corpName + "（" + roleName + "）\n"
                        + "支持组织知识库、数据分析、技能工具和报告生成，有什么需要？";
                result.put("greeting", greeting);
                result.put("corpName", corpName);
                result.put("roleName", roleName);
            } else {
                result.put("greeting", "您好！我是您的企业AI助手～\n"
                        + "支持组织知识库、数据分析、技能工具和报告生成，有什么需要？");
            }
            result.put("nickname", "员工");
            result.put("avatar", null);
        } catch (Exception e) {
            result.put("nickname", "员工");
            result.put("greeting", "您好！我是您的企业AI助手～\n"
                    + "支持组织知识库、数据分析、技能工具和报告生成，有什么需要？");
        }
        return R.ok(result);
    }

    // ═══ Helpers ═══

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

    private String resolvePhone(Long userId, Long corpId) {
        OpsMember member = memberMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPlatformMemberId, userId)
                .eq(OpsMember::getCorpId, corpId));
        if (member == null) {
            member = memberMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                    .eq(OpsMember::getId, userId)
                    .eq(OpsMember::getCorpId, corpId));
        }
        return member != null ? member.getPhone() : null;
    }
}
