package com.aip.ops.service;

import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.entity.OpsBusinessFlowRecord;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsBusinessRefund;
import com.aip.ops.mapper.OpsBusinessFlowRecordMapper;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务订单作废工作流回调。
 *
 * <p>节点动作在审批任务落库前同步执行：本类抛异常时平台审批失败、任务保持待处理。
 * 终审 {@code OPS_VOID_COMPLETE} 通过后订单置 -1 并锁定；驳回/终止恢复原业务状态。
 * 复用 {@code ops_business_flow_record} 并按 {@code taskId+actionType} 幂等。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessOrderVoidFlowActionService {

    public static final String ACTION_MINISTER = "OPS_VOID_MINISTER";
    public static final String ACTION_FINANCE = "OPS_VOID_FINANCE";
    public static final String ACTION_COMPLETE = "OPS_VOID_COMPLETE";

    private final OpsBusinessOrderMapper orderMapper;
    private final OpsBusinessFlowRecordMapper flowRecordMapper;
    private final BusinessOrderRefundService refundService;
    private final ObjectMapper objectMapper;

    /** 处理平台回调；返回业务结果（成功即允许审批推进）。 */
    public Map<String, Object> handle(WorkflowBusinessEvent event) {
        if (event == null || event.getEvent() == null) {
            throw BusinessException.of("工作流事件为空");
        }
        Long orderId = parseLong(event.getBusinessId());
        if (orderId == null) {
            throw BusinessException.of("业务回调缺少订单ID");
        }
        OpsBusinessOrder order = orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在：" + orderId);
        }
        OpsBusinessRefund refund = refundService.findByInstance(event.getInstanceId());
        if (refund == null) {
            refund = refundService.findPending(orderId);
        }
        return switch (event.getEvent()) {
            case "NODE_ACTION" -> executeNodeAction(order, refund, event);
            case "NODE_ENTERED" -> {
                refundService.markNodeEntered(refund, event);
                yield Map.of("orderId", orderId, "nodeName",
                        event.getNodeName() == null ? "" : event.getNodeName());
            }
            case "INSTANCE_COMPLETED" -> {
                if (refund != null) {
                    refundService.markApproved(order, refund, event);
                }
                yield Map.of("orderId", orderId, "voided", true);
            }
            case "INSTANCE_REJECTED" -> {
                refundService.markRejected(order, refund, event, false);
                yield Map.of("orderId", orderId, "rejected", true);
            }
            case "INSTANCE_TERMINATED" -> {
                refundService.markRejected(order, refund, event, true);
                yield Map.of("orderId", orderId, "terminated", true);
            }
            default -> throw BusinessException.of("未知工作流事件：" + event.getEvent());
        };
    }

    private Map<String, Object> executeNodeAction(OpsBusinessOrder order,
                                                  OpsBusinessRefund refund,
                                                  WorkflowBusinessEvent event) {
        String actionType = event.getActionType();
        if (actionType == null || actionType.isBlank()) {
            return Map.of("skipped", true);
        }
        OpsBusinessFlowRecord existing = findRecord(event.getTaskId(), actionType);
        if (existing != null) {
            log.info("作废流程节点动作重复回调，直接返回已有结果 taskId={}, action={}",
                    event.getTaskId(), actionType);
            return parseResult(existing.getResultJson());
        }
        if (refund == null || !OpsBusinessRefund.STATUS_PENDING.equals(refund.getStatus())) {
            // 终审动作已生效但留痕写入失败后平台重试：直接返回已作废，避免卡住任务
            if (refund != null && OpsBusinessRefund.STATUS_APPROVED.equals(refund.getStatus())
                    && ACTION_COMPLETE.equals(actionType)) {
                return Map.of("voided", true, "duplicated", true);
            }
            throw BusinessException.of("作废退款申请不存在或已结束");
        }
        // 每个节点都重新校验：审批期间订单若已出库/上报/生成采购单则 fail-closed
        refundService.assertVoidable(order);
        Map<String, Object> result = switch (actionType) {
            case ACTION_MINISTER, ACTION_FINANCE -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("approved", true);
                m.put("node", event.getNodeName());
                yield m;
            }
            case ACTION_COMPLETE -> {
                refundService.markApproved(order, refund, event);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("voided", true);
                m.put("refundId", refund.getId());
                yield m;
            }
            default -> throw BusinessException.of("未知作废节点动作：" + actionType);
        };
        saveRecord(order, event, result);
        return result;
    }

    private OpsBusinessFlowRecord findRecord(Long taskId, String actionType) {
        if (taskId == null) return null;
        return flowRecordMapper.selectOne(new LambdaQueryWrapper<OpsBusinessFlowRecord>()
                .eq(OpsBusinessFlowRecord::getTaskId, taskId)
                .eq(OpsBusinessFlowRecord::getActionType, actionType)
                .eq(OpsBusinessFlowRecord::getDeleteFlag, 0)
                .last("LIMIT 1"));
    }

    private void saveRecord(OpsBusinessOrder order, WorkflowBusinessEvent event,
                            Map<String, Object> result) {
        OpsBusinessFlowRecord record = new OpsBusinessFlowRecord();
        record.setCorpId(order.getCorpId());
        record.setOrderId(order.getId());
        record.setInstanceId(event.getInstanceId());
        record.setTaskId(event.getTaskId());
        record.setNodeOrder(event.getNodeOrder());
        record.setNodeName(event.getNodeName());
        record.setActionType(event.getActionType());
        record.setBizStatus(event.getBizStatus());
        record.setOperatorUserId(event.getOperatorUserId());
        record.setOperatorName(event.getOperatorName());
        record.setOpinion(event.getOpinion());
        record.setFormJson(writeJson(event.getFormData()));
        record.setResultJson(writeJson(result));
        record.setDeleteFlag(0);
        flowRecordMapper.insert(record);
    }

    private Map<String, Object> parseResult(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("duplicated", true);
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        } catch (Exception e) {
            return Map.of("duplicated", true);
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try {
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? null : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
