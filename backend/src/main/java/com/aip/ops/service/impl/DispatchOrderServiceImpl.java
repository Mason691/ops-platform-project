package com.aip.ops.service.impl;

import com.aip.ops.client.ScmStockOutClient;
import com.aip.ops.component.CompanyCodeResolver;
import com.aip.ops.component.DispatchNoGenerator;
import com.aip.ops.dto.DispatchCreateRequest;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsDispatchMessage;
import com.aip.ops.entity.OpsDispatchOrder;
import com.aip.ops.entity.OpsDispatchOrderItem;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.mapper.OpsDispatchMessageMapper;
import com.aip.ops.mapper.OpsDispatchOrderItemMapper;
import com.aip.ops.mapper.OpsDispatchOrderMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.DispatchOrderService;
import com.aip.ops.service.FinancePermissionService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 派工单管理实现。
 *
 * <p>资格：来源订单已完成财务核对，且未产生销售出库单（派工自动出库 /
 * 业务数据手工出库 / 同收据多订单合并出库）；同一订单存在进行中派工时
 * 禁止重复派工。施工商品取订单全部正数销售分录（排除退货负数分录），
 * 按「品类+品牌+型号」合并展示；入户随访不关联商品且说明必填。</p>
 *
 * <p>ops 无自动租户拦截器，所有查询显式按 corp_id 与可见组织集合过滤；
 * 超管可见集合为 null（全量），空集合表示无任何权限。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchOrderServiceImpl implements DispatchOrderService {

    public static final String TYPE_CONSTRUCTION = "CONSTRUCTION";
    public static final String TYPE_VISIT = "VISIT";

    private static final Set<String> ACTIVE_STATUSES =
            Set.of("PENDING", "ACCEPTED", "PROCESSING");
    /** 家电类品类：普通商品单位显示「件」，仅家电四类显示「台」。 */
    private static final Set<String> APPLIANCE_CATEGORIES = Set.of("1", "2", "3", "4");
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private final OpsDispatchOrderMapper dispatchMapper;
    private final OpsDispatchOrderItemMapper itemMapper;
    private final OpsDispatchMessageMapper messageMapper;
    private final OpsBusinessOrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;
    private final FinancePermissionService financePermissionService;
    private final CompanyCodeResolver companyCodeResolver;
    private final DispatchNoGenerator dispatchNoGenerator;
    private final ScmStockOutClient scmStockOutClient;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Map<String, Object> create(DispatchCreateRequest request) {
        requireDispatchPermission();
        validateRequest(request);
        OpsBusinessOrder order = loadOrder(request.getSourceOrderId());
        assertCorpVisible(order.getCorpId());
        List<OpsBusinessData> rows = orderMapper.selectItems(order.getId());
        assertDispatchable(order, rows);
        Assignee assignee = resolveAssignee(request.getAssigneeUserId());
        CustomerInfo customer = resolveCustomer(request, order);
        List<OpsDispatchOrderItem> items = buildItems(request, rows);
        OpsDispatchOrder dispatch = newDispatch(request, order, assignee, customer,
                resolveAgentNames(order, rows));
        dispatchMapper.insert(dispatch);
        persistItems(dispatch.getId(), items);
        log.info("派工单已创建 dispatchNo={}, orderId={}, assignee={}",
                dispatch.getDispatchNo(), order.getId(), assignee.userId());
        return summary(dispatch);
    }

    @Override
    public Map<String, Object> list(int pageNum, int pageSize, String status,
                                    String dispatchType, String keyword, Long corpId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && visible.isEmpty()) {
            return emptyPage(pageNum, pageSize);
        }
        if (corpId != null) assertCorpVisible(corpId);
        LambdaQueryWrapper<OpsDispatchOrder> qw = new LambdaQueryWrapper<>();
        if (visible != null) qw.in(OpsDispatchOrder::getCorpId, visible);
        if (corpId != null) qw.eq(OpsDispatchOrder::getCorpId, corpId);
        if (status != null && !status.isBlank()) qw.eq(OpsDispatchOrder::getStatus, status);
        if (dispatchType != null && !dispatchType.isBlank()) {
            qw.eq(OpsDispatchOrder::getDispatchType, dispatchType);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(OpsDispatchOrder::getDispatchNo, kw)
                    .or().like(OpsDispatchOrder::getCustomerName, kw)
                    .or().like(OpsDispatchOrder::getCustomerPhone, kw)
                    .or().like(OpsDispatchOrder::getSourceOrderNo, kw));
        }
        qw.orderByDesc(OpsDispatchOrder::getId);
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
        OpsDispatchOrder dispatch = requireDispatch(id);
        assertCorpVisible(dispatch.getCorpId());
        Map<String, Object> view = summary(dispatch);
        view.put("sourceSnapshot", dispatch.getSourceSnapshot());
        view.put("items", itemMapper.selectList(new LambdaQueryWrapper<OpsDispatchOrderItem>()
                .eq(OpsDispatchOrderItem::getDispatchId, id)
                .orderByAsc(OpsDispatchOrderItem::getId)));
        List<OpsDispatchMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<OpsDispatchMessage>()
                        .eq(OpsDispatchMessage::getDispatchId, id)
                        .orderByDesc(OpsDispatchMessage::getId));
        messages.forEach(m -> m.setOpenid(maskOpenid(m.getOpenid())));
        view.put("messages", messages);
        return view;
    }

    @Override
    public void cancel(Long id, String reason) {
        requireDispatchPermission();
        OpsDispatchOrder dispatch = requireDispatch(id);
        assertCorpVisible(dispatch.getCorpId());
        if (!"PENDING".equals(dispatch.getStatus())) {
            throw BusinessException.of("仅待接单的派工单可以取消");
        }
        int updated = dispatchMapper.update(null, new LambdaUpdateWrapper<OpsDispatchOrder>()
                .eq(OpsDispatchOrder::getId, id)
                .eq(OpsDispatchOrder::getCorpId, dispatch.getCorpId())
                .eq(OpsDispatchOrder::getStatus, "PENDING")
                .set(OpsDispatchOrder::getStatus, "CANCELLED")
                .set(OpsDispatchOrder::getCancelTime, LocalDateTime.now())
                .set(OpsDispatchOrder::getCancelReason, truncate(reason, 500)));
        if (updated == 0) {
            throw BusinessException.of("工单状态已变更，请刷新后重试");
        }
    }

    @Override
    public List<Map<String, Object>> assignees(Long corpId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && visible.isEmpty()) return List.of();
        if (corpId != null && corpId <= 0) {
            throw BusinessException.of("请先选择组织");
        }
        if (corpId != null) assertCorpVisible(corpId);

        String sql = "SELECT u.id AS userId, u.real_name AS realName, u.username, u.phone "
                + "FROM ai_platform.sys_user u "
                + "WHERE u.status = 1 AND u.delete_flag = 0";
        Object[] args;
        if (corpId != null) {
            sql += " AND (u.corp_id = ? OR EXISTS ("
                    + "  SELECT 1 FROM ai_platform.sys_user_corp uc "
                    + "  WHERE uc.user_id = u.id AND uc.corp_id = ? "
                    + "  AND uc.status = 1 AND uc.delete_flag = 0))";
            args = new Object[]{corpId, corpId};
        } else if (visible != null) {
            // 可管辖组织（运营商=自身+下属；城市合伙人=自身）
            String in = visible.stream().map(String::valueOf).collect(Collectors.joining(","));
            sql += " AND (u.corp_id IN (" + in + ") OR EXISTS ("
                    + "  SELECT 1 FROM ai_platform.sys_user_corp uc "
                    + "  WHERE uc.user_id = u.id AND uc.corp_id IN (" + in + ") "
                    + "  AND uc.status = 1 AND uc.delete_flag = 0))";
            args = new Object[]{};
        } else {
            args = new Object[]{};
        }
        sql += " ORDER BY u.real_name, u.id";
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, args);
        } catch (Exception e) {
            log.warn("加载派工可选员工失败 corpId={}: {}", corpId, e.getMessage());
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long userId = row.get("userId") instanceof Number n ? n.longValue() : null;
            if (userId == null) continue;
            String username = str(row.get("username"));
            String phone = username.isEmpty() ? str(row.get("phone")) : username;
            if (phone.isEmpty()) continue;
            String name = str(row.get("realName"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", userId);
            item.put("name", name.isEmpty() ? phone : name);
            item.put("phone", phone);
            result.add(item);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> activeByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return List.of();
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && visible.isEmpty()) return List.of();
        LambdaQueryWrapper<OpsDispatchOrder> qw = new LambdaQueryWrapper<OpsDispatchOrder>()
                .in(OpsDispatchOrder::getSourceOrderId, orderIds)
                .in(OpsDispatchOrder::getStatus, ACTIVE_STATUSES);
        if (visible != null) qw.in(OpsDispatchOrder::getCorpId, visible);
        qw.orderByDesc(OpsDispatchOrder::getId);
        List<OpsDispatchOrder> orders = dispatchMapper.selectList(qw);
        return orders.stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceOrderId", d.getSourceOrderId());
            item.put("dispatchId", d.getId());
            item.put("dispatchNo", d.getDispatchNo());
            item.put("status", d.getStatus());
            item.put("dispatchType", d.getDispatchType());
            item.put("assigneeName", d.getAssigneeName());
            return item;
        }).toList();
    }

    // ═══ 创建辅助 ═══

    private void validateRequest(DispatchCreateRequest request) {
        if (request == null || request.getSourceOrderId() == null) {
            throw BusinessException.of("请选择来源业务订单");
        }
        String type = request.getDispatchType();
        if (!TYPE_CONSTRUCTION.equals(type) && !TYPE_VISIT.equals(type)) {
            throw BusinessException.of("请选择工单类型（施工/入户随访）");
        }
        if (request.getExpectTime() == null) {
            throw BusinessException.of("请选择期望上门时间");
        }
        if (request.getExpectTime().isBefore(LocalDateTime.now().minusMinutes(1))) {
            throw BusinessException.of("期望上门时间不能早于当前时间");
        }
        if (request.getAssigneeUserId() == null) {
            throw BusinessException.of("请选择被指派员工");
        }
        if (TYPE_VISIT.equals(type)
                && (request.getRemark() == null || request.getRemark().isBlank())) {
            throw BusinessException.of("入户随访任务说明不能为空");
        }
        if (request.getRemark() != null && request.getRemark().length() > 500) {
            throw BusinessException.of("任务说明不能超过500字");
        }
    }

    private OpsBusinessOrder loadOrder(Long orderId) {
        OpsBusinessOrder order = orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("来源业务订单不存在");
        }
        return order;
    }

    private void assertDispatchable(OpsBusinessOrder order, List<OpsBusinessData> rows) {
        if (rows == null || rows.isEmpty()) {
            throw BusinessException.of("来源订单没有商品明细，无法派工");
        }
        boolean financeChecked = order.getFinanceCheckTime() != null
                && rows.stream().allMatch(r -> r.getFinanceCheckTime() != null);
        if (!financeChecked) {
            throw BusinessException.of("来源订单未完成财务核对，不允许发起派工");
        }
        // 已产生销售出库单（派工自动出库/业务数据手工出库/同收据合并出库）不可再派工
        Set<Long> outboundOrderIds = scmStockOutClient.outboundOrderIds(List.of(order.getId()));
        if (outboundOrderIds.contains(order.getId())) {
            throw BusinessException.of("该订单已产生销售出库单，不可重复派工");
        }
        // 锁定订单头，串行化同一订单的并发派工（否则两次请求可能同时通过“无进行中派工”检查）
        jdbcTemplate.queryForList(
                "SELECT id FROM ops_business_order WHERE id = ? FOR UPDATE", order.getId());
        OpsDispatchOrder active = dispatchMapper.selectOne(
                new LambdaQueryWrapper<OpsDispatchOrder>()
                        .eq(OpsDispatchOrder::getSourceOrderId, order.getId())
                        .eq(OpsDispatchOrder::getCorpId, order.getCorpId())
                        .in(OpsDispatchOrder::getStatus, ACTIVE_STATUSES)
                        .orderByDesc(OpsDispatchOrder::getId)
                        .last("LIMIT 1 FOR UPDATE"));
        if (active != null) {
            throw BusinessException.of("该订单已有进行中的派工单：" + active.getDispatchNo());
        }
    }

    /** 被指派员工须属于当前账号可管辖组织（DataScope 可见范围），不要求是订单经办人。 */
    private Assignee resolveAssignee(Long userId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && visible.isEmpty()) {
            throw BusinessException.forbidden("当前账号无可管辖组织");
        }
        String sql = "SELECT u.id AS userId, u.real_name AS realName, u.username, u.phone "
                + "FROM ai_platform.sys_user u "
                + "WHERE u.id = ? AND u.status = 1 AND u.delete_flag = 0";
        if (visible != null) {
            String in = visible.stream().map(String::valueOf).collect(Collectors.joining(","));
            sql += " AND (u.corp_id IN (" + in + ") OR EXISTS ("
                    + "  SELECT 1 FROM ai_platform.sys_user_corp uc "
                    + "  WHERE uc.user_id = u.id AND uc.corp_id IN (" + in + ") "
                    + "  AND uc.status = 1 AND uc.delete_flag = 0))";
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql + " LIMIT 1", userId);
        } catch (Exception e) {
            log.warn("校验派工员工失败 userId={}: {}", userId, e.getMessage());
            throw BusinessException.of("员工信息查询失败，请稍后重试");
        }
        if (rows.isEmpty()) {
            throw BusinessException.of("被指派员工不在当前账号可管辖组织内或已停用");
        }
        Map<String, Object> row = rows.get(0);
        String username = str(row.get("username"));
        String phone = username.isEmpty() ? str(row.get("phone")) : username;
        String name = str(row.get("realName"));
        return new Assignee(userId, name.isEmpty() ? phone : name, phone);
    }

    private CustomerInfo resolveCustomer(DispatchCreateRequest request, OpsBusinessOrder order) {
        String name = pick(request.getCustomerName(), order.getCustomer());
        if (name == null || name.isBlank()) {
            throw BusinessException.of("客户名称不能为空");
        }
        String phone = normalizePhone(pick(request.getCustomerPhone(), order.getCustomerPhone()));
        if (!PHONE.matcher(phone).matches()) {
            throw BusinessException.of("客户手机号格式不正确");
        }
        Long communityId = request.getCommunityId() != null
                ? request.getCommunityId() : order.getCommunityId();
        String communityName = pick(request.getCommunityName(), order.getCommunityName());
        String detailedAddress = pick(request.getDetailedAddress(), order.getDetailedAddress());
        if (name.length() > 100) throw BusinessException.of("客户名称不能超过100字");
        if (communityName.length() > 200) throw BusinessException.of("小区名称不能超过200字");
        if (detailedAddress.length() > 500) throw BusinessException.of("详细地址不能超过500字");
        String snapshot = customerSnapshot(request, order);
        return new CustomerInfo(name, phone, communityId, communityName,
                detailedAddress, snapshot);
    }

    /** 派发人修改客户信息时记录原始值，满足留痕要求。 */
    private String customerSnapshot(DispatchCreateRequest request, OpsBusinessOrder order) {
        Map<String, String> changed = new LinkedHashMap<>();
        putIfChanged(changed, "customerName", request.getCustomerName(), order.getCustomer());
        putIfChanged(changed, "customerPhone", request.getCustomerPhone(), order.getCustomerPhone());
        putIfChanged(changed, "communityName", request.getCommunityName(), order.getCommunityName());
        putIfChanged(changed, "detailedAddress", request.getDetailedAddress(), order.getDetailedAddress());
        if (changed.isEmpty()) return null;
        try {
            String json = objectMapper.writeValueAsString(changed);
            return json.length() > 1000 ? json.substring(0, 1000) : json;
        } catch (Exception e) {
            log.warn("客户信息留痕序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private List<OpsDispatchOrderItem> buildItems(DispatchCreateRequest request,
                                                  List<OpsBusinessData> rows) {
        if (TYPE_VISIT.equals(request.getDispatchType())) return List.of();
        Set<Long> selected = request.getSourceItemIds() == null ? Set.of()
                : new HashSet<>(request.getSourceItemIds());
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData row : rows) {
            // 仅正数销售分录；退货冲减（负数分录/带 source_return_id）不参与施工
            if (row.getSaleQuantity() == null || row.getSaleQuantity() <= 0) continue;
            if (row.getSourceReturnId() != null) continue;
            if (!selected.isEmpty() && !selected.contains(row.getId())) continue;
            // 同一商品可能因多经办人拆行，按「品类+品牌+型号」合并
            String key = Objects.toString(row.getCategory(), "") + "|"
                    + Objects.toString(row.getBrand(), "") + "|"
                    + Objects.toString(row.getModel(), "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        if (groups.isEmpty()) {
            throw BusinessException.of("请选择至少一个施工商品（订单正数销售分录）");
        }
        List<OpsDispatchOrderItem> items = new ArrayList<>();
        for (List<OpsBusinessData> group : groups.values()) {
            OpsBusinessData first = group.get(0);
            // 多经办人拆分的行数量相同（各自全额），取 MAX 合并，避免重复累加
            int qty = group.stream().mapToInt(r -> Math.max(1, r.getSaleQuantity()))
                    .max().orElse(1);
            OpsDispatchOrderItem item = new OpsDispatchOrderItem();
            item.setSourceItemId(first.getId());
            item.setProductName(productName(first));
            item.setBrand(first.getBrand());
            item.setCategory(first.getCategory());
            item.setModel(first.getModel());
            item.setSaleQuantity(qty);
            item.setUnit(APPLIANCE_CATEGORIES.contains(first.getCategory()) ? "台" : "件");
            item.setSalePrice(first.getSalePrice());
            items.add(item);
        }
        return items;
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

    private OpsDispatchOrder newDispatch(DispatchCreateRequest request, OpsBusinessOrder order,
                                         Assignee assignee, CustomerInfo customer,
                                         String sourceAgentNames) {
        String companyCode = companyCodeResolver.resolve(order.getCorpId());
        String dispatchNo = dispatchNoGenerator.next(companyCode, order.getCorpId());
        OpsDispatchOrder dispatch = new OpsDispatchOrder();
        dispatch.setDispatchNo(dispatchNo);
        dispatch.setSourceOrderId(order.getId());
        dispatch.setSourceOrderNo(order.getOrderNo());
        dispatch.setSourceAgentNames(truncate(sourceAgentNames, 500));
        dispatch.setReceiptNo(order.getReceiptNo());
        dispatch.setCorpId(order.getCorpId());
        dispatch.setDispatchType(request.getDispatchType());
        dispatch.setCustomerName(customer.name());
        dispatch.setCustomerPhone(customer.phone());
        dispatch.setPlatformMemberId(order.getPlatformMemberId());
        dispatch.setCommunityId(customer.communityId());
        dispatch.setCommunityName(customer.communityName());
        dispatch.setDetailedAddress(customer.detailedAddress());
        dispatch.setExpectTime(request.getExpectTime());
        dispatch.setAssigneeUserId(assignee.userId());
        dispatch.setAssigneeName(assignee.name());
        dispatch.setAssigneeUsername(assignee.phone());
        dispatch.setStatus("PENDING");
        dispatch.setDispatchBy(SecurityUtils.getCurrentUserId());
        dispatch.setDispatchByName(currentUserName());
        dispatch.setDispatchTime(LocalDateTime.now());
        dispatch.setSourceSnapshot(customer.snapshot());
        dispatch.setRemark(trimToNull(request.getRemark()));
        return dispatch;
    }

    private void persistItems(Long dispatchId, List<OpsDispatchOrderItem> items) {
        for (OpsDispatchOrderItem item : items) {
            item.setDispatchId(dispatchId);
            itemMapper.insert(item);
        }
    }

    /** 派发人显示名：优先 sys_user.real_name，查询失败回退 JWT username。 */
    private String currentUserName() {
        String username = SecurityUtils.getCurrentUser().getUsername();
        try {
            String realName = jdbcTemplate.queryForObject(
                    "SELECT real_name FROM ai_platform.sys_user WHERE id = ?",
                    String.class, SecurityUtils.getCurrentUserId());
            if (realName != null && !realName.isBlank()) return realName;
        } catch (Exception e) {
            log.debug("查询派发人姓名失败: {}", e.getMessage());
        }
        return username;
    }

    // ═══ 通用辅助 ═══

    private OpsDispatchOrder requireDispatch(Long id) {
        OpsDispatchOrder dispatch = dispatchMapper.selectById(id);
        if (dispatch == null || Integer.valueOf(1).equals(dispatch.getDeleteFlag())) {
            throw BusinessException.notFound("派工单不存在");
        }
        return dispatch;
    }

    private void requireDispatchPermission() {
        if (!financePermissionService.canCreateDispatch()) {
            throw BusinessException.forbidden("仅员工账号可发起或取消派工");
        }
    }

    private void assertCorpVisible(Long corpId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && !visible.contains(corpId)) {
            throw BusinessException.forbidden("无权操作该组织的派工单");
        }
    }

    private Map<String, Object> summary(OpsDispatchOrder d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", d.getId());
        map.put("dispatchNo", d.getDispatchNo());
        map.put("sourceOrderId", d.getSourceOrderId());
        map.put("sourceOrderNo", d.getSourceOrderNo());
        map.put("sourceAgentNames", d.getSourceAgentNames());
        map.put("receiptNo", d.getReceiptNo());
        map.put("corpId", d.getCorpId());
        map.put("dispatchType", d.getDispatchType());
        map.put("customerName", d.getCustomerName());
        map.put("customerPhone", d.getCustomerPhone());
        map.put("communityId", d.getCommunityId());
        map.put("communityName", d.getCommunityName());
        map.put("detailedAddress", d.getDetailedAddress());
        map.put("expectTime", d.getExpectTime());
        map.put("assigneeUserId", d.getAssigneeUserId());
        map.put("assigneeName", d.getAssigneeName());
        map.put("assigneeUsername", d.getAssigneeUsername());
        map.put("status", d.getStatus());
        map.put("dispatchByName", d.getDispatchByName());
        map.put("dispatchTime", d.getDispatchTime());
        map.put("acceptTime", d.getAcceptTime());
        map.put("startTime", d.getStartTime());
        map.put("completeTime", d.getCompleteTime());
        map.put("rejectTime", d.getRejectTime());
        map.put("rejectReason", d.getRejectReason());
        map.put("cancelTime", d.getCancelTime());
        map.put("cancelReason", d.getCancelReason());
        map.put("stockOutId", d.getStockOutId());
        map.put("stockOutNo", d.getStockOutNo());
        map.put("messageStatus", d.getMessageStatus());
        map.put("messageError", d.getMessageError());
        map.put("remark", d.getRemark());
        map.put("createTime", d.getCreateTime());
        return map;
    }

    private Map<String, Object> emptyPage(int pageNum, int pageSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", List.of());
        result.put("total", 0L);
        result.put("size", Math.max(1, pageSize));
        result.put("current", Math.max(1, pageNum));
        return result;
    }

    private static void putIfChanged(Map<String, String> changed, String key,
                                     String requested, String original) {
        if (requested == null || requested.isBlank()) return;
        if (!requested.trim().equals(nullToEmpty(original))) {
            changed.put(key, nullToEmpty(original));
        }
    }

    private static String pick(String requested, String fallback) {
        return requested != null && !requested.isBlank() ? requested.trim() : nullToEmpty(fallback).trim();
    }

    private static String normalizePhone(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }

    private static String productName(OpsBusinessData row) {
        String brand = nullToEmpty(row.getBrand()).trim();
        String model = nullToEmpty(row.getModel()).trim();
        String name = (brand + " " + model).trim();
        if (name.isEmpty()) return null;
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String maskOpenid(String openid) {
        if (openid == null || openid.length() <= 10) return openid;
        return openid.substring(0, 6) + "****" + openid.substring(openid.length() - 4);
    }

    private record Assignee(Long userId, String name, String phone) {
    }

    private record CustomerInfo(String name, String phone, Long communityId,
                                String communityName, String detailedAddress,
                                String snapshot) {
    }
}
