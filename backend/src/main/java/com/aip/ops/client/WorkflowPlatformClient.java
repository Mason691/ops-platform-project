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

    /** 施工进度通知场景（与平台 wf_message_template.scene_code 保持一致） */
    public static final String SCENE_CONSTRUCT_PROGRESS = "CONSTRUCT_PROGRESS";
    /** 尾款结清通知场景（与平台 wf_message_template.scene_code 保持一致） */
    public static final String SCENE_BALANCE_PAID = "BALANCE_PAID";

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
     * 终止运行中的流程实例（退回草稿 / 发起作废退款）。
     * 调用方应先查实例进度；实例已结束时不要调用本方法。
     */
    public void terminate(Long instanceId, String reason, Long operatorId, String operatorName) {
        if (instanceId == null) return;
        String url = baseUrl + "/api/internal/wf/instance/" + instanceId + "/terminate";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("operatorUserId", operatorId);
        body.put("operatorName", operatorName);
        try {
            Map<String, Object> resp = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), Map.class);
            if (resp == null) {
                throw BusinessException.of("工作流服务无响应");
            }
            Object code = resp.get("code");
            if (code instanceof Number n && n.intValue() != 200) {
                Object msg = resp.get("msg");
                throw BusinessException.of("终止工作流失败："
                        + (msg instanceof String s && !s.isBlank() ? s : "未知错误"));
            }
        } catch (RestClientResponseException e) {
            log.warn("终止工作流失败 status={}, body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("工作流服务不可用，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("终止工作流异常 instanceId={}: {}", instanceId, e.getMessage());
            throw BusinessException.of("工作流服务不可用，请稍后重试");
        }
    }

    /**
     * 施工员拒绝接受：以内部身份驳回「施工执行」待办，工作流按 PREVIOUS 退回派工节点。
     *
     * <p>ops 侧已校验任务归属与订单状态；平台侧 {@code requireTaskOwner} 会再次
     * 校验 operatorUserId 是否为任务处理人。formData 为空，避免触发派工节点动作。</p>
     */
    public void rejectConstructTask(Long taskId, String opinion, Long operatorUserId,
                                    String operatorName) {
        if (taskId == null) {
            throw BusinessException.of("缺少施工任务信息，请刷新待办后重试");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("opinion", opinion);
        body.put("operatorUserId", operatorUserId);
        body.put("operatorName", operatorName);
        try {
            Map<?, ?> resp = restTemplate.postForObject(
                    baseUrl + "/api/internal/wf/task/reject",
                    new HttpEntity<>(body, headers), Map.class);
            if (resp == null) {
                throw BusinessException.of("工作流服务无响应");
            }
            Object code = resp.get("code");
            if (code instanceof Number n && n.intValue() != 200) {
                Object msg = resp.get("msg");
                throw BusinessException.of("退回派工失败："
                        + (msg instanceof String s && !s.isBlank() ? s : "未知错误"));
            }
        } catch (RestClientResponseException e) {
            log.warn("施工员拒绝接受失败 status={}, body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw BusinessException.of("工作流服务不可用，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("施工员拒绝接受异常 taskId={}: {}", taskId, e.getMessage());
            throw BusinessException.of("工作流服务不可用，请稍后重试");
        }
    }

    /**
     * 施工进度公众号通知（尽力而为）：模板未配置/发送失败只记日志，不影响业务状态。
     */
    public void notifyConstructProgress(Long instanceId, Long receiverUserId, String orderNo,
                                        String actionName, String operatorName, String remark,
                                        String dedupKey) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("orderNo", orderNo == null ? "" : orderNo);
        vars.put("constructAction", actionName == null ? "" : actionName);
        vars.put("operatorName", operatorName == null ? "" : operatorName);
        vars.put("remark", remark == null ? "" : remark);
        vars.put("taskTypeLabel", "施工进度");
        vars.put("orgTaskTypeFull", "施工进度");
        vars.put("submitTime", java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        notifyBusinessProgress(SCENE_CONSTRUCT_PROGRESS, instanceId, receiverUserId, vars, dedupKey);
    }

    /**
     * 业务进度公众号通知（通用场景，尽力而为）。
     *
     * <p>模板未配置、接收人未关注、微信接口失败均只记日志，不抛异常，
     * 避免通知问题影响尾款补录/施工等业务状态。</p>
     */
    public void notifyBusinessProgress(String sceneCode, Long instanceId, Long receiverUserId,
                                       Map<String, Object> vars, String dedupKey) {
        if (sceneCode == null || sceneCode.isBlank()
                || instanceId == null || receiverUserId == null) {
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", instanceId);
        body.put("receiverUserId", receiverUserId);
        body.put("sceneCode", sceneCode);
        body.put("dedupKey", dedupKey);
        body.put("vars", vars == null ? Map.of() : vars);
        try {
            restTemplate.postForObject(baseUrl + "/api/internal/wf/notify",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.warn("业务进度通知失败 scene={}, instanceId={}, receiver={}: {}",
                    sceneCode, instanceId, receiverUserId, e.getMessage());
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
                        str(row.get("finishedTime")),
                        mapOf(row.get("currentApproval")),
                        mapOf(row.get("nextApproval"))));
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
        // 底座新版统一返回 currentApproval（角色 + 姓名 + 分组），直接透传；
        // 旧版/SCM 兼容响应才回退到本地分组拼接。
        Map<String, Object> approval = mapOf(data.get("currentApproval"));
        if (approval.isEmpty()) {
            approval = mapOf(mapOf(data.get("nextNodePreview")).get("approval"));
        }
        String displayText = str(approval.get("displayText"));
        if (isBlank(displayText)) {
            displayText = displayTextOf(approvers);
        }
        String nodeName = firstNonBlank(str(approval.get("nodeName")),
                str(data.get("nextNodeName")), str(data.get("currentNodeName")));
        String approverMode = firstNonBlank(str(approval.get("approverMode")),
                str(data.get("approverMode")));
        return new StartResult(instanceId,
                resolved ? String.join("、", names) : null,
                roles.isEmpty() ? null : String.join("、", roles),
                displayText, nodeName, approverMode, resolved, message);
    }

    /** 旧响应兜底：按角色分组拼接“角色（姓名、姓名）”，不跨节点。 */
    private String displayTextOf(List<Map<String, Object>> approvers) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : approvers) {
            String name = isBlank(str(row.get("userName")))
                    ? str(row.get("approverName")) : str(row.get("userName"));
            if (isBlank(name)) {
                continue;
            }
            String role = str(row.get("roleName"));
            String key = isBlank(role) ? "审批人" : role.trim();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(name.trim());
        }
        return groups.entrySet().stream()
                .map(entry -> entry.getKey() + "（" + String.join("、", entry.getValue())
                        + modeSuffix(approvers, entry.getKey()) + "）")
                .collect(java.util.stream.Collectors.joining("、"));
    }

    private String modeSuffix(List<Map<String, Object>> approvers, String role) {
        long count = approvers.stream()
                .filter(row -> role.equals(isBlank(str(row.get("roleName")))
                        ? "审批人" : str(row.get("roleName")).trim()))
                .count();
        if (count < 2) {
            return "";
        }
        String mode = null;
        for (Map<String, Object> row : approvers) {
            String rowRole = str(row.get("roleName"));
            if (role.equals(isBlank(rowRole) ? "审批人" : rowRole.trim())) {
                mode = str(row.get("mode"));
                break;
            }
        }
        if ("ALL".equalsIgnoreCase(mode)) return "，会签";
        if ("ANY".equalsIgnoreCase(mode)) return "，或签";
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
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
                                   String startedTime, String finishedTime,
                                   Map<String, Object> currentApproval,
                                   Map<String, Object> nextApproval) {
    }

    /**
     * 发起/查询结果：实例 ID + 审批人姓名/角色 + 统一展示文案 + 是否解析成功 + 兜底文案。
     * resolved=false 时前端不得静默为空，必须展示 message。
     */
    public record StartResult(Long instanceId, String approverName, String approverRole,
                              String displayText, String nodeName, String approverMode,
                              boolean resolved, String message) {
        public static StartResult unresolved(Long instanceId, String message) {
            return new StartResult(instanceId, null, null, null, null, null, false, message);
        }
    }
}
