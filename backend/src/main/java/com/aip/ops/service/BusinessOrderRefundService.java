package com.aip.ops.service;

import com.aip.ops.dto.BusinessRefundApplyRequest;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsBusinessRefund;

import java.util.Map;

/**
 * 员工端业务订单「退回草稿 / 作废退款」。
 *
 * <p>两操作均要求当前员工是该订单经办人，且业务状态在 0/1/2（派工出库前）；
 * 已出库、已上报集团、已生成以旧换新采购单、已有进行中退款时拦截。</p>
 */
public interface BusinessOrderRefundService {

    /** 退回草稿：终止当前流程、状态置 0、draft_flag=1。 */
    Map<String, Object> returnDraft(Long orderId);

    /** 发起作废退款：终止正常流程、创建退款记录、发起作废工作流。 */
    Map<String, Object> apply(Long orderId, BusinessRefundApplyRequest request);

    /** 当前订单最近一次退款（含流程进度）。 */
    Map<String, Object> detail(Long orderId);

    /** 作废驳回后重新发起正常审批（沿用原订单编号）。 */
    Map<String, Object> restartFlow(Long orderId);

    /** 退款记录 → 前端视图（状态名、节点、审批人、附件）。 */
    Map<String, Object> view(OpsBusinessRefund refund);

    /** 当前订单最近一次退款的视图（无则 null）。 */
    Map<String, Object> latestRefundView(Long orderId);

    /** 批量查询订单进行中的退款状态（列表展示“作废审批中”）。 */
    Map<Long, String> pendingStatuses(java.util.List<Long> orderIds);

    OpsBusinessRefund findPending(Long orderId);

    OpsBusinessRefund findByInstance(Long instanceId);

    /** 作废流程 NODE_ENTERED：只更新退款/订单流程节点，不改 biz_status。 */
    void markNodeEntered(OpsBusinessRefund refund, WorkflowBusinessEvent event);

    /** 终审通过：订单置 -1、行 calc_status=已作废、提成置 0。 */
    void markApproved(OpsBusinessOrder order, OpsBusinessRefund refund,
                      WorkflowBusinessEvent event);

    /** 驳回/终止：恢复原业务状态，清空流程字段，保留退款记录。 */
    void markRejected(OpsBusinessOrder order, OpsBusinessRefund refund,
                      WorkflowBusinessEvent event, boolean terminated);

    /** 作废节点动作前置校验：状态 0/1/2、未出库、未上报、无采购单。 */
    void assertVoidable(OpsBusinessOrder order);

    boolean canReturnDraft(OpsBusinessOrder order);

    boolean canApplyRefund(OpsBusinessOrder order);

    boolean canEdit(OpsBusinessOrder order);

    boolean canRestartFlow(OpsBusinessOrder order);
}
