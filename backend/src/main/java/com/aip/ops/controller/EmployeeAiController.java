package com.aip.ops.controller;

import com.aip.ops.entity.OpsMember;
import com.aip.ops.mapper.OpsMemberMapper;
import com.aip.ops.service.EmployeeService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Employee AI assistant proxy — separate from the member-facing {@link AiChatProxyController}.
 * <p>
 * Enriches AI requests with employee identity context (company, role, phone) so the
 * base platform's HermesCoreAgent can switch to an employee persona and activate
 * employee-specific tools (e.g. {@code ops_query_employee_business_data}).
 * <p>
 * Voice endpoints (ASR/TTS) are persona-agnostic and remain in {@link AiChatProxyController}.
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/ai/employee")
public class EmployeeAiController {

    private final RestTemplate restTemplate;
    private final EmployeeService employeeService;
    private final OpsMemberMapper memberMapper;

    public EmployeeAiController(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                                 EmployeeService employeeService,
                                 OpsMemberMapper memberMapper) {
        this.restTemplate = restTemplate;
        this.employeeService = employeeService;
        this.memberMapper = memberMapper;
    }

    @Value("${platform.base-url}")
    private String platformBaseUrl;

    @Value("${platform.internal-token}")
    private String internalToken;

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

    // ═══ Config & Profile ═══

    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        return R.ok(Map.of(
                "nickname", "小金",
                "greeting", "您好！我是您的业务数据AI助手，可以帮您查询业绩、分析数据、了解公司政策。",
                "voiceEnabled", true,
                "quickQuestions", List.of(
                        "本月销售额多少？", "我的提成明细",
                        "各品类销售情况", "帮我分析业务趋势"
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
                String greeting = "您好！我是您的业务数据AI助手～\n"
                        + "当前公司：" + corpName + "（" + roleName + "）\n"
                        + "可以帮您查询业绩、分析数据、了解提成规则，有什么需要？";
                result.put("greeting", greeting);
                result.put("corpName", corpName);
                result.put("roleName", roleName);
            } else {
                result.put("greeting", "您好！我是您的业务数据AI助手～\n"
                        + "可以帮您查询业绩、分析数据、了解公司政策，有什么需要？");
            }
            result.put("nickname", "员工");
            result.put("avatar", null);
        } catch (Exception e) {
            result.put("nickname", "员工");
            result.put("greeting", "您好！我是您的业务数据AI助手～\n"
                    + "可以帮您查询业绩、分析数据、了解公司政策，有什么需要？");
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
