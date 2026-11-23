package com.aip.ops.service.construct;

import com.aip.ops.dto.ConstructionTaskHint;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.util.OrderPartyText;
import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 施工节点处理人/文案的唯一来源。
 *
 * <p>施工员只取 {@code ops_business_order.worker_id}（派工出库时写入），
 * 绝不按“施工员角色”查询全量人员；经办人取订单主经办人，与完工验收审批人一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConstructionTaskHintService {

    private static final String ACTION_CONSTRUCTION = "OPS_CONSTRUCTION_RECORD";

    private final OpsBusinessOrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 平台回调使用：解析失败必须抛错阻断流程推进。 */
    public OrderWorker resolveOrderWorker(Long orderId) {
        return resolveOrderWorker(requireOrder(orderId));
    }

    /** 平台回调使用：订单已加载时避免重复查询。 */
    public OrderWorker resolveOrderWorker(OpsBusinessOrder order) {
        if (order == null || order.getWorkerId() == null) {
            String orderNo = order == null ? "" : String.valueOf(order.getOrderNo());
            throw BusinessException.of("ORDER_WORKER_NOT_SET：订单 " + orderNo
                    + " 未指派施工员，请先完成派工");
        }
        String userName = resolveUserName(order.getWorkerId(), order.getWorkerName());
        log.info("订单施工员解析: orderId={}, orderNo={}, workerId={}, workerName={}, source=ORDER_FIELD",
                order.getId(), order.getOrderNo(), order.getWorkerId(), userName);
        return new OrderWorker(order.getWorkerId(), userName);
    }

    /**
     * 施工完成节点提示：仅当前任务属于施工记录且订单已到“施工完成”时返回。
     * 页面提示、按钮、成功文案全部由此对象驱动，前端不拼接角色/人员。
     */
    public ConstructionTaskHint constructTaskHint(Long taskId, Long orderId) {
        TaskNode node = loadTaskNode(taskId);
        if (node == null || !ACTION_CONSTRUCTION.equalsIgnoreCase(node.actionType())) {
            return null;
        }
        OpsBusinessOrder order = requireOrder(orderId);
        if (!isCompletion(node, order)) {
            return null;
        }
        String workerName = order.getWorkerId() == null ? order.getWorkerName()
                : resolveUserName(order.getWorkerId(), order.getWorkerName());
        String handlerName = resolveHandlerName(order);
        return ConstructionTaskHint.of(workerName, order.getOrderNo(), handlerName);
    }

    /** 施工完成：节点名明确为“施工完成”，或订单已写入状态 5（兼容自定义节点名）。 */
    private boolean isCompletion(TaskNode node, OpsBusinessOrder order) {
        String nodeName = node.nodeName() == null ? "" : node.nodeName();
        return nodeName.contains("施工完成") || "5".equals(order.getBizStatus());
    }

    private OpsBusinessOrder requireOrder(Long orderId) {
        OpsBusinessOrder order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        return order;
    }

    private TaskNode loadTaskNode(Long taskId) {
        if (taskId == null) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT n.action_type AS actionType, n.node_name AS nodeName "
                            + "FROM ai_platform.wf_task t "
                            + "JOIN ai_platform.wf_definition_node n ON n.id = t.node_id "
                            + "WHERE t.id = ? AND t.delete_flag = 0 AND n.delete_flag = 0 LIMIT 1",
                    taskId);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            return new TaskNode(text(row.get("actionType")), text(row.get("nodeName")));
        } catch (Exception e) {
            log.warn("读取施工任务节点失败 taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /** 经办人姓名与完工验收审批人同口径：主经办人手机号 → 订单组织在职员工，兜底 agent_names。 */
    private String resolveHandlerName(OpsBusinessOrder order) {
        String phone = OrderPartyText.primaryPhone(order.getAgentPhones());
        String name = resolveUserNameByPhone(phone, order.getCorpId());
        if (!name.isEmpty()) {
            return name;
        }
        String agentName = OrderPartyText.primaryName(order.getAgentNames());
        return agentName.isEmpty() ? phone : agentName;
    }

    private String resolveUserNameByPhone(String phone, Long corpId) {
        if (phone.isEmpty() || corpId == null) {
            return "";
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT COALESCE(NULLIF(u.real_name,''), NULLIF(u.nickname,''), u.username) "
                            + "FROM ai_platform.sys_user u "
                            + "JOIN ai_platform.sys_user_corp uc ON uc.user_id = u.id "
                            + " AND uc.corp_id = ? AND uc.status = 1 AND uc.delete_flag = 0 "
                            + "WHERE u.phone = ? AND u.status = 1 AND u.delete_flag = 0 LIMIT 1",
                    String.class, corpId, phone);
            return names.isEmpty() ? "" : text(names.get(0));
        } catch (Exception e) {
            log.warn("按手机号解析经办人姓名失败: {}", e.getMessage());
            return "";
        }
    }

    private String resolveUserName(Long userId, String fallback) {
        if (userId == null) {
            return text(fallback);
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT COALESCE(NULLIF(real_name,''), NULLIF(nickname,''), username) "
                            + "FROM ai_platform.sys_user WHERE id = ? AND delete_flag = 0 LIMIT 1",
                    String.class, userId);
            String name = names.isEmpty() ? "" : text(names.get(0));
            return name.isEmpty() ? text(fallback) : name;
        } catch (Exception e) {
            log.warn("解析施工员姓名失败 userId={}: {}", userId, e.getMessage());
            return text(fallback);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record OrderWorker(Long userId, String userName) {
    }

    private record TaskNode(String actionType, String nodeName) {
    }
}
