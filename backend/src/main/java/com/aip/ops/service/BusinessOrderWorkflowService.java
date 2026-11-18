package com.aip.ops.service;

import com.aip.ops.client.WorkflowPlatformClient;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 业务数据订单 ↔ 平台工作流组件。
 *
 * <p>订单创建/导入完成后异步发起流程实例；业务处理全部在平台工作流待办中完成，
 * 节点动作通过内部回调回到 ops（见 {@code InternalWorkflowController}）。</p>
 */
@Slf4j
@Service
public class BusinessOrderWorkflowService {

    public static final String BUSINESS_TYPE = "OPS_BUSINESS_ORDER";
    public static final String BUSINESS_TYPE_VOID = "OPS_BUSINESS_ORDER_VOID";

    private final OpsBusinessOrderMapper orderMapper;
    private final WorkflowPlatformClient workflowClient;
    private final JdbcTemplate jdbcTemplate;
    private final ThreadPoolTaskExecutor executor;
    private final String definitionCode;
    private final String voidDefinitionCode;

    public BusinessOrderWorkflowService(OpsBusinessOrderMapper orderMapper,
                                        WorkflowPlatformClient workflowClient,
                                        JdbcTemplate jdbcTemplate,
                                        @Qualifier("workflowTaskExecutor") ThreadPoolTaskExecutor executor,
                                        @Value("${workflow.business-order.definition-code:biz_order_flow}")
                                        String definitionCode,
                                        @Value("${workflow.business-order.void-definition-code:biz_order_void_flow}")
                                        String voidDefinitionCode) {
        this.orderMapper = orderMapper;
        this.workflowClient = workflowClient;
        this.jdbcTemplate = jdbcTemplate;
        this.executor = executor;
        this.definitionCode = definitionCode;
        this.voidDefinitionCode = voidDefinitionCode;
    }

    /** 结果页轮询/详情兜底：按实例查询当前审批人（失败返回 resolved=false）。 */
    public WorkflowPlatformClient.StartResult currentApprover(Long instanceId) {
        return workflowClient.getCurrentApprover(instanceId);
    }

    /** 异步发起（导入/手工新增提交后调用，失败只记日志，可由管理端重试）。 */
    public void startAsync(Long orderId, Long initiatorUserId, String initiatorName) {
        if (orderId == null) {
            return;
        }
        executor.execute(() -> {
            try {
                start(orderId, initiatorUserId, initiatorName, false);
            } catch (Exception e) {
                log.warn("订单 {} 发起工作流失败：{}", orderId, e.getMessage());
            }
        });
    }

    public void startBatchAsync(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        executor.execute(() -> orderIds.stream().filter(Objects::nonNull).distinct()
                .forEach(id -> {
                    try {
                        start(id, null, null, false);
                    } catch (Exception e) {
                        log.warn("订单 {} 发起工作流失败：{}", id, e.getMessage());
                    }
                }));
    }

