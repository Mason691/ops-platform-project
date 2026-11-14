package com.aip.ops.service;

import com.aip.ops.client.ScmStockOutClient;
import com.aip.ops.dto.FinanceCheckRequest;
import com.aip.ops.dto.ScmDispatchOutboundRequest;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessFlowRecord;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessFlowRecordMapper;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 业务数据订单工作流回调处理。
 *
 * <p>节点动作在审批任务落库前同步执行：本类抛异常时平台审批失败、任务保持待处理。
 * 状态回调只更新订单业务状态，不改变 calc_status（提成维度完全解耦）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessOrderFlowActionService {

    public static final String ACTION_FINANCE = "OPS_FINANCE_CHECK";
    public static final String ACTION_DISPATCH = "OPS_DISPATCH_OUTBOUND";
    public static final String ACTION_CONSTRUCTION = "OPS_CONSTRUCTION_RECORD";
    public static final String ACTION_ACCEPTANCE = "OPS_ACCEPTANCE";
    public static final String ACTION_AFTER_SALES = "OPS_AFTER_SALES";

    private final OpsBusinessOrderMapper orderMapper;
    private final OpsBusinessDataMapper itemMapper;
    private final OpsBusinessFlowRecordMapper flowRecordMapper;
    private final BusinessDataFinanceService financeService;
    private final ScmStockOutClient scmClient;
    private final JdbcTemplate jdbcTemplate;
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
        return switch (event.getEvent()) {
            case "NODE_ACTION" -> executeNodeAction(order, event);
            case "NODE_ENTERED", "INSTANCE_COMPLETED", "INSTANCE_REJECTED",
                 "INSTANCE_TERMINATED" -> {
                syncStatus(order, event);
                Map<String, Object> statusResult = new LinkedHashMap<>();
                statusResult.put("orderId", orderId);
                statusResult.put("bizStatus", order.getBizStatus() == null
                        ? "" : order.getBizStatus());
                yield statusResult;
            }
            default -> throw BusinessException.of("未知工作流事件：" + event.getEvent());
        };
    }

    // ═══ 节点动作 ═══

    private Map<String, Object> executeNodeAction(OpsBusinessOrder order,
                                                  WorkflowBusinessEvent event) {
        String actionType = event.getActionType();
        if (actionType == null || actionType.isBlank()) {
            return Map.of("skipped", true);
        }
        OpsBusinessFlowRecord existing = findRecord(event.getTaskId(), actionType);
        if (existing != null) {
            log.info("工作流节点动作重复回调，直接返回已有结果 taskId={}, action={}",
                    event.getTaskId(), actionType);
            return parseResult(existing.getResultJson());
        }
        Map<String, Object> result = switch (actionType) {
            case ACTION_FINANCE -> financeCheck(order, event);
            case ACTION_DISPATCH -> dispatchOutbound(order, event);
            case ACTION_CONSTRUCTION, ACTION_ACCEPTANCE, ACTION_AFTER_SALES ->
                    new LinkedHashMap<>(Map.of("recorded", true));
            default -> throw BusinessException.of("未知节点动作：" + actionType);
        };
        saveRecord(order, event, result);
        return result;
    }

    private Map<String, Object> financeCheck(OpsBusinessOrder order, WorkflowBusinessEvent event) {
        Map<String, Object> form = event.getFormData() == null
                ? Map.of() : event.getFormData();
        FinanceCheckRequest request = new FinanceCheckRequest();
        request.setReceiptNo(str(form.get("receiptNo")));
        request.setFinanceReceiptDate(str(form.get("financeReceiptDate")));
        Object fundRows = form.get("fundDynamicRows");
        if (fundRows instanceof Number n) {
            // 保留小数，交由财务校验拒绝非整数（不能静默截断 12.5 → 12）
            request.setFundDynamicRows(new BigDecimal(n.toString()));
        } else if (fundRows instanceof String s && !s.isBlank()) {
            try {
                request.setFundDynamicRows(new BigDecimal(s.trim()));
            } catch (NumberFormatException e) {
                throw BusinessException.of("资金动态表横排数必须为大于等于0的整数");
            }
        }
        request.setIsReceived(str(form.get("isReceived")));
        request.setCheckConclusion(str(form.get("checkConclusion")));
        request.setCheckRemark(str(form.get("checkRemark")));
        Map<String, Object> result = financeService.financeCheckByOrder(
                order.getId(), request, event.getOperatorUserId(), event.getOperatorName());
        return new LinkedHashMap<>(result);
    }

    /**
     * 派工出库：施工类型生成 SCM 未审核销售出库单；入户随访只留痕不出库。
     * 同一 taskId 幂等；订单已有有效出库单时禁止重复派工。
     */
    private Map<String, Object> dispatchOutbound(OpsBusinessOrder order,
                                                 WorkflowBusinessEvent event) {
        Map<String, Object> form = event.getFormData() == null
                ? Map.of() : event.getFormData();
        String dispatchType = str(form.get("dispatchType"));
        if (dispatchType.isEmpty()) {
            dispatchType = "CONSTRUCTION";
        }
        Long assigneeUserId = parseLong(form.get("assigneeUserId"));
        if (assigneeUserId == null) {
            throw BusinessException.of("请选择上门员工");
        }
        Map<String, Object> assignee = loadAssignee(assigneeUserId, order.getCorpId());
        if (assignee == null) {
            throw BusinessException.of("上门员工不存在、已停用或不属于订单组织");
        }
        if (str(form.get("expectTime")).isEmpty()) {
            throw BusinessException.of("请选择期望上门时间");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchType", dispatchType);
        result.put("assigneeUserId", assigneeUserId);
        result.put("assigneeName", assignee.get("name"));
        result.put("assigneeCorpId", assignee.get("corpId"));
        result.put("expectTime", form.get("expectTime"));
        if ("VISIT".equalsIgnoreCase(dispatchType)) {
            if (str(form.get("visitNote")).isEmpty()) {
                throw BusinessException.of("入户随访必须填写任务说明");
            }
            result.put("visitNote", form.get("visitNote"));
            result.put("productCount", 0);
            return result;
        }
        List<OpsBusinessData> items = positiveItems(order.getId(), form.get("selectedItemIds"));
        if (items.isEmpty()) {
            throw BusinessException.of("订单没有可施工的正数商品分录");
        }
        // 同一订单仅允许一张有效出库单；SCM 不可达时 fail-closed
        Set<Long> outbounds = scmClient.outboundOrderIds(List.of(order.getId()));
        if (outbounds.contains(order.getId())) {
            throw BusinessException.of("该订单已产生销售出库单，不可重复派工");
        }
        List<ScmDispatchOutboundRequest.Item> outboundItems = groupItems(items);
        String dispatchNo = dispatchNo(order, event.getTaskId(), event.getInstanceId());
        ScmDispatchOutboundRequest request = new ScmDispatchOutboundRequest(
                order.getCorpId(), event.getTaskId(), dispatchNo,
                order.getId(), order.getOrderNo(),
                event.getOperatorUserId(), event.getOperatorName(),
                order.getCustomer(), order.getCustomerPhone(), order.getPlatformMemberId(),
                order.getCommunityName(), order.getDetailedAddress(), outboundItems);
        ScmStockOutClient.Result outbound = scmClient.createFromDispatch(request);
        result.put("stockOutId", outbound.id());
        result.put("stockOutNo", outbound.billNo());
        result.put("productCount", outboundItems.size());
        return result;
    }

    /** 正数销售分录；formData.selectedItemIds 为空时取订单全部正数分录。 */
    private List<OpsBusinessData> positiveItems(Long orderId, Object selected) {
        List<OpsBusinessData> rows = itemMapper.selectList(new LambdaQueryWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getOrderId, orderId)
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .gt(OpsBusinessData::getSaleQuantity, 0)
                .isNull(OpsBusinessData::getSourceReturnId)
                .orderByAsc(OpsBusinessData::getId));
        Set<Long> selectedIds = parseIds(selected);
        if (selectedIds.isEmpty()) {
            return rows;
        }
        List<OpsBusinessData> filtered = rows.stream()
                .filter(row -> selectedIds.contains(row.getId()))
                .toList();
        return filtered.isEmpty() ? rows : filtered;
    }

    /** 同商品（品类+品牌+型号+单价）合并，数量取 MAX（多经办人行数量相同）。 */
    private List<ScmDispatchOutboundRequest.Item> groupItems(List<OpsBusinessData> rows) {
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData row : rows) {
            String key = str(row.getCategory()) + "|" + str(row.getBrand()) + "|"
                    + str(row.getModel()) + "|"
                    + (row.getSalePrice() == null ? "" : row.getSalePrice().stripTrailingZeros().toPlainString());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<ScmDispatchOutboundRequest.Item> items = new ArrayList<>();
        for (List<OpsBusinessData> group : groups.values()) {
            OpsBusinessData first = group.get(0);
            Integer quantity = group.stream().map(OpsBusinessData::getSaleQuantity)
                    .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(1);
            items.add(new ScmDispatchOutboundRequest.Item(first.getId(), first.getBrand(),
                    first.getCategory(), first.getModel(), quantity, first.getSalePrice()));
        }
        return items;
    }

    private String dispatchNo(OpsBusinessOrder order, Long taskId, Long instanceId) {
        String base = order.getOrderNo() == null ? String.valueOf(order.getId()) : order.getOrderNo();
        // 出库单号带流程实例ID + 任务ID：既便于追溯，也作为 SCM 幂等键的补充
        String no = base + "-WF-" + (instanceId == null ? "0" : instanceId)
                + "-" + (taskId == null ? "0" : taskId);
        return no.length() > 64 ? no.substring(0, 64) : no;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadAssignee(Long userId, Long corpId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT u.id, COALESCE(u.real_name, u.username) AS name, u.corp_id AS corpId "
                            + "FROM ai_platform.sys_user u WHERE u.id = ? "
                            + "AND u.status = 1 AND u.delete_flag = 0 "
                            + "AND (u.corp_id = ? OR EXISTS (SELECT 1 FROM ai_platform.sys_user_corp uc "
                            + "  WHERE uc.user_id = u.id AND uc.corp_id = ? "
                            + "  AND uc.status = 1 AND uc.delete_flag = 0)) LIMIT 1",
                    userId, corpId, corpId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("校验上门员工失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    // ═══ 状态同步 ═══

    private void syncStatus(OpsBusinessOrder order, WorkflowBusinessEvent event) {
        LambdaUpdateWrapper<OpsBusinessOrder> update = new LambdaUpdateWrapper<>();
        update.eq(OpsBusinessOrder::getId, order.getId())
                .set(OpsBusinessOrder::getModifyTime, LocalDateTime.now());
        if (event.getBizStatus() != null && !event.getBizStatus().isBlank()) {
            update.set(OpsBusinessOrder::getBizStatus, event.getBizStatus().trim());
        }
        if (event.getInstanceId() != null) {
            update.set(OpsBusinessOrder::getWfInstanceId, event.getInstanceId());
        }
        if (event.getNodeOrder() != null) {
            update.set(OpsBusinessOrder::getWfNodeOrder, event.getNodeOrder());
        }
        if (event.getNodeName() != null) {
            update.set(OpsBusinessOrder::getWfNodeName, event.getNodeName());
        }
        orderMapper.update(null, update);
        if (event.getBizStatus() != null && !event.getBizStatus().isBlank()) {
            order.setBizStatus(event.getBizStatus().trim());
        }
        log.info("订单 {} 业务状态同步：{} -> {} ({})", order.getId(),
                event.getEvent(), event.getBizStatus(), event.getNodeName());
    }

    // ═══ 留痕 ═══

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

    private static Set<Long> parseIds(Object raw) {
        Set<Long> ids = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Long id = parseLong(map.get("itemId"));
                    if (id != null) ids.add(id);
                } else {
                    Long id = parseLong(item);
                    if (id != null) ids.add(id);
                }
            }
        }
        return ids;
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

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
