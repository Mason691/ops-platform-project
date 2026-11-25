package com.aip.ops.service.construct;

import com.aip.ops.client.WorkflowPlatformClient;
import com.aip.ops.dto.ConstructActionDtos;
import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsCustomerNotice;
import com.aip.ops.entity.OpsOrderConstructLog;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.mapper.OpsCustomerNoticeMapper;
import com.aip.ops.mapper.OpsOrderConstructLogMapper;
import com.aip.ops.service.BizStatusService;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.ops.service.DataScopeService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aip.ops.service.construct.OrderConstructStatus.ACCEPTED;
import static com.aip.ops.service.construct.OrderConstructStatus.ARRIVED;
import static com.aip.ops.service.construct.OrderConstructStatus.CONSTRUCTING;
import static com.aip.ops.service.construct.OrderConstructStatus.CONSTRUCTION_DONE;
import static com.aip.ops.service.construct.OrderConstructStatus.CUSTOMER_CONFIRMED;
import static com.aip.ops.service.construct.OrderConstructStatus.CUSTOMER_REFUSED;
import static com.aip.ops.service.construct.OrderConstructStatus.DISPATCH;
import static com.aip.ops.service.construct.OrderConstructStatus.WAIT_ACCEPT;

/**
 * 施工状态链实现。
 *
 * <p>并发：{@code SELECT ... FOR UPDATE} 串行化同一订单，状态更新再带
 * biz_status + version 的 CAS；重复提交按当前状态/签名哈希幂等返回，不写重复日志。</p>
 *
 * <p>留痕：每次动作写 ops_order_construct_log；客户签字写 ops_customer_notice
 * （版本递增，旧版本 status=0 保留）。对外通知异步执行，失败不影响业务。</p>
 */
