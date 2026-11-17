package com.aip.ops.client;

import com.aip.sdk.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 平台工作流组件客户端。
 *
 * <p>ops 侧负责「按订单归属组织发起流程」与「查询当前审批人」；审批推进、待办、
 * 公众号消息全部由平台工作流组件承担。</p>
 *
 * <p>发起响应（底座 {@code InstanceView} 的 toStartView）本身已返回
 * {@code currentApprovers/currentApproverNames/nextApprovers}，这里直接解析并带回
 * 业务侧，避免“先发起再查”的时序空窗；若发起失败，返回 resolved=false +
 * 明确兜底文案，前端据此展示“审批人信息获取失败”，不再静默为空。</p>
 */
@Slf4j
@Component
public class WorkflowPlatformClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    public WorkflowPlatformClient(RestTemplate restTemplate,
                                  @Value("${platform.base-url}") String baseUrl,
                                  @Value("${platform.internal-token}") String internalToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
    }

    /** 发起流程实例，返回实例 ID + 首个审批节点的实际审批人。 */
    @SuppressWarnings("unchecked")
    public StartResult start(StartRequest request) {
        String url = baseUrl + "/api/internal/wf/instance/start";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionCode", request.definitionCode());
        body.put("businessType", request.businessType());
        body.put("businessId", request.businessId());
        body.put("businessSummary", request.businessSummary());
        body.put("businessAddress", request.businessAddress());
        body.put("requestId", request.requestId());
        body.put("corpId", request.corpId());
        body.put("initiatorUserId", request.initiatorUserId());
        body.put("initiatorName", request.initiatorName());
        try {
            Map<String, Object> resp = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), Map.class);
            if (resp == null) {
                throw BusinessException.of("工作流服务无响应");
            }
            Object code = resp.get("code");
            if (code instanceof Number n && n.intValue() != 200) {
                Object msg = resp.get("msg");
                throw BusinessException.of("发起工作流失败："
                        + (msg instanceof String s && !s.isBlank() ? s : "未知错误"));
            }
            Object data = resp.get("data");
            if (!(data instanceof Map<?, ?> instance)) {
                throw BusinessException.of("发起工作流失败：返回数据异常");
            }
            Object id = instance.get("id");
            if (!(id instanceof Number n)) {
                throw BusinessException.of("发起工作流失败：未返回实例ID");
            }
            return toStartResult(n.longValue(), (Map<String, Object>) instance);
        } catch (RestClientResponseException e) {
            log.warn("发起工作流失败 status={}, body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("工作流服务不可用，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("发起工作流异常: {}", e.getMessage());
            throw BusinessException.of("工作流服务不可用，请稍后重试");
        }
    }

    /**
     * 按实例查询当前审批人（用于结果页轮询/详情页兜底）。
     *
     * <p>底座实例详情接口需要用户 JWT；从当前请求透传员工 Bearer Token。
     * 查不到/组件不可达时返回 resolved=false + 明确文案，不抛异常拖垮成功页。</p>
     */
    @SuppressWarnings("unchecked")
    public StartResult getCurrentApprover(Long instanceId) {
        if (instanceId == null) {
            return StartResult.unresolved(null, "缺少流程实例ID，暂无法获取审批人");
        }
        String token = currentUserTokenOrNull();
        if (token == null) {
            return StartResult.unresolved(instanceId, "缺少员工身份，暂无法获取审批人");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/api/wf/instance/" + instanceId,
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null) {
                return StartResult.unresolved(instanceId, "审批人信息获取失败，请稍后重试");
            }
            Object code = body.get("code");
            if (code instanceof Number n && n.intValue() != 200) {
                Object msg = body.get("msg");
                log.warn("查询当前审批人失败 instanceId={} msg={}", instanceId, msg);
                return StartResult.unresolved(instanceId, "审批人信息获取失败，请稍后重试");
            }
            Object data = body.get("data");
            if (!(data instanceof Map<?, ?> instance)) {
                return StartResult.unresolved(instanceId, "审批人信息获取失败，请稍后重试");
            }
            return toStartResult(instanceId, (Map<String, Object>) instance);
        } catch (Exception e) {
            log.warn("查询当前审批人异常 instanceId={}: {}", instanceId, e.getMessage());
            return StartResult.unresolved(instanceId, "审批人信息获取失败，请稍后重试");
        }
    }

    /**
     * 批量查询流程进度（当前/下一节点、状态、处理人）。供员工订单列表一次取数，
     * 避免逐单调用底座；底座不可达/未返回时返回空 Map，由调用方展示“流程信息待同步”。
     */
    @SuppressWarnings("unchecked")
    public Map<Long, InstanceProgress> instanceProgressBatch(List<Long> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) return Map.of();
        List<Long> ids = instanceIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(100)
                .toList();
        if (ids.isEmpty()) return Map.of();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("instanceIds", ids);
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    baseUrl + "/api/internal/wf/instance/progress",
                    new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            if (respBody == null || !(respBody.get("code") instanceof Number n)
                    || n.intValue() != 200) {
                log.warn("查询工作流进度失败 instanceIds={} resp={}", ids, respBody);
                return Map.of();
            }
            Object data = respBody.get("data");
            if (!(data instanceof List<?> list)) return Map.of();
            Map<Long, InstanceProgress> result = new LinkedHashMap<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) continue;
                Long instanceId = row.get("instanceId") instanceof Number num
                        ? num.longValue() : null;
                if (instanceId == null) continue;
                result.put(instanceId, new InstanceProgress(
                        instanceId,
                        longOrNull(row.get("currentNodeId")),
                        str(row.get("status")),
                        str(row.get("definitionName")),
                        str(row.get("currentNodeName")),
                        intOrNull(row.get("currentNodeOrder")),
                        str(row.get("currentBizStatus")),
                        longOrNull(row.get("nextNodeId")),
                        str(row.get("nextNodeName")),
                        intOrNull(row.get("nextNodeOrder")),
                        str(row.get("nextBizStatus")),
                        listOfMaps(row.get("currentApprovers")),
                        listOfMaps(row.get("nextApprovers")),
                        str(row.get("resolveStatus")),
                        str(row.get("resolveMessage")),
                        str(row.get("startedTime")),
                        str(row.get("finishedTime"))));
            }
            return result;
        } catch (Exception e) {
            log.warn("查询工作流进度异常 instanceIds={}: {}", ids, e.getMessage());
            return Map.of();
        }
    }

    private Integer intOrNull(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private Long longOrNull(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /**
     * 读取底座任务详情，用于前端审批页的**服务端**身份校验：
     * 只有 task.approverUserId = 当前用户、task 仍 PENDING、businessId 匹配时，
     * 才允许查看申请人视角不可见的物资领用/核销详情。
     *
     * <p>透传当前员工 Bearer Token；组件不可达返回 null，调用方 fail-closed。</p>
     */
    @SuppressWarnings("unchecked")
    public TaskIdentity taskIdentity(Long taskId) {
        if (taskId == null) return null;
        String token = currentUserTokenOrNull();
        if (token == null) return null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/api/wf/task/" + taskId,
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null || !(body.get("code") instanceof Number n) || n.intValue() != 200) {
                return null;
            }
            if (!(body.get("data") instanceof Map<?, ?> data)) return null;
            Object taskObj = data.get("task");
            Object instanceObj = data.get("instance");
            if (!(taskObj instanceof Map<?, ?> task) || !(instanceObj instanceof Map<?, ?> instance)) {
                return null;
            }
            Object approver = task.get("approverUserId");
            Long approverUserId = approver instanceof Number num ? num.longValue() : null;
            return new TaskIdentity(approverUserId, str(task.get("status")),
                    str(instance.get("businessType")), str(instance.get("businessId")));
        } catch (Exception e) {
            log.warn("查询工作流任务详情失败 taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * 解析底座发起响应 / 实例详情中的审批人字段。
     *
     * <p>优先 currentApprovers（当前待办），其次 nextApprovers（下一节点），
     * 兼容旧响应回退 tasks 中 PENDING 任务。会签节点有 PENDING 时只展示待处理人。</p>
     */
    private StartResult toStartResult(Long instanceId, Map<String, Object> data) {
        List<Map<String, Object>> approvers = listOfMaps(data.get("currentApprovers"));
        if (approvers.isEmpty()) {
            approvers = listOfMaps(data.get("nextApprovers"));
        }
        if (approvers.isEmpty()) {
            for (Map<String, Object> task : listOfMaps(data.get("tasks"))) {
                if ("PENDING".equals(str(task.get("status")))) {
                    approvers.add(task);
                }
            }
        }
        boolean hasPending = approvers.stream()
                .anyMatch(row -> "PENDING".equals(str(row.get("status"))));
        if (hasPending) {
            approvers = approvers.stream()
                    .filter(row -> {
                        String status = str(row.get("status"));
                        return isBlank(status) || "PENDING".equals(status);
                    })
                    .toList();
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        for (Map<String, Object> row : approvers) {
            String name = str(row.get("userName"));
            if (isBlank(name)) {
                name = str(row.get("approverName"));
            }
            if (!isBlank(name)) {
                names.add(name.trim());
            }
            String role = str(row.get("roleName"));
            if (!isBlank(role)) {
                roles.add(role.trim());
            }
        }
        boolean resolved = !names.isEmpty();
        String message = resolved ? null : fallbackMessage(data);
        return new StartResult(instanceId,
                resolved ? String.join("、", names) : null,
                roles.isEmpty() ? null : String.join("、", roles),
                resolved, message);
    }

    private String fallbackMessage(Map<String, Object> data) {
        String status = str(data.get("status"));
        if ("COMPLETED".equals(status)) {
            return "审批已完成";
        }
        if ("REJECTED".equals(status)) {
            return "审批已驳回";
        }
        if ("TERMINATED".equals(status)) {
            return "审批已终止";
        }
        String resolveMessage = str(data.get("resolveMessage"));
        return isBlank(resolveMessage) ? "待分配审批人，请联系管理员" : resolveMessage;
    }

    /** 当前请求的 Bearer Token（员工 H5 发起/查询时透传）。 */
    private String currentUserTokenOrNull() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String header = attrs.getRequest().getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7).trim();
                return token.isEmpty() ? null : token;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        }
        return result;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record StartRequest(String definitionCode, String businessType, String businessId,
                               String businessSummary, String businessAddress, String requestId,
                               Long corpId, Long initiatorUserId, String initiatorName) {
    }

    /** 审批任务的归属与状态（服务端权限校验用）。 */
    public record TaskIdentity(Long approverUserId, String status, String businessType,
                               String businessId) {
    }

    /** 批量流程进度（订单列表/详情展示用）。 */
    public record InstanceProgress(Long instanceId, Long currentNodeId, String status,
                                   String definitionName, String currentNodeName,
                                   Integer currentNodeOrder, String currentBizStatus,
                                   Long nextNodeId, String nextNodeName, Integer nextNodeOrder,
                                   String nextBizStatus,
                                   List<Map<String, Object>> currentApprovers,
                                   List<Map<String, Object>> nextApprovers,
                                   String resolveStatus, String resolveMessage,
                                   String startedTime, String finishedTime) {
    }

    /**
     * 发起/查询结果：实例 ID + 审批人姓名/角色 + 是否解析成功 + 兜底文案。
     * resolved=false 时前端不得静默为空，必须展示 message。
     */
    public record StartResult(Long instanceId, String approverName, String approverRole,
                              boolean resolved, String message) {
        public static StartResult unresolved(Long instanceId, String message) {
            return new StartResult(instanceId, null, null, false, message);
        }
    }
}
