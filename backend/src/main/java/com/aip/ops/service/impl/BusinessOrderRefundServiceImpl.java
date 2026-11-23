package com.aip.ops.service.impl;

import com.aip.ops.client.ScmStockOutClient;
import com.aip.ops.client.WorkflowPlatformClient;
import com.aip.ops.dto.BusinessRefundApplyRequest;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsBusinessRefund;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.mapper.OpsBusinessRefundMapper;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.ops.service.BusinessOrderRefundService;
import com.aip.ops.service.BusinessOrderService;
import com.aip.ops.service.BusinessOrderWorkflowService;
import com.aip.ops.service.EmployeeService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.LoginUser;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工端退回草稿 / 作废退款。
 *
 * <p>退回草稿：终止正常流程 → biz_status=0 + draft_flag=1 → 员工编辑后沿用原订单编号重新提交。
 * 作废退款：终止正常流程 → 发起 {@code biz_order_void_flow}（部长→财务→总经办）→
 * 终审通过后订单 biz_status=-1、行 calc_status=已作废、提成清零；驳回恢复原业务状态。</p>
 *
 * <p>已出库/已上报集团/已生成以旧换新采购单/已有进行中退款时拦截；SCM 不可达 fail-closed。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessOrderRefundServiceImpl implements BusinessOrderRefundService {

    private static final String REPORTED_STATUS = "已上报集团";
    private static final String VOID_STATUS = "-1";
    private static final String VOID_CALC_STATUS = "已作废";
    private static final Set<String> EDITABLE_STATUSES = Set.of("0", "1", "2");
    private static final int MAX_REASON = 500;
    private static final int MAX_REMARK = 500;
    private static final int MAX_ATTACHMENTS = 5;

    private final OpsBusinessOrderMapper orderMapper;
    private final OpsBusinessDataMapper itemMapper;
    private final OpsBusinessRefundMapper refundMapper;
    private final BusinessAttachmentService attachmentService;
    private final BusinessOrderService orderService;
    private final BusinessOrderWorkflowService workflowService;
    private final WorkflowPlatformClient workflowClient;
    private final ScmStockOutClient scmClient;
    private final EmployeeService employeeService;
    private final JdbcTemplate jdbcTemplate;
    private final JwtTokenInspector jwtTokenInspector;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> returnDraft(Long orderId) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可操作业务订单");
        }
        Actor actor = actor();
        OpsBusinessOrder order = requireAgentOrder(orderId, actor);
        // 锁定订单行：与并发退款/退草稿串行化
        orderService.lockById(orderId);
        order = requireAgentOrder(orderId, actor);
        assertStatusEditable(order, "退回草稿");
        if (Integer.valueOf(1).equals(order.getDraftFlag())) {
            throw BusinessException.of("订单已是草稿状态，无需重复退回");
        }
        if (findPending(orderId) != null) {
            throw BusinessException.of("订单正在作废退款审批中，不可退回草稿");
        }
        assertNoOutbound(order);
        assertNotReported(order);
        assertNoTradeInPurchase(order);
        workflowService.terminateActive(order.getWfInstanceId(), "退回草稿（经办人）",
                actor.userId(), actor.name());
        orderMapper.update(null, new LambdaUpdateWrapper<OpsBusinessOrder>()
                .eq(OpsBusinessOrder::getId, orderId)
                .eq(OpsBusinessOrder::getCorpId, actor.corpId())
                .set(OpsBusinessOrder::getBizStatus, "0")
                .set(OpsBusinessOrder::getDraftFlag, 1)
                .set(OpsBusinessOrder::getWfInstanceId, null)
                .set(OpsBusinessOrder::getWfNodeOrder, null)
                .set(OpsBusinessOrder::getWfNodeName, null)
                .set(OpsBusinessOrder::getModifyTime, LocalDateTime.now()));
        log.info("订单 {} 已退回草稿 operator={}", orderId, actor.phone());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("draftFlag", 1);
        result.put("bizStatus", "0");
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(Long orderId, BusinessRefundApplyRequest request) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可操作业务订单");
        }
        if (request == null) throw BusinessException.of("退款申请不能为空");
        Actor actor = actor();
        OpsBusinessOrder order = requireAgentOrder(orderId, actor);
        // 锁定订单行：防止并发提交产生两条 PENDING 退款
        orderService.lockById(orderId);
        order = requireAgentOrder(orderId, actor);
        assertStatusEditable(order, "发起退款申请");
        if (findPending(orderId) != null) {
            throw BusinessException.of("该订单已有进行中的作废退款申请，请勿重复发起");
        }
        assertNoOutbound(order);
        assertNotReported(order);
        assertNoTradeInPurchase(order);

        String reason = trimToLength(request.getReason(), MAX_REASON, "退款原因");
        if (reason.isEmpty()) throw BusinessException.of("请填写退款原因");
        String remark = trimToLength(request.getRemark(), MAX_REMARK, "备注");
        BigDecimal payable = payable(order);
        if (payable.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.of("订单应付合计异常，无法发起退款");
        }
        BigDecimal amount = request.getAmount() == null ? payable : request.getAmount();
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(payable) > 0) {
            throw BusinessException.of("退款金额必须大于0且不超过应付合计 ¥"
                    + payable.toPlainString());
        }
        // 下调退款金额必须在备注说明理由，避免无依据少退（需求三.4）
        if (amount.compareTo(payable) < 0 && remark.isEmpty()) {
            throw BusinessException.of("退款金额低于应付合计时，请在备注中说明调整理由");
        }
        List<Long> attachmentIds = request.getAttachmentIds() == null ? List.of()
                : request.getAttachmentIds().stream().filter(Objects::nonNull).distinct().toList();
        if (attachmentIds.size() > MAX_ATTACHMENTS) {
            throw BusinessException.of("退款补充附件最多" + MAX_ATTACHMENTS + "个");
        }
        List<OpsBusinessAttachment> files = attachmentIds.isEmpty() ? List.of()
                : attachmentService.requireOwnedList(attachmentIds,
                        OpsBusinessAttachment.TYPE_REFUND, actor.corpId(), orderId);

        // 先创建退款记录：若后续终止/发起流程失败，事务回滚不会先终止正常流程
        OpsBusinessRefund refund = new OpsBusinessRefund();
        refund.setCorpId(actor.corpId());
        refund.setOrderId(orderId);
        refund.setRefundNo("RF" + orderId + "-" + System.currentTimeMillis());
        refund.setReason(reason);
        refund.setAmount(amount);
        refund.setAttachmentIds(attachmentIds.isEmpty() ? null : joinIds(attachmentIds));
        refund.setAttachmentUrls(files.isEmpty() ? null : attachmentService.toUrlCsv(files));
        refund.setStatus(OpsBusinessRefund.STATUS_PENDING);
        refund.setOriginalBizStatus(order.getBizStatus() == null ? "0" : order.getBizStatus());
        refund.setApplicantUserId(actor.userId());
        refund.setApplicantName(actor.name());
        refund.setApplicantPhone(actor.phone());
        refund.setRemark(remark.isEmpty() ? null : remark);
        refund.setDeleteFlag(0);
        if (refundMapper.insert(refund) <= 0) {
            throw BusinessException.of("退款申请保存失败，请重试");
        }
        if (!attachmentIds.isEmpty()) {
            attachmentService.bindToOrder(attachmentIds, orderId, actor.corpId());
        }
        // 终止正常审批，避免作废审批期间正常流程继续推进到派工出库
        workflowService.terminateActive(order.getWfInstanceId(), "发起作废退款（经办人）",
                actor.userId(), actor.name());
        WorkflowPlatformClient.StartResult started;
        try {
            started = workflowService.startVoid(order, refund.getId(), reason,
                    actor.userId(), actor.name());
        } catch (RuntimeException e) {
            // 正常流程已终止：补偿性重新发起正常审批，避免订单停在无流程状态
            try {
                workflowService.restartNormal(orderId, actor.userId(), actor.name());
            } catch (Exception restartError) {
                log.warn("作废发起失败后重新发起正常流程失败 orderId={}: {}",
                        orderId, restartError.getMessage());
            }
            throw e;
        }
        refundMapper.update(null, new LambdaUpdateWrapper<OpsBusinessRefund>()
                .eq(OpsBusinessRefund::getId, refund.getId())
                .set(OpsBusinessRefund::getWfInstanceId, started.instanceId())
                .set(OpsBusinessRefund::getWfNodeOrder, 2)
                .set(OpsBusinessRefund::getWfNodeName, "部长审核")
                .set(OpsBusinessRefund::getModifyTime, LocalDateTime.now()));
        log.info("订单 {} 已发起作废退款 refundId={} instanceId={} amount={}",
                orderId, refund.getId(), started.instanceId(), amount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("refundId", refund.getId());
        result.put("status", OpsBusinessRefund.STATUS_PENDING);
        result.put("wfInstanceId", started.instanceId());
        result.put("approverName", started.approverName());
        result.put("approverRole", started.approverRole());
        result.put("currentApprovalDisplay",
                started.resolved() ? started.displayText() : null);
        result.put("currentApprovalNodeName", started.nodeName());
        result.put("currentApprovalMode", started.approverMode());
        result.put("approverResolved", started.resolved());
        result.put("approverMessage", started.message());
        return result;
    }

    @Override
    public Map<String, Object> detail(Long orderId) {
        Actor actor = actor();
        OpsBusinessOrder order = requireAgentOrder(orderId, actor);
        OpsBusinessRefund refund = latest(orderId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("draftFlag", order.getDraftFlag());
        result.put("refund", refund == null ? null : view(refund));
        result.put("canReturnDraft", canReturnDraft(order));
        result.put("canApplyRefund", canApplyRefund(order));
        result.put("canEdit", canEdit(order));
        result.put("canRestartFlow", canRestartFlow(order));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> restartFlow(Long orderId) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可操作业务订单");
        }
        Actor actor = actor();
        OpsBusinessOrder order = requireAgentOrder(orderId, actor);
        orderService.lockById(orderId);
        order = requireAgentOrder(orderId, actor);
        assertStatusEditable(order, "重新提交审批");
        if (findPending(orderId) != null) {
            throw BusinessException.of("订单正在作废退款审批中，不可重新提交");
        }
        if (order.getWfInstanceId() != null) {
            throw BusinessException.of("订单已有进行中的审批流程，请勿重复提交");
        }
        if (Integer.valueOf(1).equals(order.getDraftFlag())) {
            throw BusinessException.of("草稿订单请编辑后提交，无需重新提交审批");
        }
        WorkflowPlatformClient.StartResult started =
                workflowService.restartNormal(orderId, actor.userId(), actor.name());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("wfInstanceId", started.instanceId());
        result.put("approverName", started.approverName());
        result.put("approverRole", started.approverRole());
        result.put("currentApprovalDisplay",
                started.resolved() ? started.displayText() : null);
        result.put("currentApprovalNodeName", started.nodeName());
        result.put("currentApprovalMode", started.approverMode());
        result.put("approverResolved", started.resolved());
        result.put("approverMessage", started.message());
        return result;
    }

    @Override
    public Map<String, Object> view(OpsBusinessRefund refund) {
        if (refund == null) return null;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", refund.getId());
        v.put("refundNo", refund.getRefundNo());
        v.put("status", refund.getStatus());
        v.put("statusName", statusName(refund.getStatus()));
        v.put("reason", refund.getReason());
        v.put("amount", refund.getAmount());
        v.put("remark", refund.getRemark());
        v.put("attachmentUrls", splitCsv(refund.getAttachmentUrls()));
        v.put("applicantName", refund.getApplicantName());
        v.put("applicantPhone", refund.getApplicantPhone());
        v.put("createTime", refund.getCreateTime());
        v.put("approvedTime", refund.getApprovedTime());
        v.put("rejectReason", refund.getRejectReason());
        v.put("wfInstanceId", refund.getWfInstanceId());
        v.put("currentNodeName", refund.getWfNodeName());
        v.put("flow", buildFlow(refund));
        return v;
    }

    @Override
    public Map<String, Object> latestRefundView(Long orderId) {
        OpsBusinessRefund refund = latest(orderId);
        return refund == null ? null : view(refund);
    }

    @Override
    public Map<Long, String> pendingStatuses(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return Map.of();
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().limit(500).toList();
        if (ids.isEmpty()) return Map.of();
        List<OpsBusinessRefund> rows = refundMapper.selectList(
                new LambdaQueryWrapper<OpsBusinessRefund>()
                        .in(OpsBusinessRefund::getOrderId, ids)
                        .eq(OpsBusinessRefund::getStatus, OpsBusinessRefund.STATUS_PENDING)
                        .eq(OpsBusinessRefund::getDeleteFlag, 0));
        Map<Long, String> result = new LinkedHashMap<>();
        for (OpsBusinessRefund row : rows) {
            result.put(row.getOrderId(), row.getStatus());
        }
        return result;
    }

    @Override
    public OpsBusinessRefund findPending(Long orderId) {
        if (orderId == null) return null;
        return refundMapper.selectOne(new LambdaQueryWrapper<OpsBusinessRefund>()
                .eq(OpsBusinessRefund::getOrderId, orderId)
                .eq(OpsBusinessRefund::getStatus, OpsBusinessRefund.STATUS_PENDING)
                .eq(OpsBusinessRefund::getDeleteFlag, 0)
                .orderByDesc(OpsBusinessRefund::getId)
                .last("LIMIT 1"));
    }

    @Override
    public OpsBusinessRefund findByInstance(Long instanceId) {
        if (instanceId == null) return null;
        return refundMapper.selectOne(new LambdaQueryWrapper<OpsBusinessRefund>()
                .eq(OpsBusinessRefund::getWfInstanceId, instanceId)
                .eq(OpsBusinessRefund::getDeleteFlag, 0)
                .orderByDesc(OpsBusinessRefund::getId)
                .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markNodeEntered(OpsBusinessRefund refund, WorkflowBusinessEvent event) {
        if (refund == null || !OpsBusinessRefund.STATUS_PENDING.equals(refund.getStatus())) return;
        Long instanceId = event.getInstanceId() == null
                ? refund.getWfInstanceId() : event.getInstanceId();
        refundMapper.update(null, new LambdaUpdateWrapper<OpsBusinessRefund>()
                .eq(OpsBusinessRefund::getId, refund.getId())
                .set(instanceId != null, OpsBusinessRefund::getWfInstanceId, instanceId)
                .set(event.getNodeOrder() != null, OpsBusinessRefund::getWfNodeOrder,
                        event.getNodeOrder())
                .set(event.getNodeName() != null, OpsBusinessRefund::getWfNodeName,
                        event.getNodeName())
                .set(OpsBusinessRefund::getModifyTime, LocalDateTime.now()));
        LambdaUpdateWrapper<OpsBusinessOrder> orderUpdate =
                new LambdaUpdateWrapper<OpsBusinessOrder>()
                        .eq(OpsBusinessOrder::getId, refund.getOrderId())
                        .set(instanceId != null, OpsBusinessOrder::getWfInstanceId, instanceId)
                        .set(event.getNodeOrder() != null, OpsBusinessOrder::getWfNodeOrder,
                                event.getNodeOrder())
                        .set(event.getNodeName() != null, OpsBusinessOrder::getWfNodeName,
                                event.getNodeName())
                        .set(OpsBusinessOrder::getModifyTime, LocalDateTime.now());
        orderMapper.update(null, orderUpdate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markApproved(OpsBusinessOrder order, OpsBusinessRefund refund,
                             WorkflowBusinessEvent event) {
        if (order == null || refund == null) return;
        if (OpsBusinessRefund.STATUS_APPROVED.equals(refund.getStatus())) return;
        if (OpsBusinessRefund.STATUS_REJECTED.equals(refund.getStatus())
                || OpsBusinessRefund.STATUS_TERMINATED.equals(refund.getStatus())) return;
        LocalDateTime now = LocalDateTime.now();
        orderMapper.update(null, new LambdaUpdateWrapper<OpsBusinessOrder>()
                .eq(OpsBusinessOrder::getId, order.getId())
                .eq(OpsBusinessOrder::getCorpId, order.getCorpId())
                .set(OpsBusinessOrder::getBizStatus, VOID_STATUS)
                .set(OpsBusinessOrder::getCalcStatus, VOID_CALC_STATUS)
                .set(OpsBusinessOrder::getDraftFlag, 0)
                .set(OpsBusinessOrder::getTotalCommission, BigDecimal.ZERO)
                .set(OpsBusinessOrder::getModifyTime, now));
        itemMapper.update(null, new LambdaUpdateWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getOrderId, order.getId())
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .set(OpsBusinessData::getCalcStatus, VOID_CALC_STATUS)
                .set(OpsBusinessData::getCommissionAmount, BigDecimal.ZERO)
                .setSql("modify_time = NOW()"));
        refundMapper.update(null, new LambdaUpdateWrapper<OpsBusinessRefund>()
                .eq(OpsBusinessRefund::getId, refund.getId())
                .set(OpsBusinessRefund::getStatus, OpsBusinessRefund.STATUS_APPROVED)
                .set(OpsBusinessRefund::getApprovedTime, now)
                .set(OpsBusinessRefund::getRejectReason, null)
                .set(event != null && event.getNodeName() != null,
                        OpsBusinessRefund::getWfNodeName, event == null ? null : event.getNodeName())
                .set(OpsBusinessRefund::getModifyTime, now));
        log.info("订单 {} 作废退款通过 refundId={}", order.getId(), refund.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRejected(OpsBusinessOrder order, OpsBusinessRefund refund,
                             WorkflowBusinessEvent event, boolean terminated) {
        if (order == null || refund == null
                || !OpsBusinessRefund.STATUS_PENDING.equals(refund.getStatus())) return;
        LocalDateTime now = LocalDateTime.now();
        String reason = event == null ? null : event.getOpinion();
        if (reason == null || reason.isBlank()) {
            reason = terminated ? "流程终止" : "审批驳回";
        }
        refundMapper.update(null, new LambdaUpdateWrapper<OpsBusinessRefund>()
                .eq(OpsBusinessRefund::getId, refund.getId())
                .set(OpsBusinessRefund::getStatus, terminated
                        ? OpsBusinessRefund.STATUS_TERMINATED
                        : OpsBusinessRefund.STATUS_REJECTED)
                .set(OpsBusinessRefund::getRejectReason, reason)
                .set(event != null && event.getNodeName() != null,
                        OpsBusinessRefund::getWfNodeName, event == null ? null : event.getNodeName())
                .set(OpsBusinessRefund::getModifyTime, now));
        String original = refund.getOriginalBizStatus();
        orderMapper.update(null, new LambdaUpdateWrapper<OpsBusinessOrder>()
                .eq(OpsBusinessOrder::getId, order.getId())
                .eq(OpsBusinessOrder::getCorpId, order.getCorpId())
                .set(OpsBusinessOrder::getBizStatus,
                        original == null || original.isBlank() ? "0" : original)
                .set(OpsBusinessOrder::getWfInstanceId, null)
                .set(OpsBusinessOrder::getWfNodeOrder, null)
                .set(OpsBusinessOrder::getWfNodeName, null)
                .set(OpsBusinessOrder::getModifyTime, now));
        log.info("订单 {} 作废退款{} refundId={} reason={}", order.getId(),
                terminated ? "终止" : "驳回", refund.getId(), reason);
    }

    @Override
    public void assertVoidable(OpsBusinessOrder order) {
        if (order == null) throw BusinessException.notFound("业务订单不存在");
        assertStatusEditable(order, "作废退款");
        assertNoOutbound(order);
        assertNotReported(order);
        assertNoTradeInPurchase(order);
    }

    @Override
    public boolean canReturnDraft(OpsBusinessOrder order) {
        return editable(order) && !Integer.valueOf(1).equals(order.getDraftFlag())
                && findPending(order.getId()) == null;
    }

    @Override
    public boolean canApplyRefund(OpsBusinessOrder order) {
        return editable(order) && findPending(order.getId()) == null;
    }

    @Override
    public boolean canEdit(OpsBusinessOrder order) {
        return order != null && Integer.valueOf(1).equals(order.getDraftFlag())
                && findPending(order.getId()) == null;
    }

    @Override
    public boolean canRestartFlow(OpsBusinessOrder order) {
        return editable(order) && !Integer.valueOf(1).equals(order.getDraftFlag())
                && findPending(order.getId()) == null
                && order.getWfInstanceId() == null;
    }

    // ═══ 内部 ═══

    private boolean editable(OpsBusinessOrder order) {
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) return false;
        String status = order.getBizStatus() == null ? "0" : order.getBizStatus();
        return EDITABLE_STATUSES.contains(status);
    }

    private void assertStatusEditable(OpsBusinessOrder order, String action) {
        String status = order.getBizStatus() == null ? "0" : order.getBizStatus();
        if (VOID_STATUS.equals(status)) {
            throw BusinessException.of("订单已作废退款，不可" + action);
        }
        if (!EDITABLE_STATUSES.contains(status)) {
            throw BusinessException.of("业务状态已到派工出库及之后，不可" + action);
        }
    }

    private void assertNoOutbound(OpsBusinessOrder order) {
        Set<Long> outbounds = scmClient.outboundOrderIds(List.of(order.getId()));
        if (outbounds.contains(order.getId())) {
            throw BusinessException.of("该订单已产生销售出库单，不可退回草稿或发起退款");
        }
    }

    private void assertNotReported(OpsBusinessOrder order) {
        Long reported = itemMapper.selectCount(new LambdaQueryWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getOrderId, order.getId())
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .eq(OpsBusinessData::getCalcStatus, REPORTED_STATUS));
        if (reported != null && reported > 0) {
            throw BusinessException.of("该订单已上报集团，不可退回草稿或发起退款");
        }
    }

    private void assertNoTradeInPurchase(OpsBusinessOrder order) {
        String status = order.getTradeInPurchaseStatus();
        boolean generated = order.getTradeInPurchaseOrderId() != null
                || (order.getTradeInPurchaseBillNo() != null
                && !order.getTradeInPurchaseBillNo().isBlank())
                || "PENDING".equals(status) || "SUCCESS".equals(status);
        if (generated) {
            throw BusinessException.of("该订单已生成以旧换新采购单，不可退回草稿或发起退款");
        }
    }

    private OpsBusinessRefund latest(Long orderId) {
        return refundMapper.selectOne(new LambdaQueryWrapper<OpsBusinessRefund>()
                .eq(OpsBusinessRefund::getOrderId, orderId)
                .eq(OpsBusinessRefund::getDeleteFlag, 0)
                .orderByDesc(OpsBusinessRefund::getId)
                .last("LIMIT 1"));
    }

    private OpsBusinessOrder requireAgentOrder(Long orderId, Actor actor) {
        OpsBusinessOrder order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        if (!actor.corpId().equals(order.getCorpId())) {
            throw BusinessException.forbidden("无权操作其他组织的订单");
        }
        if (!agentPhonesContains(order.getAgentPhones(), actor.phone())
                && !actor.userId().equals(order.getCreateUser())) {
            throw BusinessException.forbidden("仅订单经办人可执行该操作");
        }
        return order;
    }

    private boolean agentPhonesContains(String phones, String phone) {
        if (phones == null || phones.isBlank() || phone == null || phone.isBlank()) return false;
        for (String part : phones.split("[、,，]")) {
            if (phone.equals(part.trim())) return true;
        }
        return false;
    }

    private Actor actor() {
        String phone = employeeService.getCurrentAgentPhone();
        LoginUser user = SecurityUtils.getCurrentUser();
        if (user == null || user.getUserId() == null) {
            throw BusinessException.forbidden("登录状态失效，请重新登录");
        }
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.forbidden("请先切换到具体组织");
        }
        return new Actor(user.getUserId(), resolveName(phone, user.getUsername()),
                phone, corpId);
    }

    private String resolveName(String phone, String fallback) {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT COALESCE(real_name, username) FROM ai_platform.sys_user "
                            + "WHERE username = ? AND status = 1 AND delete_flag = 0 "
                            + "ORDER BY id LIMIT 1", String.class, phone);
            if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) {
                return names.get(0);
            }
        } catch (Exception e) {
            log.warn("解析员工姓名失败 phone={}: {}", phone, e.getMessage());
        }
        return fallback == null ? phone : fallback;
    }

    private BigDecimal payable(OpsBusinessOrder order) {
        if (order.getPayableAmount() != null) {
            return order.getPayableAmount().setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal total = order.getTotalAmount() == null
                ? BigDecimal.ZERO : order.getTotalAmount();
        BigDecimal deduction = order.getTradeInDeductionAmount() == null
                ? BigDecimal.ZERO : order.getTradeInDeductionAmount();
        return total.subtract(deduction).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> buildFlow(OpsBusinessRefund refund) {
        Map<String, Object> flow = new LinkedHashMap<>();
        if (refund.getWfInstanceId() == null) {
            flow.put("status", "NOT_STARTED");
            flow.put("statusName", "未发起");
            flow.put("message", "作废流程未发起");
            return flow;
        }
        Map<Long, WorkflowPlatformClient.InstanceProgress> progress =
                workflowClient.instanceProgressBatch(List.of(refund.getWfInstanceId()));
        WorkflowPlatformClient.InstanceProgress p = progress.get(refund.getWfInstanceId());
        if (p == null) {
            flow.put("status", "UNKNOWN");
            flow.put("statusName", "待同步");
            flow.put("currentNodeName", refund.getWfNodeName());
            flow.put("message", "流程信息待同步");
            return flow;
        }
        flow.put("status", p.status());
        flow.put("statusName", instanceStatusName(p.status()));
        flow.put("currentNodeName", p.currentNodeName() == null
                ? refund.getWfNodeName() : p.currentNodeName());
        flow.put("currentApprovers", p.currentApprovers());
        flow.put("currentApproval", p.currentApproval());
        flow.put("nextNodeName", p.nextNodeName());
        flow.put("nextApprovers", p.nextApprovers());
        flow.put("nextApproval", p.nextApproval());
        flow.put("startedTime", p.startedTime());
        flow.put("finishedTime", p.finishedTime());
        flow.put("resolveMessage", p.resolveMessage());
        return flow;
    }

    private String statusName(String status) {
        if (status == null) return "";
        return switch (status) {
            case OpsBusinessRefund.STATUS_PENDING -> "作废审批中";
            case OpsBusinessRefund.STATUS_APPROVED -> "已作废退款";
            case OpsBusinessRefund.STATUS_REJECTED -> "已驳回";
            case OpsBusinessRefund.STATUS_TERMINATED -> "已终止";
            default -> status;
        };
    }

    private String instanceStatusName(String status) {
        if (status == null) return "";
        return switch (status) {
            case "RUNNING" -> "进行中";
            case "COMPLETED" -> "已完成";
            case "REJECTED" -> "已驳回";
            case "TERMINATED" -> "已终止";
            default -> status;
        };
    }

    private String trimToLength(String value, int max, String label) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max) {
            throw BusinessException.of(label + "不能超过" + max + "个字");
        }
        return text;
    }

    private String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) result.add(part.trim());
        }
        return result;
    }

    private record Actor(Long userId, String name, String phone, Long corpId) {
    }
}