@Slf4j
@Service
public class OrderConstructServiceImpl implements OrderConstructService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DEFAULT_NOTICE_TEMPLATE = """
            施工用户告知书

            尊敬的客户：
                您好！我司施工员【{workerName}】已于 {noticeTime} 上门，
            对订单【{orderNo}】（小区：{community}，地址：{address}，施工内容：{products}）进行施工服务。
                现与您确认施工内容及开工安排，请您在下方签字确认。

            施工员：{workerName}        客户签字：________
            日期：{date}                日期：__________""";

    private final OpsBusinessOrderMapper orderMapper;
    private final OpsOrderConstructLogMapper logMapper;
    private final OpsCustomerNoticeMapper noticeMapper;
    private final BusinessAttachmentService attachmentService;
    private final BizStatusService bizStatusService;
    private final WorkflowPlatformClient workflowClient;
    private final JdbcTemplate jdbcTemplate;
    private final ThreadPoolTaskExecutor executor;
    private final ConstructSignatureStorage signatureStorage;
    private final DataScopeService dataScopeService;
    private final com.aip.ops.client.ScmStockOutClient scmClient;

    @Value("${construct.notice-template:}")
    private String configuredNoticeTemplate;

    @Value("${construct.notify.enabled:true}")
    private boolean notifyEnabled;

    public OrderConstructServiceImpl(OpsBusinessOrderMapper orderMapper,
                                     OpsOrderConstructLogMapper logMapper,
                                     OpsCustomerNoticeMapper noticeMapper,
                                     BusinessAttachmentService attachmentService,
                                     BizStatusService bizStatusService,
                                     WorkflowPlatformClient workflowClient,
                                     JdbcTemplate jdbcTemplate,
                                     @Qualifier("workflowTaskExecutor")
                                     ThreadPoolTaskExecutor executor,
                                     ConstructSignatureStorage signatureStorage,
                                     DataScopeService dataScopeService,
                                     com.aip.ops.client.ScmStockOutClient scmClient) {
        this.orderMapper = orderMapper;
        this.logMapper = logMapper;
        this.noticeMapper = noticeMapper;
        this.attachmentService = attachmentService;
        this.bizStatusService = bizStatusService;
        this.workflowClient = workflowClient;
        this.jdbcTemplate = jdbcTemplate;
        this.executor = executor;
        this.signatureStorage = signatureStorage;
        this.dataScopeService = dataScopeService;
        this.scmClient = scmClient;
    }

    // ═══ 读视图 ═══

    @Override
    public Map<String, Object> view(Long orderId) {
        OpsBusinessOrder order = requireViewableOrder(orderId);
        Long userId = SecurityUtils.getCurrentUserId();
        boolean worker = isWorker(order, userId);
        boolean active = worker && activeInCorp(userId, order.getCorpId());
        boolean corpMatch = Objects.equals(SecurityUtils.getCurrentCorpIdOrNull(),
                order.getCorpId());
        boolean running = workflowRunning(order);
        boolean canOperate = active && corpMatch && running;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", order.getId());
        view.put("orderNo", order.getOrderNo());
        // 供 H5 待办按当前组织过滤（施工员多组织时只显示当前组织任务）
        view.put("corpId", order.getCorpId());
        view.put("bizStatus", statusOf(order));
        view.put("bizStatusName", statusName(order));
        view.put("workerId", order.getWorkerId());
        view.put("workerName", order.getWorkerName());
        view.put("workerUserType", order.getWorkerUserType());
        view.put("acceptTime", text(order.getAcceptTime()));
        view.put("arriveTime", text(order.getArriveTime()));
        view.put("customerConfirmTime", text(order.getCustomerConfirmTime()));
        view.put("startTime", text(order.getStartTime()));
        view.put("constructionNote", order.getConstructionNote());
        view.put("completionNote", order.getCompletionNote());
        view.put("acceptanceSubmittedTime", text(order.getAcceptanceSubmittedTime()));
        view.put("constructionPhotos",
                attachmentViews(order.getConstructionAttachmentIds(), orderAttachments(order)));
        view.put("isWorker", worker);
        view.put("permissions", permissions(order, canOperate));
        view.put("operateBlockedReason", blockedReason(worker, active, corpMatch, running));
        view.put("logs", timeline(order));
        view.put("notice", activeNoticeView(order));
        view.put("noticeHistory", noticeHistory(order));
        view.put("noticePreview", renderNotice(order));
        return view;
    }

    private OpsBusinessOrder requireViewableOrder(Long orderId) {
        OpsBusinessOrder order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (isWorker(order, userId) || isAgent(order) || hasAnyTask(order, userId)
                || inDataScope(order)) {
            return order;
        }
        throw BusinessException.forbidden("无权查看该订单施工进度");
    }

    /** 运营/派工员/上级组织按数据范围只读查看；空集合不等于超管。 */
    private boolean inDataScope(OpsBusinessOrder order) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        return visible == null || visible.contains(order.getCorpId());
    }

    private Map<String, Boolean> permissions(OpsBusinessOrder order, boolean canOperate) {
        String status = statusOf(order);
        Map<String, Boolean> p = new LinkedHashMap<>();
        p.put("accept", canOperate && WAIT_ACCEPT.equals(status));
        p.put("reject", canOperate && WAIT_ACCEPT.equals(status));
        p.put("arrive", canOperate && ACCEPTED.equals(status));
        p.put("customerConfirm", canOperate && (ARRIVED.equals(status)
                || CUSTOMER_REFUSED.equals(status) || CUSTOMER_CONFIRMED.equals(status)));
        p.put("customerRefuse", canOperate && ARRIVED.equals(status));
        return p;
    }

    private String blockedReason(boolean worker, boolean active, boolean corpMatch,
                                 boolean running) {
        if (!worker) return "您不是该订单的施工员，无权操作";
        if (!active) return "您的账号在该组织已停用，请联系管理员";
        if (!corpMatch) return "请先切换到订单所属组织后再操作";
        if (!running) return "流程已结束，无法操作";
        return "";
    }

    // ═══ 施工员操作 ═══

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> accept(Long orderId) {
        OpsBusinessOrder order = requireOperableOrder(orderId, false);
        String from = statusOf(order);
        if (ACCEPTED.equals(from)) return result(order, "您已接受该任务");
        OrderConstructStatus.requireTransition(from, ACCEPTED);
        LocalDateTime now = LocalDateTime.now();
        transitionAsCurrent(order, ACCEPTED,
                w -> w.set(OpsBusinessOrder::getAcceptTime, now));
        insertLog(order, OpsOrderConstructLog.ACTION_ACCEPT, from, ACCEPTED,
                Location.none(), null, null, null);
        // 接受任务 → 对应物料领用单 0 未审核 自动置 1 已审核（SCM 幂等；失败则整体回滚）
        if (order.getDispatchTaskId() != null) {
            Long workerId = SecurityUtils.getCurrentUserId();
            scmClient.dispatchAccepted(order.getCorpId(), order.getDispatchTaskId(), workerId);
        }
        notifyProgress(order, "已接受任务", null);
        return result(order, "确认接受成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reject(Long orderId, String reason) {
        String clean = requireReason(reason, "REJECT_REASON_REQUIRED：请填写拒绝原因（不少于5个字）");
        OpsBusinessOrder order = requireOperableOrder(orderId, true);
        String from = statusOf(order);
        Long userId = SecurityUtils.getCurrentUserId();
        if (DISPATCH.equals(from) && hasLog(orderId, OpsOrderConstructLog.ACTION_REJECT,
                userId)) {
            return result(order, "您已拒绝该任务，订单已退回派工");
        }
        OrderConstructStatus.requireTransition(from, DISPATCH);
        Long taskId = pendingConstructTaskId(orderId, userId, order.getWfInstanceId());
        if (taskId == null) {
            throw BusinessException.of(
                    "ORDER_STATUS_CHANGED：未找到待处理的施工任务，请刷新后重试");
        }
        transitionAsCurrent(order, DISPATCH, this::clearWorkerAndTimes);
        insertLog(order, OpsOrderConstructLog.ACTION_REJECT, from, DISPATCH,
                Location.none(), null, null, clean);
        // 拒绝接单 → 对应物料领用单逻辑删除并留痕（SCM 幂等；失败则整体回滚）
        if (order.getDispatchTaskId() != null) {
            scmClient.dispatchRejected(order.getCorpId(), order.getDispatchTaskId(),
                    clean, userId, operatorName());
        }
        // 平台侧按任务所有者再次校验后驳回，工作流 PREVIOUS 退回派工节点并重建派工待办
        workflowClient.rejectConstructTask(taskId, clean, userId, operatorName());
        notifyProgress(order, "拒绝接受", clean);
        return result(order, "已拒绝接受，订单退回派工");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> arrive(Long orderId,
                                      ConstructActionDtos.ArriveRequest request) {
        OpsBusinessOrder order = requireOperableOrder(orderId, false);
        String from = statusOf(order);
        if (ARRIVED.equals(from) && hasLog(orderId, OpsOrderConstructLog.ACTION_ARRIVE, null)) {
            return result(order, "已打卡，请勿重复提交");
        }
        OrderConstructStatus.requireTransition(from, ARRIVED);
        LocalDateTime now = LocalDateTime.now();
        assertAfterAccept(order, now);
        Location location = normalizeLocation(request);
        List<Long> ids = validAttachmentIds(request == null ? null : request.attachmentIds());
        bindPhotos(order, ids);
        String remark = request == null ? null : trimToNull(request.remark());
        if (remark != null && remark.length() > 500) {
            throw BusinessException.of("打卡备注不能超过500字");
        }
        transitionAsCurrent(order, ARRIVED,
                w -> w.set(OpsBusinessOrder::getArriveTime, now));
        insertLog(order, OpsOrderConstructLog.ACTION_ARRIVE, from, ARRIVED, location,
                joinIds(ids), null, remark);
        notifyProgress(order, "已上门打卡", null);
        return result(order, "打卡成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> customerConfirm(
            Long orderId, ConstructActionDtos.CustomerConfirmRequest request) {
        OpsBusinessOrder order = requireOperableOrder(orderId, false);
        String from = statusOf(order);
        if (order.getArriveTime() == null) {
            throw BusinessException.of("请先完成上门打卡，再请客户签字");
        }
        ConstructSignatureStorage.StoredSignature stored = signatureStorage.store(
                order.getCorpId(), order.getId(),
                request == null ? null : request.signImageBase64());
        try {
            return confirmWithSignature(order, from, request, stored);
        } catch (RuntimeException e) {
            // 文件先于数据库落盘：后续校验/插入失败时清理孤儿签字图片
            signatureStorage.delete(stored);
            throw e;
        }
    }

    private Map<String, Object> confirmWithSignature(OpsBusinessOrder order, String from,
                                                     ConstructActionDtos.CustomerConfirmRequest request,
                                                     ConstructSignatureStorage.StoredSignature stored) {
        if (CUSTOMER_CONFIRMED.equals(from) && isSameSignature(order.getId(), stored.sha256())) {
            signatureStorage.delete(stored);
            // 兼容上线前已签字未开工的存量单：重试时自动补齐开工
            startConstructing(order, requireSignedNotice(order.getId()),
                    "已开始施工", "存量签字单自动进入施工中");
            return result(order, "客户已签字确认，任务已进入施工中");
        }
        // 签字即开工后，网络重试同一签名按幂等成功返回，不能报状态不允许
        if (CONSTRUCTING.equals(from) && isSameSignature(order.getId(), stored.sha256())) {
            signatureStorage.delete(stored);
            return result(order, "客户已签字确认，任务已进入施工中");
        }
        OrderConstructStatus.requireTransition(from, CUSTOMER_CONFIRMED);
        LocalDateTime now = LocalDateTime.now();
        // 签字定位优先取请求坐标，缺省沿用上门打卡坐标（客户签字时通常就在现场）
        Location location = customerLocation(order.getId(), request);
        OpsCustomerNotice notice = saveNotice(order, stored, now, location);
        boolean first = order.getCustomerConfirmTime() == null;
        transitionAsCurrent(order, CUSTOMER_CONFIRMED, w -> {
            if (first) w.set(OpsBusinessOrder::getCustomerConfirmTime, now);
        });
        String remark = first ? null : "重新签字";
        insertLog(order, OpsOrderConstructLog.ACTION_CUSTOMER_CONFIRM, from,
                CUSTOMER_CONFIRMED, location, null, notice.getId(), remark);
        notifyProgress(order, "客户已签字确认", null);
        // 签字即开工：客户确认后无需再点“确认开工”，同一事务内自动进入施工中
        startConstructing(order, notice, "已开始施工", "客户签字后自动进入施工中");
        return result(order, "客户已签字确认，任务已进入施工中");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> customerRefuse(Long orderId, String reason) {
        String clean = requireReason(reason,
                "CUSTOMER_REFUSE_REASON_REQUIRED：请填写客户拒绝原因（不少于5个字）");
        OpsBusinessOrder order = requireOperableOrder(orderId, false);
        String from = statusOf(order);
        if (CUSTOMER_REFUSED.equals(from)) {
            return result(order, "已记录客户拒绝确认");
        }
        OrderConstructStatus.requireTransition(from, CUSTOMER_REFUSED);
        transitionAsCurrent(order, CUSTOMER_REFUSED, w -> { });
        insertLog(order, OpsOrderConstructLog.ACTION_CUSTOMER_REFUSE, from,
                CUSTOMER_REFUSED, Location.none(), null, null, clean);
        notifyProgress(order, "客户拒绝确认", clean);
        return result(order, "已记录客户拒绝，请联系派工人处理");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(Long orderId) {
        OpsBusinessOrder order = requireOperableOrder(orderId, false);
        String from = statusOf(order);
        if (CONSTRUCTING.equals(from)) return result(order, "订单已开工");
        if (!CUSTOMER_CONFIRMED.equals(from)) {
            throw BusinessException.of(
                    "INVALID_STATUS_TRANSITION：当前状态不允许开始施工，请先完成客户签字");
        }
        OpsCustomerNotice notice = requireSignedNotice(orderId);
        startConstructing(order, notice, "已开始施工", "兼容旧流程：确认开工");
        return result(order, "已开始施工");
    }

    /** 客户签字后自动开工（历史“确认开工”接口也复用）：强校验告知书与上门打卡。 */
    private void startConstructing(OpsBusinessOrder order, OpsCustomerNotice notice,
                                   String notifyAction, String remark) {
        if (notice == null || isBlank(notice.getSignImageUrl())) {
            throw BusinessException.of(
                    "CUSTOMER_NOTICE_REQUIRED：客户尚未签署用户告知书，无法开工");
        }
        if (!hasLog(order.getId(), OpsOrderConstructLog.ACTION_ARRIVE, null)) {
            throw BusinessException.of("ARRIVE_BEFORE_ACCEPT：未完成上门打卡，无法开工");
        }
        LocalDateTime now = LocalDateTime.now();
        transitionAsCurrent(order, CONSTRUCTING,
                w -> w.set(OpsBusinessOrder::getStartTime, now));
        insertLog(order, OpsOrderConstructLog.ACTION_START, CUSTOMER_CONFIRMED, CONSTRUCTING,
                Location.none(), null, notice.getId(), remark);
        notifyProgress(order, notifyAction, null);
    }

    private OpsCustomerNotice requireSignedNotice(Long orderId) {
        OpsCustomerNotice notice = activeNotice(orderId);
        if (notice == null || isBlank(notice.getSignImageUrl())) {
            throw BusinessException.of(
                    "CUSTOMER_NOTICE_REQUIRED：客户尚未签署用户告知书，无法开工");
        }
        return notice;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitAcceptanceRecord(Long orderId, String constructionNote,
                                                      String completionNote,
                                                      List<Long> attachmentIds,
                                                      Long operatorUserId, String operatorName) {
        OpsBusinessOrder order = requireAcceptanceOrder(orderId);
        String from = statusOf(order);
        String construction = noteText(constructionNote, "施工说明");
        String completion = requireCompletionNote(completionNote);
        List<Long> ids = acceptancePhotos(order, attachmentIds, operatorUserId);
        // 历史订单在施工链上线前已置施工中（无客户签字/打卡记录），沿用旧口径允许直接提交；
        // 新流程订单完成签字后 customerConfirmTime 必非空，仍需满足签字与打卡校验。
        boolean legacy = isLegacyConstruction(order);
        OpsCustomerNotice notice = legacy ? null : requireSignedNotice(orderId);
        if (!legacy && !hasLog(orderId, OpsOrderConstructLog.ACTION_ARRIVE, null)) {
            throw BusinessException.of("ARRIVE_BEFORE_ACCEPT：未完成上门打卡，无法提交验收");
        }
        saveAcceptanceRecord(order, from, construction, completion, ids, notice,
                operatorUserId, operatorName);
        // 验收人通知由工作流创建节点待办时统一发送（既有消息机制），此处不重复推送
        return result(order, "施工记录已保存，验收申请已提交");
    }

    /** 施工链上线前的在途订单：无客户确认时间且无上门打卡记录。 */
    private boolean isLegacyConstruction(OpsBusinessOrder order) {
        return order.getCustomerConfirmTime() == null
                && !hasLog(order.getId(), OpsOrderConstructLog.ACTION_ARRIVE, null);
    }

    private OpsBusinessOrder requireAcceptanceOrder(Long orderId) {
        OpsBusinessOrder order = orderId == null ? null : orderMapper.selectForUpdate(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        String status = statusOf(order);
        if (!CONSTRUCTING.equals(status) && !CONSTRUCTION_DONE.equals(status)) {
            throw BusinessException.of(
                    "CONSTRUCTION_NOT_STARTED：请先完成确认接受、上门打卡与客户签字");
        }
        return order;
    }

    private static String requireCompletionNote(String raw) {
        String completion = noteText(raw, "完工说明");
        if (completion == null) {
            throw BusinessException.of("COMPLETION_NOTE_REQUIRED：请填写完工说明");
        }
        return completion;
    }

    /** 施工照片：新上传优先；旧客户端未传时保留上次已绑定照片。 */
    private List<Long> acceptancePhotos(OpsBusinessOrder order, List<Long> attachmentIds,
                                        Long operatorUserId) {
        List<Long> ids = validAttachmentIds(attachmentIds);
        if (attachmentIds == null && !isBlank(order.getConstructionAttachmentIds())) {
            ids = validAttachmentIds(parseIds(order.getConstructionAttachmentIds()));
        }
        if (!ids.isEmpty()) {
            attachmentService.requireOwnedListForUploader(ids, OpsBusinessAttachment.TYPE_CONSTRUCT,
                    order.getCorpId(), order.getId(), operatorUserId);
        }
        return ids;
    }

    private void saveAcceptanceRecord(OpsBusinessOrder order, String from, String construction,
                                      String completion, List<Long> ids,
                                      OpsCustomerNotice notice, Long operatorUserId,
                                      String operatorName) {
        LocalDateTime now = LocalDateTime.now();
        int nextVersion = versionOf(order) + 1;
        int updated = orderMapper.update(null, Wrappers.<OpsBusinessOrder>lambdaUpdate()
                .eq(OpsBusinessOrder::getId, order.getId())
                .in(OpsBusinessOrder::getBizStatus, CONSTRUCTING, CONSTRUCTION_DONE)
                .set(OpsBusinessOrder::getConstructionNote, construction)
                .set(OpsBusinessOrder::getCompletionNote, completion)
                .set(OpsBusinessOrder::getConstructionAttachmentIds, joinIds(ids))
                .set(OpsBusinessOrder::getAcceptanceSubmittedTime, now)
                .set(OpsBusinessOrder::getBizStatus, CONSTRUCTION_DONE)
                .set(OpsBusinessOrder::getVersion, nextVersion)
                .set(OpsBusinessOrder::getModifyTime, now));
        if (updated == 0) {
            throw BusinessException.of("ORDER_STATUS_CHANGED：订单状态已变更，请刷新后重试");
        }
        if (!ids.isEmpty()) {
            attachmentService.bindToOrder(ids, order.getId(), order.getCorpId());
        }
        order.setBizStatus(CONSTRUCTION_DONE);
        order.setVersion(nextVersion);
        insertLogAs(order, OpsOrderConstructLog.ACTION_SUBMIT_ACCEPTANCE, from,
                CONSTRUCTION_DONE, operatorUserId, operatorName, null, Location.none(),
                joinIds(ids), notice == null ? null : notice.getId(), null);
    }

    /** 施工记录文本：允许为空；填写时最多 1000 字。 */
    private static String noteText(String value, String label) {
        String text = trimToNull(value);
        if (text == null) return null;
        if (text.length() > 1000) {
            throw BusinessException.of(label + "不能超过1000字");
        }
        return text;
    }

    @Override
    public Map<String, Object> verifyNotice(Long orderId) {
        requireViewableOrder(orderId);
        OpsCustomerNotice notice = activeNotice(orderId);
        if (notice == null || isBlank(notice.getSignImageUrl())) {
            throw BusinessException.notFound("尚无有效的用户告知书");
        }
        String actual = signatureStorage.hashOf(notice.getSignImageUrl());
        boolean consistent = notice.getSignImageHash() != null
                && notice.getSignImageHash().equalsIgnoreCase(actual);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("noticeId", notice.getId());
        result.put("version", notice.getVersion());
        result.put("storedHash", notice.getSignImageHash());
        result.put("actualHash", actual);
        result.put("consistent", consistent);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markDispatched(OpsBusinessOrder order, Long workerUserId, String workerName,
                               String workerPhone, Integer workerType, String dispatchType,
                               Long operatorUserId, String operatorName, String remark) {
        if (order == null || order.getId() == null) return;
        OpsBusinessOrder fresh = orderMapper.selectForUpdate(order.getId());
        if (fresh == null) {
            throw BusinessException.notFound("业务订单不存在");
        }
        boolean visit = "VISIT".equalsIgnoreCase(dispatchType);
        String from = statusOf(fresh);
        String to = visit ? CONSTRUCTING : WAIT_ACCEPT;
        if (to.equals(from) && Objects.equals(workerUserId, fresh.getWorkerId())) {
            return;
        }
        if (!DISPATCH.equals(from)) {
            // fail-closed：状态不符时让平台审批失败、任务保持待处理，避免推进后订单无施工员
            throw BusinessException.of(
                    "ORDER_STATUS_CHANGED：订单当前状态不允许派工，请刷新后重试");
        }
        OrderConstructStatus.requireTransition(from, to);
        transition(fresh, to, operatorUserId, operatorName, w -> w
                .set(OpsBusinessOrder::getWorkerId, workerUserId)
                .set(OpsBusinessOrder::getWorkerName, workerName)
                .set(OpsBusinessOrder::getWorkerUserType, workerType)
                .set(OpsBusinessOrder::getAcceptTime, null)
                .set(OpsBusinessOrder::getArriveTime, null)
                .set(OpsBusinessOrder::getCustomerConfirmTime, null)
                .set(OpsBusinessOrder::getStartTime, null));
        insertLogAs(fresh, OpsOrderConstructLog.ACTION_ASSIGN, from, to,
                operatorUserId, operatorName, null, Location.none(), null, null, remark,
                workerUserId, workerName, workerPhone);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markDispatchAttempt(Long orderId, Long taskId) {
        if (orderId == null || taskId == null) return;
        orderMapper.update(null, Wrappers.<OpsBusinessOrder>lambdaUpdate()
                .eq(OpsBusinessOrder::getId, orderId)
                .set(OpsBusinessOrder::getDispatchTaskId, taskId)
                .set(OpsBusinessOrder::getModifyTime, LocalDateTime.now()));
    }

    @Override
    public boolean isRedispatchAfterReject(Long orderId) {
        if (orderId == null) return false;
        Long assign = logMapper.selectCount(Wrappers.<OpsOrderConstructLog>lambdaQuery()
                .eq(OpsOrderConstructLog::getOrderId, orderId)
                .eq(OpsOrderConstructLog::getAction, OpsOrderConstructLog.ACTION_ASSIGN)
                .eq(OpsOrderConstructLog::getDeleteFlag, 0));
        if (assign == null || assign <= 0) return false;
        Long reject = logMapper.selectCount(Wrappers.<OpsOrderConstructLog>lambdaQuery()
                .eq(OpsOrderConstructLog::getOrderId, orderId)
                .eq(OpsOrderConstructLog::getAction, OpsOrderConstructLog.ACTION_REJECT)
                .eq(OpsOrderConstructLog::getDeleteFlag, 0));
        return reject != null && reject > 0;
    }

    // ═══ 状态机 / 权限 ═══

    private OpsBusinessOrder requireOperableOrder(Long orderId, boolean allowRejected) {
        OpsBusinessOrder order = orderId == null ? null : orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw BusinessException.notFound("业务订单不存在");
        }
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || !corpId.equals(order.getCorpId())) {
            throw BusinessException.forbidden("请先切换到订单所属组织后再操作");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (!isWorker(order, userId)) {
            if (allowRejected && DISPATCH.equals(statusOf(order))
                    && hasLog(orderId, OpsOrderConstructLog.ACTION_REJECT, userId)) {
                return order;
            }
            throw BusinessException.forbidden(
                    "NOT_ORDER_WORKER：您不是该订单的施工员，无权操作");
        }
        if (!activeInCorp(userId, order.getCorpId())) {
            throw BusinessException.forbidden("您的账号在该组织已停用，请联系管理员");
        }
        if (!workflowRunning(order)) {
            throw BusinessException.of("ORDER_STATUS_CHANGED：流程已结束，请刷新后重试");
        }
        return order;
    }

    private void transitionAsCurrent(OpsBusinessOrder order, String to,
                                     Consumer<LambdaUpdateWrapper<OpsBusinessOrder>> extra) {
        transition(order, to, SecurityUtils.getCurrentUserId(), operatorName(), extra);
    }

    /**
     * CAS：状态 + version 双条件，0 行即视为他人已变更。
     *
     * <p>operator 显式传入：工作流派工回调没有 SecurityContext，不能依赖当前登录人。</p>
     */
    private void transition(OpsBusinessOrder order, String to, Long operatorId,
                            String operator, Consumer<LambdaUpdateWrapper<OpsBusinessOrder>> extra) {
        String from = statusOf(order);
        OrderConstructStatus.requireTransition(from, to);
        int version = versionOf(order);
        LambdaUpdateWrapper<OpsBusinessOrder> update = new LambdaUpdateWrapper<>();
        update.eq(OpsBusinessOrder::getId, order.getId())
                .eq(OpsBusinessOrder::getVersion, version)
                .set(OpsBusinessOrder::getBizStatus, to)
                .set(OpsBusinessOrder::getVersion, version + 1)
                .set(OpsBusinessOrder::getModifyTime, LocalDateTime.now())
                .set(OpsBusinessOrder::getModifyUser, operatorId)
                .set(OpsBusinessOrder::getModifyUserName, operator);
        if (from == null) {
            update.isNull(OpsBusinessOrder::getBizStatus);
        } else {
            update.eq(OpsBusinessOrder::getBizStatus, from);
        }
        extra.accept(update);
        if (orderMapper.update(null, update) == 0) {
            throw BusinessException.of("ORDER_STATUS_CHANGED：订单状态已变更，请刷新后重试");
        }
        order.setBizStatus(to);
        order.setVersion(version + 1);
    }

    private void clearWorkerAndTimes(LambdaUpdateWrapper<OpsBusinessOrder> update) {
        update.set(OpsBusinessOrder::getWorkerId, null)
                .set(OpsBusinessOrder::getWorkerName, null)
                .set(OpsBusinessOrder::getWorkerUserType, null)
                .set(OpsBusinessOrder::getAcceptTime, null)
                .set(OpsBusinessOrder::getArriveTime, null)
                .set(OpsBusinessOrder::getCustomerConfirmTime, null)
                .set(OpsBusinessOrder::getStartTime, null);
    }

    // ═══ 告知书 ═══

    private OpsCustomerNotice saveNotice(OpsBusinessOrder order,
                                         ConstructSignatureStorage.StoredSignature stored,
                                         LocalDateTime now, Location location) {
        int nextVersion = latestNoticeVersion(order.getId()) + 1;
        deactivateNotices(order.getId());
        OpsCustomerNotice notice = new OpsCustomerNotice();
        notice.setCorpId(order.getCorpId());
        notice.setOrderId(order.getId());
        notice.setOrderNo(order.getOrderNo());
        notice.setWorkerId(order.getWorkerId() == null
                ? SecurityUtils.getCurrentUserId() : order.getWorkerId());
        notice.setWorkerName(isBlank(order.getWorkerName())
                ? operatorName() : order.getWorkerName());
        notice.setCustomerName(order.getCustomer());
        notice.setNoticeContent(renderNotice(order));
        notice.setNoticeTime(order.getArriveTime() == null ? now : order.getArriveTime());
        notice.setSignImageUrl(stored.url());
        notice.setSignImageHash(stored.sha256());
        notice.setSignTime(now);
        notice.setSignLocationLng(location.lng());
        notice.setSignLocationLat(location.lat());
        notice.setVersion(nextVersion);
        notice.setStatus(1);
        notice.setDeleteFlag(0);
        notice.setCreateTime(now);
        notice.setModifyTime(now);
        notice.setCreateUser(SecurityUtils.getCurrentUserId());
        notice.setModifyUser(SecurityUtils.getCurrentUserId());
        notice.setCreateUserName(operatorName());
        notice.setModifyUserName(operatorName());
        noticeMapper.insert(notice);
        log.info("客户告知书签署: orderId={}, noticeId={}, version={}, hash={}",
                order.getId(), notice.getId(), nextVersion, stored.sha256());
        return notice;
    }

    private boolean isSameSignature(Long orderId, String hash) {
        OpsCustomerNotice latest = latestActiveNotice(orderId);
        return latest != null && hash != null && hash.equals(latest.getSignImageHash());
    }

    private void deactivateNotices(Long orderId) {
        noticeMapper.update(null, Wrappers.<OpsCustomerNotice>lambdaUpdate()
                .eq(OpsCustomerNotice::getOrderId, orderId)
                .eq(OpsCustomerNotice::getStatus, 1)
                .eq(OpsCustomerNotice::getDeleteFlag, 0)
                .set(OpsCustomerNotice::getStatus, 0)
                .set(OpsCustomerNotice::getModifyTime, LocalDateTime.now()));
    }

    private int latestNoticeVersion(Long orderId) {
        OpsCustomerNotice latest = noticeMapper.selectOne(
                Wrappers.<OpsCustomerNotice>lambdaQuery()
                        .eq(OpsCustomerNotice::getOrderId, orderId)
                        .eq(OpsCustomerNotice::getDeleteFlag, 0)
                        .orderByDesc(OpsCustomerNotice::getVersion)
                        .last("LIMIT 1"));
        return latest == null || latest.getVersion() == null ? 0 : latest.getVersion();
    }

    private OpsCustomerNotice latestActiveNotice(Long orderId) {
        return noticeMapper.selectOne(Wrappers.<OpsCustomerNotice>lambdaQuery()
                .eq(OpsCustomerNotice::getOrderId, orderId)
                .eq(OpsCustomerNotice::getStatus, 1)
                .eq(OpsCustomerNotice::getDeleteFlag, 0)
                .orderByDesc(OpsCustomerNotice::getVersion)
                .last("LIMIT 1"));
    }

    private OpsCustomerNotice activeNotice(Long orderId) {
        return latestActiveNotice(orderId);
    }

    private String renderNotice(OpsBusinessOrder order) {
        String template = isBlank(configuredNoticeTemplate)
                ? DEFAULT_NOTICE_TEMPLATE : configuredNoticeTemplate;
        LocalDateTime noticeTime = order.getArriveTime() == null
                ? LocalDateTime.now() : order.getArriveTime();
        String community = isBlank(order.getCommunityName()) ? "-" : order.getCommunityName();
        String address = isBlank(order.getDetailedAddress()) ? "-" : order.getDetailedAddress();
        return template
                .replace("{workerName}", nvl(order.getWorkerName()))
                .replace("{noticeTime}", noticeTime.format(TIME_FMT))
                .replace("{orderNo}", nvl(order.getOrderNo()))
                .replace("{community}", community)
                .replace("{address}", address)
                .replace("{products}", productSummary(order.getId()))
                .replace("{customerName}", nvl(order.getCustomer()))
                .replace("{date}", noticeTime.toLocalDate().toString());
    }

    private String productSummary(Long orderId) {
        List<OpsBusinessData> items = orderMapper.selectItems(orderId);
        StringBuilder summary = new StringBuilder();
        int limit = Math.min(items.size(), 5);
        for (int i = 0; i < limit; i++) {
            OpsBusinessData item = items.get(i);
            if (i > 0) summary.append('、');
            summary.append(nvl(item.getBrand())).append(nvl(item.getModel()))
                    .append("×").append(item.getSaleQuantity() == null ? 1 : item.getSaleQuantity());
        }
        if (items.size() > limit) summary.append(" 等").append(items.size()).append("项");
        return summary.isEmpty() ? "-" : summary.toString();
    }

    // ═══ 留痕 ═══

    private void insertLog(OpsBusinessOrder order, String action, String from, String to,
                           Location location, String attachmentIds, Long noticeId,
                           String remark) {
        insertLogAs(order, action, from, to, SecurityUtils.getCurrentUserId(),
                operatorName(), SecurityUtils.getCurrentUserTypeOrNull(), location,
                attachmentIds, noticeId, remark);
    }

    private void insertLogAs(OpsBusinessOrder order, String action, String from, String to,
                             Long operatorId, String operator, Integer operatorType,
                             String remark) {
        insertLogAs(order, action, from, to, operatorId, operator, operatorType,
                Location.none(), null, null, remark, null, null, null);
    }

    private void insertLogAs(OpsBusinessOrder order, String action, String from, String to,
                             Long operatorId, String operator, Integer operatorType,
                             Location location, String attachmentIds, Long noticeId,
                             String remark) {
        insertLogAs(order, action, from, to, operatorId, operator, operatorType,
                location, attachmentIds, noticeId, remark, null, null, null);
    }

    private void insertLogAs(OpsBusinessOrder order, String action, String from, String to,
                             Long operatorId, String operator, Integer operatorType,
                             Location location, String attachmentIds, Long noticeId,
                             String remark, Long workerId, String workerName,
                             String workerPhone) {
        LocalDateTime now = LocalDateTime.now();
        OpsOrderConstructLog row = new OpsOrderConstructLog();
        row.setCorpId(order.getCorpId());
        row.setOrderId(order.getId());
        row.setOrderNo(nvl(order.getOrderNo()));
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setAction(action);
        row.setOperatorId(operatorId == null ? 0L : operatorId);
        row.setOperatorName(operator);
        row.setOperatorType(operatorType);
        row.setOperateTime(now);
        row.setLocationStatus(location.status());
        row.setLocationLng(location.lng());
        row.setLocationLat(location.lat());
        row.setLocationAddress(location.address());
        row.setAttachmentIds(attachmentIds);
        row.setNoticeId(noticeId);
        row.setWorkerId(workerId);
        row.setWorkerName(workerName);
        row.setWorkerPhone(workerPhone);
        row.setCreateTime(now);
        row.setModifyTime(now);
        row.setCreateUser(operatorId);
        row.setModifyUser(operatorId);
        row.setCreateUserName(operator);
        row.setModifyUserName(operator);
        row.setDeleteFlag(0);
        row.setRemark(remark);
        logMapper.insert(row);
        log.info("施工流转: orderId={}, {} {}→{}, operator={}, location={}",
                order.getId(), action, from, to, operator, location.status());
    }

    private List<Map<String, Object>> timeline(OpsBusinessOrder order) {
        List<OpsOrderConstructLog> logs = logMapper.selectList(
                Wrappers.<OpsOrderConstructLog>lambdaQuery()
                        .eq(OpsOrderConstructLog::getOrderId, order.getId())
                        .eq(OpsOrderConstructLog::getDeleteFlag, 0)
                        .orderByAsc(OpsOrderConstructLog::getId));
        Map<Long, OpsBusinessAttachment> files = orderAttachments(order);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OpsOrderConstructLog row : logs) {
            rows.add(logView(row, files, order));
        }
        return rows;
    }

    private Map<Long, OpsBusinessAttachment> orderAttachments(OpsBusinessOrder order) {
        return attachmentService.listByOrder(order.getId())
                .stream().collect(Collectors.toMap(OpsBusinessAttachment::getId, f -> f,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private Map<String, Object> logView(OpsOrderConstructLog row,
                                        Map<Long, OpsBusinessAttachment> files,
                                        OpsBusinessOrder order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("action", row.getAction());
        view.put("actionName", actionName(row.getAction()));
        view.put("displayText", displayText(row, order));
        view.put("displayHint", displayHint(row));
        view.put("fromStatus", row.getFromStatus());
        view.put("fromStatusName", bizStatusService.label(row.getFromStatus()));
        view.put("toStatus", row.getToStatus());
        view.put("toStatusName", bizStatusService.label(row.getToStatus()));
        view.put("operatorId", row.getOperatorId());
        view.put("operatorName", row.getOperatorName());
        view.put("operatorType", row.getOperatorType());
        view.put("workerId", row.getWorkerId());
        view.put("workerName", workerName(row, order));
        view.put("workerPhone", maskPhone(row.getWorkerPhone()));
        view.put("operateTime", text(row.getOperateTime()));
        view.put("locationStatus", row.getLocationStatus());
        view.put("locationLng", row.getLocationLng());
        view.put("locationLat", row.getLocationLat());
        view.put("locationAddress", row.getLocationAddress());
        view.put("noticeId", row.getNoticeId());
        view.put("remark", row.getRemark());
        view.put("attachments", attachmentViews(row.getAttachmentIds(), files));
        return view;
    }

    private List<Map<String, Object>> attachmentViews(String csv,
                                                      Map<Long, OpsBusinessAttachment> files) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long id : parseIds(csv)) {
            OpsBusinessAttachment file = files.get(id);
            if (file == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", file.getId());
            row.put("url", file.getFileUrl());
            row.put("name", file.getFileName());
            rows.add(row);
        }
        return rows;
    }

    // ═══ 查询辅助 ═══

    /** 施工员在订单组织内必须是启用状态的任职（派工后被停用/移除则不可继续操作）。 */
    private boolean activeInCorp(Long userId, Long corpId) {
        if (userId == null || corpId == null) return false;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_platform.sys_user u "
                        + "JOIN ai_platform.sys_user_corp uc ON uc.user_id = u.id "
                        + "WHERE u.id = ? AND u.status = 1 AND u.delete_flag = 0 "
                        + "AND uc.corp_id = ? AND uc.status = 1 AND uc.delete_flag = 0",
                Long.class, userId, corpId);
        return count != null && count > 0;
    }

    private boolean isWorker(OpsBusinessOrder order, Long userId) {
        if (userId == null) return false;
        if (userId.equals(order.getWorkerId())) return true;
        return hasPendingConstructTask(order.getId(), userId, order.getWfInstanceId());
    }

    private boolean isAgent(OpsBusinessOrder order) {
        String username = SecurityUtils.getCurrentUser().getUsername();
        if (isBlank(username) || isBlank(order.getAgentPhones())) return false;
        return splitPhones(order.getAgentPhones()).contains(username.trim());
    }

    private boolean hasAnyTask(OpsBusinessOrder order, Long userId) {
        if (order.getWfInstanceId() == null) return false;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_platform.wf_task t "
                        + "JOIN ai_platform.wf_instance i ON i.id = t.instance_id "
                        + "WHERE i.business_type IN ('OPS_BUSINESS_ORDER',"
                        + "'OPS_BUSINESS_ORDER_VOID') AND i.business_id = ? "
                        + "AND i.id = ? AND t.approver_user_id = ? AND t.delete_flag = 0 "
                        + "AND i.delete_flag = 0",
                Long.class, String.valueOf(order.getId()), order.getWfInstanceId(), userId);
        return count != null && count > 0;
    }

    private boolean hasPendingConstructTask(Long orderId, Long userId, Long instanceId) {
        if (instanceId == null) return false;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_platform.wf_task t "
                        + "JOIN ai_platform.wf_instance i ON i.id = t.instance_id "
                        + "JOIN ai_platform.wf_definition_node n ON n.id = t.node_id "
                        + "WHERE i.business_type = 'OPS_BUSINESS_ORDER' "
                        + "AND i.business_id = ? AND i.id = ? AND t.approver_user_id = ? "
                        + "AND t.status = 'PENDING' AND i.status = 'RUNNING' "
                        + "AND t.delete_flag = 0 AND i.delete_flag = 0 AND n.delete_flag = 0 "
                        // 施工执行节点及执行中的历史任务处理人均为施工侧人员，可读施工视图
                        + "AND n.node_order IN (5, 6)",
                Long.class, String.valueOf(orderId), instanceId, userId);
        return count != null && count > 0;
    }

    private Long pendingConstructTaskId(Long orderId, Long userId, Long instanceId) {
        if (instanceId == null) return null;
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT t.id FROM ai_platform.wf_task t "
                        + "JOIN ai_platform.wf_instance i ON i.id = t.instance_id "
                        + "JOIN ai_platform.wf_definition_node n ON n.id = t.node_id "
                        + "WHERE i.business_type = 'OPS_BUSINESS_ORDER' "
                        + "AND i.business_id = ? AND i.id = ? AND t.approver_user_id = ? "
                        + "AND t.status = 'PENDING' AND i.status = 'RUNNING' "
                        + "AND t.delete_flag = 0 AND i.delete_flag = 0 AND n.delete_flag = 0 "
                        + "AND n.node_order = 5 ORDER BY t.id DESC LIMIT 1",
                Long.class, String.valueOf(orderId), instanceId, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private boolean workflowRunning(OpsBusinessOrder order) {
        if (order.getWfInstanceId() == null) return false;
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT status FROM ai_platform.wf_instance "
                        + "WHERE id = ? AND delete_flag = 0",
                String.class, order.getWfInstanceId());
        return !rows.isEmpty() && "RUNNING".equals(rows.get(0));
    }

    private boolean hasLog(Long orderId, String action, Long operatorId) {
        var query = Wrappers.<OpsOrderConstructLog>lambdaQuery()
                .eq(OpsOrderConstructLog::getOrderId, orderId)
                .eq(OpsOrderConstructLog::getAction, action)
                .eq(OpsOrderConstructLog::getDeleteFlag, 0);
        if (operatorId != null) {
            query.eq(OpsOrderConstructLog::getOperatorId, operatorId);
        }
        return logMapper.selectCount(query) > 0;
    }

    // ═══ 通知 ═══

    private void notifyProgress(OpsBusinessOrder order, String actionName, String remark) {
        if (!notifyEnabled || order.getWfInstanceId() == null) return;
        Long instanceId = order.getWfInstanceId();
        String orderNo = order.getOrderNo();
        String operator = operatorName();
        String dedup = "construct:" + order.getId() + ":" + actionName + ":"
                + versionOf(order);
        List<Long> receivers = notificationReceivers(order);
        if (receivers.isEmpty()) return;
        Runnable send = () -> executor.execute(() -> receivers.forEach(userId ->
                workflowClient.notifyConstructProgress(instanceId, userId, orderNo,
                        actionName, operator, remark, dedup + ":" + userId)));
        // 事务提交成功后再发消息：回滚的操作不应产生「已接受任务/已开工」通知
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            send.run();
                        }
                    });
            return;
        }
        send.run();
    }

    private List<Long> notificationReceivers(OpsBusinessOrder order) {
        Set<Long> receivers = new LinkedHashSet<>();
        Long dispatcher = dispatcherUserId(order.getId());
        if (dispatcher != null) receivers.add(dispatcher);
        receivers.addAll(agentUserIds(order));
        Long current = SecurityUtils.getCurrentUserId();
        receivers.remove(current);
        return receivers.stream().filter(Objects::nonNull).limit(5).toList();
    }

    private Long dispatcherUserId(Long orderId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT t.approver_user_id FROM ai_platform.wf_task t "
                        + "JOIN ai_platform.wf_instance i ON i.id = t.instance_id "
                        + "JOIN ai_platform.wf_definition_node n ON n.id = t.node_id "
                        + "WHERE i.business_type = 'OPS_BUSINESS_ORDER' "
                        + "AND i.business_id = ? AND n.action_type = 'OPS_DISPATCH_OUTBOUND' "
                        + "AND t.status = 'APPROVED' AND t.delete_flag = 0 "
                        + "ORDER BY t.id DESC LIMIT 1",
                Long.class, String.valueOf(orderId));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Collection<Long> agentUserIds(OpsBusinessOrder order) {
        List<String> phones = splitPhones(order.getAgentPhones()).stream()
                .filter(p -> !p.isBlank()).distinct().limit(10).toList();
        if (phones.isEmpty()) return List.of();
        String placeholders = phones.stream().map(p -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.queryForList(
                "SELECT id FROM ai_platform.sys_user WHERE status = 1 "
                        + "AND delete_flag = 0 AND username IN (" + placeholders + ")",
                Long.class, phones.toArray());
    }

    // ═══ 小工具 ═══

    private Location normalizeLocation(ConstructActionDtos.ArriveRequest request) {
        if (request == null) {
            throw BusinessException.of(
                    "LOCATION_REQUIRED：请允许定位权限后重试，或手动填写地址");
        }
        String status = isBlank(request.locationStatus())
                ? OrderConstructStatus.LOCATION_FAILED : request.locationStatus().trim().toUpperCase();
        if (!Set.of(OrderConstructStatus.LOCATION_SUCCESS,
                OrderConstructStatus.LOCATION_FAILED,
                OrderConstructStatus.LOCATION_MANUAL).contains(status)) {
            status = OrderConstructStatus.LOCATION_FAILED;
        }
        BigDecimal lng = request.lng();
        BigDecimal lat = request.lat();
        if (OrderConstructStatus.LOCATION_SUCCESS.equals(status) && (lng == null || lat == null)) {
            status = OrderConstructStatus.LOCATION_MANUAL;
        }
        validateCoordinate(lng, lat);
        String address = trimToNull(request.address());
        if (address != null && address.length() > 255) {
            throw BusinessException.of("上门地址不能超过255字");
        }
        if (!OrderConstructStatus.LOCATION_SUCCESS.equals(status) && address == null) {
            throw BusinessException.of(
                    "LOCATION_REQUIRED：请允许定位权限后重试，或手动填写地址");
        }
        return new Location(status, lng, lat, address);
    }

    private Location customerLocation(Long orderId,
                                      ConstructActionDtos.CustomerConfirmRequest request) {
        BigDecimal lng = request == null ? null : request.lng();
        BigDecimal lat = request == null ? null : request.lat();
        if (lng == null || lat == null) {
            OpsOrderConstructLog arrive = logMapper.selectOne(
                    Wrappers.<OpsOrderConstructLog>lambdaQuery()
                            .eq(OpsOrderConstructLog::getOrderId, orderId)
                            .eq(OpsOrderConstructLog::getAction,
                                    OpsOrderConstructLog.ACTION_ARRIVE)
                            .eq(OpsOrderConstructLog::getDeleteFlag, 0)
                            .orderByDesc(OpsOrderConstructLog::getId)
                            .last("LIMIT 1"));
            if (arrive != null && arrive.getLocationLng() != null
                    && arrive.getLocationLat() != null) {
                lng = arrive.getLocationLng();
                lat = arrive.getLocationLat();
            }
        }
        validateCoordinate(lng, lat);
        String status = lng == null || lat == null
                ? OrderConstructStatus.LOCATION_FAILED : OrderConstructStatus.LOCATION_SUCCESS;
        return new Location(status, lng, lat, null);
    }

    private static void validateCoordinate(BigDecimal lng, BigDecimal lat) {
        if (lng != null && (lng.compareTo(BigDecimal.valueOf(-180)) < 0
                || lng.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw BusinessException.of("经度不合法");
        }
        if (lat != null && (lat.compareTo(BigDecimal.valueOf(-90)) < 0
                || lat.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw BusinessException.of("纬度不合法");
        }
    }

    private void assertAfterAccept(OpsBusinessOrder order, LocalDateTime now) {
        if (order.getAcceptTime() == null) {
            throw BusinessException.of("ARRIVE_BEFORE_ACCEPT：未接受任务，无法上门打卡");
        }
        if (now.isBefore(order.getAcceptTime())) {
            throw BusinessException.of("ARRIVE_BEFORE_ACCEPT：打卡时间早于接受时间");
        }
    }

    private void bindPhotos(OpsBusinessOrder order, List<Long> ids) {
        if (ids.isEmpty()) return;
        attachmentService.requireOwnedList(ids, OpsBusinessAttachment.TYPE_CONSTRUCT,
                order.getCorpId(), order.getId());
        attachmentService.bindToOrder(ids, order.getId(), order.getCorpId());
    }

    private static List<Long> validAttachmentIds(List<Long> raw) {
        if (raw == null) return List.of();
        return raw.stream().filter(id -> id != null && id > 0).distinct().limit(3).toList();
    }

    private String requireReason(String reason, String message) {
        String clean = trimToNull(reason);
        if (clean == null || clean.length() < 5) {
            throw BusinessException.of(message);
        }
        if (clean.length() > 500) {
            throw BusinessException.of("原因不能超过500字");
        }
        return clean;
    }

    private String operatorName() {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            String name = realName(userId);
            return isBlank(name) ? SecurityUtils.getCurrentUser().getUsername() : name;
        } catch (Exception e) {
            return "未知用户";
        }
    }

    private String realName(Long userId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT COALESCE(NULLIF(real_name,''), NULLIF(nickname,''), username) "
                            + "FROM ai_platform.sys_user WHERE id = ?",
                    String.class, userId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> result(OpsBusinessOrder order, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("bizStatus", statusOf(order));
        result.put("bizStatusName", statusName(order));
        result.put("message", message);
        return result;
    }

    private Map<String, Object> activeNoticeView(OpsBusinessOrder order) {
        OpsCustomerNotice notice = latestActiveNotice(order.getId());
        return notice == null ? null : noticeView(notice);
    }

    private List<Map<String, Object>> noticeHistory(OpsBusinessOrder order) {
        List<OpsCustomerNotice> notices = noticeMapper.selectList(
                Wrappers.<OpsCustomerNotice>lambdaQuery()
                        .eq(OpsCustomerNotice::getOrderId, order.getId())
                        .eq(OpsCustomerNotice::getDeleteFlag, 0)
                        .orderByDesc(OpsCustomerNotice::getVersion));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OpsCustomerNotice notice : notices) rows.add(noticeView(notice));
        return rows;
    }

    private Map<String, Object> noticeView(OpsCustomerNotice notice) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", notice.getId());
        view.put("version", notice.getVersion());
        view.put("status", notice.getStatus());
        view.put("content", notice.getNoticeContent());
        view.put("noticeTime", text(notice.getNoticeTime()));
        view.put("signTime", text(notice.getSignTime()));
        view.put("signImageUrl", notice.getSignImageUrl());
        view.put("signImageHash", notice.getSignImageHash());
        view.put("workerName", notice.getWorkerName());
        view.put("customerName", notice.getCustomerName());
        view.put("pdfUrl", notice.getPdfUrl());
        return view;
    }

    private String statusOf(OpsBusinessOrder order) {
        String status = order.getBizStatus();
        return isBlank(status) ? "0" : status.trim();
    }

    private String statusName(OpsBusinessOrder order) {
        return bizStatusService.label(statusOf(order));
    }

    private static int versionOf(OpsBusinessOrder order) {
        return order.getVersion() == null ? 0 : order.getVersion();
    }

    private static String actionName(String action) {
        if (action == null) return "";
        return switch (action) {
            case OpsOrderConstructLog.ACTION_ASSIGN -> "派工指派";
            case OpsOrderConstructLog.ACTION_ACCEPT -> "确认接受";
            case OpsOrderConstructLog.ACTION_REJECT -> "拒绝接受";
            case OpsOrderConstructLog.ACTION_ARRIVE -> "上门打卡";
            case OpsOrderConstructLog.ACTION_CUSTOMER_CONFIRM -> "客户签字确认";
            case OpsOrderConstructLog.ACTION_CUSTOMER_REFUSE -> "客户拒绝确认";
            case OpsOrderConstructLog.ACTION_START -> "开始施工";
            case OpsOrderConstructLog.ACTION_SUBMIT_ACCEPTANCE -> "提交验收申请";
            default -> action;
        };
    }

    /** 流转记录统一文案：角色 + 人员 + 动作；派工指派展示目标施工员而不是派工人。 */
    private static String displayText(OpsOrderConstructLog row, OpsBusinessOrder order) {
        if (row.getAction() == null) return actionName(null);
        String actor = roleActor(row.getOperatorName(), order.getWorkerName());
        return switch (row.getAction()) {
            case OpsOrderConstructLog.ACTION_ASSIGN -> {
                String name = workerName(row, order);
                String phone = maskPhone(row.getWorkerPhone());
                String suffix = isBlank(phone) ? "" : "（" + phone + "）";
                yield "施工员 " + (isBlank(name) ? "待定" : name) + suffix + " 待接受任务";
            }
            case OpsOrderConstructLog.ACTION_ACCEPT -> actor + " 已接受任务";
            case OpsOrderConstructLog.ACTION_REJECT -> actor + " 拒绝接受任务";
            case OpsOrderConstructLog.ACTION_ARRIVE -> actor + " 已上门打卡";
            case OpsOrderConstructLog.ACTION_CUSTOMER_CONFIRM -> actor + " 客户已签字确认";
            case OpsOrderConstructLog.ACTION_CUSTOMER_REFUSE -> actor + " 客户拒绝确认";
            case OpsOrderConstructLog.ACTION_START -> actor + " 已开始施工";
            case OpsOrderConstructLog.ACTION_SUBMIT_ACCEPTANCE -> actor + " 提交验收申请";
            default -> actionName(row.getAction());
        };
    }

    private static String roleActor(String operatorName, String fallbackWorker) {
        // 历史账号可能以手机号作为显示名；优先展示施工员姓名，避免流转记录只有手机号
        String name = operatorName;
        if (isBlank(name) || name.matches("\\d{6,}")) {
            name = fallbackWorker;
        }
        return "施工员 " + (isBlank(name) ? "—" : name);
    }

    /** 派工指派补充派工人，避免把派工人误当成施工员。 */
    private static String displayHint(OpsOrderConstructLog row) {
        if (!OpsOrderConstructLog.ACTION_ASSIGN.equals(row.getAction())
                || isBlank(row.getOperatorName())) {
            return null;
        }
        String dispatcher = row.getOperatorName().trim();
        return "派工人：" + (dispatcher.matches("\\d{7,}") ? maskPhone(dispatcher) : dispatcher);
    }

    private static String workerName(OpsOrderConstructLog row, OpsBusinessOrder order) {
        if (!isBlank(row.getWorkerName())) return row.getWorkerName();
        return OpsOrderConstructLog.ACTION_ASSIGN.equals(row.getAction())
                ? order.getWorkerName() : null;
    }

    private static String maskPhone(String phone) {
        String value = phone == null ? "" : phone.trim();
        if (value.length() < 7) return value;
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private static List<Long> parseIds(String csv) {
        if (isBlank(csv)) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                if (!part.isBlank()) ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // 脏数据不影响时间线展示
            }
        }
        return ids;
    }

    private static String joinIds(List<Long> ids) {
        return ids == null || ids.isEmpty() ? null
                : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<String> splitPhones(String phones) {
        if (isBlank(phones)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String phone : phones.split("[、,，;；\\s]+")) {
            if (!phone.isBlank()) result.add(phone.trim());
        }
        return result;
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.format(TIME_FMT);
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    /** 打卡/签字定位（status 必填，经纬度可空）。 */
    private record Location(String status, BigDecimal lng, BigDecimal lat, String address) {
        static Location none() {
            return new Location(null, null, null, null);
        }
    }
}
