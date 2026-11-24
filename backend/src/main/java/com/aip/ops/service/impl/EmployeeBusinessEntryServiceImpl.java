package com.aip.ops.service.impl;

import com.aip.ops.client.ScmBaseDataClient;
import com.aip.ops.client.ScmPurchaseOrderClient;
import com.aip.ops.client.WorkflowPlatformClient;
import com.aip.ops.component.OrderNoGenerator;
import com.aip.ops.dto.BalanceReceiveRequest;
import com.aip.ops.dto.CommunityCreateRequest;
import com.aip.ops.dto.EmployeeBusinessEntryRequest;
import com.aip.ops.dto.ProductPriceDtos;
import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessFlowRecord;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsBusinessOrderTradeIn;
import com.aip.ops.entity.OpsBusinessSpecialFile;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessFlowRecordMapper;
import com.aip.ops.mapper.OpsBusinessOrderTradeInMapper;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.ops.service.BusinessOrderRefundService;
import com.aip.ops.service.BusinessOrderService;
import com.aip.ops.service.BusinessOrderWorkflowService;
import com.aip.ops.service.BusinessSpecialFileService;
import com.aip.ops.service.EmployeeBusinessEntryService;
import com.aip.ops.service.GasMemberMatcher;
import com.aip.ops.service.ProductPriceService;
import com.aip.ops.util.OrderPartyText;
import com.aip.ops.util.PaymentMethod;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 员工手工新增业务数据实现。
 *
 * <p>安全边界：corp_id / agent / agent_phone 全部由 JWT + 平台 sys_user 派生；
 * 批次必须属于当前组织且未上报集团；品牌/品类/型号必须命中 SCM 基础资料。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeBusinessEntryServiceImpl implements EmployeeBusinessEntryService {

    private static final String REPORTED_STATUS = "已上报集团";
    private static final String INITIAL_CALC_STATUS = "未计算";
    /** 单笔业务最多产品行/经办人数，防止超大批量录入拖垮事务 */
    private static final int MAX_PRODUCTS = 50;
    private static final int MAX_AGENTS = 20;
    private static final int MAX_TEXT_LENGTH = 100;
    private static final int MAX_DETAIL_ADDRESS_LENGTH = 200;
    /** sys_member.real_name / nickname 为 VARCHAR(50)，自动建会员时不能超长 */
    private static final int MAX_CUSTOMER_LENGTH = 50;
    private static final int MAX_REMARK_LENGTH = 500;
    private static final int MAX_TRADE_IN_ITEMS = 50;
    /** 旧品专用品牌：以旧换新旧品只能选择该品牌商品，正常销售商品不能使用 */
    private static final String OLD_GOODS_BRAND = "旧品";
    /** 与 SCM 自动采购单单笔最多 100 条旧品分录保持一致，避免订单保存成功但采购单永远建不出来 */
    private static final int MAX_TOTAL_TRADE_IN_ITEMS = 100;
    private static final int MAX_TRADE_IN_NAME_LENGTH = 200;
    private static final int MAX_TRADE_IN_BRAND_LENGTH = 100;
    private static final int MAX_TRADE_IN_MODEL_LENGTH = 200;
    private static final int MAX_TRADE_IN_UNIT_LENGTH = 50;
    private static final int MAX_RECEIPT_NO_LENGTH = 50;
    /** 收据号允许字符集：字母/数字/短横线/下划线/斜杠（不含空格，便于财务核对与导出）。 */
    private static final Pattern RECEIPT_NO_PATTERN = Pattern.compile("^[A-Za-z0-9_/-]+$");

    /** 货款补录列表/角标共用查询：当前组织 + 当前经办人 + 定金未结清。 */
    private static final String DEPOSIT_PENDING_SQL =
            "SELECT id, order_no, customer, customer_phone, total_amount, payable_amount, "
                    + "deposit_amount, balance_amount, receipt_date, create_time, agent_names, "
                    + "balance_remind_time, biz_status FROM ops_business_order "
                    + "WHERE corp_id = ? AND delete_flag = 0 "
                    + "AND payment_type = 'DEPOSIT' AND is_full_payment = 0 "
                    + "AND (create_user = ? OR FIND_IN_SET(?, "
                    + "  REPLACE(COALESCE(agent_phones, ''), '、', ',')) > 0)";

    private final JdbcTemplate jdbcTemplate;
    private final PlatformInternalClient platformInternalClient;
    private final OpsBusinessDataMapper businessDataMapper;
    private final OpsBusinessOrderTradeInMapper tradeInMapper;
    private final OpsBusinessFlowRecordMapper flowRecordMapper;
    private final ObjectMapper objectMapper;
    private final ScmPurchaseOrderClient scmPurchaseOrderClient;
    private final GasMemberMatcher memberMatcher;
    private final ScmBaseDataClient scmClient;
    private final JwtTokenInspector jwtTokenInspector;
    private final PlatformTransactionManager transactionManager;
    private final BusinessOrderService orderService;
    private final BusinessOrderRefundService refundService;
    private final OrderNoGenerator orderNoGenerator;
    private final BusinessOrderWorkflowService workflowService;
    private final ProductPriceService productPriceService;
    private final BusinessSpecialFileService specialFileService;
    private final BusinessAttachmentService attachmentService;
    private final WorkflowPlatformClient workflowPlatformClient;
    private final ThreadPoolTaskExecutor workflowTaskExecutor;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Map<String, Object> getManualEntryOptions() {
        EmployeeContext ctx = resolveEmployeeContext();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("corpId", ctx.corpId());
        result.put("corpName", ctx.corpName());
        result.put("company", ctx.company());
        result.put("employeeUserId", ctx.employeeUserId());
        result.put("employeeName", ctx.employeeName());
        result.put("employeePhone", ctx.employeePhone());
        result.put("employees", loadCorpEmployees(ctx.corpId(), ctx.employeeUserId(),
                ctx.employeeName(), ctx.employeePhone()));
        result.put("batches", selectableBatches(ctx.corpId()));
        result.put("newBatchPrefix", monthlyBatchNo(ctx.company()));
        result.put("communities", loadCommunities(ctx.corpId()));
        result.put("regionId", ctx.regionId());
        result.put("regionName", ctx.regionName());
        result.put("regionConfigured", ctx.regionId() != null && ctx.regionId() > 0);
        fillProductOptions(result, ctx.corpId());
        return result;
    }

    @Override
    public Map<String, Object> lookupMember(String phone) {
        assertEmployee();
        String normalized = normalizePhone(phone);
        if (!GasMemberMatcher.PHONE_PATTERN.matcher(normalized).matches()) {
            throw BusinessException.of("请输入正确的11位大陆手机号");
        }
        try {
            Map<String, Object> info = memberMatcher.findMemberInfoByPhone(normalized);
            if (info == null) return Map.of("found", false);
            Long memberId = info.get("id") instanceof Number n ? n.longValue() : null;
            Long communityId = info.get("communityId") instanceof Number n ? n.longValue() : null;
            if (communityId == null) {
                communityId = findGasUserCommunity(memberId, normalized);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("memberId", memberId);
            result.put("name", info.get("name"));
            result.put("communityId", communityId);
            result.put("communityName", communityName(communityId));
            return result;
        } catch (IllegalStateException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    // ── 小区 / 详细地址 ──

    /**
     * 员工制单新建小区：corp_id / 操作人只从登录态派生，区县由平台按组织 region_id 关联。
     * 同名重复提交由平台侧幂等返回已有小区。外部员工与业务订单策略一致，暂不允许
     * （否则只开放小区入口却无法下单，且形成绕过订单限制的写入口）。
     */
    @Override
    public Map<String, Object> createCommunity(CommunityCreateRequest request) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可新建小区");
        }
        String name = requireText(request == null ? null : request.getName(), "小区名称", 200);
        EmployeeContext ctx = resolveEmployeeContext();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corpId", ctx.corpId());
        body.put("name", name);
        body.put("operatorUserId", ctx.employeeUserId());
        body.put("operatorName", ctx.employeeName());
        Map<String, Object> resp;
        try {
            resp = platformInternalClient.post("/api/internal/community/create", body, null);
        } catch (Exception e) {
            log.warn("新建小区调用平台失败 corpId={} name={}: {}",
                    ctx.corpId(), name, e.getMessage());
            throw BusinessException.of("新建小区失败，请稍后重试");
        }
        if (resp == null || !(resp.get("code") instanceof Number n) || n.intValue() != 200) {
            String msg = resp == null ? "" : str(resp.get("msg"));
            throw BusinessException.of(msg.isEmpty() ? "新建小区失败，请稍后重试" : msg);
        }
        if (!(resp.get("data") instanceof Map<?, ?> data) || !(data.get("id") instanceof Number id)) {
            throw BusinessException.of("新建小区失败，请稍后重试");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id.longValue());
        result.put("name", data.get("name"));
        result.put("regionId", data.get("regionId"));
        result.put("regionName", data.get("regionName"));
        result.put("existed", Boolean.TRUE.equals(data.get("existed")));
        log.info("员工新建小区 corpId={} name={} id={} operator={}", ctx.corpId(), name,
                id.longValue(), ctx.employeeName());
        return result;
    }

    /** 当前员工所属组织的小区下拉（sys_community.corp_id 精确过滤）。 */
    private List<Map<String, Object>> loadCommunities(Long corpId) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id, community_name AS name FROM ai_platform.sys_community "
                            + "WHERE delete_flag = 0 AND corp_id = ? ORDER BY community_name",
                    corpId);
        } catch (Exception e) {
            log.warn("加载组织小区列表失败 corpId={}: {}", corpId, e.getMessage());
            return List.of();
        }
    }

    /** 会员未直接关联小区时，按手机号/会员ID 从燃气用户回退取小区。 */
    private Long findGasUserCommunity(Long memberId, String phone) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT community_id FROM ops_gas_user "
                            + "WHERE delete_flag = 0 AND community_id IS NOT NULL "
                            + "AND (phone = ? OR platform_member_id = ?) "
                            + "ORDER BY (platform_member_id = ?) DESC, id LIMIT 1",
                    phone, memberId, memberId);
            if (rows.isEmpty()) return null;
            Object value = rows.get(0).get("community_id");
            if (!(value instanceof Number n)) return null;
            return n.longValue() > 0 ? n.longValue() : null;
        } catch (Exception e) {
            log.debug("回退查询会员小区失败 memberId={}, phone={}: {}",
                    memberId, phone, e.getMessage());
            return null;
        }
    }

    private String communityName(Long communityId) {
        if (communityId == null || communityId <= 0) return null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT community_name FROM ai_platform.sys_community "
                            + "WHERE id = ? AND delete_flag = 0 LIMIT 1", communityId);
            return rows.isEmpty() ? null : str(rows.get(0).get("community_name"));
        } catch (Exception e) {
            log.debug("查询小区名称失败 communityId={}: {}", communityId, e.getMessage());
            return null;
        }
    }

    /**
     * 当前组织可选经办人：sys_user_corp 关联 sys_user（账号 username 即手机号）。
     * 当前操作员工固定在首位且 isSelf=true；若 sys_user_corp 缺关联仍兜底展示本人。
     */
    private List<Map<String, Object>> loadCorpEmployees(Long corpId, Long selfUserId,
                                                        String selfName, String selfPhone) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT u.id AS userId, u.real_name AS realName, u.username, u.phone "
                            + "FROM ai_platform.sys_user u "
                            + "WHERE u.status = 1 AND u.delete_flag = 0 "
                            + "AND (u.corp_id = ? OR EXISTS ("
                            + "  SELECT 1 FROM ai_platform.sys_user_corp uc "
                            + "  WHERE uc.user_id = u.id AND uc.corp_id = ? "
                            + "  AND uc.status = 1 AND uc.delete_flag = 0)) "
                            + "ORDER BY u.real_name, u.id",
                    corpId, corpId);
            for (Map<String, Object> row : rows) {
                Long userId = row.get("userId") instanceof Number n ? n.longValue() : null;
                if (userId == null) continue;
                String username = str(row.get("username"));
                String phone = username.isEmpty() ? str(row.get("phone")) : username;
                if (phone.isEmpty()) continue;
                String name = str(row.get("realName"));
                result.add(employeeOption(userId, name.isEmpty() ? phone : name, phone,
                        userId.equals(selfUserId)));
            }
        } catch (Exception e) {
            log.warn("加载组织员工列表失败 corpId={}: {}", corpId, e.getMessage());
        }
        boolean selfPresent = result.stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.get("isSelf")));
        if (!selfPresent && selfUserId != null) {
            result.removeIf(item -> selfUserId.equals(item.get("userId")));
            result.add(0, employeeOption(selfUserId, selfName, selfPhone, true));
        }
        return result;
    }

    private Map<String, Object> employeeOption(Long userId, String name, String phone,
                                               boolean isSelf) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("userId", userId);
        item.put("name", name);
        item.put("phone", phone);
        item.put("isSelf", isSelf);
        return item;
    }

    @Override
    public Map<String, Object> createManualEntry(EmployeeBusinessEntryRequest request) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可发起业务");
        }
        EmployeeContext ctx = resolveEmployeeContext();
        Validated v = validateRequest(request, ctx);
        // 平台/SCM 的 HTTP 查询在事务外完成；事务只包裹批次取号+行锁、会员创建、INSERT，
        // 避免 Hikari 连接在外部 HTTP 期间被长期占用（连接池耗尽风险）。
        Map<String, Object> result = transactionTemplate.execute(
                status -> persistManualEntry(request, ctx, v));
        // 事务提交后自动创建以旧换新采购单（SCM 幂等）；失败标记异常并随结果返回提示
        if (result != null && Boolean.TRUE.equals(result.get("hasTradeIn"))
                && result.get("orderId") instanceof Number orderId) {
            autoCreateTradeInPurchase(orderId.longValue(), ctx.corpId(),
                    str(result.get("orderNo")), ctx.employeeUserId(), ctx.employeeName(), result);
        }
        // 事务提交后同步发起审批流程：成功页必须拿到下一审批人；
        // 发起失败不阻塞制单，返回 resolved=false + 兜底文案由前端展示。
        if (result != null && result.get("orderId") instanceof Number orderId) {
            try {
                WorkflowPlatformClient.StartResult started = workflowService.start(
                        orderId.longValue(), ctx.employeeUserId(), ctx.employeeName(), false);
                result.put("wfInstanceId", started.instanceId());
                result.put("currentApproverName", started.approverName());
                result.put("currentApproverRole", started.approverRole());
                result.put("currentApprovalDisplay",
                        started.resolved() ? started.displayText() : null);
                result.put("currentApprovalNodeName", started.nodeName());
                result.put("currentApprovalMode", started.approverMode());
                result.put("approverResolved", started.resolved());
                result.put("approverMessage", started.message());
            } catch (Exception e) {
                log.warn("订单 {} 发起工作流失败：{}", orderId, e.getMessage());
                result.put("approverResolved", false);
                result.put("approverMessage", "审批流程发起失败，请联系管理员");
            }
        }
        return result;
    }

    // ═══ 退回草稿后的编辑（沿用原订单编号/业务组号）═══

    @Override
    public Map<String, Object> draftEditData(Long orderId) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可编辑业务");
        }
        EmployeeContext ctx = resolveEmployeeContext();
        return buildEditForm(requireDraftOrder(ctx, orderId), ctx);
    }

    /** 驳回退回修改：订单不是草稿，工作流实例停在发起节点等待重新提交。 */
    @Override
    public Map<String, Object> revisionEditData(Long orderId) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可编辑业务");
        }
        EmployeeContext ctx = resolveEmployeeContext();
        OpsBusinessOrder order = requireReturnedOrder(ctx, orderId);
        requireReturnPendingTask(ctx, order);
        return buildEditForm(order, ctx);
    }

    /**
     * 草稿编辑与驳回退回修改共用的回显：按「品类|品牌|型号|单价」合并商品行，
     * 多经办人行只回显一行，并带回旧品/凭证/附件。
     */
    private Map<String, Object> buildEditForm(OpsBusinessOrder order, EmployeeContext ctx) {
        Long orderId = order.getId();
        List<OpsBusinessData> rows = businessDataMapper.selectList(
                new LambdaQueryWrapper<OpsBusinessData>()
                        .eq(OpsBusinessData::getOrderId, orderId)
                        .eq(OpsBusinessData::getDeleteFlag, 0)
                        .orderByAsc(OpsBusinessData::getId));
        if (rows.isEmpty()) {
            throw BusinessException.of("订单没有商品明细，无法编辑");
        }
        List<OpsBusinessOrderTradeIn> tradeIns = tradeInMapper.selectList(
                new LambdaQueryWrapper<OpsBusinessOrderTradeIn>()
                        .eq(OpsBusinessOrderTradeIn::getOrderId, orderId)
                        .eq(OpsBusinessOrderTradeIn::getDeleteFlag, 0)
                        .orderByAsc(OpsBusinessOrderTradeIn::getId));
        Map<String, List<OpsBusinessOrderTradeIn>> tradeByKey = new LinkedHashMap<>();
        for (OpsBusinessOrderTradeIn row : tradeIns) {
            tradeByKey.computeIfAbsent(str(row.getProductKey()), k -> new ArrayList<>()).add(row);
        }
        // 按「品类|品牌|型号|单价」合并商品行；多经办人行只回显一行
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData row : rows) {
            groups.computeIfAbsent(draftProductKey(row), k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> products = new ArrayList<>();
        for (Map.Entry<String, List<OpsBusinessData>> entry : groups.entrySet()) {
            OpsBusinessData first = entry.getValue().get(0);
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("uid", "p" + first.getId());
            product.put("productId", null);
            product.put("category", BusinessDataServiceImpl.KEY_TO_CN.getOrDefault(
                    str(first.getCategory()), first.getCategory()));
            product.put("brand", first.getBrand());
            product.put("model", first.getModel());
            product.put("salePrice", first.getSalePrice());
            product.put("saleQuantity",
                    String.valueOf(first.getSaleQuantity() == null ? 1 : first.getSaleQuantity()));
            product.put("standardSalePrice", first.getStandardSalePrice());
            product.put("priceConfigured", true);
            product.put("priceLoading", false);
            product.put("priceError", "");
            product.put("isSpecial", "是".equals(first.getIsSpecial()));
            product.put("specialFileId", first.getSpecialFileId());
            product.put("specialFileUrl", first.getSpecialFileUrl());
            product.put("specialFileName", first.getSpecialFileName());
            product.put("commissionRate",
                    first.getCommissionRate() == null ? "" : first.getCommissionRate());
            product.put("isTradeIn", "是".equals(first.getIsTradeIn()));
            product.put("deductionAmount",
                    first.getDeductionAmount() == null ? "" : first.getDeductionAmount());
            product.put("tradeInItems", draftTradeInItems(tradeByKey.get(entry.getKey())));
            products.add(product);
        }
        Set<String> agentPhones = rows.stream().map(OpsBusinessData::getAgentPhone)
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> employees = loadCorpEmployees(ctx.corpId(),
                ctx.employeeUserId(), ctx.employeeName(), ctx.employeePhone());
        List<Long> agentUserIds = new ArrayList<>();
        for (Map<String, Object> employee : employees) {
            if (employee.get("userId") instanceof Number userId
                    && agentPhones.contains(str(employee.get("phone")))) {
                agentUserIds.add(userId.longValue());
            }
        }
        if (!agentUserIds.contains(ctx.employeeUserId())) {
            agentUserIds.add(ctx.employeeUserId());
        }
        List<OpsBusinessAttachment> files = attachmentService.listByOrder(orderId);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("orderId", orderId);
        form.put("orderNo", order.getOrderNo());
        form.put("batchNo", order.getBatchNo());
        form.put("newBatch", false);
        form.put("customerPhone", order.getCustomerPhone());
        form.put("customer", order.getCustomer());
        form.put("communityId", order.getCommunityId());
        form.put("communityName", order.getCommunityName());
        form.put("detailAddress", order.getDetailedAddress());
        form.put("paymentType", order.getPaymentType() == null ? "FULL" : order.getPaymentType());
        form.put("depositAmount",
                order.getDepositAmount() == null ? "" : order.getDepositAmount());
        form.put("paymentMethod",
                order.getPaymentMethod() == null ? "QR" : order.getPaymentMethod());
        form.put("receiptDate", order.getReceiptDate());
        form.put("receiptNo", order.getReceiptNo());
        form.put("remark", order.getRemark());
        form.put("agentUserIds", agentUserIds);
        form.put("products", products);
        form.put("receiptImage", firstAttachment(files, OpsBusinessAttachment.TYPE_RECEIPT));
        form.put("paymentVouchers", attachmentsOfType(files, OpsBusinessAttachment.TYPE_PAYMENT));
        form.put("contractAttachments",
                attachmentsOfType(files, OpsBusinessAttachment.TYPE_CONTRACT));
        return form;
    }

    @Override
    public Map<String, Object> updateDraft(Long orderId, EmployeeBusinessEntryRequest request) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可编辑业务");
        }
        if (request == null) throw BusinessException.of("请求参数不能为空");
        EmployeeContext ctx = resolveEmployeeContext();
        OpsBusinessOrder order = requireDraftOrder(ctx, orderId);
        Validated v = validateRequest(request, ctx, orderId);
        Map<String, Object> result = transactionTemplate.execute(
                status -> persistDraftUpdate(request, ctx, v, order));
        if (result != null && Boolean.TRUE.equals(result.get("hasTradeIn"))
                && result.get("orderId") instanceof Number oid) {
            autoCreateTradeInPurchase(oid.longValue(), ctx.corpId(),
                    str(result.get("orderNo")), ctx.employeeUserId(), ctx.employeeName(), result);
        }
        if (result != null) {
            try {
                WorkflowPlatformClient.StartResult started = workflowService.start(
                        orderId, ctx.employeeUserId(), ctx.employeeName(), true);
                result.put("wfInstanceId", started.instanceId());
                result.put("currentApproverName", started.approverName());
                result.put("currentApproverRole", started.approverRole());
                result.put("currentApprovalDisplay",
                        started.resolved() ? started.displayText() : null);
                result.put("currentApprovalNodeName", started.nodeName());
                result.put("currentApprovalMode", started.approverMode());
                result.put("approverResolved", started.resolved());
                result.put("approverMessage", started.message());
            } catch (Exception e) {
                log.warn("草稿编辑后发起工作流失败 orderId={}: {}", orderId, e.getMessage());
                result.put("approverResolved", false);
                result.put("approverMessage", "审批流程发起失败，请联系管理员");
            }
        }
        return result;
    }

    /**
     * 驳回退回修改后重新提交：在同一工作流实例上推进发起节点任务，
     * 不再像草稿提交那样终止旧流程、重新发起新实例。
     */
    @Override
    public Map<String, Object> updateRevision(Long orderId, EmployeeBusinessEntryRequest request) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可编辑业务");
        }
        if (request == null) throw BusinessException.of("请求参数不能为空");
        EmployeeContext ctx = resolveEmployeeContext();
        OpsBusinessOrder order = requireReturnedOrder(ctx, orderId);
        WorkflowPlatformClient.PendingReturnTask returnTask = requireReturnPendingTask(ctx, order);
        Validated v = validateRequest(request, ctx, orderId);
        Map<String, Object> result = transactionTemplate.execute(
                status -> persistDraftUpdate(request, ctx, v, order, true));
        if (result != null && Boolean.TRUE.equals(result.get("hasTradeIn"))
                && result.get("orderId") instanceof Number oid) {
            autoCreateTradeInPurchase(oid.longValue(), ctx.corpId(),
                    str(result.get("orderNo")), ctx.employeeUserId(), ctx.employeeName(), result);
        }
        applyReturnSubmitResult(result, returnTask, ctx);
        return result;
    }

    /** 重新提交结果：用平台返回的当前节点/审批人填充成功页，失败时明确告知。 */
    private void applyReturnSubmitResult(Map<String, Object> result,
                                         WorkflowPlatformClient.PendingReturnTask returnTask,
                                         EmployeeContext ctx) {
        Map<String, Object> flow = workflowPlatformClient.approveReturnTask(returnTask.taskId(),
                ctx.employeeUserId(), ctx.employeeName());
        Map<String, Object> approval = asMap(flow.get("currentApproval"));
        List<?> approvers = flow.get("nextApprovers") instanceof List<?> list ? list : List.of();
        Map<String, Object> first = approvers.isEmpty() ? Map.of() : asMap(approvers.get(0));
        result.put("wfInstanceId", returnTask.instanceId());
        result.put("currentApproverName", first.get("userName"));
        result.put("currentApproverRole", first.get("roleName"));
        result.put("currentApprovalDisplay", approval.get("displayText"));
        result.put("currentApprovalNodeName", flow.get("nextNodeName"));
        result.put("currentApprovalMode", flow.get("approverMode"));
        boolean resolved = "SUCCESS".equals(str(flow.get("resolveStatus"))) && !first.isEmpty();
        result.put("approverResolved", resolved);
        result.put("approverMessage", resolved ? null : flow.get("resolveMessage"));
    }

    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private OpsBusinessOrder requireDraftOrder(EmployeeContext ctx, Long orderId) {
        OpsBusinessOrder order = requireAgentOrder(ctx, orderId);
        if (!Integer.valueOf(1).equals(order.getDraftFlag())) {
            throw BusinessException.of("订单不是草稿状态，无法编辑");
        }
        if (refundService.findPending(orderId) != null) {
            throw BusinessException.of("订单正在作废退款审批中，不可编辑");
        }
        return order;
    }

    /**
     * 驳回退回待修改：非草稿且流程未结束。是否真的处于退回状态由平台
     * pending-return 任务判定（业务状态回调失败时不至于把经办人挡在门外）。
     */
    private OpsBusinessOrder requireReturnedOrder(EmployeeContext ctx, Long orderId) {
        OpsBusinessOrder order = requireAgentOrder(ctx, orderId);
        if (Integer.valueOf(1).equals(order.getDraftFlag())) {
            throw BusinessException.of("订单为草稿状态，请使用草稿编辑");
        }
        if (order.getWfInstanceId() == null) {
            throw BusinessException.of("订单当前不是驳回待修改状态");
        }
        if (refundService.findPending(orderId) != null) {
            throw BusinessException.of("订单正在作废退款审批中，不可编辑");
        }
        return order;
    }

    /** 平台侧二次确认：当前节点必须是发起节点，且退回任务属于当前员工。 */
    private WorkflowPlatformClient.PendingReturnTask requireReturnPendingTask(EmployeeContext ctx,
                                                                             OpsBusinessOrder order) {
        WorkflowPlatformClient.PendingReturnTask task =
                workflowPlatformClient.pendingReturnTask(order.getId(), ctx.employeeUserId());
        if (task == null) {
            throw BusinessException.of("未找到该订单的驳回待重新提交任务，请刷新待办后重试");
        }
        return task;
    }

    private Map<String, Object> persistDraftUpdate(EmployeeBusinessEntryRequest request,
                                                   EmployeeContext ctx, Validated v,
                                                   OpsBusinessOrder order) {
        return persistDraftUpdate(request, ctx, v, order, false);
    }

    /**
     * @param revision true=驳回退回修改：保留工作流实例/节点字段
     */
    private Map<String, Object> persistDraftUpdate(EmployeeBusinessEntryRequest request,
                                                   EmployeeContext ctx, Validated v,
                                                   OpsBusinessOrder order, boolean revision) {
        // 事务内锁定订单并复查：与并发退款/重复提交串行化
        OpsBusinessOrder locked = orderService.lockById(order.getId());
        boolean stateOk = locked != null && (revision
                ? !Integer.valueOf(1).equals(locked.getDraftFlag())
                && locked.getWfInstanceId() != null
                : Integer.valueOf(1).equals(locked.getDraftFlag()));
        if (!stateOk) {
            throw BusinessException.of(revision
                    ? "订单当前不是驳回待修改状态" : "订单不是草稿状态，无法编辑");
        }
        if (refundService.findPending(order.getId()) != null) {
            throw BusinessException.of("订单正在作废退款审批中，不可编辑");
        }
        order = locked;
        String batchNo = resolveBatch(request, ctx);
        MemberResolution member = resolveOrCreateMember(v.phone(), v.customer(), ctx.corpId());
        CommunityInfo community = resolveCommunity(v.communityId(), ctx.corpId());
        BigDecimal totalAmount = v.products().stream().map(ValidatedProduct::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (revision) {
            orderService.updateRevisionOrder(order.getId(), ctx.corpId(), batchNo, v.receiptNo(),
                    ctx.company(), v.customer(), v.phone(), member.id(), community.id(),
                    community.name(), v.detailAddress(), v.receiptDate(), totalAmount, v.remark(),
                    v.paymentMethod(), v.paymentType(), v.depositAmount(), v.isFullPayment(),
                    v.balanceAmount(), INITIAL_CALC_STATUS);
        } else {
            orderService.updateDraftOrder(order.getId(), ctx.corpId(), batchNo, v.receiptNo(),
                    ctx.company(), v.customer(), v.phone(), member.id(), community.id(),
                    community.name(), v.detailAddress(), v.receiptDate(), totalAmount, v.remark(),
                    v.paymentMethod(), v.paymentType(), v.depositAmount(), v.isFullPayment(),
                    v.balanceAmount(), INITIAL_CALC_STATUS);
        }
        orderService.updateVoucherAttachments(order.getId(), ctx.corpId(), v.receiptImageUrl(),
                v.paymentVoucherUrls(), v.contractAttachmentUrls());
        // 替换旧明细/旧品（逻辑删除，保留历史审计）
        businessDataMapper.delete(new LambdaQueryWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getOrderId, order.getId()));
        tradeInMapper.delete(new LambdaQueryWrapper<OpsBusinessOrderTradeIn>()
                .eq(OpsBusinessOrderTradeIn::getOrderId, order.getId()));
        // 新附件绑定；未被复用的旧凭证解绑（文件由定时任务清理）
        attachmentService.bindToOrder(v.attachmentIds(), order.getId(), ctx.corpId());
        Set<Long> keep = new LinkedHashSet<>(v.attachmentIds());
        List<Long> stale = attachmentService.listByOrder(order.getId()).stream()
                .filter(file -> OpsBusinessAttachment.TYPE_RECEIPT.equals(file.getFileType())
                        || OpsBusinessAttachment.TYPE_PAYMENT.equals(file.getFileType())
                        || OpsBusinessAttachment.TYPE_CONTRACT.equals(file.getFileType()))
                .map(OpsBusinessAttachment::getId)
                .filter(id -> !keep.contains(id))
                .toList();
        attachmentService.unbindFromOrder(order.getId(), stale);
        List<Long> ids = new ArrayList<>();
        Map<String, Long> firstItemIds = new LinkedHashMap<>();
        for (ValidatedProduct product : v.products()) {
            for (AgentInfo agent : v.agents()) {
                OpsBusinessData entity = buildEntity(ctx, v, product, agent, batchNo,
                        order.getOrderNo(), order.getBusinessGroupNo(), member.id(), community);
                entity.setOrderId(order.getId());
                businessDataMapper.insert(entity);
                ids.add(entity.getId());
                firstItemIds.putIfAbsent(tradeInProductKey(product), entity.getId());
            }
        }
        int tradeInCount = insertTradeInItems(ctx, v, order.getId(), firstItemIds);
        orderService.updateTradeInPurchaseStatus(order.getId(),
                tradeInCount > 0 ? "PENDING" : null, null, null, null);
        orderService.refreshAgentSummary(order.getId());
        orderService.refreshTotals(order.getId());
        lockAndCheckBatch(ctx.corpId(), batchNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("batchNo", batchNo);
        result.put("businessGroupNo", order.getBusinessGroupNo());
        result.put("ids", ids);
        result.put("detailCount", ids.size());
        result.put("productCount", v.products().size());
        result.put("agentCount", v.agents().size());
        BigDecimal deductionTotal = v.products().stream().map(ValidatedProduct::deduction)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean hasTradeIn = v.products().stream().anyMatch(ValidatedProduct::tradeIn);
        result.put("hasTradeIn", hasTradeIn);
        result.put("tradeInCount", tradeInCount);
        result.put("receiptAmount", totalAmount);
        result.put("tradeInDeduction", deductionTotal);
        result.put("payableAmount", totalAmount.subtract(deductionTotal).max(BigDecimal.ZERO));
        result.put("paymentType", v.paymentType());
        result.put("depositAmount", v.depositAmount());
        result.put("balanceAmount", v.balanceAmount());
        result.put("isFullPayment", v.isFullPayment());
        result.put("platformMemberId", member.id());
        result.put("memberCreated", member.created());
        return result;
    }

    private static String draftProductKey(OpsBusinessData row) {
        return str(row.getCategory()) + "|" + str(row.getBrand()) + "|" + str(row.getModel())
                + "|" + (row.getSalePrice() == null ? ""
                : row.getSalePrice().stripTrailingZeros().toPlainString());
    }

    private List<Map<String, Object>> draftTradeInItems(List<OpsBusinessOrderTradeIn> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (rows == null) return result;
        for (OpsBusinessOrderTradeIn row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uid", "t" + row.getId());
            item.put("productId", row.getOldProductId());
            item.put("materialNumber", row.getOldMaterialNumber());
            item.put("productName", row.getOldProductName());
            item.put("brand", row.getBrand());
            item.put("model", row.getModel());
            item.put("category", row.getCategory());
            item.put("quantity", String.valueOf(row.getQuantity() == null ? 1 : row.getQuantity()));
            item.put("unit", row.getUnit());
            item.put("remark", row.getRemark());
            item.put("isManual", Integer.valueOf(1).equals(row.getIsManual()));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> firstAttachment(List<OpsBusinessAttachment> files, String type) {
        List<Map<String, Object>> list = attachmentsOfType(files, type);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<Map<String, Object>> attachmentsOfType(List<OpsBusinessAttachment> files,
                                                        String type) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OpsBusinessAttachment file : files) {
            if (!type.equals(file.getFileType())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", file.getId());
            item.put("url", file.getFileUrl());
            item.put("fileName", file.getFileName());
            item.put("contentType", file.getContentType());
            item.put("fileSize", file.getFileSize());
            item.put("fileType", file.getFileType());
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> retryTradeInPurchase(Long orderId) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可发起业务");
        }
        EmployeeContext ctx = resolveEmployeeContext();
        OpsBusinessOrder order = requireAgentOrder(ctx, orderId);
        if (!Integer.valueOf(1).equals(order.getHasTradeIn())) {
            throw BusinessException.of("该订单不含以旧换新");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("orderNo", order.getOrderNo());
        if ("SUCCESS".equals(order.getTradeInPurchaseStatus())
                && order.getTradeInPurchaseOrderId() != null) {
            result.put("status", "SUCCESS");
            result.put("purchaseOrderId", order.getTradeInPurchaseOrderId());
            result.put("billNo", order.getTradeInPurchaseBillNo());
            return result;
        }
        try {
            ScmPurchaseOrderClient.TradeInPurchaseResult created =
                    scmPurchaseOrderClient.createTradeInPurchaseOrder(
                            buildTradeInPurchaseRequest(ctx.corpId(), orderId, order.getOrderNo(),
                                    ctx.employeeUserId(), ctx.employeeName()));
            orderService.updateTradeInPurchaseStatus(orderId, "SUCCESS", created.orderId(),
                    created.billNo(), null);
            result.put("status", "SUCCESS");
            result.put("purchaseOrderId", created.orderId());
            result.put("billNo", created.billNo());
            result.put("auditStatus", created.auditStatus());
            result.put("approvalStatus", created.approvalStatus());
            result.put("supplierNumber", created.supplierNumber());
            result.put("warehouseNumber", created.warehouseNumber());
        } catch (Exception e) {
            String error = truncate(e.getMessage(), 500);
            orderService.updateTradeInPurchaseStatus(orderId, "FAILED", null, null, error);
            result.put("status", "FAILED");
            result.put("error", error);
        }
        return result;
    }

    /** 事务提交后自动创建以旧换新采购单；失败标记 FAILED 并随成功响应提示，不静默。 */
    private void autoCreateTradeInPurchase(Long orderId, Long corpId, String orderNo,
                                           Long operatorUserId, String operatorName,
                                           Map<String, Object> result) {
        try {
            ScmPurchaseOrderClient.TradeInPurchaseResult created =
                    scmPurchaseOrderClient.createTradeInPurchaseOrder(
                            buildTradeInPurchaseRequest(corpId, orderId, orderNo,
                                    operatorUserId, operatorName));
            orderService.updateTradeInPurchaseStatus(orderId, "SUCCESS", created.orderId(),
                    created.billNo(), null);
            result.put("tradeInPurchaseStatus", "SUCCESS");
            result.put("tradeInPurchaseOrderId", created.orderId());
            result.put("tradeInPurchaseBillNo", created.billNo());
            // 生成结果：DRAFT（默认供应商/默认仓库已补齐），待业务订单财务核对后自动审核
            result.put("tradeInPurchaseAuditStatus", created.auditStatus());
            result.put("tradeInPurchaseApprovalStatus", created.approvalStatus());
            result.put("tradeInPurchaseSupplierNumber", created.supplierNumber());
            result.put("tradeInPurchaseWarehouseNumber", created.warehouseNumber());
        } catch (Exception e) {
            String error = truncate(e.getMessage(), 500);
            orderService.updateTradeInPurchaseStatus(orderId, "FAILED", null, null, error);
            result.put("tradeInPurchaseStatus", "FAILED");
            result.put("tradeInPurchaseError", error);
            log.warn("以旧换新采购单创建失败 orderId={}: {}", orderId, error);
        }
    }

    private ScmPurchaseOrderClient.TradeInPurchaseRequest buildTradeInPurchaseRequest(
            Long corpId, Long orderId, String orderNo,
            Long operatorUserId, String operatorName) {
        List<OpsBusinessOrderTradeIn> rows = tradeInMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpsBusinessOrderTradeIn>()
                        .eq(OpsBusinessOrderTradeIn::getOrderId, orderId)
                        .eq(OpsBusinessOrderTradeIn::getDeleteFlag, 0)
                        .orderByAsc(OpsBusinessOrderTradeIn::getId));
        if (rows.isEmpty()) {
            throw BusinessException.of("订单没有旧品明细，无法创建采购单");
        }
        Map<String, BigDecimal> unitPriceByKey = tradeInUnitPrices(orderId, rows);
        List<ScmPurchaseOrderClient.TradeInItem> items = new ArrayList<>();
        for (OpsBusinessOrderTradeIn row : rows) {
            BigDecimal taxPrice = unitPriceByKey.get(row.getProductKey());
            if (taxPrice == null) {
                throw BusinessException.of("旧品抵扣金额或数量缺失，无法计算采购单价");
            }
            items.add(new ScmPurchaseOrderClient.TradeInItem(
                    row.getId(), row.getOldProductId(), row.getOldMaterialNumber(),
                    row.getOldProductName(), row.getBrand(), row.getModel(), row.getCategory(),
                    row.getQuantity(), taxPrice, row.getUnit(), row.getRemark(),
                    Integer.valueOf(1).equals(row.getIsManual())));
        }
        return new ScmPurchaseOrderClient.TradeInPurchaseRequest(corpId, orderId, orderNo,
                operatorUserId, operatorName, items);
    }

    /**
     * 采购单价 = 商品行抵扣金额 / 该商品行旧品总数量（两位小数）。
     * 多经办人行抵扣同值，按产品组 MAX 去重；同一商品行多件旧品共用该单价。
     */
    private Map<String, BigDecimal> tradeInUnitPrices(Long orderId,
                                                      List<OpsBusinessOrderTradeIn> rows) {
        Map<String, BigDecimal> deductionByKey = new LinkedHashMap<>();
        List<OpsBusinessData> orderItems = businessDataMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpsBusinessData>()
                        .eq(OpsBusinessData::getOrderId, orderId)
                        .eq(OpsBusinessData::getDeleteFlag, 0));
        for (OpsBusinessData item : orderItems) {
            if (item.getDeductionAmount() == null || item.getDeductionAmount().signum() <= 0) {
                continue;
            }
            String key = tradeInProductKey(item.getCategory(), item.getBrand(),
                    item.getModel(), item.getSalePrice());
            deductionByKey.merge(key, item.getDeductionAmount(), BigDecimal::max);
        }
        Map<String, Integer> quantityByKey = new LinkedHashMap<>();
        for (OpsBusinessOrderTradeIn row : rows) {
            int quantity = row.getQuantity() == null ? 0 : row.getQuantity();
            if (quantity <= 0) {
                throw BusinessException.of("旧品数量必须为正整数");
            }
            quantityByKey.merge(row.getProductKey(), quantity, Integer::sum);
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : deductionByKey.entrySet()) {
            Integer totalQuantity = quantityByKey.get(entry.getKey());
            if (totalQuantity == null || totalQuantity <= 0) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue()
                    .divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP));
        }
        return result;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    @Override
    public Map<String, Object> currentApprover(Long orderId) {
        EmployeeIdentity identity = assertEmployee();
        OpsBusinessOrder order = orderService.getById(orderId);
        if (order == null || !Objects.equals(order.getCorpId(), identity.corpId())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        if (order.getWfInstanceId() == null) {
            result.put("resolved", false);
            result.put("message", "审批流程尚未发起，请联系管理员");
            return result;
        }
        WorkflowPlatformClient.StartResult started =
                workflowService.currentApprover(order.getWfInstanceId());
        result.put("instanceId", started.instanceId());
        result.put("approverName", started.approverName());
        result.put("approverRole", started.approverRole());
        result.put("displayText", started.resolved() ? started.displayText() : null);
        result.put("nodeName", started.nodeName());
        result.put("approverMode", started.approverMode());
        result.put("resolved", started.resolved());
        result.put("message", started.message());
        return result;
    }

    // ── 货款补录（定金支付 → 收齐余款） ──

    @Override
    public List<Map<String, Object>> listDepositPending() {
        EmployeeContext ctx = resolveEmployeeContext();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                DEPOSIT_PENDING_SQL + " ORDER BY create_time DESC, id DESC LIMIT 200",
                ctx.corpId(), ctx.employeeUserId(), ctx.employeePhone());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", longVal(row.get("id")));
            item.put("orderNo", str(row.get("order_no")));
            item.put("customer", str(row.get("customer")));
            item.put("customerPhone", str(row.get("customer_phone")));
            item.put("totalAmount", money(row.get("total_amount")));
            item.put("payableAmount", money(row.get("payable_amount") == null
                    || money(row.get("payable_amount")).signum() <= 0
                    ? row.get("total_amount") : row.get("payable_amount")));
            item.put("depositAmount", money(row.get("deposit_amount")));
            item.put("balanceAmount", balanceAmountOf(row));
            // 定金收款时间优先取制单收款日期，兼容历史数据无该值时回退制单时间
            item.put("depositReceiptDate", str(row.get("receipt_date")).isEmpty()
                    ? row.get("create_time") : str(row.get("receipt_date")));
            item.put("balanceStatus", "未结清");
            // 定时提醒任务未跑过时，首次提醒时间回退制单时间，保证列表有可解释的时间
            Object remind = row.get("balance_remind_time");
            item.put("lastRemindTime", remind == null ? row.get("create_time") : remind);
            item.put("createTime", row.get("create_time"));
            item.put("agentNames", str(row.get("agent_names")));
            item.put("bizStatus", str(row.get("biz_status")));
            result.add(item);
        }
        return result;
    }

    @Override
    public long countDepositPending() {
        EmployeeContext ctx = resolveEmployeeContext();
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" + DEPOSIT_PENDING_SQL + ") pending",
                Long.class, ctx.corpId(), ctx.employeeUserId(), ctx.employeePhone());
        return count == null ? 0L : count;
    }

    @Override
    public Map<String, Object> depositPendingDetail(Long orderId) {
        EmployeeContext ctx = resolveEmployeeContext();
        OpsBusinessOrder order = requireAgentOrder(ctx, orderId);
        if (!"DEPOSIT".equals(order.getPaymentType())) {
            throw BusinessException.of("该订单不是定金支付，无需补录");
        }
        return balanceOrderView(order);
    }

    @Override
    public Map<String, Object> receiveBalance(Long orderId, BalanceReceiveRequest request) {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户暂不可操作货款补录");
        }
        EmployeeContext ctx = resolveEmployeeContext();
        if (request == null) throw BusinessException.of("补录信息不能为空");
        BigDecimal received = request.getReceivedAmount();
        if (received == null || received.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.of("实收余款必须大于0");
        }
        if (received.scale() > 2) {
            throw BusinessException.of("实收余款最多保留两位小数");
        }
        BigDecimal amount = received.setScale(2, RoundingMode.HALF_UP);
        String voucher = str(request.getVoucherUrl());
        if (voucher.isEmpty()) throw BusinessException.of("请上传收款凭证");
        // 复用员工特批文件上传目录，但只接受本组织命名空间下的凭证，防止引用其他组织的文件
        String voucherPrefix = "/uploads/ops/special-approval/" + ctx.corpId() + "/";
        if (voucher.length() > 500 || !voucher.startsWith(voucherPrefix)) {
            throw BusinessException.of("收款凭证地址不合法，请重新上传");
        }
        String remark = str(request.getRemark());
        // 是否全款=否的补录必须写清收款说明（后端为准，防绕过前端）
        if (remark.isEmpty()) {
            throw BusinessException.of("请填写收款备注");
        }
        if (remark.length() > MAX_REMARK_LENGTH) {
            throw BusinessException.of("收款备注不能超过" + MAX_REMARK_LENGTH + "个字");
        }
        PaymentMethod method = PaymentMethod.normalize(request.getPaymentMethod());
        String receiptDate = com.aip.ops.util.BalanceReceiptRules
                .normalizeDate(request.getReceiptDate());
        String receiptNo = com.aip.ops.util.BalanceReceiptRules
                .normalizeOptionalReceiptNo(request.getReceiptNo());
        Map<String, Object> result = transactionTemplate.execute(status -> doReceiveBalance(
                ctx, orderId, amount, method, receiptDate, receiptNo, voucher, remark));
        // 尾款已结清：异步通知当前派工节点审批人可派工；通知失败不影响收款结果。
        // 订单必须在请求线程按租户上下文加载，异步线程没有 SecurityContext，
        // 直接查 orderService 会被租户过滤器拦截为空。
        if (result != null && !Boolean.TRUE.equals(result.get("alreadyReceived"))) {
            try {
                OpsBusinessOrder latest = orderService.getById(orderId);
                if (latest != null) {
                    workflowTaskExecutor.execute(() -> notifyBalancePaid(
                            latest, ctx.employeeName(), ctx.employeePhone()));
                }
            } catch (Exception e) {
                log.warn("尾款结清通知调度失败 orderId={}: {}", orderId, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 尾款结清后通知派工节点审批人（部长/派工员）可派工。
     *
     * <p>仅通知当前 PENDING 的派工任务处理人；订单尚未进入派工节点时
     * 由工作流后续进入节点时正常发待办通知。通知失败只记日志。</p>
     */
    private void notifyBalancePaid(OpsBusinessOrder order, String operatorName,
                                   String operatorPhone) {
        if (order == null || order.getWfInstanceId() == null) return;
        try {
            List<Map<String, Object>> tasks = pendingDispatchTasks(order.getWfInstanceId());
            if (tasks.isEmpty()) return;
            Map<String, Object> baseVars = balancePaidVars(order, operatorName, operatorPhone);
            tasks.forEach(task -> sendBalancePaidNotice(order, task, baseVars));
            log.info("尾款结清通知已提交 orderId={}, instanceId={}, receiverCount={}",
                    order.getId(), order.getWfInstanceId(), tasks.size());
        } catch (Exception e) {
            log.warn("尾款结清通知失败 orderId={}: {}",
                    order == null ? null : order.getId(), e.getMessage());
        }
    }

    /** 当前仍在派工节点（当前节点 + PENDING）的审批人；同一审批人只取一条任务。 */
    private List<Map<String, Object>> pendingDispatchTasks(Long instanceId) {
        return jdbcTemplate.queryForList(
                "SELECT t.approver_user_id AS approverUserId, MIN(t.id) AS taskId "
                        + "FROM ai_platform.wf_task t "
                        + "JOIN ai_platform.wf_instance i ON i.id = t.instance_id "
                        + "  AND i.current_node_id = t.node_id "
                        + "  AND i.status = 'RUNNING' AND i.delete_flag = 0 "
                        + "JOIN ai_platform.wf_definition_node n ON n.id = t.node_id "
                        + "WHERE t.instance_id = ? AND t.status = 'PENDING' "
                        + "AND t.delete_flag = 0 AND t.cc_flag = 0 "
                        + "AND n.delete_flag = 0 "
                        + "AND n.action_type = 'OPS_DISPATCH_OUTBOUND' "
                        + "AND t.approver_user_id IS NOT NULL "
                        + "GROUP BY t.approver_user_id ORDER BY MIN(t.id)",
                instanceId);
    }

    /** 尾款结清通知变量：经办人姓名/电话用于部长催收或派工前联系。 */
    private Map<String, Object> balancePaidVars(OpsBusinessOrder order, String operatorName,
                                                String operatorPhone) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("orderNo", order.getOrderNo());
        vars.put("businessSummary", "订单" + str(order.getOrderNo()) + "尾款已结清，可派工");
        vars.put("nodeName", "尾款结清");
        vars.put("changeAction", "尾款结清");
        vars.put("handlerName", OrderPartyText.primaryName(order.getAgentNames()));
        String phone = OrderPartyText.primaryPhone(order.getAgentPhones());
        if (phone.isEmpty()) phone = str(order.getCustomerPhone());
        if (phone.isEmpty()) phone = str(operatorPhone);
        vars.put("handlerPhone", phone);
        vars.put("operatorName", str(operatorName).isEmpty() ? "系统" : operatorName);
        vars.put("remark", "订单尾款已结清，可派工出库");
        vars.put("submitTime", LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return vars;
    }

    private void sendBalancePaidNotice(OpsBusinessOrder order, Map<String, Object> task,
                                       Map<String, Object> baseVars) {
        Long taskId = longVal(task.get("taskId"));
        Long receiverUserId = longVal(task.get("approverUserId"));
        if (taskId == null || receiverUserId == null) return;
        Map<String, Object> vars = new LinkedHashMap<>(baseVars);
        vars.put("taskId", taskId);
        workflowPlatformClient.notifyBusinessProgress(
                WorkflowPlatformClient.SCENE_BALANCE_PAID,
                order.getWfInstanceId(), receiverUserId, vars,
                "BALANCE_PAID:" + order.getId() + ":" + taskId);
    }

    /** 事务内：行锁订单 → 重新校验经办人/付款方式/金额 → 原子置全款。 */
    private Map<String, Object> doReceiveBalance(EmployeeContext ctx, Long orderId,
                                                 BigDecimal received, PaymentMethod method,
                                                 String receiptDate, String receiptNo,
                                                 String voucher, String remark) {
        jdbcTemplate.queryForList(
                "SELECT id FROM ops_business_order WHERE id=? AND delete_flag=0 FOR UPDATE",
                Long.class, orderId);
        OpsBusinessOrder order = requireAgentOrder(ctx, orderId);
        if (!"DEPOSIT".equals(order.getPaymentType())) {
            throw BusinessException.of("该订单不是定金支付，无需补录");
        }
        if (Integer.valueOf(1).equals(order.getIsFullPayment())) {
            return alreadyReceivedView(order);
        }
        BigDecimal due = balanceOf(order);
        if (due.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.of("订单应补余款为0，无需补录");
        }
        if (received.compareTo(due) != 0) {
            throw BusinessException.of("实收余款必须等于应补余款 ¥"
                    + due.toPlainString());
        }
        int updated = markBalanceReceived(ctx, orderId, received, method, receiptDate,
                receiptNo, voucher, remark);
        if (updated != 1) {
            OpsBusinessOrder latest = orderService.getById(orderId);
            if (latest != null && Integer.valueOf(1).equals(latest.getIsFullPayment())) {
                return alreadyReceivedView(latest);
            }
            throw BusinessException.of("订单状态已变化，请刷新后重试");
        }
        OpsBusinessOrder latest = orderService.getById(orderId);
        if (latest == null) throw BusinessException.of("订单状态已变化，请刷新后重试");
        // 仅更新收款状态与派工准入；销售金额/提成仍按下单商品金额统计，不因收款时点回算
        saveBalanceReceiveRecord(ctx, latest, received, method, receiptDate, receiptNo,
                voucher, remark);
        log.info("货款补录成功 orderId={} userId={} received={} method={} voucher={}",
                orderId, ctx.employeeUserId(), received, method.name(), voucher);
        Map<String, Object> result = balanceOrderView(latest);
        result.put("alreadyReceived", false);
        result.put("message", "货款补录成功，订单已标记为全款");
        return result;
    }

    /** 原子置全款并写入尾款明细；返回受影响行数，0 表示已被并发处理。 */
    private int markBalanceReceived(EmployeeContext ctx, Long orderId, BigDecimal received,
                                    PaymentMethod method, String receiptDate, String receiptNo,
                                    String voucher, String remark) {
        return jdbcTemplate.update(
                "UPDATE ops_business_order SET is_full_payment=1, balance_received_amount=?, "
                        + "balance_paid_at=NOW(), balance_voucher_url=?, balance_remark=?, "
                        + "balance_payment_method=?, balance_receipt_date=?, balance_receipt_no=?, "
                        + "modify_time=NOW(), modify_user=?, modify_user_name=? "
                        + "WHERE id=? AND is_full_payment=0 AND delete_flag=0",
                received, voucher, remark, method.name(), receiptDate, receiptNo,
                ctx.employeeUserId(), ctx.employeeName(), orderId);
    }

    /** 幂等响应：已补录订单再次提交时返回同一结构，避免重复收款。 */
    private Map<String, Object> alreadyReceivedView(OpsBusinessOrder order) {
        Map<String, Object> result = balanceOrderView(order);
        result.put("alreadyReceived", true);
        result.put("message", "该订单已完成货款补录");
        return result;
    }

    /** 货款补录业务留痕：订单详情 flowRecords 可追溯收款人、时间、金额、方式与凭证。 */
    private void saveBalanceReceiveRecord(EmployeeContext ctx, OpsBusinessOrder order,
                                          BigDecimal received, PaymentMethod method,
                                          String receiptDate, String receiptNo,
                                          String voucher, String remark) {
        OpsBusinessFlowRecord record = new OpsBusinessFlowRecord();
        record.setCorpId(order.getCorpId());
        record.setOrderId(order.getId());
        record.setInstanceId(order.getWfInstanceId());
        record.setNodeName("货款补录");
        record.setActionType("OPS_BALANCE_RECEIVE");
        record.setBizStatus(order.getBizStatus());
        record.setOperatorUserId(ctx.employeeUserId());
        record.setOperatorName(ctx.employeeName());
        record.setOpinion("尾款结清");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("receivedAmount", received);
        form.put("paymentMethod", method.name());
        form.put("paymentMethodName", method.label());
        form.put("receiptDate", receiptDate);
        form.put("receiptNo", receiptNo);
        form.put("voucherUrl", voucher);
        form.put("remark", remark);
        record.setFormJson(toJson(form));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("isFullPayment", 1);
        result.put("balancePaidAt", order.getBalancePaidAt());
        record.setResultJson(toJson(result));
        record.setDeleteFlag(0);
        flowRecordMapper.insert(record);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("货款补录留痕序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 经办人可见性：本组织 + 当前员工手机号在 agent_phones 中（或 create_user 为本人）。 */
    private OpsBusinessOrder requireAgentOrder(EmployeeContext ctx, Long orderId) {
        OpsBusinessOrder order = orderId == null ? null : orderService.getById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())
                || !Objects.equals(order.getCorpId(), ctx.corpId())
                || !visibleToAgent(order, ctx)) {
            throw BusinessException.notFound("订单不存在或不属于当前经办人");
        }
        return order;
    }

    private boolean visibleToAgent(OpsBusinessOrder order, EmployeeContext ctx) {
        if (Objects.equals(order.getCreateUser(), ctx.employeeUserId())) return true;
        String phones = str(order.getAgentPhones());
        if (phones.isEmpty() || ctx.employeePhone() == null) return false;
        for (String phone : phones.split("、")) {
            if (ctx.employeePhone().equals(phone.trim())) return true;
        }
        return false;
    }

    private Map<String, Object> balanceOrderView(OpsBusinessOrder order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", order.getId());
        view.put("orderNo", order.getOrderNo());
        view.put("customer", order.getCustomer());
        view.put("customerPhone", order.getCustomerPhone());
        view.put("agentNames", order.getAgentNames());
        view.put("totalAmount", money(order.getTotalAmount()));
        view.put("payableAmount", order.getPayableAmount() == null
                || order.getPayableAmount().signum() <= 0
                ? money(order.getTotalAmount()) : money(order.getPayableAmount()));
        view.put("depositAmount", money(order.getDepositAmount()));
        view.put("balanceAmount", balanceOf(order));
        view.put("paymentType", order.getPaymentType());
        view.put("paymentMethod", order.getPaymentMethod());
        view.put("paymentMethodName", PaymentMethod.labelOf(order.getPaymentMethod()));
        view.put("isFullPayment", order.getIsFullPayment());
        view.put("balanceStatus", Integer.valueOf(1).equals(order.getIsFullPayment())
                ? "已结清" : "未结清");
        view.put("balanceReceivedAmount", order.getBalanceReceivedAmount());
        view.put("balancePaidAt", order.getBalancePaidAt());
        view.put("balanceVoucherUrl", order.getBalanceVoucherUrl());
        view.put("balanceRemark", order.getBalanceRemark());
        view.put("balancePaymentMethod", order.getBalancePaymentMethod());
        view.put("balancePaymentMethodName",
                PaymentMethod.labelOf(order.getBalancePaymentMethod()));
        view.put("balanceReceiptDate", order.getBalanceReceiptDate());
        view.put("balanceReceiptNo", order.getBalanceReceiptNo());
        view.put("depositReceiptDate", str(order.getReceiptDate()).isEmpty()
                ? order.getCreateTime() : order.getReceiptDate());
        view.put("lastRemindTime", order.getBalanceRemindTime() == null
                ? order.getCreateTime() : order.getBalanceRemindTime());
        view.put("createTime", order.getCreateTime());
        view.put("alreadyReceived", Integer.valueOf(1).equals(order.getIsFullPayment()));
        return view;
    }

    private BigDecimal balanceOf(OpsBusinessOrder order) {
        BigDecimal balance = order.getBalanceAmount() != null ? order.getBalanceAmount()
                : money(order.getTotalAmount()).subtract(money(order.getDepositAmount()));
        return balance.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Object value) {
        if (value instanceof BigDecimal b) return b.setScale(2, RoundingMode.HALF_UP);
        if (value instanceof Number n) {
            return new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static Long longVal(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /** 列表展示的待补金额：优先落库 balance_amount，历史数据回退 total - deposit。 */
    private static BigDecimal balanceAmountOf(Map<String, Object> row) {
        BigDecimal balance = money(row.get("balance_amount"));
        if (balance.signum() > 0) return balance;
        return money(row.get("total_amount")).subtract(money(row.get("deposit_amount")))
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public Map<String, Object> checkReceiptNo(String receiptNo, String customer) {
        EmployeeIdentity identity = assertEmployee();
        Map<String, Object> result = new LinkedHashMap<>();
        // 收据号在提交时必填；查重接口对空值保持“无冲突”以兼容失焦调用
        if (str(receiptNo).isEmpty()) {
            result.put("duplicate", false);
            return result;
        }
        String normalized = normalizeReceiptNo(receiptNo);
        String conflict = findReceiptConflict(identity.corpId(), normalized, str(customer));
        result.put("duplicate", conflict != null);
        if (conflict != null) result.put("message", conflict);
        return result;
    }

    private Map<String, Object> persistManualEntry(EmployeeBusinessEntryRequest request,
                                                   EmployeeContext ctx, Validated v) {
        String batchNo = resolveBatch(request, ctx);
        MemberResolution member = resolveOrCreateMember(v.phone(), v.customer(), ctx.corpId());
        CommunityInfo community = resolveCommunity(v.communityId(), ctx.corpId());
        // 订单编号由独立事务生成（REQUIRES_NEW），避免长事务持有流水行锁；回滚可能产生号段空洞
        OrderNoGenerator.Generated generated = orderNoGenerator.next(ctx.corpId());
        String orderNo = generated.orderNo();
        // 订单编号即业务组号：提成/统计/财务核对均以订单为维度；
        // 同时写入 business_group_no 兼容历史分组逻辑
        String businessGroupNo = orderNo;
        List<Long> ids = new ArrayList<>();
        Map<String, Long> firstItemIds = new LinkedHashMap<>();
        BigDecimal totalAmount = v.products().stream()
                .map(ValidatedProduct::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 主子表拆分：一次提交 = 1 条订单头 + N（产品×经办人）条子表
        OpsBusinessOrder order = orderService.createOrderWithPayment(
                ctx.corpId(), generated.orderKey(), orderNo, batchNo, businessGroupNo,
                v.receiptNo(), ctx.company(), v.customer(), v.phone(), member.id(),
                community.id(), community.name(), v.detailAddress(), v.receiptDate(),
                null, null, null, v.voucherUrl(),
                totalAmount, INITIAL_CALC_STATUS, v.remark(), v.paymentMethod(),
                v.paymentType(), v.depositAmount());
        // 凭证信息落订单头，并把附件回填 order_id（同一事务内完成）
        orderService.updateVoucherAttachments(order.getId(), ctx.corpId(), v.receiptImageUrl(),
                v.paymentVoucherUrls(), v.contractAttachmentUrls());
        attachmentService.bindToOrder(v.attachmentIds(), order.getId(), ctx.corpId());
        // 多经办人按「产品 × 经办人」拆行：每行独立 agent_phone 归属，
        // 提成计算沿用「同收据号+客户+品类+型号」组内均分规则。
        for (ValidatedProduct product : v.products()) {
            for (AgentInfo agent : v.agents()) {
                OpsBusinessData entity = buildEntity(ctx, v, product, agent,
                        batchNo, orderNo, businessGroupNo, member.id(), community);
                entity.setOrderId(order.getId());
                businessDataMapper.insert(entity);
                ids.add(entity.getId());
                firstItemIds.putIfAbsent(tradeInProductKey(product), entity.getId());
            }
        }
        int tradeInCount = insertTradeInItems(ctx, v, order.getId(), firstItemIds);
        if (tradeInCount > 0) {
            // 与业务订单同事务落 PENDING：进程在提交后崩溃也能看出采购单未完成，前端据此提供重试
            orderService.updateTradeInPurchaseStatus(order.getId(), "PENDING", null, null, null);
        }
        orderService.refreshAgentSummary(order.getId());
        orderService.refreshTotals(order.getId());
        // 新月度批次首次写入时无行可锁；入库后再次锁定校验，
        // 防止「检查为空 → 并发上报 → 继续插入」产生已上报批次下的新行
        lockAndCheckBatch(ctx.corpId(), batchNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("ids", ids);
        result.put("detailCount", ids.size());
        result.put("productCount", v.products().size());
        result.put("agentCount", v.agents().size());
        result.put("batchNo", batchNo);
        result.put("orderNo", orderNo);
        result.put("businessGroupNo", businessGroupNo);
        result.put("receiptAmount", totalAmount);
        BigDecimal deductionTotal = v.products().stream()
                .map(ValidatedProduct::deduction)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean hasTradeIn = v.products().stream().anyMatch(ValidatedProduct::tradeIn);
        result.put("hasTradeIn", hasTradeIn);
        result.put("tradeInDeduction", deductionTotal);
        result.put("payableAmount", totalAmount.subtract(deductionTotal).max(BigDecimal.ZERO));
        result.put("tradeInCount", tradeInCount);
        result.put("paymentType", v.paymentType());
        result.put("depositAmount", v.depositAmount());
        result.put("balanceAmount", v.balanceAmount());
        result.put("isFullPayment", v.isFullPayment());
        result.put("platformMemberId", member.id());
        result.put("memberCreated", member.created());
        return result;
    }

    /** 旧品按销售商品行分组落库；同一产品多经办人共用一份旧品。 */
    private int insertTradeInItems(EmployeeContext ctx, Validated v, Long orderId,
                                   Map<String, Long> firstItemIds) {
        int count = 0;
        for (ValidatedProduct product : v.products()) {
            if (!product.tradeIn()) continue;
            String productKey = tradeInProductKey(product);
            Long sourceItemId = firstItemIds.get(productKey);
            for (ValidatedTradeIn old : product.tradeInItems()) {
                OpsBusinessOrderTradeIn row = new OpsBusinessOrderTradeIn();
                row.setCorpId(ctx.corpId());
                row.setOrderId(orderId);
                row.setProductKey(productKey);
                row.setSourceItemId(sourceItemId);
                row.setOldProductId(old.productId());
                row.setOldMaterialNumber(old.materialNumber());
                row.setOldProductName(old.productName());
                row.setBrand(old.brand());
                row.setModel(old.model());
                row.setCategory(old.category());
                row.setQuantity(old.quantity());
                row.setUnit(old.unit());
                row.setRemark(old.remark());
                row.setIsManual(old.manual() ? 1 : 0);
                row.setDeleteFlag(0);
                tradeInMapper.insert(row);
                count++;
            }
        }
        return count;
    }

    private static String tradeInProductKey(ValidatedProduct product) {
        return tradeInProductKey(product.categoryKey(), product.brand(), product.model(),
                product.price());
    }

    private static String tradeInProductKey(String category, String brand, String model,
                                            BigDecimal price) {
        return category + "|" + brand + "|" + model
                + "|" + (price == null ? "" : price.stripTrailingZeros().toPlainString());
    }

    // ── 员工上下文 ──

    private EmployeeContext resolveEmployeeContext() {
        EmployeeIdentity identity = assertEmployee();
        CorpInfo corp = fetchCorpInfo(identity.corpId());
        return new EmployeeContext(identity.corpId(), corp.corpName(), corp.company(),
                identity.name(), identity.phone(), SecurityUtils.getCurrentUserId(),
                corp.regionId(), corp.regionName());
    }

    /** 校验当前 JWT 确为在职员工；返回组织 + 经办人姓名/手机号。 */
    private EmployeeIdentity assertEmployee() {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            throw BusinessException.of("仅员工账号可录入业务数据");
        }
        Long corpId = SecurityUtils.getCurrentCorpId();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请选择所属公司后再录入业务数据");
        }
        Map<String, Object> user = loadPlatformUser(SecurityUtils.getCurrentUserId());
        String username = str(user.get("username"));
        String userPhone = str(user.get("phone"));
        // 员工 JWT 的 username 来自 sys_user.username。会员 SSO JWT 的 sub 是
        // sys_member.id，恰与某个 sys_user.id 相同时不能仅凭 id 放行为员工。
        String jwtUsername = SecurityUtils.getCurrentUser().getUsername();
        if (!username.equals(jwtUsername) && !userPhone.equals(jwtUsername)) {
            throw BusinessException.of("仅员工账号可录入业务数据");
        }
        String phone = GasMemberMatcher.PHONE_PATTERN.matcher(username).matches()
                ? username : userPhone;
        if (!GasMemberMatcher.PHONE_PATTERN.matcher(phone).matches()) {
            throw BusinessException.of("员工账号未绑定有效手机号，请联系管理员");
        }
        String name = str(user.get("real_name"));
        if (name.isEmpty()) name = fallbackMemberName();
        if (name.isEmpty()) name = username;
        return new EmployeeIdentity(corpId, name, phone);
    }

    private Map<String, Object> loadPlatformUser(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT username, real_name, phone, status, delete_flag "
                        + "FROM ai_platform.sys_user WHERE id = ? LIMIT 1", userId);
        if (rows.isEmpty()) {
            throw BusinessException.of("仅员工账号可录入业务数据");
        }
        Map<String, Object> row = rows.get(0);
        if (num(row.get("status")) != 1 || num(row.get("delete_flag")) != 0) {
            throw BusinessException.of("员工账号已被禁用，请联系管理员");
        }
        return row;
    }

    /** 员工姓名缺失时，用 JWT member_id 关联的平台会员姓名兜底。 */
    private String fallbackMemberName() {
        try {
            Long memberId = SecurityUtils.getCurrentUser().getMemberId();
            if (memberId == null) return "";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT real_name, nickname FROM ai_platform.sys_member "
                            + "WHERE id = ? AND delete_flag = 0 LIMIT 1", memberId);
            if (rows.isEmpty()) return "";
            String realName = str(rows.get(0).get("real_name"));
            return realName.isEmpty() ? str(rows.get(0).get("nickname")) : realName;
        } catch (Exception e) {
            log.debug("员工姓名兜底查询失败: {}", e.getMessage());
            return "";
        }
    }

    private CorpInfo fetchCorpInfo(Long corpId) {
        try {
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/internal/corp/" + corpId, null);
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) {
                throw BusinessException.of("无法获取所属公司信息");
            }
            String name = str(data.get("name"));
            String shortName = str(data.get("shortName"));
            // 批次前缀优先用组织简称；简称缺失时沿用该组织历史业务数据的“公司”口径，
            // 最后才回退到全称，避免批次前缀与既有数据（临洮/康乐/…）不一致。
            String company = shortName.isEmpty() ? establishedCompany(corpId) : shortName;
            if (company.isEmpty()) company = name;
            if (company.isEmpty()) {
                throw BusinessException.of("所属公司名称为空，请联系管理员");
            }
            if (company.length() > 32) company = company.substring(0, 32);
            Object rawRegionId = data.get("regionId");
            Long regionId = rawRegionId instanceof Number n ? n.longValue() : null;
            return new CorpInfo(name.isEmpty() ? company : name, company,
                    regionId, str(data.get("regionName")));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("获取组织信息失败 corpId={}: {}", corpId, e.getMessage());
            throw BusinessException.of("无法获取所属公司信息，请稍后重试");
        }
    }

    /** 组织历史业务数据中最常用的公司名（仅用于组织无简称时的批次前缀兜底）。 */
    private String establishedCompany(Long corpId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT company FROM ops_business_order_item "
                            + "WHERE corp_id = ? AND delete_flag = 0 "
                            + "AND company IS NOT NULL AND company <> '' "
                            + "GROUP BY company ORDER BY COUNT(*) DESC LIMIT 1",
                    String.class, corpId);
            return rows.isEmpty() ? "" : str(rows.get(0));
        } catch (Exception e) {
            log.debug("查询组织历史公司名失败 corpId={}: {}", corpId, e.getMessage());
            return "";
        }
    }

    // ── 批次 ──

    private List<String> selectableBatches(Long corpId) {
        String sql = "SELECT batch_no FROM ops_business_order_item "
                + "WHERE corp_id = ? AND delete_flag = 0 "
                + "AND batch_no IS NOT NULL AND batch_no <> '' "
                + "GROUP BY batch_no "
                + "HAVING SUM(CASE WHEN calc_status = ? THEN 1 ELSE 0 END) = 0 "
                + "ORDER BY MAX(id) DESC LIMIT 200";
        return jdbcTemplate.queryForList(sql, String.class, corpId, REPORTED_STATUS);
    }

    private String resolveBatch(EmployeeBusinessEntryRequest req, EmployeeContext ctx) {
        if (Boolean.TRUE.equals(req.getNewBatch())) {
            // 一公司一月一批次：存在则复用（已上报则拒绝），不存在则使用该月批次
            String monthly = monthlyBatchNo(ctx.company());
            lockAndCheckBatch(ctx.corpId(), monthly);
            return monthly;
        }
        String batchNo = str(req.getBatchNo());
        if (batchNo.isEmpty()) {
            throw BusinessException.of("请选择批次或新建批次");
        }
        if (batchNo.length() > 64) {
            throw BusinessException.of("批次号过长");
        }
        boolean exists = lockAndCheckBatch(ctx.corpId(), batchNo);
        if (!exists) {
            throw BusinessException.of("批次不存在或不属于当前公司");
        }
        return batchNo;
    }

    /** 公司月度批次：{公司简称}-{yyyyMM}。 */
    private static String monthlyBatchNo(String company) {
        return company + "-" + YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    /**
     * 锁定批次现有行并检查是否已上报集团（SELECT ... FOR UPDATE，锁定读）。
     * 与「提成上报集团」更新串行化，避免“检查未上报 → 上报提交 → 新行插入”
     * 竞态产生部分上报批次；锁定读也保证能看到最新已提交的上报状态，
     * 不受 REPEATABLE READ 快照影响。
     *
     * @return 批次是否已有行（false = 可创建新批次）
     */
    private boolean lockAndCheckBatch(Long corpId, String batchNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT calc_status FROM ops_business_order_item "
                        + "WHERE corp_id = ? AND batch_no = ? AND delete_flag = 0 FOR UPDATE",
                corpId, batchNo);
        for (Map<String, Object> row : rows) {
            if (REPORTED_STATUS.equals(str(row.get("calc_status")))) {
                throw BusinessException.of("该批次已上报集团，无法新增业务");
            }
        }
        return !rows.isEmpty();
    }

    // 订单编号由 OrderNoGenerator 统一生成（导入/手工共用同一运营商体系每日流水）。
    // ── 校验 ──

    private Validated validateRequest(EmployeeBusinessEntryRequest req, EmployeeContext ctx) {
        return validateRequest(req, ctx, null);
    }

    private Validated validateRequest(EmployeeBusinessEntryRequest req, EmployeeContext ctx,
                                      Long allowOrderId) {
        if (req == null) throw BusinessException.of("请求参数不能为空");
        String phone = normalizePhone(req.getCustomerPhone());
        if (!GasMemberMatcher.PHONE_PATTERN.matcher(phone).matches()) {
            throw BusinessException.of("客户手机号须为11位大陆手机号");
        }
        String customer = requireText(req.getCustomer(), "客户名称", MAX_CUSTOMER_LENGTH);
        List<ValidatedProduct> products = validateProducts(req.getProducts(), ctx.corpId());
        int totalTradeInRows = products.stream()
                .filter(ValidatedProduct::tradeIn)
                .mapToInt(product -> product.tradeInItems().size())
                .sum();
        if (totalTradeInRows > MAX_TOTAL_TRADE_IN_ITEMS) {
            throw BusinessException.of("单笔业务旧品最多" + MAX_TOTAL_TRADE_IN_ITEMS + "行");
        }
        List<AgentInfo> agents = validateAgents(req.getAgentUserIds(), ctx);
        String receiptDate = normalizeDate(req.getReceiptDate());
        String receiptNo = normalizeReceiptNo(req.getReceiptNo());
        // 收据号必填（OCR 或手填）；制单阶段仍二次查重，防止绕过前端直接提交；
        // 草稿编辑时排除本订单旧行，避免改客户后与自己历史收据号冲突
        assertReceiptNoAvailable(ctx.corpId(), receiptNo, customer, allowOrderId);
        PaymentMethod paymentMethod = PaymentMethod.normalize(req.getPaymentMethod());
        // 凭证信息：支付凭证/合同附件必填；收据图片可选（用于 OCR）
        List<OpsBusinessAttachment> receiptImages = req.getReceiptImageId() == null
                ? List.of()
                : attachmentService.requireOwnedList(List.of(req.getReceiptImageId()),
                        OpsBusinessAttachment.TYPE_RECEIPT, ctx.corpId(), allowOrderId);
        List<OpsBusinessAttachment> paymentVouchers = attachmentService.requireOwnedList(
                req.getPaymentVoucherIds(), OpsBusinessAttachment.TYPE_PAYMENT,
                ctx.corpId(), allowOrderId);
        List<OpsBusinessAttachment> contracts = attachmentService.requireOwnedList(
                req.getContractAttachmentIds(), OpsBusinessAttachment.TYPE_CONTRACT,
                ctx.corpId(), allowOrderId);
        String receiptImageUrl = receiptImages.isEmpty() ? null : receiptImages.get(0).getFileUrl();
        String paymentVoucherUrls = attachmentService.toUrlCsv(paymentVouchers);
        String contractAttachmentUrls = attachmentService.toUrlCsv(contracts);
        // 订单头 legacy voucher_url 继续写支付凭证，兼容既有列表/财务视图
        String voucherUrl = paymentVoucherUrls;
        List<Long> attachmentIds = new ArrayList<>();
        if (!receiptImages.isEmpty()) attachmentIds.add(receiptImages.get(0).getId());
        paymentVouchers.forEach(file -> attachmentIds.add(file.getId()));
        contracts.forEach(file -> attachmentIds.add(file.getId()));
        Long communityId = req.getCommunityId();
        if (communityId != null && communityId <= 0) communityId = null;
        // 小区必填：订单需归属到具体小区，便于后续网格/台账统计
        if (communityId == null) {
            throw BusinessException.of("请选择小区");
        }
        String detailAddress = requireText(
                req.getDetailAddress(), "详细地址", MAX_DETAIL_ADDRESS_LENGTH);
        String remark = normalizeRemark(req.getRemark());
        BigDecimal salesAmount = products.stream().map(ValidatedProduct::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        // 定金/余款以抵扣后的应付合计为基数，避免以旧换新订单按销售额多收余款
        BigDecimal deductionAmount = products.stream().map(ValidatedProduct::deduction)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payableAmount = salesAmount.subtract(deductionAmount)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        PaymentTerms terms = validatePaymentTerms(req.getPaymentType(), req.getDepositAmount(),
                payableAmount);
        return new Validated(phone, customer, products, agents,
                receiptDate, receiptNo, paymentMethod.name(), voucherUrl,
                receiptImageUrl, paymentVoucherUrls, contractAttachmentUrls, attachmentIds,
                communityId, detailAddress, remark,
                terms.paymentType(), terms.isFullPayment(), terms.depositAmount(),
                terms.balanceAmount());
    }

    /**
     * 付款方式校验（服务端最终口径）：
     * FULL：定金必须为 0，余款 0，is_full_payment=1；
     * DEPOSIT：定金 >0 且严格小于应付合计（销售金额 − 以旧换新抵扣），
     * 余款 = 应付合计 - 定金（服务端计算）。
     */
    private PaymentTerms validatePaymentTerms(String rawType, BigDecimal rawDeposit,
                                              BigDecimal payableAmount) {
        String type = rawType == null || rawType.isBlank() ? "FULL" : rawType.trim().toUpperCase();
        if (!"FULL".equals(type) && !"DEPOSIT".equals(type)) {
            throw BusinessException.of("付款方式必须是 FULL（全款支付）或 DEPOSIT（定金支付）");
        }
        if ("FULL".equals(type)) {
            if (rawDeposit != null && rawDeposit.compareTo(BigDecimal.ZERO) != 0) {
                throw BusinessException.of("全款支付时定金金额必须为0");
            }
            return new PaymentTerms("FULL", 1, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (rawDeposit == null) {
            throw BusinessException.of("定金支付请填写定金金额");
        }
        if (rawDeposit.scale() > 2) {
            throw BusinessException.of("定金金额最多保留两位小数");
        }
        BigDecimal deposit = rawDeposit.setScale(2, RoundingMode.HALF_UP);
        if (deposit.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.of("定金金额必须大于0");
        }
        if (deposit.compareTo(payableAmount) >= 0) {
            throw BusinessException.of("定金金额必须小于应付合计，全额支付请选择全款支付");
        }
        BigDecimal balance = payableAmount.subtract(deposit).setScale(2, RoundingMode.HALF_UP);
        return new PaymentTerms("DEPOSIT", 0, deposit, balance);
    }

    /** 收据号必填：去首尾空白；空值拒绝；校验长度与字符集。 */
    private static String normalizeReceiptNo(String raw) {
        String value = str(raw);
        if (value.isEmpty()) throw BusinessException.of("请填写收据号");
        if (value.length() > MAX_RECEIPT_NO_LENGTH) {
            throw BusinessException.of("收据号不能超过" + MAX_RECEIPT_NO_LENGTH + "个字符");
        }
        if (!RECEIPT_NO_PATTERN.matcher(value).matches()) {
            throw BusinessException.of("收据号只能包含字母、数字、短横线、下划线和斜杠");
        }
        return value;
    }

    private void assertReceiptNoAvailable(Long corpId, String receiptNo, String customer) {
        assertReceiptNoAvailable(corpId, receiptNo, customer, null);
    }

    private void assertReceiptNoAvailable(Long corpId, String receiptNo, String customer,
                                          Long excludeOrderId) {
        String conflict = findReceiptConflict(corpId, receiptNo, customer, excludeOrderId);
        if (conflict != null) throw BusinessException.of(conflict);
    }

    /**
     * 收据号查重：与财务核对同口径——同组织内同一客户可复用（同订单拆分/补录），
     * 被其他客户占用时返回可读冲突说明，未冲突返回 null。
     */
    private String findReceiptConflict(Long corpId, String receiptNo, String customer) {
        return findReceiptConflict(corpId, receiptNo, customer, null);
    }

    private String findReceiptConflict(Long corpId, String receiptNo, String customer,
                                       Long excludeOrderId) {
        List<Map<String, Object>> dups = jdbcTemplate.queryForList(
                "SELECT batch_no, customer FROM ops_business_order_item "
                        + "WHERE corp_id = ? AND receipt_no = ? AND delete_flag = 0 "
                        + "AND (? IS NULL OR order_id <> ?) "
                        + "ORDER BY id LIMIT 20",
                corpId, receiptNo, excludeOrderId, excludeOrderId);
        for (Map<String, Object> dup : dups) {
            if (!customer.equals(str(dup.get("customer")))) {
                return "收据号 " + receiptNo + " 已被其他客户占用（批次 "
                        + str(dup.get("batch_no")) + "，客户 " + str(dup.get("customer"))
                        + "），请更换";
            }
        }
        return null;
    }

    /** 备注选填：去首尾空白，超长拒绝，空串落库为 null。 */
    private static String normalizeRemark(String value) {
        String remark = value == null ? "" : value.trim();
        if (remark.length() > MAX_REMARK_LENGTH) {
            throw BusinessException.of("备注不能超过" + MAX_REMARK_LENGTH + "个字");
        }
        return remark.isEmpty() ? null : remark;
    }

    /** 产品明细：至少一行；每行独立校验品牌/品类/型号、标准售价、单价、数量与低价特批。 */
    private List<ValidatedProduct> validateProducts(
            List<EmployeeBusinessEntryRequest.ProductItem> items, Long corpId) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.of("请至少添加一行产品");
        }
        if (items.size() > MAX_PRODUCTS) {
            throw BusinessException.of("单笔业务最多" + MAX_PRODUCTS + "行产品");
        }
        List<ValidatedProduct> result = new ArrayList<>();
        Map<String, Integer> seenModels = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ValidatedProduct product = validateProduct(items.get(i), i + 1, corpId);
            // 同一型号重复成行会被提成均分逻辑误判为多经办人/重复订单，要求合并数量
            String key = product.brand() + "|" + product.categoryKey() + "|" + product.model();
            Integer firstRow = seenModels.putIfAbsent(key, i + 1);
            if (firstRow != null) {
                throw BusinessException.of("第" + (i + 1) + "个产品与第" + firstRow
                        + "个产品品牌/品类/型号重复，请合并数量后录入");
            }
            result.add(product);
        }
        return result;
    }

    private ValidatedProduct validateProduct(EmployeeBusinessEntryRequest.ProductItem item,
                                             int rowNo, Long corpId) {
        String prefix = "第" + rowNo + "个产品：";
        if (item == null) throw BusinessException.of(prefix + "信息不能为空");
        String categoryName = requireText(item.getCategory(), prefix + "品类");
        // 品牌必填规则由品类配置/SCM 目录决定：报警器这类无品牌品类允许品牌为空
        boolean brandRequired = scmClient.requiresBrand(corpId, categoryName);
        String brand = brandRequired
                ? requireText(item.getBrand(), prefix + "品牌", 50)
                : optionalText(item.getBrand(), 50, prefix + "品牌");
        if (OLD_GOODS_BRAND.equals(brand)) {
            throw BusinessException.of(prefix + "旧品品牌不能作为销售商品，请选择正常商品");
        }
        String model = requireText(item.getModel(), prefix + "型号");
        BigDecimal price = normalizePrice(item.getSalePrice(), prefix);
        int qty = item.getSaleQuantity() != null ? item.getSaleQuantity() : 0;
        if (qty < 1) throw BusinessException.of(prefix + "销售数量必须为正整数");
        BigDecimal amount = price.multiply(BigDecimal.valueOf(qty))
                .setScale(2, RoundingMode.HALF_UP);

        // 标准售价：以产品售价表为准；未配置/停用直接拒绝制单（后端二次校验防绕过）
        ProductPriceDtos.ProductPriceInfo priceInfo = productPriceService.lookupForEmployee(
                corpId, item.getProductId(), brand, categoryName, model);
        if (!priceInfo.configured()) {
            throw BusinessException.of(prefix + priceInfo.message());
        }
        boolean special = price.compareTo(priceInfo.salePrice()) < 0;
        BigDecimal rate = BigDecimal.ZERO;
        Long specialFileId = null;
        String specialFileUrl = null;
        String specialFileName = null;
        if (special) {
            OpsBusinessSpecialFile file = specialFileService.requireOwned(
                    item.getSpecialFileId(), corpId);
            specialFileId = file.getId();
            specialFileUrl = file.getFileUrl();
            specialFileName = file.getFileName();
            rate = validateCommissionRate(true, item.getCommissionRate(), prefix);
        }
        String categoryKey;
        try {
            categoryKey = scmClient.requireCategoryKey(corpId, categoryName);
            if (item.getProductId() != null && item.getProductId() > 0) {
                // productId 是售价主键：必须与提交的品牌/品类/型号同源，
                // 否则可用低价商品ID套高价商品绕过特批校验。
                scmClient.requireMaterialById(corpId, item.getProductId(), brand,
                        categoryName, model);
            } else {
                scmClient.requireMaterial(corpId, brand, categoryName, model);
            }
        } catch (BusinessException e) {
            throw BusinessException.of(prefix + e.getMessage());
        }
        boolean tradeIn = Boolean.TRUE.equals(item.getIsTradeIn());
        BigDecimal deduction = BigDecimal.ZERO;
        List<ValidatedTradeIn> tradeInItems = List.of();
        if (tradeIn) {
            deduction = validateTradeInDeduction(item.getDeductionAmount(), amount, prefix);
            tradeInItems = validateTradeInItems(item.getTradeInItems(), corpId, prefix);
        }
        return new ValidatedProduct(item.getProductId(), brand, categoryKey, model, price, qty,
                amount, rate, priceInfo.salePrice(), special, specialFileId, specialFileUrl,
                specialFileName, priceInfo.retailFloorPrice(), priceInfo.groupLowestPrice(),
                tradeIn, deduction, tradeInItems);
    }

    /** 抵扣金额：>0、最多两位小数、不得超过该商品行销售金额。 */
    private BigDecimal validateTradeInDeduction(BigDecimal raw, BigDecimal sales, String prefix) {
        if (raw == null || raw.signum() <= 0) {
            throw BusinessException.of(prefix + "以旧换新抵扣金额必须大于0");
        }
        if (raw.scale() > 2) {
            throw BusinessException.of(prefix + "抵扣金额最多保留两位小数");
        }
        BigDecimal deduction = raw.setScale(2, RoundingMode.HALF_UP);
        if (deduction.compareTo(sales) > 0) {
            throw BusinessException.of(prefix + "抵扣金额不能超过商品金额");
        }
        return deduction;
    }

    /**
     * 旧品校验：至少1件；选中 SCM 商品时以商品资料为准（防篡改品牌/型号），
     * 其他旧品允许手工录入名称/品牌/型号。
     */
    private List<ValidatedTradeIn> validateTradeInItems(
            List<EmployeeBusinessEntryRequest.TradeInItem> items, Long corpId, String prefix) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.of(prefix + "以旧换新至少录入1件旧品");
        }
        if (items.size() > MAX_TRADE_IN_ITEMS) {
            throw BusinessException.of(prefix + "旧品最多录入" + MAX_TRADE_IN_ITEMS + "件");
        }
        List<ValidatedTradeIn> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String rowPrefix = prefix + "第" + (i + 1) + "件旧品：";
            result.add(validateTradeInItem(items.get(i), corpId, rowPrefix));
        }
        return result;
    }

    /** 单件旧品：选中 SCM 商品以商品资料为准；否则按手工旧品校验名称/品牌/型号。 */
    private ValidatedTradeIn validateTradeInItem(EmployeeBusinessEntryRequest.TradeInItem item,
                                                 Long corpId, String rowPrefix) {
        if (item == null) throw BusinessException.of(rowPrefix + "信息不能为空");
        int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
        if (quantity < 1) throw BusinessException.of(rowPrefix + "数量必须为正整数");
        String remark = optionalText(item.getRemark(), MAX_REMARK_LENGTH, rowPrefix + "备注");
        if (item.getProductId() != null && item.getProductId() > 0) {
            ScmBaseDataClient.MaterialOption material;
            try {
                material = scmClient.requireMaterial(corpId, item.getProductId());
            } catch (BusinessException e) {
                throw BusinessException.of(rowPrefix + e.getMessage());
            }
            String name = str(material.name());
            if (name.isEmpty()) {
                name = (str(material.brand()) + " " + str(material.model())).trim();
            }
            if (name.isEmpty()) {
                throw BusinessException.of(rowPrefix + "旧品名称不能为空");
            }
            if (!OLD_GOODS_BRAND.equals(str(material.brand()).trim())) {
                throw BusinessException.of(rowPrefix + "旧品只能选择品牌为「旧品」的商品");
            }
            return new ValidatedTradeIn(material.id(), str(material.number()),
                    truncate(name, MAX_TRADE_IN_NAME_LENGTH),
                    truncate(str(material.brand()), MAX_TRADE_IN_BRAND_LENGTH),
                    truncate(str(material.model()), MAX_TRADE_IN_MODEL_LENGTH),
                    truncate(str(material.category()), MAX_TRADE_IN_BRAND_LENGTH),
                    quantity, truncate(str(material.unit()), MAX_TRADE_IN_UNIT_LENGTH),
                    remark, false);
        }
        String name = requireText(item.getProductName(), rowPrefix + "旧品名称",
                MAX_TRADE_IN_NAME_LENGTH);
        String brand = optionalText(item.getBrand(), MAX_TRADE_IN_BRAND_LENGTH,
                rowPrefix + "品牌");
        String model = optionalText(item.getModel(), MAX_TRADE_IN_MODEL_LENGTH,
                rowPrefix + "型号");
        String category = optionalText(item.getCategory(), MAX_TRADE_IN_BRAND_LENGTH,
                rowPrefix + "品类");
        String unit = optionalText(item.getUnit(), MAX_TRADE_IN_UNIT_LENGTH,
                rowPrefix + "单位");
        return new ValidatedTradeIn(null, null, name, brand, model, category,
                quantity, unit, remark, true);
    }

    /** 选填文本：去首尾空白、超长拒绝、空串归 null。 */
    private static String optionalText(String raw, int maxLength, String label) {
        String value = str(raw);
        if (value.isEmpty()) return null;
        if (value.length() > maxLength) {
            throw BusinessException.of(label + "不能超过" + maxLength + "个字符");
        }
        return value;
    }

    /**
     * 经办人：本人始终在列且不可移除；其他人员必须属于当前组织且在职。
     * 手机号取 sys_user.username（员工登录账号即手机号）。
     */
    private List<AgentInfo> validateAgents(List<Long> requestedIds, EmployeeContext ctx) {
        List<AgentInfo> agents = new ArrayList<>();
        agents.add(new AgentInfo(ctx.employeeUserId(), ctx.employeeName(), ctx.employeePhone()));
        LinkedHashSet<Long> requested = new LinkedHashSet<>();
        if (requestedIds != null) {
            for (Long id : requestedIds) {
                if (id != null && id > 0) requested.add(id);
            }
        }
        requested.remove(ctx.employeeUserId());
        if (requested.isEmpty()) return agents;
        if (requested.size() + 1 > MAX_AGENTS) {
            throw BusinessException.of("单笔业务最多选择" + MAX_AGENTS + "名经办人");
        }
        agents.addAll(findCorpAgents(requested, ctx.corpId()));
        return agents;
    }

    /** 按 ID 回查当前组织在职员工，返回顺序与请求一致；有缺失即拒绝。 */
    private List<AgentInfo> findCorpAgents(Set<Long> requested, Long corpId) {
        String placeholders = String.join(",",
                Collections.nCopies(requested.size(), "?"));
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT u.id AS userId, u.real_name AS realName, u.username, u.phone "
                            + "FROM ai_platform.sys_user u "
                            + "WHERE u.status = 1 AND u.delete_flag = 0 "
                            + "AND (u.corp_id = ? OR EXISTS ("
                            + "  SELECT 1 FROM ai_platform.sys_user_corp uc "
                            + "  WHERE uc.user_id = u.id AND uc.corp_id = ? "
                            + "  AND uc.status = 1 AND uc.delete_flag = 0)) "
                            + "AND u.id IN (" + placeholders + ")",
                    buildAgentQueryArgs(corpId, requested));
        } catch (Exception e) {
            log.warn("校验经办人失败 corpId={}: {}", corpId, e.getMessage());
            throw BusinessException.of("经办人信息查询失败，请稍后重试");
        }
        Map<Long, AgentInfo> found = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long userId = row.get("userId") instanceof Number n ? n.longValue() : null;
            String username = str(row.get("username"));
            String phone = username.isEmpty() ? str(row.get("phone")) : username;
            if (userId == null || phone.isEmpty()) continue;
            String name = str(row.get("realName"));
            found.put(userId, new AgentInfo(userId, name.isEmpty() ? phone : name, phone));
        }
        if (found.size() != requested.size()) {
            throw BusinessException.of("部分经办人不属于当前组织或已停用，请重新选择");
        }
        List<AgentInfo> result = new ArrayList<>();
        for (Long id : requested) result.add(found.get(id));
        return result;
    }

    /** 查询参数：组织ID 出现两次（sys_user.corp_id 与 sys_user_corp.corp_id），后接员工ID。 */
    private static Object[] buildAgentQueryArgs(Long corpId, Set<Long> requested) {
        List<Object> args = new ArrayList<>();
        args.add(corpId);
        args.add(corpId);
        args.addAll(requested);
        return args.toArray();
    }

    private BigDecimal validateCommissionRate(boolean special, BigDecimal raw, String prefix) {
        if (!special) return BigDecimal.ZERO;
        if (raw == null) throw BusinessException.of(prefix + "特批时提成比例不能为空");
        if (raw.signum() < 0 || raw.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw BusinessException.of(prefix + "提成比例必须在0-100之间");
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizePrice(BigDecimal raw, String prefix) {
        if (raw == null) throw BusinessException.of(prefix + "销售单价不能为空");
        BigDecimal price = raw.setScale(2, RoundingMode.HALF_UP);
        if (price.signum() <= 0) throw BusinessException.of(prefix + "销售单价必须大于0");
        return price;
    }

    private String normalizeDate(String raw) {
        String value = str(raw);
        if (value.isEmpty()) return LocalDate.now().toString();
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw BusinessException.of("市场收款日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    private static String requireText(String raw, String label) {
        return requireText(raw, label, MAX_TEXT_LENGTH);
    }

    private static String requireText(String raw, String label, int maxLength) {
        String value = str(raw);
        if (value.isEmpty()) throw BusinessException.of(label + "不能为空");
        if (value.length() > maxLength) {
            throw BusinessException.of(label + "不能超过" + maxLength + "个字符");
        }
        return value;
    }

    // ── 会员 / 落库 ──

    private MemberResolution resolveOrCreateMember(String phone, String customer, Long corpId) {
        try {
            // 与导入链路一致：先在本组织燃气公司范围的燃气用户中按手机号匹配
            // （可拿到燃气用户已关联的 platform_member_id，即使 sys_member 手机号不同），
            // 再走全局 uk_phone 复用，最后自动建会员。
            String gasCompanyId = memberMatcher.resolveGasCompanyId(corpId);
            Map<String, Long> gasMembers = memberMatcher.memberIdByPhone(
                    List.of(phone), gasCompanyId);
            Long gasMember = gasMembers.get(phone);
            if (gasMember != null) return new MemberResolution(gasMember, false);

            Long existing = memberMatcher.findMemberIdByPhone(phone);
            if (existing != null) return new MemberResolution(existing, false);
            Long created = memberMatcher.createMember(phone, customer, corpId,
                    "员工手工录入自动创建");
            return new MemberResolution(created, true);
        } catch (IllegalStateException e) {
            throw BusinessException.of(e.getMessage());
        }
    }

    private OpsBusinessData buildEntity(EmployeeContext ctx, Validated v, ValidatedProduct p,
                                        AgentInfo agent, String batchNo, String orderNo,
                                        String businessGroupNo,
                                        Long memberId, CommunityInfo community) {
        OpsBusinessData entity = new OpsBusinessData();
        entity.setCorpId(ctx.corpId());
        entity.setOperatorId(ctx.corpId());
        entity.setBatchNo(batchNo);
        entity.setOrderNo(orderNo);
        entity.setBusinessGroupNo(businessGroupNo);
        entity.setCompany(ctx.company());
        entity.setCustomer(v.customer());
        entity.setCustomerPhone(v.phone());
        entity.setPlatformMemberId(memberId);
        entity.setCommunityId(community.id());
        entity.setCommunityName(community.name());
        entity.setDetailedAddress(v.detailAddress());
        entity.setBrand(p.brand());
        entity.setCategory(p.categoryKey());
        entity.setModel(p.model());
        entity.setSalePrice(p.price());
        entity.setSaleQuantity(p.qty());
        entity.setReceiptAmount(p.amount());
        // 以旧换新：收款金额仍为销售额（提成基数不变），抵扣后金额用于应付/展示
        entity.setIsTradeIn(p.tradeIn() ? "是" : "否");
        entity.setDeductionAmount(p.deduction());
        entity.setTradeInPayableAmount(p.amount().subtract(p.deduction()).max(BigDecimal.ZERO));
        // 底价统一取产品售价表（制单已强制要求配置），保证提成分档与售价配置同源
        entity.setRetailFloorPrice(p.retailFloorPrice());
        entity.setGroupLowestPrice(p.groupLowestPrice());
        // 销售日期取市场收款日期：员工业务数据列表按 sale_date 分月，避免新数据落入「未知」
        entity.setSaleDate(v.receiptDate());
        entity.setReceiptDate(v.receiptDate());
        entity.setReceiptNo(v.receiptNo());
        entity.setFinanceReceiptDate(null);
        entity.setAgent(agent.name());
        entity.setAgentPhone(agent.phone());
        entity.setIsSpecial(p.special() ? "是" : "否");
        entity.setCommissionRate(p.rate());
        entity.setStandardSalePrice(p.standardPrice());
        entity.setSpecialFileId(p.specialFileId());
        entity.setSpecialFileUrl(p.specialFileUrl());
        entity.setSpecialFileName(p.specialFileName());
        entity.setPaymentMethod(v.paymentMethod());
        entity.setVoucherUrl(v.voucherUrl());
        // 备注同时写子表：导出「备注」列读取的是子表 remark，保证手工单与导入单口径一致
        entity.setRemark(v.remark());
        entity.setCalcStatus(INITIAL_CALC_STATUS);
        entity.setDeleteFlag(0);
        return entity;
    }

    /**
     * 小区必填；按 id + corp 校验归属并取名称（不接受前端传来的名称，防止跨租户引用）。
     * 小区名称落库为冗余字段，供列表/导出/出库打印直接展示。
     */
    private CommunityInfo resolveCommunity(Long communityId, Long corpId) {
        if (communityId == null) return new CommunityInfo(null, null);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT community_name FROM ai_platform.sys_community "
                        + "WHERE id = ? AND corp_id = ? AND delete_flag = 0 LIMIT 1",
                communityId, corpId);
        if (rows.isEmpty()) {
            throw BusinessException.of("所选小区不属于当前组织，请重新选择");
        }
        String name = str(rows.get(0).get("community_name"));
        if (name.isEmpty()) {
            throw BusinessException.of("小区名称为空，请重新选择");
        }
        return new CommunityInfo(communityId, name);
    }

    private void fillProductOptions(Map<String, Object> result, Long corpId) {
        try {
            ScmBaseDataClient.ScmOptions opts = scmClient.options(corpId);
            result.put("brands", opts.brands());
            result.put("categories", opts.categories());
            result.put("materials", opts.materials());
            boolean empty = opts.categories().isEmpty() || opts.materials().isEmpty();
            result.put("productError", empty
                    ? "供应链基础资料为空，请联系管理员在供应链管理模块同步品牌/商品类别/商品信息"
                    : null);
        } catch (Exception e) {
            log.warn("员工手工录入加载供应链基础资料失败 corpId={}: {}", corpId, e.getMessage());
            result.put("brands", List.of());
            result.put("categories", List.of());
            result.put("materials", List.of());
            result.put("productError", e.getMessage() != null
                    ? e.getMessage() : "供应链基础资料加载失败，请稍后重试");
        }
    }

    // ── helpers ──

    private static String normalizePhone(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").replace("-", "").trim();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static int num(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    // ── records ──

    private record EmployeeIdentity(Long corpId, String name, String phone) {}

    private record EmployeeContext(Long corpId, String corpName, String company,
                                   String employeeName, String employeePhone,
                                   Long employeeUserId, Long regionId, String regionName) {}

    private record CorpInfo(String corpName, String company, Long regionId, String regionName) {}

    private record Validated(String phone, String customer,
                             List<ValidatedProduct> products, List<AgentInfo> agents,
                             String receiptDate, String receiptNo, String paymentMethod,
                             String voucherUrl, String receiptImageUrl,
                             String paymentVoucherUrls, String contractAttachmentUrls,
                             List<Long> attachmentIds, Long communityId, String detailAddress,
                             String remark, String paymentType, Integer isFullPayment,
                             BigDecimal depositAmount, BigDecimal balanceAmount) {}

    private record PaymentTerms(String paymentType, Integer isFullPayment,
                                BigDecimal depositAmount, BigDecimal balanceAmount) {}

    private record ValidatedProduct(Long productId, String brand, String categoryKey, String model,
                                    BigDecimal price, int qty, BigDecimal amount,
                                    BigDecimal rate, BigDecimal standardPrice, boolean special,
                                    Long specialFileId, String specialFileUrl,
                                    String specialFileName, BigDecimal retailFloorPrice,
                                    BigDecimal groupLowestPrice,
                                    boolean tradeIn, BigDecimal deduction,
                                    List<ValidatedTradeIn> tradeInItems) {}

    private record ValidatedTradeIn(Long productId, String materialNumber, String productName,
                                    String brand, String model, String category, int quantity,
                                    String unit, String remark, boolean manual) {}

    private record AgentInfo(Long userId, String name, String phone) {}

    private record MemberResolution(Long id, boolean created) {}

    private record CommunityInfo(Long id, String name) {}
}
