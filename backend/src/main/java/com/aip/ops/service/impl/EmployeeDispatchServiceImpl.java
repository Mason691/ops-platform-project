package com.aip.ops.service.impl;

import com.aip.ops.client.ScmStockOutClient;
import com.aip.ops.dto.DispatchCreateRequest;
import com.aip.ops.dto.ScmDispatchOutboundRequest;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsDispatchOrder;
import com.aip.ops.entity.OpsDispatchOrderItem;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.mapper.OpsDispatchOrderItemMapper;
import com.aip.ops.mapper.OpsDispatchOrderMapper;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.DispatchMessageService;
import com.aip.ops.service.DispatchOrderService;
import com.aip.ops.service.EmployeeDispatchService;
import com.aip.ops.service.FinancePermissionService;
import com.aip.ops.service.GasMemberMatcher;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 员工端工单实现。
 *
 * <p>授权边界：工单只能被指派员工本人操作（assignee_user_id = 当前员工），
 * 不依赖当前组织切换；列表/详情/状态流转均带 assignee 条件。员工 JWT 与
 * 会员 SSO JWT 的 sub 语义不同，这里用 {@link JwtTokenInspector} 拒绝 SSO 令牌。</p>
 *
 * <p>接单幂等：单实例内存锁 + 状态条件更新；SCM 内部接口按 dispatchId 幂等，
 * 因此出库单创建成功但本地更新失败时，重试会返回同一张出库单，不会重复出库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeDispatchServiceImpl implements EmployeeDispatchService {

    /** 进行中的派工状态（同一订单不可重复派工）。 */
    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "ACCEPTED", "PROCESSING");
    /** PRODUCT_CATEGORY 字典 type_id（品类 key → 中文名），与会员台账口径一致。 */
    private static final long CATEGORY_DICT_TYPE_ID = 9L;
    /** 字典不可达时的品类兜底（仅已在字典中的 key；原始中文品类直接透传）。 */
    private static final Map<String, String> FALLBACK_CATEGORY_LABELS = Map.ofEntries(
            Map.entry("1", "燃气灶"), Map.entry("2", "抽油烟机"),
            Map.entry("3", "热水器"), Map.entry("4", "壁挂炉"),
            Map.entry("5", "壁挂炉以旧换新销售"), Map.entry("6", "户内美装销售"),
            Map.entry("7", "定尺波纹管"), Map.entry("8", "户内美装工程"),
            Map.entry("9", "户内美装运维"), Map.entry("10", "保险"),
            Map.entry("11", "美装管道整改"), Map.entry("12", "美装安全评估"),
            Map.entry("13", "厨具"), Map.entry("15", "商业报警器"),
            Map.entry("qimi", "安全评估技术服务"));

    private final OpsDispatchOrderMapper dispatchMapper;
    private final OpsDispatchOrderItemMapper itemMapper;
    private final OpsBusinessOrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;
    private final JwtTokenInspector jwtTokenInspector;
    private final ScmStockOutClient scmClient;
    private final DispatchMessageService messageService;
    private final DispatchOrderService dispatchOrderService;
    private final FinancePermissionService financePermissionService;
    private final DataScopeService dataScopeService;

    /** 单实例按工单串行化接单；多实例部署需改为分布式锁/状态 CAS。 */
    private final ConcurrentHashMap<Long, Object> acceptLocks = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> listPending(int pageNum, int pageSize) {
        EmployeeIdentity emp = assertEmployee();
        return page(emp, "PENDING", pageNum, pageSize);
    }

    @Override
    public Map<String, Object> listMine(int pageNum, int pageSize, String status) {
        EmployeeIdentity emp = assertEmployee();
        return page(emp, status, pageNum, pageSize);
    }

    private Map<String, Object> page(EmployeeIdentity emp, String status,
                                     int pageNum, int pageSize) {
        LambdaQueryWrapper<OpsDispatchOrder> qw = new LambdaQueryWrapper<OpsDispatchOrder>()
                .eq(OpsDispatchOrder::getAssigneeUserId, emp.userId());
        if (status != null && !status.isBlank()) {
            qw.eq(OpsDispatchOrder::getStatus, status);
        }
        qw.orderByAsc(OpsDispatchOrder::getStatus)
                .orderByAsc(OpsDispatchOrder::getExpectTime)
                .orderByDesc(OpsDispatchOrder::getId);
        Page<OpsDispatchOrder> page = dispatchMapper.selectPage(
                new Page<>(Math.max(1, pageNum), Math.max(1, pageSize)), qw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        result.put("size", page.getSize());
        result.put("current", page.getCurrent());
        return result;
    }

    @Override
    public Map<String, Object> detail(Long id) {
        EmployeeIdentity emp = assertEmployee();
        OpsDispatchOrder dispatch = requireOwned(id, emp.userId());
        messageService.markRead(id, emp.userId());
        return view(dispatch);
    }

    @Override
    public Map<String, Object> accept(Long id) {
        EmployeeIdentity emp = assertEmployee();
        Object lock = acceptLocks.computeIfAbsent(id, k -> new Object());
        synchronized (lock) {
            OpsDispatchOrder dispatch = requireOwned(id, emp.userId());
            if (isAccepted(dispatch)) return view(dispatch);
            if (!"PENDING".equals(dispatch.getStatus())) {
                throw BusinessException.of("当前工单状态不可接单：" + statusName(dispatch.getStatus()));
            }
            // 先占领为 ACCEPTING：取消接口只允许 PENDING，避免“取消与接单”竞态生成孤儿出库单
            if (!claim(dispatch.getId(), emp.userId())) {
                OpsDispatchOrder latest = requireOwned(id, emp.userId());
                if (isAccepted(latest)) return view(latest);
                throw BusinessException.of("工单正在处理中，请稍后刷新");
            }
            try {
                List<OpsDispatchOrderItem> items = loadItems(id);
                ScmStockOutClient.Result outbound = createOutboundIfNeeded(dispatch, items, emp);
                finishAccepted(dispatch.getId(), emp.userId(), outbound);
            } catch (RuntimeException e) {
                releaseClaim(dispatch.getId(), emp.userId());
                throw e;
            }
            return view(requireOwned(id, emp.userId()));
        }
    }

    /** PENDING → ACCEPTING 原子占领；失败说明已被其它请求处理。 */
    private boolean claim(Long id, Long userId) {
        return dispatchMapper.update(null, new LambdaUpdateWrapper<OpsDispatchOrder>()
                .eq(OpsDispatchOrder::getId, id)
                .eq(OpsDispatchOrder::getAssigneeUserId, userId)
                .eq(OpsDispatchOrder::getStatus, "PENDING")
                .set(OpsDispatchOrder::getStatus, "ACCEPTING")
                .set(OpsDispatchOrder::getAcceptLockTime, LocalDateTime.now())) == 1;
    }

    /** ACCEPTING → ACCEPTED；SCM 失败时由调用方 releaseClaim 回退 PENDING。 */
    private void finishAccepted(Long id, Long userId, ScmStockOutClient.Result outbound) {
        LambdaUpdateWrapper<OpsDispatchOrder> update = new LambdaUpdateWrapper<OpsDispatchOrder>()
                .eq(OpsDispatchOrder::getId, id)
                .eq(OpsDispatchOrder::getAssigneeUserId, userId)
                .eq(OpsDispatchOrder::getStatus, "ACCEPTING")
                .set(OpsDispatchOrder::getStatus, "ACCEPTED")
                .set(OpsDispatchOrder::getAcceptTime, LocalDateTime.now())
                .set(OpsDispatchOrder::getAcceptLockTime, null);
        if (outbound != null) {
            update.set(OpsDispatchOrder::getStockOutId, outbound.id())
                    .set(OpsDispatchOrder::getStockOutNo, outbound.billNo());
        }
        if (dispatchMapper.update(null, update) == 0) {
            OpsDispatchOrder latest = requireOwned(id, userId);
            if (isAccepted(latest)) return; // 超时恢复后重试：SCM 幂等返回同一张出库单
            throw BusinessException.of("接单失败，请刷新后重试");
        }
    }

    private void releaseClaim(Long id, Long userId) {
        dispatchMapper.update(null, new LambdaUpdateWrapper<OpsDispatchOrder>()
                .eq(OpsDispatchOrder::getId, id)
                .eq(OpsDispatchOrder::getAssigneeUserId, userId)
                .eq(OpsDispatchOrder::getStatus, "ACCEPTING")
                .set(OpsDispatchOrder::getStatus, "PENDING")
                .set(OpsDispatchOrder::getAcceptLockTime, null));
    }

    /** 施工类型生成出库单；随访类型无商品，直接接单。 */
    private ScmStockOutClient.Result createOutboundIfNeeded(OpsDispatchOrder dispatch,
                                                            List<OpsDispatchOrderItem> items,
                                                            EmployeeIdentity emp) {
        if (DispatchOrderServiceImpl.TYPE_VISIT.equals(dispatch.getDispatchType())) {
            return null;
        }
        if (items.isEmpty()) {
            throw BusinessException.of("工单没有施工商品，无法生成销售出库单");
        }
        ScmDispatchOutboundRequest request = new ScmDispatchOutboundRequest(
                dispatch.getCorpId(), dispatch.getId(), dispatch.getDispatchNo(),
                dispatch.getSourceOrderId(), dispatch.getSourceOrderNo(),
                emp.userId(), emp.name(),
                dispatch.getCustomerName(), dispatch.getCustomerPhone(),
                dispatch.getPlatformMemberId(), dispatch.getCommunityName(),
                dispatch.getDetailedAddress(),
                items.stream().map(i -> new ScmDispatchOutboundRequest.Item(
                        i.getSourceItemId(), i.getBrand(), i.getCategory(), i.getModel(),
                        i.getSaleQuantity(), i.getSalePrice())).toList());
        return scmClient.createFromDispatch(request);
    }

    @Override
    public Map<String, Object> start(Long id) {
        EmployeeIdentity emp = assertEmployee();
        transition(id, emp.userId(), Set.of("ACCEPTED"), "PROCESSING",
                u -> u.set(OpsDispatchOrder::getStartTime, LocalDateTime.now()));
        return view(requireOwned(id, emp.userId()));
    }

    @Override
    public Map<String, Object> complete(Long id) {
        EmployeeIdentity emp = assertEmployee();
        transition(id, emp.userId(), Set.of("ACCEPTED", "PROCESSING"), "COMPLETED",
                u -> u.set(OpsDispatchOrder::getCompleteTime, LocalDateTime.now()));
        return view(requireOwned(id, emp.userId()));
    }

    @Override
    public Map<String, Object> reject(Long id, String reason) {
        EmployeeIdentity emp = assertEmployee();
        if (reason == null || reason.isBlank()) {
            throw BusinessException.of("请填写退回原因");
        }
        transition(id, emp.userId(), Set.of("PENDING"), "REJECTED", u -> u
                .set(OpsDispatchOrder::getRejectTime, LocalDateTime.now())
                .set(OpsDispatchOrder::getRejectReason, truncate(reason.trim(), 500)));
        return view(requireOwned(id, emp.userId()));
    }

    // ═══ 员工 H5 发起派工（任何员工；可管辖组织范围） ═══

    @Override
    public Map<String, Object> canDispatch() {
        EmployeeIdentity emp = assertEmployee();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canDispatch", financePermissionService.canCreateDispatch());
        result.put("corpId", emp.corpId());
        return result;
    }

    @Override
    public Map<String, Object> listDispatchableOrders(int pageNum, int pageSize, String keyword) {
        assertEmployee();
        assertCanDispatch();
        int size = Math.min(Math.max(pageSize, 1), 50);
        int current = Math.max(pageNum, 1);
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && visible.isEmpty()) {
            return emptyPage(current, size);
        }

        StringBuilder where = new StringBuilder(
                " FROM ops_business_order o WHERE o.delete_flag = 0"
                        + " AND o.finance_check_time IS NOT NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM ops_business_order_item u"
                        + "   WHERE u.order_id = o.id AND u.delete_flag = 0"
                        + "     AND u.finance_check_time IS NULL)"
                        + " AND EXISTS (SELECT 1 FROM ops_business_order_item i"
                        + "   WHERE i.order_id = o.id AND i.delete_flag = 0)"
                        + " AND NOT EXISTS (SELECT 1 FROM ops_dispatch_order d"
                        + "   WHERE d.source_order_id = o.id AND d.corp_id = o.corp_id"
                        + "     AND d.delete_flag = 0"
                        + "     AND d.status IN ('PENDING','ACCEPTED','PROCESSING'))");
        if (visible != null) {
            where.append(" AND o.corp_id IN (")
                    .append(visible.stream().map(String::valueOf)
                            .collect(Collectors.joining(",")))
                    .append(")");
        }
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            where.append(" AND (o.order_no LIKE ? OR o.customer LIKE ?"
                    + " OR o.customer_phone LIKE ? OR o.receipt_no LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
                "SELECT o.id, o.order_no AS orderNo, o.batch_no AS batchNo,"
                        + " o.receipt_no AS receiptNo, o.customer,"
                        + " o.customer_phone AS customerPhone, o.community_id AS communityId,"
                        + " o.community_name AS communityName,"
                        + " o.detailed_address AS detailedAddress, o.company,"
                        + " o.corp_id AS corpId,"
                        + " COALESCE(NULLIF(o.agent_names, ''),"
                        + "   (SELECT GROUP_CONCAT(DISTINCT i.agent ORDER BY i.agent SEPARATOR '、')"
                        + "    FROM ops_business_order_item i WHERE i.order_id = o.id"
                        + "      AND i.delete_flag = 0 AND i.agent IS NOT NULL AND i.agent <> ''))"
                        + "   AS agentNames,"
                        + " o.total_amount AS totalAmount,"
                        + " o.finance_check_user_name AS financeCheckUserName,"
                        + " DATE_FORMAT(o.finance_check_time, '%Y-%m-%d %H:%i') AS financeCheckTime,"
                        + " (SELECT COUNT(DISTINCT CONCAT(IFNULL(i.category,''), '|',"
                        + "    IFNULL(i.brand,''), '|', IFNULL(i.model,'')))"
                        + "  FROM ops_business_order_item i WHERE i.order_id = o.id"
                        + "   AND i.delete_flag = 0 AND i.sale_quantity > 0"
                        + "   AND i.source_return_id IS NULL) AS productCount"
                        + where + " ORDER BY o.id DESC", args.toArray());

        List<Long> orderIds = candidates.stream()
                .map(r -> longVal(r, "id")).filter(Objects::nonNull).toList();
        Set<Long> outbound = orderIds.isEmpty() ? Set.of() : scmClient.outboundOrderIds(orderIds);
        List<Map<String, Object>> filtered = candidates.stream()
                .filter(r -> !outbound.contains(longVal(r, "id"))).toList();
        int total = filtered.size();
        int from = Math.min((current - 1) * size, total);
        int to = Math.min(from + size, total);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", filtered.subList(from, to));
        result.put("total", (long) total);
        result.put("size", size);
        result.put("current", current);
        return result;
    }

    @Override
    public Map<String, Object> dispatchableOrder(Long orderId) {
        assertEmployee();
        assertCanDispatch();
        OpsBusinessOrder order = orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && !visible.contains(order.getCorpId())) {
            throw BusinessException.forbidden("无权查看该业务订单");
        }
        List<OpsBusinessData> rows = orderMapper.selectItems(orderId);
        boolean financeChecked = order.getFinanceCheckTime() != null
                && rows.stream().allMatch(r -> r.getFinanceCheckTime() != null);
        OpsDispatchOrder active = dispatchMapper.selectOne(
                new LambdaQueryWrapper<OpsDispatchOrder>()
                        .eq(OpsDispatchOrder::getSourceOrderId, orderId)
                        .eq(OpsDispatchOrder::getCorpId, order.getCorpId())
                        .in(OpsDispatchOrder::getStatus, ACTIVE_STATUSES)
                        .orderByDesc(OpsDispatchOrder::getId)
                        .last("LIMIT 1"));
        boolean hasOutbound = scmClient.outboundOrderIds(List.of(orderId)).contains(orderId);
        boolean dispatchable = financeChecked && !rows.isEmpty()
                && active == null && !hasOutbound;

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", order.getId());
        view.put("orderNo", order.getOrderNo());
        view.put("batchNo", order.getBatchNo());
        view.put("receiptNo", order.getReceiptNo());
        view.put("customer", order.getCustomer());
        view.put("customerPhone", order.getCustomerPhone());
        view.put("communityId", order.getCommunityId());
        view.put("communityName", order.getCommunityName());
        view.put("detailedAddress", order.getDetailedAddress());
        view.put("company", order.getCompany());
        view.put("corpId", order.getCorpId());
        view.put("agentNames", resolveAgentNames(order, rows));
        view.put("totalAmount", order.getTotalAmount());
        view.put("financeCheckUserName", order.getFinanceCheckUserName());
        view.put("financeCheckTime", order.getFinanceCheckTime());
        view.put("financeChecked", financeChecked);
        view.put("hasOutbound", hasOutbound);
        view.put("dispatchable", dispatchable);
        view.put("activeDispatchNo", active == null ? null : active.getDispatchNo());
        view.put("products", groupSaleProducts(rows));
        return view;
    }

    @Override
    public List<Map<String, Object>> dispatchAssignees() {
        assertEmployee();
        assertCanDispatch();
        return dispatchOrderService.assignees(null);
    }

    @Override
    public Map<String, Object> createDispatch(DispatchCreateRequest request) {
        assertEmployee();
        assertCanDispatch();
        Map<String, Object> result = dispatchOrderService.create(request);
        Object id = result.get("id");
        if (id instanceof Number n) {
            messageService.sendDispatchNoticeAsync(n.longValue());
        }
        return result;
    }

    private void assertCanDispatch() {
        if (!financePermissionService.canCreateDispatch()) {
            throw BusinessException.forbidden("仅员工账号可发起派工");
        }
    }

    /**
     * 订单全部正数销售分录按「品类+品牌+型号」聚合（排除退货负数分录）。
     * 多经办人拆分行数量相同，取 MAX 合并，避免重复累加。
     */
    private List<Map<String, Object>> groupSaleProducts(List<OpsBusinessData> rows) {
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData row : rows) {
            if (row.getSaleQuantity() == null || row.getSaleQuantity() <= 0) continue;
            if (row.getSourceReturnId() != null) continue;
            String key = Objects.toString(row.getCategory(), "") + "|"
                    + Objects.toString(row.getBrand(), "") + "|"
                    + Objects.toString(row.getModel(), "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        Map<String, String> categoryLabels = loadCategoryLabels();
        List<Map<String, Object>> products = new ArrayList<>();
        groups.forEach((key, group) -> {
            OpsBusinessData first = group.get(0);
            String category = first.getCategory();
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("productKey", key);
            product.put("itemIds", group.stream().map(OpsBusinessData::getId).toList());
            product.put("brand", first.getBrand());
            product.put("category", category);
            product.put("categoryName", category == null
                    ? "" : categoryLabels.getOrDefault(category, category));
            product.put("model", first.getModel());
            product.put("salePrice", first.getSalePrice());
            product.put("saleQuantity", group.stream()
                    .mapToInt(r -> Math.max(1, r.getSaleQuantity())).max().orElse(1));
            product.put("productName", (Objects.toString(first.getBrand(), "") + " "
                    + Objects.toString(first.getModel(), "")).trim());
            products.add(product);
        });
        return products;
    }

    /** 品类 key → 中文名（PRODUCT_CATEGORY 字典，失败回退内置映射；原始中文品类透传）。 */
    private Map<String, String> loadCategoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>(FALLBACK_CATEGORY_LABELS);
        try {
            jdbcTemplate.queryForList(
                            "SELECT item_key, item_value FROM ai_platform.sys_dict_item"
                                    + " WHERE type_id = ? AND delete_flag = 0",
                            CATEGORY_DICT_TYPE_ID)
                    .forEach(row -> {
                        String key = str(row.get("item_key"));
                        String value = str(row.get("item_value"));
                        if (!key.isEmpty() && !value.isEmpty()) labels.put(key, value);
                    });
        } catch (Exception e) {
            log.warn("加载品类字典失败，使用兜底品类: {}", e.getMessage());
        }
        return labels;
    }

    /** 订单经办人：优先订单头聚合字段，缺失时从分录 distinct 汇总（展示/追溯用）。 */
    private String resolveAgentNames(OpsBusinessOrder order, List<OpsBusinessData> rows) {
        String names = order.getAgentNames();
        if (names != null && !names.isBlank()) return names;
        return rows.stream()
                .map(OpsBusinessData::getAgent)
                .filter(a -> a != null && !a.isBlank())
                .distinct()
                .collect(Collectors.joining("、"));
    }

    private Map<String, Object> emptyPage(int current, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", List.of());
        result.put("total", 0L);
        result.put("size", size);
        result.put("current", current);
        return result;
    }

    private static Long longVal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.longValue() : null;
    }

    private void transition(Long id, Long userId, Set<String> from,
                            String to, Consumer<LambdaUpdateWrapper<OpsDispatchOrder>> extra) {
        requireOwned(id, userId);
        LambdaUpdateWrapper<OpsDispatchOrder> update = new LambdaUpdateWrapper<OpsDispatchOrder>()
                .eq(OpsDispatchOrder::getId, id)
                .eq(OpsDispatchOrder::getAssigneeUserId, userId)
                .in(OpsDispatchOrder::getStatus, from)
                .set(OpsDispatchOrder::getStatus, to);
        extra.accept(update);
        if (dispatchMapper.update(null, update) == 0) {
            throw BusinessException.of("工单状态已变更，请刷新后重试");
        }
    }

    private OpsDispatchOrder requireOwned(Long id, Long userId) {
        OpsDispatchOrder dispatch = dispatchMapper.selectById(id);
        if (dispatch == null || Integer.valueOf(1).equals(dispatch.getDeleteFlag())) {
            throw BusinessException.notFound("派工单不存在");
        }
        if (!userId.equals(dispatch.getAssigneeUserId())) {
            throw BusinessException.forbidden("无权查看或操作该工单");
        }
        return dispatch;
    }

    private List<OpsDispatchOrderItem> loadItems(Long dispatchId) {
        return itemMapper.selectList(new LambdaQueryWrapper<OpsDispatchOrderItem>()
                .eq(OpsDispatchOrderItem::getDispatchId, dispatchId)
                .orderByAsc(OpsDispatchOrderItem::getId));
    }

    /** 校验当前请求确为员工 JWT（拒绝会员 SSO 令牌）。 */
    private EmployeeIdentity assertEmployee() {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            throw BusinessException.of("仅员工账号可查看工单");
        }
        Long corpId = SecurityUtils.getCurrentCorpId();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请选择所属公司后再查看工单");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, Object> user = loadPlatformUser(userId);
        String username = str(user.get("username"));
        String userPhone = str(user.get("phone"));
        String jwtUsername = SecurityUtils.getCurrentUser().getUsername();
        if (!username.equals(jwtUsername) && !userPhone.equals(jwtUsername)) {
            throw BusinessException.of("仅员工账号可查看工单");
        }
        String phone = GasMemberMatcher.PHONE_PATTERN.matcher(username).matches()
                ? username : userPhone;
        if (!GasMemberMatcher.PHONE_PATTERN.matcher(phone).matches()) {
            throw BusinessException.of("员工账号未绑定有效手机号，请联系管理员");
        }
        String name = str(user.get("real_name"));
        return new EmployeeIdentity(userId, corpId, name.isEmpty() ? username : name, phone);
    }

    private Map<String, Object> loadPlatformUser(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT username, real_name, phone, status, delete_flag "
                        + "FROM ai_platform.sys_user WHERE id = ? LIMIT 1", userId);
        if (rows.isEmpty()) {
            throw BusinessException.of("仅员工账号可查看工单");
        }
        Map<String, Object> row = rows.get(0);
        if (!"1".equals(str(row.get("status"))) || !"0".equals(str(row.get("delete_flag")))) {
            throw BusinessException.of("员工账号已被禁用，请联系管理员");
        }
        return row;
    }

    private Map<String, Object> view(OpsDispatchOrder d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", d.getId());
        map.put("dispatchNo", d.getDispatchNo());
        map.put("sourceOrderId", d.getSourceOrderId());
        map.put("sourceOrderNo", d.getSourceOrderNo());
        map.put("receiptNo", d.getReceiptNo());
        map.put("dispatchType", d.getDispatchType());
        map.put("customerName", d.getCustomerName());
        map.put("customerPhone", d.getCustomerPhone());
        map.put("communityName", d.getCommunityName());
        map.put("detailedAddress", d.getDetailedAddress());
        map.put("expectTime", d.getExpectTime());
        map.put("assigneeName", d.getAssigneeName());
        map.put("status", d.getStatus());
        map.put("dispatchByName", d.getDispatchByName());
        map.put("dispatchTime", d.getDispatchTime());
        map.put("acceptTime", d.getAcceptTime());
        map.put("startTime", d.getStartTime());
        map.put("completeTime", d.getCompleteTime());
        map.put("rejectReason", d.getRejectReason());
        map.put("cancelReason", d.getCancelReason());
        map.put("stockOutNo", d.getStockOutNo());
        map.put("remark", d.getRemark());
        map.put("items", loadItems(d.getId()));
        return map;
    }

    private static boolean isAccepted(OpsDispatchOrder d) {
        return "ACCEPTED".equals(d.getStatus())
                || "PROCESSING".equals(d.getStatus())
                || "COMPLETED".equals(d.getStatus());
    }

    private static String statusName(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "待接单";
            case "ACCEPTING" -> "接单处理中";
            case "ACCEPTED" -> "已接单";
            case "PROCESSING" -> "处理中";
            case "COMPLETED" -> "已完成";
            case "REJECTED" -> "已退回";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record EmployeeIdentity(Long userId, Long corpId, String name, String phone) {
    }
}