    /**
     * 同步发起（管理端手动重试/驳回后重新提交）。
     *
     * @param force true=忽略已有实例，重新发起（驳回后重新提交）
     */
    public WorkflowPlatformClient.StartResult start(Long orderId, Long initiatorUserId,
                                                    String initiatorName, boolean force) {
        OpsBusinessOrder order = orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw com.aip.sdk.exception.BusinessException.notFound("业务订单不存在");
        }
        if (order.getWfInstanceId() != null && !force) {
            // 已有实例：返回当前审批人（成功页/详情页兜底查询）
            return workflowClient.getCurrentApprover(order.getWfInstanceId());
        }
        Initiator initiator = resolveInitiator(order, initiatorUserId, initiatorName);
        if (initiator == null) {
            throw com.aip.sdk.exception.BusinessException.of(
                    "未找到订单经办人员工账号，无法发起审批流程");
        }
        String requestId = force
                ? BUSINESS_TYPE + ":" + orderId + ":retry:" + System.currentTimeMillis()
                : BUSINESS_TYPE + ":" + orderId + ":1";
        WorkflowPlatformClient.StartResult started = workflowClient.start(
                new WorkflowPlatformClient.StartRequest(
                        definitionCode, BUSINESS_TYPE, String.valueOf(orderId),
                        summary(order), address(order), requestId, order.getCorpId(),
                        initiator.userId(), initiator.userName()));
        // 只回写实例ID：节点进入回调是平台事务提交后异步执行的，可能先于本更新到达，
        // 这里不能覆盖 biz_status/wf_node_*，否则会把已进入部长审核的状态冲回 0。
        orderMapper.update(null, new LambdaUpdateWrapper<OpsBusinessOrder>()
                .eq(OpsBusinessOrder::getId, orderId)
                .set(OpsBusinessOrder::getWfInstanceId, started.instanceId())
                .set(OpsBusinessOrder::getModifyTime, LocalDateTime.now()));
        log.info("订单 {} 已发起工作流 instanceId={} approver={}",
                orderId, started.instanceId(), started.approverName());
        return started;
    }

    /**
     * 终止当前运行中的实例（退回草稿 / 发起作废退款）。
     * 先查进度：已结束的实例直接跳过，避免平台报「不在运行中」。
     */
    public void terminateActive(Long instanceId, String reason,
                                Long operatorId, String operatorName) {
        if (instanceId == null) return;
        Map<Long, WorkflowPlatformClient.InstanceProgress> progress =
                workflowClient.instanceProgressBatch(List.of(instanceId));
        WorkflowPlatformClient.InstanceProgress p = progress.get(instanceId);
        if (p != null && !"RUNNING".equals(p.status())) {
            return;
        }
        workflowClient.terminate(instanceId, reason, operatorId, operatorName);
    }

    /** 发起「业务订单作废工作流」：businessType=OPS_BUSINESS_ORDER_VOID。 */
    public WorkflowPlatformClient.StartResult startVoid(OpsBusinessOrder order,
                                                        Long refundId,
                                                        String reason,
                                                        Long initiatorUserId,
                                                        String initiatorName) {
        String requestId = BUSINESS_TYPE_VOID + ":" + order.getId() + ":" + refundId + ":1";
        String summary = "业务订单作废 "
                + (order.getOrderNo() == null ? "#" + order.getId() : order.getOrderNo());
        if (order.getCustomer() != null && !order.getCustomer().isBlank()) {
            summary += " | " + order.getCustomer();
        }
        if (order.getPayableAmount() != null) {
            summary += " | 退款 " + order.getPayableAmount().stripTrailingZeros().toPlainString();
        }
        if (reason != null && !reason.isBlank()) {
            summary += " | " + reason.trim();
        }
        if (summary.length() > 200) {
            summary = summary.substring(0, 200);
        }
        WorkflowPlatformClient.StartResult started = workflowClient.start(
                new WorkflowPlatformClient.StartRequest(
                        voidDefinitionCode, BUSINESS_TYPE_VOID, String.valueOf(order.getId()),
                        summary, address(order), requestId, order.getCorpId(),
                        initiatorUserId, initiatorName));
        // 只回写实例/节点展示字段，不改 biz_status（作废流程节点 biz_status 为空）
        orderMapper.update(null, new LambdaUpdateWrapper<OpsBusinessOrder>()
                .eq(OpsBusinessOrder::getId, order.getId())
                .set(OpsBusinessOrder::getWfInstanceId, started.instanceId())
                .set(OpsBusinessOrder::getWfNodeOrder, 2)
                .set(OpsBusinessOrder::getWfNodeName, "部长审核")
                .set(OpsBusinessOrder::getModifyTime, LocalDateTime.now()));
        return started;
    }

    /** 作废驳回后重新发起正常审批（沿用原订单编号/订单）。 */
    public WorkflowPlatformClient.StartResult restartNormal(Long orderId, Long initiatorUserId,
                                                            String initiatorName) {
        return start(orderId, initiatorUserId, initiatorName, true);
    }

    private String summary(OpsBusinessOrder order) {
        StringBuilder sb = new StringBuilder("业务订单 ");
        sb.append(order.getOrderNo() == null ? "#" + order.getId() : order.getOrderNo());
        if (order.getCustomer() != null && !order.getCustomer().isBlank()) {
            sb.append(" | ").append(order.getCustomer());
        }
        BigDecimal amount = order.getTotalAmount();
        if (amount != null) {
            sb.append(" | 金额 ").append(amount.stripTrailingZeros().toPlainString());
        }
        if (order.getCommunityName() != null && !order.getCommunityName().isBlank()) {
            sb.append(" | ").append(order.getCommunityName());
        }
        String text = sb.toString();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    /**
     * 公众号消息「区域地址」内容：客户名称 + 小区 + 详细地址。
     * 微信 thing 字段上限 20 字，平台渲染时按字段类型裁剪；此处只做拼接与兜底，
     * 三项全空时退回订单摘要，避免 thing 字段为空导致消息被微信拒收（errcode 47003）。
     */
    private String address(OpsBusinessOrder order) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, order.getCustomer());
        appendPart(sb, order.getCommunityName());
        appendPart(sb, order.getDetailedAddress());
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            return summary(order);
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(part.trim());
    }

    /**
     * 发起人优先取订单首个经办人（员工账号即手机号）；导入场景无法从 JWT 取得
     * 实际经办人时也能把待办/驳回消息发给正确的人。
     */
    private Initiator resolveInitiator(OpsBusinessOrder order, Long userId, String userName) {
        if (userId != null) {
            return new Initiator(userId, userName == null ? "" : userName);
        }
        String phones = order.getAgentPhones();
        if (phones != null && !phones.isBlank()) {
            for (String phone : phones.split("、")) {
                String trimmed = phone.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    var rows = jdbcTemplate.queryForList(
                            "SELECT id, COALESCE(real_name, username) AS name "
                                    + "FROM ai_platform.sys_user WHERE username = ? "
                                    + "AND status = 1 AND delete_flag = 0 ORDER BY id LIMIT 1",
                            trimmed);
                    if (!rows.isEmpty()) {
                        Object id = rows.get(0).get("id");
                        Object name = rows.get(0).get("name");
                        if (id instanceof Number n) {
                            return new Initiator(n.longValue(),
                                    name == null ? trimmed : String.valueOf(name));
                        }
                    }
                } catch (Exception e) {
                    log.warn("按经办人手机号 {} 解析员工失败：{}", trimmed, e.getMessage());
                }
            }
        }
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT id, COALESCE(real_name, username) AS name FROM ai_platform.sys_user "
                            + "WHERE corp_id = ? AND is_admin = 1 AND status = 1 AND delete_flag = 0 "
                            + "ORDER BY id LIMIT 1", order.getCorpId());
            if (!rows.isEmpty() && rows.get(0).get("id") instanceof Number n) {
                Object name = rows.get(0).get("name");
                return new Initiator(n.longValue(), name == null ? "管理员" : String.valueOf(name));
            }
        } catch (Exception e) {
            log.warn("按组织解析管理员失败 corpId={}: {}", order.getCorpId(), e.getMessage());
        }
        return null;
    }

    private record Initiator(Long userId, String userName) {
    }
}
