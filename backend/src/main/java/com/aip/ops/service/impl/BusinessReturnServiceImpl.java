package com.aip.ops.service.impl;

import com.aip.ops.dto.BusinessReturnApproveRequest;
import com.aip.ops.dto.BusinessReturnRequest;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsBusinessReturn;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessReturnMapper;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.BusinessOrderService;
import com.aip.ops.service.BusinessReturnService;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.FinancePermissionService;
import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessReturnServiceImpl implements BusinessReturnService {

    private static final String REPORTED_STATUS = "已上报集团";

    private final OpsBusinessDataMapper itemMapper;
    private final OpsBusinessReturnMapper returnMapper;
    private final BusinessOrderService orderService;
    private final BusinessDataService businessDataService;
    private final DataScopeService dataScopeService;
    private final FinancePermissionService financePermissionService;
    private final OrgRoleService orgRoleService;
    private final JwtTokenInspector jwtTokenInspector;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Long itemId, BusinessReturnRequest request) {
        if (itemId == null) throw BusinessException.of("业务数据ID不能为空");
        OpsBusinessData item = itemMapper.selectById(itemId);
        if (item == null) throw BusinessException.notFound("业务数据不存在");
        assertScope(item.getCorpId());
        // 组织数据主权：上级组织只读，不得对下级组织业务数据发起退货
        assertOwnCorp(item.getCorpId());
        OpsBusinessOrder order = orderService.ensureOrderForItem(item);
        // 锁同产品全部经办人行并取当前版本，串行化并发退货/审批
        LockedGroup group = lockProductGroup(order.getId(), item);
        item = group.item();
        if (item == null) throw BusinessException.of("业务数据已变更，请刷新后重试");
        assertNoReportedItemCurrent(order.getId());
        assertNotReported(order, item);
        assertCanApply(item);

        int qty = validateQuantity(request);
        if (request.getReason() != null && request.getReason().length() > 500) {
            throw BusinessException.of("退货原因不能超过500个字符");
        }
        int remaining = remainingQuantity(item, order.getId());
        if (qty > remaining) {
            throw BusinessException.of("退货数量不能超过剩余可退数量（" + remaining + "）");
        }
        String productKey = productKey(item);
        Long pending = returnMapper.selectCount(new LambdaQueryWrapper<OpsBusinessReturn>()
                .eq(OpsBusinessReturn::getOrderId, order.getId())
                .eq(OpsBusinessReturn::getProductKey, productKey)
                .eq(OpsBusinessReturn::getStatus, OpsBusinessReturn.STATUS_PENDING)
                .eq(OpsBusinessReturn::getDeleteFlag, 0));
        if (pending != null && pending > 0) {
            throw BusinessException.of("该产品已有审批中的退货申请，请等待审批完成");
        }

        OpsBusinessReturn entity = new OpsBusinessReturn();
        entity.setCorpId(item.getCorpId());
        entity.setOrderId(order.getId());
        entity.setItemId(itemId);
        entity.setProductKey(productKey);
        entity.setReturnQuantity(qty);
        entity.setReturnAmount(amountOf(item, qty));
        entity.setReason(request != null ? trimToNull(request.getReason()) : null);
        entity.setStatus(OpsBusinessReturn.STATUS_PENDING);
        fillApplicant(entity);
        entity.setDeleteFlag(0);
        returnMapper.insert(entity);

        updateGroupReturnStatus(order.getId(), item, OpsBusinessReturn.STATUS_PENDING);
        log.info("退货申请创建: returnId={} orderId={} itemId={} qty={}",
                entity.getId(), order.getId(), itemId, qty);
        return toMap(entity, item, order);
    }

    @Override
    public List<Map<String, Object>> listByOrder(Long orderId) {
        if (orderId == null) return List.of();
        requireCorpContext();
        OpsBusinessOrder order = orderService.getById(orderId);
        if (order == null) throw BusinessException.notFound("订单不存在");
        assertScope(order.getCorpId());
        List<OpsBusinessReturn> rows = returnMapper.selectList(
                new LambdaQueryWrapper<OpsBusinessReturn>()
                        .eq(OpsBusinessReturn::getOrderId, orderId)
                        .eq(OpsBusinessReturn::getDeleteFlag, 0)
                        .orderByDesc(OpsBusinessReturn::getId));
        return toMaps(rows);
    }

    @Override
    public List<Map<String, Object>> listPending() {
        requireCorpContext();
        assertCanApprove();
        LambdaQueryWrapper<OpsBusinessReturn> qw = new LambdaQueryWrapper<OpsBusinessReturn>()
                .eq(OpsBusinessReturn::getStatus, OpsBusinessReturn.STATUS_PENDING)
                .eq(OpsBusinessReturn::getDeleteFlag, 0);
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null) {
            if (visible.isEmpty()) return List.of();
            qw.in(OpsBusinessReturn::getCorpId, visible);
        }
        qw.orderByDesc(OpsBusinessReturn::getId).last("LIMIT 200");
        return toMaps(returnMapper.selectList(qw));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> approve(Long requestId, BusinessReturnApproveRequest request) {
        if (requestId == null) throw BusinessException.of("退货申请ID不能为空");
        // 行锁串行化并发审批；锁后重新读取状态，避免同一申请被重复通过
        List<Long> locked = jdbcTemplate.queryForList(
                "SELECT id FROM ops_business_return WHERE id = ? FOR UPDATE", Long.class, requestId);
        if (locked.isEmpty()) throw BusinessException.notFound("退货申请不存在");
        OpsBusinessReturn entity = returnMapper.selectById(requestId);
        if (entity == null) throw BusinessException.notFound("退货申请不存在");
        if (!OpsBusinessReturn.STATUS_PENDING.equals(entity.getStatus())) {
            throw BusinessException.of("该退货申请已处理，请勿重复审批");
        }
        if (request == null || request.getApproved() == null) {
            throw BusinessException.of("请选择通过或驳回");
        }
        if (request.getRemark() != null && request.getRemark().length() > 500) {
            throw BusinessException.of("审批意见不能超过500个字符");
        }
        assertScope(entity.getCorpId());
        requireCorpContext();
        assertCanApprove();
        OpsBusinessData item = itemMapper.selectById(entity.getItemId());
        if (item == null) throw BusinessException.notFound("业务数据不存在");
        OpsBusinessOrder order = orderService.getById(entity.getOrderId());
        if (order == null) order = orderService.ensureOrderForItem(item);
        // 锁同产品全部经办人行并取当前版本，串行化并发退货/审批
        LockedGroup group = lockProductGroup(order.getId(), item);
        item = group.item();
        if (item == null) throw BusinessException.of("业务数据已变更，请刷新后重试");

        boolean approved = request != null && Boolean.TRUE.equals(request.getApproved());
        if (approved) {
            // 已上报集团禁止“通过”；驳回只回退 return_status，不影响金额，允许用于解除僵死申请
            assertNoReportedItemCurrent(order.getId());
            assertNotReported(order, item);
            int remaining = remainingQuantity(item, order.getId());
            int qty = entity.getReturnQuantity() != null ? entity.getReturnQuantity() : 0;
            if (qty <= 0) throw BusinessException.of("退货数量无效");
            if (qty > remaining) {
                throw BusinessException.of("退货数量超过剩余可退数量（" + remaining + "），不能通过");
            }
            applyApproved(entity, order, item, qty, group.size());
            // 提交前再次当前读：若并发上报了同订单其它产品，整笔退货回滚，避免已上报订单被改写
            assertNoReportedItemCurrent(order.getId());
        } else {
            entity.setStatus(OpsBusinessReturn.STATUS_REJECTED);
            restoreGroupStatusAfterReject(order.getId(), item, entity.getId());
        }
        entity.setApproverUserId(SecurityUtils.getCurrentUserId());
        entity.setApproverName(currentUserName());
        entity.setApproveTime(LocalDateTime.now());
        entity.setApproveRemark(request != null ? trimToNull(request.getRemark()) : null);
        returnMapper.updateById(entity);
        log.info("退货审批: returnId={} approved={} orderId={} itemId={}",
                entity.getId(), approved, entity.getOrderId(), entity.getItemId());
        return toMap(entity, item, order);
    }

    /** 通过：同产品全部经办人行同步累计退货、结算数量/金额，主表重算并触发订单提成重算。 */
    private void applyApproved(OpsBusinessReturn entity, OpsBusinessOrder order,
                               OpsBusinessData item, int qty, int expectedSize) {
        BigDecimal price = item.getSalePrice() != null ? item.getSalePrice() : BigDecimal.ZERO;
        int itemReturned = item.getReturnQuantity() != null ? item.getReturnQuantity() : 0;
        // 同产品多经办人行理论上同步累计；历史异常时以最大值兜底，避免把已退数量回退
        Integer maxReturned = jdbcTemplate.queryForObject(
                "SELECT MAX(COALESCE(return_quantity,0)) FROM ops_business_order_item "
                        + "WHERE order_id = ? AND delete_flag = 0 "
                        + "AND category <=> ? AND brand <=> ? AND model <=> ? AND sale_price <=> ?",
                Integer.class, order.getId(), item.getCategory(), item.getBrand(),
                item.getModel(), item.getSalePrice());
        int returnedBefore = Math.max(itemReturned, maxReturned != null ? maxReturned : 0);
        int returnedAfter = returnedBefore + qty;
        BigDecimal returnAmount = price.multiply(BigDecimal.valueOf(returnedAfter))
                .setScale(2, RoundingMode.HALF_UP);
        // 注意：MySQL 同一条 UPDATE 内后置赋值会读到前面已更新的列值，
        // 因此派生值全部用显式参数；settle_quantity 按每行自己的 sale_quantity 计算。
        int updated = jdbcTemplate.update(
                "UPDATE ops_business_order_item SET "
                        + "original_receipt_amount = COALESCE(original_receipt_amount, receipt_amount), "
                        + "settle_quantity = GREATEST(COALESCE(sale_quantity,1) - ?, 0), "
                        + "is_return = '是', return_status = '已通过', "
                        + "return_amount = ?, "
                        + "receipt_amount = ? * GREATEST(COALESCE(sale_quantity,1) - ?, 0), "
                        + "return_quantity = ?, "
                        + "commission_amount = 0, calc_status = '未计算', modify_time = NOW() "
                        + "WHERE order_id = ? AND delete_flag = 0 "
                        + "AND (calc_status IS NULL OR calc_status <> '已上报集团') "
                        + "AND category <=> ? AND brand <=> ? AND model <=> ? AND sale_price <=> ?",
                returnedAfter, returnAmount, price, returnedAfter, returnedAfter,
                order.getId(), item.getCategory(), item.getBrand(), item.getModel(), item.getSalePrice());
        if (updated != expectedSize || updated == 0) {
            throw BusinessException.of("订单内部分明细已上报集团或状态已变更，退货未生效，请刷新后重试");
        }
        entity.setStatus(OpsBusinessReturn.STATUS_APPROVED);
        orderService.refreshTotals(order.getId());
        orderService.refreshAgentSummary(order.getId());
        businessDataService.recalculateOrder(order.getId());
        orderService.syncStatus(order.getId());
    }

    /** 驳回后回退子表退货状态：已通过历史申请优先，其次其他审批中申请，否则已驳回。 */
    private void restoreGroupStatusAfterReject(Long orderId, OpsBusinessData item, Long requestId) {
        String productKey = productKey(item);
        Long approved = returnMapper.selectCount(new LambdaQueryWrapper<OpsBusinessReturn>()
                .eq(OpsBusinessReturn::getOrderId, orderId)
                .eq(OpsBusinessReturn::getProductKey, productKey)
                .ne(OpsBusinessReturn::getId, requestId)
                .eq(OpsBusinessReturn::getStatus, OpsBusinessReturn.STATUS_APPROVED)
                .eq(OpsBusinessReturn::getDeleteFlag, 0));
        Long pending = returnMapper.selectCount(new LambdaQueryWrapper<OpsBusinessReturn>()
                .eq(OpsBusinessReturn::getOrderId, orderId)
                .eq(OpsBusinessReturn::getProductKey, productKey)
                .ne(OpsBusinessReturn::getId, requestId)
                .eq(OpsBusinessReturn::getStatus, OpsBusinessReturn.STATUS_PENDING)
                .eq(OpsBusinessReturn::getDeleteFlag, 0));
        String status = (approved != null && approved > 0) ? OpsBusinessReturn.STATUS_APPROVED
                : (pending != null && pending > 0) ? OpsBusinessReturn.STATUS_PENDING
                : OpsBusinessReturn.STATUS_REJECTED;
        updateGroupReturnStatus(orderId, item, status);
    }

    private void updateGroupReturnStatus(Long orderId, OpsBusinessData item, String status) {
        jdbcTemplate.update(
                "UPDATE ops_business_order_item SET return_status = ?, modify_time = NOW() "
                        + "WHERE order_id = ? AND delete_flag = 0 "
                        + "AND category <=> ? AND brand <=> ? AND model <=> ? AND sale_price <=> ?",
                status, orderId, item.getCategory(), item.getBrand(), item.getModel(), item.getSalePrice());
    }

    /**
     * 锁定同一订单同一产品的全部经办人行，并返回当前版本（当前读，避免 REPEATABLE READ 快照读到旧值）。
     * 并发退货/审批按产品串行化，防止 pending 检查与累计数量竞态。
     */
    private LockedGroup lockProductGroup(Long orderId, OpsBusinessData item) {
        if (orderId == null || item == null) return new LockedGroup(item, 0);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ops_business_order_item WHERE order_id = ? AND delete_flag = 0 "
                        + "AND category <=> ? AND brand <=> ? AND model <=> ? AND sale_price <=> ? FOR UPDATE",
                Long.class, orderId, item.getCategory(), item.getBrand(),
                item.getModel(), item.getSalePrice());
        List<OpsBusinessData> rows = jdbcTemplate.query(
                "SELECT * FROM ops_business_order_item WHERE id = ? AND delete_flag = 0 FOR UPDATE",
                new org.springframework.jdbc.core.BeanPropertyRowMapper<>(OpsBusinessData.class),
                item.getId());
        return new LockedGroup(rows.isEmpty() ? null : rows.get(0), ids.size());
    }

    /** 当前读检查整单是否已有上报集团行（避免先读快照后上报导致退货绕过锁定）。 */
    private void assertNoReportedItemCurrent(Long orderId) {
        if (orderId == null) return;
        List<Long> reported = jdbcTemplate.queryForList(
                "SELECT id FROM ops_business_order_item WHERE order_id = ? AND delete_flag = 0 "
                        + "AND calc_status = '已上报集团' LIMIT 1 FOR UPDATE",
                Long.class, orderId);
        if (!reported.isEmpty()) {
            throw BusinessException.of("该订单包含已上报集团数据，禁止退货");
        }
    }

    private record LockedGroup(OpsBusinessData item, int size) {}

    /** 同产品全部经办人行中最小剩余可退数量；子表可能存在历史不一致，取最保守值。 */
    private int remainingQuantity(OpsBusinessData item, Long orderId) {
        Integer sold = item.getSaleQuantity();
        int base = sold != null ? sold : 1;
        Integer returned = item.getReturnQuantity();
        int remaining = base - (returned != null ? returned : 0);
        if (orderId == null) return Math.max(remaining, 0);
        try {
            Integer min = jdbcTemplate.queryForObject(
                    "SELECT MIN(COALESCE(sale_quantity,1) - COALESCE(return_quantity,0)) "
                            + "FROM ops_business_order_item WHERE order_id = ? AND delete_flag = 0 "
                            + "AND category <=> ? AND brand <=> ? AND model <=> ? AND sale_price <=> ?",
                    Integer.class, orderId, item.getCategory(), item.getBrand(),
                    item.getModel(), item.getSalePrice());
            if (min != null) remaining = Math.min(remaining, min);
        } catch (Exception e) {
            log.warn("查询剩余可退数量失败 itemId={}: {}", item.getId(), e.getMessage());
        }
        return Math.max(remaining, 0);
    }

    private BigDecimal amountOf(OpsBusinessData item, int qty) {
        BigDecimal price = item.getSalePrice() != null ? item.getSalePrice() : BigDecimal.ZERO;
        return price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
    }

    private int validateQuantity(BusinessReturnRequest request) {
        if (request == null || request.getReturnQuantity() == null) {
            throw BusinessException.of("请填写退货数量");
        }
        int qty = request.getReturnQuantity();
        if (qty <= 0) throw BusinessException.of("退货数量必须为正整数");
        return qty;
    }

    private void assertNotReported(OpsBusinessOrder order, OpsBusinessData item) {
        if (order != null && order.getId() != null) {
            Long reportedItems = itemMapper.selectCount(new LambdaQueryWrapper<OpsBusinessData>()
                    .eq(OpsBusinessData::getOrderId, order.getId())
                    .eq(OpsBusinessData::getCalcStatus, REPORTED_STATUS)
                    .eq(OpsBusinessData::getDeleteFlag, 0));
            if (reportedItems != null && reportedItems > 0) {
                throw BusinessException.of("该订单包含已上报集团数据，禁止退货");
            }
        }
        if ((order != null && REPORTED_STATUS.equals(order.getCalcStatus()))
                || REPORTED_STATUS.equals(item.getCalcStatus())) {
            throw BusinessException.of("该订单已上报集团，禁止退货");
        }
    }

    private void assertCanApply(OpsBusinessData item) {
        if (financePermissionService.canFinanceCheck() || orgRoleService.isOperatorOrSuperAdmin()) {
            return;
        }
        // 员工 JWT 无 token_type（null）；会员 SSO JWT 为 "sso"，必须排除
        if (!"sso".equals(jwtTokenInspector.currentTokenType())) {
            String phone = currentUserPhone();
            Long corpId = SecurityUtils.getCurrentCorpId();
            if (phone != null && phone.equals(item.getAgentPhone())
                    && (corpId == null || corpId.equals(item.getCorpId()))) {
                return;
            }
        }
        throw BusinessException.forbidden("仅该业务经办人、财务角色或运营商管理员可申请退货");
    }

    private void assertCanApprove() {
        if (financePermissionService.canFinanceCheck() || orgRoleService.isOperatorOrSuperAdmin()) {
            return;
        }
        throw BusinessException.forbidden("仅财务角色或运营商管理员可审批退货");
    }

    private void assertScope(Long corpId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible == null) return; // 超管
        if (corpId == null || !visible.contains(corpId)) {
            throw BusinessException.forbidden("无权操作该业务数据");
        }
    }

    /** 无 corp_id claim 的令牌（会员 SSO / 异常令牌）不得进入退货审批/列表。 */
    private void requireCorpContext() {
        if (SecurityUtils.getCurrentCorpId() == null) {
            throw BusinessException.forbidden("无法确定所属组织，禁止操作退货数据");
        }
    }

    /** 非超管只能操作本组织业务数据；上级组织对下级数据仅可查看/核审。 */
    private void assertOwnCorp(Long corpId) {
        Long current = SecurityUtils.getCurrentCorpId();
        if (current == null) {
            throw BusinessException.forbidden("无法确定所属组织，禁止操作业务数据");
        }
        if (current == 0L) return; // 超管
        if (corpId == null || !current.equals(corpId)) {
            throw BusinessException.forbidden("只能操作本组织的业务数据");
        }
    }

    private void fillApplicant(OpsBusinessReturn entity) {
        entity.setApplicantUserId(SecurityUtils.getCurrentUserId());
        entity.setApplicantPhone(currentUserPhone());
        entity.setApplicantName(currentUserName());
    }

    private String currentUserPhone() {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT username, phone FROM ai_platform.sys_user WHERE id = ? LIMIT 1", userId);
            if (!rows.isEmpty()) {
                String username = trimToNull(Objects.toString(rows.get(0).get("username"), null));
                String phone = trimToNull(Objects.toString(rows.get(0).get("phone"), null));
                if (username != null) return username;
                if (phone != null) return phone;
            }
        } catch (Exception e) {
            log.debug("查询申请人手机号失败: {}", e.getMessage());
        }
        try {
            return SecurityUtils.getCurrentUser().getUsername();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentUserName() {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT real_name FROM ai_platform.sys_user WHERE id = ?", String.class, userId);
            if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) {
                return names.get(0).trim();
            }
        } catch (Exception e) {
            log.debug("查询审批人姓名失败: {}", e.getMessage());
        }
        return currentUserPhone();
    }

    private List<Map<String, Object>> toMaps(List<OpsBusinessReturn> rows) {
        if (rows.isEmpty()) return List.of();
        Set<Long> itemIds = rows.stream().map(OpsBusinessReturn::getItemId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, OpsBusinessData> items = itemIds.isEmpty() ? Map.of()
                : itemMapper.selectBatchIds(itemIds).stream()
                        .collect(Collectors.toMap(OpsBusinessData::getId, x -> x, (a, b) -> a));
        Set<Long> orderIds = rows.stream().map(OpsBusinessReturn::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, OpsBusinessOrder> orders = orderIds.isEmpty() ? Map.of()
                : orderService.findByIds(orderIds).stream()
                        .collect(Collectors.toMap(OpsBusinessOrder::getId, x -> x, (a, b) -> a));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OpsBusinessReturn row : rows) {
            result.add(toMap(row, items.get(row.getItemId()), orders.get(row.getOrderId())));
        }
        return result;
    }

    private Map<String, Object> toMap(OpsBusinessReturn entity, OpsBusinessData item,
                                      OpsBusinessOrder order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("orderId", entity.getOrderId());
        map.put("itemId", entity.getItemId());
        map.put("returnQuantity", entity.getReturnQuantity());
        map.put("returnAmount", entity.getReturnAmount());
        map.put("reason", entity.getReason());
        map.put("status", entity.getStatus());
        map.put("applicantUserId", entity.getApplicantUserId());
        map.put("applicantName", entity.getApplicantName());
        map.put("applicantPhone", entity.getApplicantPhone());
        map.put("approverUserId", entity.getApproverUserId());
        map.put("approverName", entity.getApproverName());
        map.put("approveTime", entity.getApproveTime());
        map.put("approveRemark", entity.getApproveRemark());
        map.put("createTime", entity.getCreateTime());
        if (item != null) {
            map.put("customer", item.getCustomer());
            map.put("category", item.getCategory());
            map.put("brand", item.getBrand());
            map.put("model", item.getModel());
            map.put("agent", item.getAgent());
            map.put("saleQuantity", item.getSaleQuantity());
            map.put("returnedQuantity", item.getReturnQuantity());
            map.put("settleQuantity", item.getSettleQuantity());
        }
        if (order != null) {
            map.put("orderNo", order.getOrderNo());
            map.put("receiptNo", order.getReceiptNo());
        }
        return map;
    }

    private static String productKey(OpsBusinessData item) {
        return nullSafe(item.getCategory()) + "|" + nullSafe(item.getBrand()) + "|"
                + nullSafe(item.getModel()) + "|"
                + (item.getSalePrice() == null ? "" : item.getSalePrice().stripTrailingZeros().toPlainString());
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v.trim();
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
