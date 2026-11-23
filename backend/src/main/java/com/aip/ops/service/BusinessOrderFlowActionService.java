package com.aip.ops.service;

import com.aip.ops.client.ScmPurchaseOrderClient;
import com.aip.ops.client.ScmStockOutClient;
import com.aip.ops.client.WorkflowPlatformClient;
import com.aip.ops.dto.FinanceCheckRequest;
import com.aip.ops.dto.ScmDispatchOutboundRequest;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessFlowRecord;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessFlowRecordMapper;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.service.construct.ConstructionTaskHintService;
import com.aip.ops.service.construct.OrderConstructService;
import com.aip.ops.service.construct.OrderConstructStatus;
import com.aip.ops.util.OrderPartyText;
import com.aip.ops.util.VideoDurationValidator;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /** 完工验收至少 1 个图片/视频，图片 ≤9 张、视频 ≤1 段。 */
    private static final int MAX_ACCEPTANCE_IMAGES = 9;
    private static final int MAX_ACCEPTANCE_VIDEOS = 1;
    private static final int MAX_ACCEPTANCE_VIDEO_SECONDS = 30;

    private final OpsBusinessOrderMapper orderMapper;
    private final OpsBusinessDataMapper itemMapper;
    private final OpsBusinessFlowRecordMapper flowRecordMapper;
    private final BusinessDataFinanceService financeService;
    private final DispatchEligibilityService dispatchEligibilityService;
    /** 派工成功回写施工员/待接收，并校验施工记录只允许在已开工后提交。 */
    private final OrderConstructService orderConstructService;
    /** 施工节点唯一处理人（订单施工员）与施工完成提示文案的唯一来源。 */
    private final ConstructionTaskHintService constructionTaskHintService;
    private final ScmStockOutClient scmClient;
    /** 以旧换新采购单：财务核对触发自动审核 / 撤销核对联动反审 */
    private final ScmPurchaseOrderClient scmPurchaseOrderClient;
    private final BusinessOrderRefundService refundService;
    private final BusinessAttachmentService attachmentService;
    private final VideoDurationValidator videoDurationValidator;
    private final WorkflowPlatformClient workflowClient;
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
            // 完工验收节点动态解析为订单主经办人；解析失败由平台阻断推进并落异常中心
            case "RESOLVE_APPROVER" -> resolveOrderHandler(order);
            // 施工节点动态解析为订单已指派的唯一施工员，禁止按角色解析全量施工员
            case "RESOLVE_WORKER" -> resolveOrderWorker(order);
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
        // 作废/草稿订单的正常流程待办已终止：迟到的节点动作直接拒绝，避免作废后仍执行出库/核对
        if ("-1".equals(order.getBizStatus())) {
            throw BusinessException.of("订单已作废退款，不可继续审批");
        }
        if (Integer.valueOf(1).equals(order.getDraftFlag())) {
            throw BusinessException.of("订单已退回草稿，请重新提交后再审批");
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
            case ACTION_ACCEPTANCE -> acceptance(order, event);
            case ACTION_CONSTRUCTION -> constructionRecord(order, event);
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
        // 财务核对完成 → 事务提交后触发 SCM 采购单自动审核（失败不阻塞财务核对）
        triggerFinanceAutoApprove(order, event);
        return new LinkedHashMap<>(result);
    }

    /** 财务核对提交后触发 SCM：以旧换新采购单 DRAFT → AUDITED + 推送金蝶（SCM 幂等）。 */
    private void triggerFinanceAutoApprove(OpsBusinessOrder order, WorkflowBusinessEvent event) {
        if (!Integer.valueOf(1).equals(order.getHasTradeIn())
                && order.getTradeInPurchaseOrderId() == null) {
            return;
        }
        runAfterCommit(() -> {
            try {
                scmPurchaseOrderClient.financeVerified(order.getCorpId(), order.getId(),
                        event.getOperatorUserId(), event.getOperatorName());
            } catch (Exception e) {
                log.warn("财务核对触发采购单自动审核失败 orderId={}: {}", order.getId(), e.getMessage());
            }
        });
    }

    /** 事务活跃则 afterCommit 执行；无事务（节点动作回调）则直接执行。 */
    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    /**
     * 订单经办人解析：主经办人 = agent_phones 中第一个非空手机号。
     * 多经办人订单不猜测、不轮询，保证「完工验收审批人」唯一且可解释。
     */
    private Map<String, Object> resolveOrderHandler(OpsBusinessOrder order) {
        String phone = OrderPartyText.primaryPhone(order.getAgentPhones());
        if (phone.isEmpty()) {
            throw BusinessException.of("ORDER_HANDLER_NOT_SET：订单未指定经办人，请联系管理员");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approverPhone", phone);
        result.put("approverName", OrderPartyText.primaryName(order.getAgentNames()));
        result.put("handlerSource", "AGENT_PHONE");
        log.info("完工验收经办人解析: orderId={}, orderNo={}, phone={}", order.getId(),
                order.getOrderNo(), phone);
        return result;
    }

    /**
     * 订单施工员解析：只认派工出库写入的 order.worker_id 唯一施工员。
     * 施工执行/施工完成节点的任务与“下一步处理人”都由本结果生成，
     * 禁止按“施工员角色”查全量人员（会解析出孙莉娟等其他施工员）。
     */
    private Map<String, Object> resolveOrderWorker(OpsBusinessOrder order) {
        ConstructionTaskHintService.OrderWorker worker =
                constructionTaskHintService.resolveOrderWorker(order);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approverUserId", worker.userId());
        result.put("approverName", worker.userName());
        result.put("roleName", "施工员");
        result.put("workerSource", "ORDER_FIELD");
        log.info("施工节点施工员解析: orderId={}, orderNo={}, workerId={}, workerName={}",
                order.getId(), order.getOrderNo(), worker.userId(), worker.userName());
        return result;
    }

    /**
     * 完工验收节点动作：结论/意见/凭证强校验，任何一项不满足都不推进流程。
     * 附件归属按任务处理人校验；绑定订单后订单详情与流程留痕均可回看。
     */
    private Map<String, Object> acceptance(OpsBusinessOrder order,
                                           WorkflowBusinessEvent event) {
        Map<String, Object> form = event.getFormData() == null ? Map.of() : event.getFormData();
        String conclusion = str(form.get("acceptanceConclusion")).toUpperCase();
        if (!"PASS".equals(conclusion) && !"REJECT".equals(conclusion)) {
            throw BusinessException.of("请选择验收结论（通过/不通过）");
        }
        String note = str(form.get("acceptanceNote")).trim();
        if (note.length() < 5) {
            throw BusinessException.of("ACCEPTANCE_OPINION_REQUIRED：验收意见不少于5个字");
        }
        if (note.length() > 500) {
            throw BusinessException.of("验收意见不能超过500字");
        }
        List<Long> ids = new ArrayList<>(parseIds(form.get("attachmentIds")));
        if (ids.isEmpty()) {
            throw BusinessException.of(
                    "ACCEPTANCE_ATTACHMENT_REQUIRED：请至少上传 1 张图片或 1 段视频作为验收凭证");
        }
        List<OpsBusinessAttachment> files = attachmentService.requireOwnedListForUploader(
                ids, OpsBusinessAttachment.TYPE_ACCEPTANCE, order.getCorpId(), order.getId(),
                event.getOperatorUserId());
        validateAcceptanceEvidence(files);
        attachmentService.bindToOrder(ids, order.getId(), order.getCorpId());
        Map<String, Object> result = acceptanceResult(conclusion, note, files);
        log.info("完工验收提交: orderId={}, orderNo={}, result={}, attachments={}, "
                        + "videoSeconds={}, operator={}",
                order.getId(), order.getOrderNo(), conclusion, files.size(),
                result.get("videoDurationSeconds"), event.getOperatorUserId());
        return result;
    }

    private void validateAcceptanceEvidence(List<OpsBusinessAttachment> files) {
        long images = files.stream().filter(BusinessOrderFlowActionService::isImage).count();
        long videos = files.stream().filter(BusinessOrderFlowActionService::isVideo).count();
        if (images + videos == 0) {
            throw BusinessException.of(
                    "ACCEPTANCE_ATTACHMENT_REQUIRED：请至少上传 1 张图片或 1 段视频作为验收凭证");
        }
        if (images > MAX_ACCEPTANCE_IMAGES) {
            throw BusinessException.of("验收图片最多 " + MAX_ACCEPTANCE_IMAGES + " 张");
        }
        if (videos > MAX_ACCEPTANCE_VIDEOS) {
            throw BusinessException.of("验收短视频最多 " + MAX_ACCEPTANCE_VIDEOS + " 段");
        }
        for (OpsBusinessAttachment file : files) {
            checkEvidenceFile(file);
            if (isVideo(file)) {
                recheckVideoDuration(file);
            }
        }
    }

    /**
     * 提交时再次读取落盘视频的 mvhd/ffprobe，防止上传后文件被替换或旧数据缺时长。
     */
    private void recheckVideoDuration(OpsBusinessAttachment file) {
        int actual;
        try {
            actual = videoDurationValidator.requireDurationSeconds(
                    attachmentService.resolveLocalPath(file));
        } catch (RuntimeException e) {
            throw BusinessException.of(
                    "ACCEPTANCE_FILE_INVALID：视频文件不存在或无法校验，请重新上传");
        }
        if (actual > MAX_ACCEPTANCE_VIDEO_SECONDS) {
            throw BusinessException.of("ACCEPTANCE_VIDEO_TOO_LONG：短视频时长不能超过 30 秒");
        }
    }

    private static void checkEvidenceFile(OpsBusinessAttachment file) {
        long size = file.getFileSize() == null ? 0L : file.getFileSize();
        if (isImage(file)) {
            if (size > 10L * 1024 * 1024) {
                throw BusinessException.of("ACCEPTANCE_FILE_TOO_LARGE：图片不能超过 10MB");
            }
            return;
        }
        if (!isVideo(file)) {
            throw BusinessException.of(
                    "ACCEPTANCE_FILE_INVALID：附件格式不支持（仅支持 jpg/png/mp4/mov）");
        }
        if (size > 50L * 1024 * 1024) {
            throw BusinessException.of("ACCEPTANCE_FILE_TOO_LARGE：视频不能超过 50MB");
        }
        Integer duration = file.getDurationSeconds();
        if (duration == null || duration <= 0) {
            throw BusinessException.of("ACCEPTANCE_FILE_INVALID：视频时长无法校验，请重新录制");
        }
        if (duration > MAX_ACCEPTANCE_VIDEO_SECONDS) {
            throw BusinessException.of("ACCEPTANCE_VIDEO_TOO_LONG：短视频时长不能超过 30 秒");
        }
    }

    private static Map<String, Object> acceptanceResult(String conclusion, String note,
                                                        List<OpsBusinessAttachment> files) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acceptanceConclusion", conclusion);
        result.put("acceptanceNote", note);
        result.put("attachmentCount", files.size());
        result.put("attachmentIds", files.stream().map(OpsBusinessAttachment::getId).toList());
        result.put("acceptanceEvidenceUrls",
                files.stream().map(OpsBusinessAttachment::getFileUrl).toList());
        result.put("videoDurationSeconds", files.stream()
                .filter(BusinessOrderFlowActionService::isVideo)
                .map(OpsBusinessAttachment::getDurationSeconds)
                .filter(Objects::nonNull).findFirst().orElse(null));
        return result;
    }

    private static boolean isImage(OpsBusinessAttachment file) {
        String contentType = file.getContentType() == null ? "" : file.getContentType();
        if (contentType.startsWith("image/")) return true;
        String url = file.getFileUrl() == null ? "" : file.getFileUrl().toLowerCase();
        return url.endsWith(".jpg") || url.endsWith(".jpeg") || url.endsWith(".png");
    }

    private static boolean isVideo(OpsBusinessAttachment file) {
        String contentType = file.getContentType() == null ? "" : file.getContentType();
        if (contentType.startsWith("video/")) return true;
        String url = file.getFileUrl() == null ? "" : file.getFileUrl().toLowerCase();
        return url.endsWith(".mp4") || url.endsWith(".mov");
    }

    /**
     * 派工出库：施工类型生成 SCM 未审核销售出库单；入户随访只留痕不出库。
     * 同一 taskId 幂等；订单已有有效出库单时禁止重复派工。
     */
    private Map<String, Object> dispatchOutbound(OpsBusinessOrder order,
                                                 WorkflowBusinessEvent event) {
        // 统一准入（最终防线）：必须已收齐全款 + 已财务核对；所有派工入口都复用本校验
        dispatchEligibilityService.assertCanDispatch(order);
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
        boolean visit = "VISIT".equalsIgnoreCase(dispatchType);
        Object workerRole = assignee.get("workerRole");
        // 施工工单强制施工员角色（内部/外部均可）；随访不受限。
        // 前端候选列表已按角色过滤，这里作为绕过前端的服务端最终防线。
        if (!visit && (!(workerRole instanceof Number roleCount) || roleCount.longValue() <= 0)) {
            throw BusinessException.of("上门员工必须是施工员角色");
        }
        if (str(form.get("expectTime")).isEmpty()) {
            throw BusinessException.of("请选择期望上门时间");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatchType", dispatchType);
        result.put("assigneeUserId", assigneeUserId);
        // 施工派工的语义角色是“施工员”；平台据此生成“施工员（姓名）”，
        // 不能回退到员工在其他组织的财务/总经办等角色。
        if (!visit) {
            result.put("assigneeRoleName", "施工员");
        }
        result.put("assigneeName", assignee.get("name"));
        result.put("assigneeCorpId", assignee.get("corpId"));
        result.put("assigneeUserType", assignee.get("userType"));
        Object wechat = assignee.get("wechatBound");
        result.put("workerWechatBound", wechat instanceof Number n && n.longValue() > 0);
        String assignSource = str(form.get("assignSource"));
        result.put("assignSource",
                "CREATED_ON_SITE".equals(assignSource) ? "CREATED_ON_SITE" : "SELECTED");
        result.put("expectTime", form.get("expectTime"));
        if (visit) {
            if (str(form.get("visitNote")).isEmpty()) {
                throw BusinessException.of("入户随访必须填写任务说明");
            }
            result.put("visitNote", form.get("visitNote"));
            result.put("productCount", 0);
            // 随访保持旧口径：派工后直接进入施工中；施工派工则进入待接收
            markDispatched(order, assignee, assigneeUserId, dispatchType, form, event);
            return result;
        }
        List<OpsBusinessData> items = positiveItems(order.getId(), form.get("selectedItemIds"));
        if (items.isEmpty()) {
            throw BusinessException.of("订单没有可施工的正数商品分录");
        }
        List<ScmDispatchOutboundRequest.Item> outboundItems = groupItems(items);
        // SCM 不可达时 fail-closed。已存在出库单时区分两种可恢复场景：
        //   1) 同一派工任务重试（SCM 已建单但状态回写失败）：SCM 按 dispatchId 幂等复用；
        //   2) 拒绝接单后重新指派：货物已出库，复用原出库单，不再重复出库。
        Set<Long> outbounds = scmClient.outboundOrderIds(List.of(order.getId()));
        boolean outboundExists = outbounds.contains(order.getId());
        boolean reDispatch = outboundExists
                && orderConstructService.isRedispatchAfterReject(order.getId());
        boolean sameTaskRetry = outboundExists && !reDispatch
                && event.getTaskId() != null
                && event.getTaskId().equals(order.getDispatchTaskId());
        if (outboundExists && !reDispatch && !sameTaskRetry) {
            throw BusinessException.of("该订单已产生销售出库单，不可重复派工");
        }
        if (!reDispatch) {
            // 先落本次派工任务ID（独立事务）：SCM 建单成功但回写失败时可按同一任务重试
            orderConstructService.markDispatchAttempt(order.getId(), event.getTaskId());
        }
        if (reDispatch) {
            result.put("reusedOutbound", true);
            result.put("stockOutId", outbounds.iterator().next());
            result.put("productCount", outboundItems.size());
            markDispatched(order, assignee, assigneeUserId, dispatchType, form, event);
            return result;
        }
        // 派工物料清单是“需求预填”：随派工一起传给 SCM，由 SCM 同事务生成物料领用草稿；
        // 为空表示本次派工不需要物料，不生成领用单。
        List<ScmDispatchOutboundRequest.MaterialItem> materialItems =
                dispatchMaterialItems(form.get("materialList"));
        String materialStockNumber = str(form.get("materialStockNumber"));
        String dispatchNo = dispatchNo(order, event.getTaskId(), event.getInstanceId());
        ScmDispatchOutboundRequest request = new ScmDispatchOutboundRequest(
                order.getCorpId(), event.getTaskId(), dispatchNo,
                order.getId(), order.getOrderNo(),
                event.getOperatorUserId(), event.getOperatorName(),
                order.getCustomer(), order.getCustomerPhone(), order.getPlatformMemberId(),
                order.getCommunityName(), order.getDetailedAddress(), outboundItems,
                materialStockNumber.isEmpty() ? null : materialStockNumber, materialItems);
        ScmStockOutClient.Result outbound = scmClient.createFromDispatch(request);
        result.put("stockOutId", outbound.id());
        result.put("stockOutNo", outbound.billNo());
        result.put("productCount", outboundItems.size());
        if (outbound.materialPickId() != null) {
            result.put("materialPickId", outbound.materialPickId());
            result.put("materialPickBillNo", outbound.materialPickBillNo());
            result.put("materialPickCount", materialItems.size());
        }
        markDispatched(order, assignee, assigneeUserId, dispatchType, form, event);
        return result;
    }

    /** 派工成功后把施工员与「待接收」写回订单；SCM 出库失败时不会走到这里。 */
    private void markDispatched(OpsBusinessOrder order, Map<String, Object> assignee,
                                Long assigneeUserId, String dispatchType,
                                Map<String, Object> form, WorkflowBusinessEvent event) {
        Integer userType = assignee.get("userType") instanceof Number n ? n.intValue() : null;
        String expectTime = str(form.get("expectTime"));
        orderConstructService.markDispatched(order, assigneeUserId,
                str(assignee.get("name")), userType, dispatchType,
                event.getOperatorUserId(), event.getOperatorName(),
                expectTime.isEmpty() ? null : "期望上门：" + expectTime);
    }

    /**
     * 施工记录（施工完成）节点动作：只有已确认开工（或验收退回返工）才允许提交，
     * 防止绕过确认接收/打卡/客户签字直接推进流程。
     */
    private Map<String, Object> constructionRecord(OpsBusinessOrder order,
                                                  WorkflowBusinessEvent event) {
        String status = order.getBizStatus();
        if (!OrderConstructStatus.isConstructing(status)) {
            throw BusinessException.of(
                    "CONSTRUCTION_NOT_STARTED：请先完成确认接收、上门打卡、客户签字与确认开工");
        }
        // 施工执行/施工完成两个节点共用本动作，日志带上节点与任务，便于串联一次施工完成提交
        log.info("施工记录提交: orderId={}, orderNo={}, instanceId={}, nodeName={}, taskId={}, "
                        + "operator={}, bizStatus={}",
                order.getId(), order.getOrderNo(), event.getInstanceId(), event.getNodeName(),
                event.getTaskId(), event.getOperatorUserId(), status);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recorded", true);
        result.put("bizStatus", status);
        return result;
    }

    /** 物料清单解析：允许为空；同一商品多行合并，数量必须大于 0。 */
    private List<ScmDispatchOutboundRequest.MaterialItem> dispatchMaterialItems(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String number = str(map.get("materialNumber"));
            if (number.isEmpty()) {
                continue;
            }
            BigDecimal qty;
            try {
                qty = new BigDecimal(str(map.get("qty")));
            } catch (NumberFormatException e) {
                throw BusinessException.of("物料数量格式不正确：" + number);
            }
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.of("物料数量必须大于0：" + number);
            }
            merged.merge(number, qty, BigDecimal::add);
        }
        return merged.entrySet().stream()
                .map(e -> new ScmDispatchOutboundRequest.MaterialItem(e.getKey(), e.getValue()))
                .toList();
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
                    "SELECT u.id, COALESCE(NULLIF(u.real_name,''), NULLIF(u.nickname,''), "
                            + "u.username) AS name, "
                            + "uc.corp_id AS corpId, c.name AS corpName, "
                            // TINYINT(1) 会被 Connector/J 映射为 Boolean，显式 CAST 保证 0/1
                            + "CAST(u.user_type AS SIGNED) AS userType, "
                            + "(SELECT COUNT(1) FROM ai_platform.sys_user_wechat w "
                            + " WHERE w.user_id = u.id AND w.status = 1 AND w.delete_flag = 0) "
                            + " AS wechatBound, "
                            // 施工工单只允许派给施工员；角色按订单组织核算，内部/外部不限
                            + "(SELECT COUNT(1) FROM ai_platform.sys_user_corp_role ur "
                            + " JOIN ai_platform.sys_corp_user_role cr ON cr.id = ur.corp_role_id "
                            + "  AND cr.corp_id = uc.corp_id AND cr.delete_flag = 0 "
                            + " WHERE ur.user_id = u.id AND cr.role_name = '施工员') AS workerRole "
                            + "FROM ai_platform.sys_user u "
                            + "JOIN ai_platform.sys_user_corp uc ON uc.user_id = u.id "
                            + " AND uc.corp_id = ? AND uc.status = 1 AND uc.delete_flag = 0 "
                            + "LEFT JOIN ai_platform.sys_corp c "
                            + " ON c.id = uc.corp_id AND c.delete_flag = 0 "
                            + "WHERE u.id = ? AND u.status = 1 AND u.delete_flag = 0 LIMIT 1",
                    corpId, userId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("校验上门员工失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    // ═══ 状态同步 ═══

    private void syncStatus(OpsBusinessOrder order, WorkflowBusinessEvent event) {
        // 撤销财务核对检测：旧状态=2 且新状态≠2 时联动 SCM 采购单反审
        String oldBizStatus = order.getBizStatus();
        // 终止/驳回事件可能晚于新实例（作废流程）到达：不能覆盖新的实例/节点字段
        boolean terminal = "INSTANCE_TERMINATED".equals(event.getEvent())
                || "INSTANCE_REJECTED".equals(event.getEvent());
        // 作废流程占用期间（PENDING）、已作废或草稿态时，正常流程的迟到事件不得覆盖 wf 字段
        boolean voidOwned = "-1".equals(order.getBizStatus())
                || Integer.valueOf(1).equals(order.getDraftFlag())
                || (event.getInstanceId() != null
                && !event.getInstanceId().equals(order.getWfInstanceId())
                && refundService.findPending(order.getId()) != null);
        // 旧实例的迟到 NODE_ENTERED：实例已结束则跳过，避免覆盖新实例字段/业务状态
        boolean staleInstance = false;
        if (event.getInstanceId() != null
                && !event.getInstanceId().equals(order.getWfInstanceId())) {
            Map<Long, WorkflowPlatformClient.InstanceProgress> progress =
                    workflowClient.instanceProgressBatch(List.of(event.getInstanceId()));
            WorkflowPlatformClient.InstanceProgress p = progress.get(event.getInstanceId());
            staleInstance = p != null && !"RUNNING".equals(p.status());
        }
        boolean skipWf = terminal || voidOwned || staleInstance;
        boolean skipStatus = voidOwned || staleInstance;
        String incomingStatus = event.getBizStatus() == null
                ? "" : event.getBizStatus().trim();
        // 新状态链订单的迟到「派工出库」回调：只有工作流确实退回派工节点（node_order=4）
        // 才接受回退；否则忽略，避免把待接收/已开工的订单冲回派工状态。
        boolean dispatchRegression = isDispatchRegression(incomingStatus, oldBizStatus);
        // 迟到回调的 wf 节点字段同样过期（会把「施工执行」显示回「派工出库」），一并忽略
        skipWf = skipWf || dispatchRegression;
        boolean applyStatus = !skipStatus && !incomingStatus.isEmpty() && !dispatchRegression;
        LambdaUpdateWrapper<OpsBusinessOrder> update = new LambdaUpdateWrapper<>();
        update.eq(OpsBusinessOrder::getId, order.getId())
                // 并发退回草稿时（回调读到的是退回前状态）不得把 biz_status 冲回去
                .ne(OpsBusinessOrder::getDraftFlag, 1)
                .set(OpsBusinessOrder::getModifyTime, LocalDateTime.now());
        if (applyStatus) {
            update.set(OpsBusinessOrder::getBizStatus, incomingStatus);
        }
        if (applyStatus && OrderConstructStatus.DISPATCH.equals(incomingStatus)) {
            // 退回派工：清空施工员与各节点时间，等待派工人重新指派
            clearWorkerFields(update);
        }
        if (event.getInstanceId() != null && !skipWf) {
            update.set(OpsBusinessOrder::getWfInstanceId, event.getInstanceId());
        }
        if (event.getNodeOrder() != null && !skipWf) {
            update.set(OpsBusinessOrder::getWfNodeOrder, event.getNodeOrder());
        }
        if (event.getNodeName() != null && !skipWf) {
            update.set(OpsBusinessOrder::getWfNodeName, event.getNodeName());
        }
        orderMapper.update(null, update);
        if (applyStatus) {
            order.setBizStatus(incomingStatus);
            if (OrderConstructStatus.DISPATCH.equals(incomingStatus)) {
                clearWorkerFields(order);
            }
        }
        // 财务核对（biz_status=2）被撤销：未入库且未推送成功时由 SCM 自动反审采购单
        if (applyStatus && oldBizStatus != null && "2".equals(oldBizStatus.trim())
                && !"2".equals(incomingStatus)
                && (Integer.valueOf(1).equals(order.getHasTradeIn())
                    || order.getTradeInPurchaseOrderId() != null)) {
            runAfterCommit(() -> scmPurchaseOrderClient.financeReverted(
                    order.getCorpId(), order.getId()));
        }
        log.info("订单 {} 业务状态同步：{} -> {} ({})", order.getId(),
                event.getEvent(), event.getBizStatus(), event.getNodeName());
    }

    /**
     * 新状态链订单的迟到「派工出库」回调一律忽略。
     *
     * <p>合法退回派工只有一条路径：施工员在工作台点「拒绝接单」——业务接口已在
     * 同一事务内把状态写回 3 并清空施工员，状态回调无需再写。若回调把
     * WAIT_ACCEPT/ACCEPTED 覆盖回 3，反而会造成「有施工任务但订单无施工员」的卡单。</p>
     */
    private boolean isDispatchRegression(String incomingStatus, String oldStatus) {
        return OrderConstructStatus.DISPATCH.equals(incomingStatus)
                && isAfterDispatch(oldStatus);
    }

    private static void clearWorkerFields(LambdaUpdateWrapper<OpsBusinessOrder> update) {
        update.set(OpsBusinessOrder::getWorkerId, null)
                .set(OpsBusinessOrder::getWorkerName, null)
                .set(OpsBusinessOrder::getWorkerUserType, null)
                .set(OpsBusinessOrder::getAcceptTime, null)
                .set(OpsBusinessOrder::getArriveTime, null)
                .set(OpsBusinessOrder::getCustomerConfirmTime, null)
                .set(OpsBusinessOrder::getStartTime, null);
    }

    private static void clearWorkerFields(OpsBusinessOrder order) {
        order.setWorkerId(null);
        order.setWorkerName(null);
        order.setWorkerUserType(null);
        order.setAcceptTime(null);
        order.setArriveTime(null);
        order.setCustomerConfirmTime(null);
        order.setStartTime(null);
    }

    /** 业务状态已在派工之后（新状态链/施工中/完成/验收/归档）。 */
    private static boolean isAfterDispatch(String status) {
        if (status == null) return false;
        String value = status.trim();
        return OrderConstructStatus.isPreStart(value)
                || OrderConstructStatus.isConstructing(value)
                || "6".equals(value) || "7".equals(value);
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
