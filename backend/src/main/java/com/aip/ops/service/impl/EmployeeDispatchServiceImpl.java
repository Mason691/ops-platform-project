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

    /** 可施工品类：燃气灶/抽油烟机/热水器/壁挂炉。 */
    private static final Set<String> CONSTRUCTION_CATEGORIES = Set.of("1", "2", "3", "4");
    /** 户内美装品类：可免财务核对直接派工。 */
    private static final Set<String> HOME_DECOR_CATEGORIES = Set.of("6", "8", "9");
    /** 进行中的派工状态（同一订单不可重复派工）。 */
    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "ACCEPTED", "PROCESSING");

    private final OpsDispatchOrderMapper dispatchMapper;
    private final OpsDispatchOrderItemMapper itemMapper;
    private final OpsBusinessOrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;
    private final JwtTokenInspector jwtTokenInspector;
    private final ScmStockOutClient scmClient;
    private final DispatchMessageService messageService;
    private final DispatchOrderService dispatchOrderService;
    private final FinancePermissionService financePermissionService;

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

    // ═══ 员工 H5 发起派工（任何员工；仅限本组织订单） ═══

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
        EmployeeIdentity emp = assertEmployee();
        assertCanDispatch();
        int size = Math.min(Math.max(pageSize, 1), 50);
        int offset = (Math.max(pageNum, 1) - 1) * size;

        StringBuilder where = new StringBuilder(
                " FROM ops_business_order o WHERE o.delete_flag = 0 AND o.corp_id = ?"
                        + " AND ((o.finance_check_time IS NOT NULL AND NOT EXISTS ("
                        + "   SELECT 1 FROM ops_business_order_item u"
                        + "   WHERE u.order_id = o.id AND u.delete_flag = 0"
                        + "     AND u.finance_check_time IS NULL))"
                        + "   OR EXISTS ("
                        + "   SELECT 1 FROM ops_business_order_item h"
                        + "   WHERE h.order_id = o.id AND h.delete_flag = 0"
                        + "     AND h.category IN ('6','8','9')))"
                        + " AND EXISTS (SELECT 1 FROM ops_business_order_item i"
                        + "   WHERE i.order_id = o.id AND i.delete_flag = 0 AND i.sale_quantity > 0)"
                        + " AND NOT EXISTS (SELECT 1 FROM ops_dispatch_order d"
                        + "   WHERE d.source_order_id = o.id AND d.corp_id = o.corp_id"
                        + "     AND d.delete_flag = 0"
                        + "     AND d.status IN ('PENDING','ACCEPTED','PROCESSING'))");
        List<Object> args = new ArrayList<>();
        args.add(emp.corpId());
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            where.append(" AND (o.order_no LIKE ? OR o.customer LIKE ?"
                    + " OR o.customer_phone LIKE ? OR o.receipt_no LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + where, Long.class, args.toArray());
        args.add(size);
        args.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT o.id, o.order_no AS orderNo, o.receipt_no AS receiptNo,"
                        + " o.customer, o.customer_phone AS customerPhone,"
                        + " o.community_id AS communityId, o.community_name AS communityName,"
                        + " o.detailed_address AS detailedAddress,"
                        + " DATE_FORMAT(o.finance_check_time, '%Y-%m-%d %H:%i') AS financeCheckTime,"
                        + " (SELECT COUNT(*) FROM ops_business_order_item i"
                        + "   WHERE i.order_id = o.id AND i.delete_flag = 0"
                        + "     AND i.sale_quantity > 0 AND i.source_return_id IS NULL"
                        + "     AND i.category IN ('1','2','3','4')) AS constructionItemCount"
                        + where + " ORDER BY o.id DESC LIMIT ? OFFSET ?", args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", rows);
        result.put("total", total == null ? 0L : total);
        result.put("size", size);
        result.put("current", Math.max(pageNum, 1));
        return result;
    }

    @Override
    public Map<String, Object> dispatchableOrder(Long orderId) {
        EmployeeIdentity emp = assertEmployee();
        assertCanDispatch();
        OpsBusinessOrder order = orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        if (!emp.corpId().equals(order.getCorpId())) {
            throw BusinessException.forbidden("只能查看本组织的业务订单");
        }
        List<OpsBusinessData> rows = orderMapper.selectItems(orderId);
        boolean financeChecked = order.getFinanceCheckTime() != null
                && rows.stream().allMatch(r -> r.getFinanceCheckTime() != null);
        boolean homeDecor = rows.stream()
                .anyMatch(r -> HOME_DECOR_CATEGORIES.contains(r.getCategory()));
        OpsDispatchOrder active = dispatchMapper.selectOne(
                new LambdaQueryWrapper<OpsDispatchOrder>()
                        .eq(OpsDispatchOrder::getSourceOrderId, orderId)
                        .eq(OpsDispatchOrder::getCorpId, order.getCorpId())
                        .in(OpsDispatchOrder::getStatus, ACTIVE_STATUSES)
                        .orderByDesc(OpsDispatchOrder::getId)
                        .last("LIMIT 1"));
        boolean dispatchable = (financeChecked || homeDecor)
                && !rows.isEmpty() && active == null;

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", order.getId());
        view.put("orderNo", order.getOrderNo());
        view.put("receiptNo", order.getReceiptNo());
        view.put("customer", order.getCustomer());
        view.put("customerPhone", order.getCustomerPhone());
        view.put("communityId", order.getCommunityId());
        view.put("communityName", order.getCommunityName());
        view.put("detailedAddress", order.getDetailedAddress());
        view.put("corpId", order.getCorpId());
        view.put("financeCheckTime", order.getFinanceCheckTime());
        view.put("financeChecked", financeChecked);
        view.put("homeDecor", homeDecor);
        view.put("dispatchable", dispatchable);
        view.put("activeDispatchNo", active == null ? null : active.getDispatchNo());
        view.put("products", groupConstructionProducts(rows));
        return view;
    }

    @Override
    public List<Map<String, Object>> dispatchAssignees() {
        EmployeeIdentity emp = assertEmployee();
        assertCanDispatch();
        return dispatchOrderService.assignees(emp.corpId());
    }

    @Override
    public Map<String, Object> createDispatch(DispatchCreateRequest request) {
        EmployeeIdentity emp = assertEmployee();
        assertCanDispatch();
        Map<String, Object> result = dispatchOrderService.createForCorp(request, emp.corpId());
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

    /** 按「品类+品牌+型号+单价」聚合可施工商品，同一分组共享 itemIds。 */
    private List<Map<String, Object>> groupConstructionProducts(List<OpsBusinessData> rows) {
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData row : rows) {
            if (row.getSaleQuantity() == null || row.getSaleQuantity() <= 0) continue;
            if (row.getSourceReturnId() != null) continue;
            if (!CONSTRUCTION_CATEGORIES.contains(row.getCategory())) continue;
            String key = Objects.toString(row.getCategory(), "") + "|"
                    + Objects.toString(row.getBrand(), "") + "|"
                    + Objects.toString(row.getModel(), "") + "|"
                    + Objects.toString(row.getSalePrice(), "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> products = new ArrayList<>();
        groups.forEach((key, group) -> {
            OpsBusinessData first = group.get(0);
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("productKey", key);
            product.put("itemIds", group.stream().map(OpsBusinessData::getId).toList());
            product.put("brand", first.getBrand());
            product.put("category", first.getCategory());
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
