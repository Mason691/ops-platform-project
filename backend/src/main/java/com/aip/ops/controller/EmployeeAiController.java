package com.aip.ops.controller;

import com.aip.ops.entity.OpsMember;
import com.aip.ops.mapper.OpsMemberMapper;
import com.aip.ops.service.EmployeeService;
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
@RequiredArgsConstructor
public class EmployeeAiController {

    private final RestTemplate restTemplate;
    private final EmployeeService employeeService;
    private final OpsMemberMapper memberMapper;

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
        } catch (Exception e) {
            log.warn("Employee AI chat 调用失败 userId={}: {}", userId, e.getMessage());
            return R.ok(Map.of("reply", "抱歉，AI 助手正在休息，请稍后重试。"));
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
