package com.aip.ops.service.impl;

import com.aip.ops.dto.ScmSaleReturnSyncRequest;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.BusinessOrderService;
import com.aip.ops.service.ScmSaleReturnSyncService;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * SCM 销售退货审核/反审核 → 业务数据订单负数分录反写。
 *
 * <p>退货不再扣减原销售分录数量，而是按「订单 + 品类 + 品牌 + 型号 + 单价」
 * 逐经办人新增负数分录：数量/收款金额/提成为负，订单级公共信息与原分录一致；
 * 正数分录保持原始销售数量。提成由订单提成引擎按原提成比例冲减。</p>
 *
 * <p>幂等：`ops_scm_return_sync` 记录每张 SCM 退货单在每个产品组已回写的数量，
 * 重复回调 delta=0 直接跳过；数量变化时删除本单旧负数分录后按新数量重建，
 * 反审核同样删除本单负数分录。已上报集团的订单禁止回写/撤销。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScmSaleReturnSyncServiceImpl implements ScmSaleReturnSyncService {

    private static final String AUDITED = "AUDITED";
    private static final String REPORTED_STATUS = "已上报集团";
    private static final String HISTORY_RETURN_NO = "历史退货";

    private final OpsBusinessDataMapper itemMapper;
    private final BusinessOrderService orderService;
    private final BusinessDataService businessDataService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sync(ScmSaleReturnSyncRequest request) {
        validate(request);
        Map<String, GroupRequest> groups = resolveGroups(request);
        boolean audit = AUDITED.equals(request.getAuditStatus());
        int changedRows = 0;
        // 按产品组键排序加锁，避免同一次退货单的多条分录以不同顺序锁同多个产品组
        for (GroupRequest group : new TreeMap<>(groups).values()) {
            changedRows += audit ? applyAudit(request, group) : applyRevert(request, group);
        }
        Set<Long> orderIds = new LinkedHashSet<>();
        groups.values().forEach(g -> orderIds.add(g.orderId));
        for (Long orderId : orderIds) {
            orderService.refreshTotals(orderId);
            orderService.refreshAgentSummary(orderId);
            orderService.syncStatus(orderId);
            // 审核：新负数分录待计算；反审核：后续退货分录已标记未计算，需按剩余提成重算
            businessDataService.recalculateOrder(orderId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scmReturnId", request.getScmReturnId());
        result.put("billNo", request.getBillNo());
        result.put("orders", orderIds);
        result.put("changedItems", changedRows);
        log.info("SCM 销售退货反写负数分录: scmReturnId={} billNo={} status={} orders={} items={}",
                request.getScmReturnId(), request.getBillNo(), request.getAuditStatus(),
                orderIds, changedRows);
        return result;
    }

    private void validate(ScmSaleReturnSyncRequest request) {
        if (request == null || request.getCorpId() == null || request.getCorpId() <= 0) {
            throw BusinessException.forbidden("缺少退货组织信息");
        }
        if (request.getScmReturnId() == null) {
            throw BusinessException.of("缺少退货单号，无法回写业务数据");
        }
        if (request.getEntries() == null || request.getEntries().isEmpty()) {
            throw BusinessException.of("退货分录为空，无法回写业务数据");
        }
        String status = request.getAuditStatus();
        if (!AUDITED.equals(status) && !"DRAFT".equals(status)) {
            throw BusinessException.of("未知的退货审核状态：" + status);
        }
    }

    /** 解析来源行并按「订单 + 产品」归并数量；所有来源必须同订单同产品。 */
    private Map<String, GroupRequest> resolveGroups(ScmSaleReturnSyncRequest request) {
        Map<String, GroupRequest> groups = new LinkedHashMap<>();
        for (ScmSaleReturnSyncRequest.Entry entry : request.getEntries()) {
            if (entry == null) continue;
            List<OpsBusinessData> rows = resolveRows(request.getCorpId(), entry);
            if (rows.isEmpty()) {
                throw BusinessException.of("未找到退货来源业务数据，请重新选择来源");
            }
            OpsBusinessData first = rows.get(0);
            if (first.getOrderId() == null) {
                throw BusinessException.of("退货来源业务数据未关联订单头，请先执行业务数据迁移");
            }
            for (OpsBusinessData row : rows) {
                if (!Objects.equals(row.getOrderId(), first.getOrderId())
                        || !sameProduct(first, row)) {
                    throw BusinessException.of("退货来源跨多个订单/产品，请按订单拆分退货单");
                }
            }
            String key = first.getOrderId() + "|" + productKey(first);
            GroupRequest group = groups.computeIfAbsent(key,
                    k -> new GroupRequest(first.getOrderId(), first));
            group.qty += entry.getQty() != null ? entry.getQty() : 0;
        }
        return groups;
    }

    /** 解析来源行：rowIds 优先；无 rowIds 时按收据号回退。只取正常销售（非负数）分录。 */
    private List<OpsBusinessData> resolveRows(Long corpId, ScmSaleReturnSyncRequest.Entry entry) {
        List<OpsBusinessData> rows = new ArrayList<>();
        if (entry.getRowIds() != null && !entry.getRowIds().isEmpty()) {
            for (OpsBusinessData row : itemMapper.selectBatchIds(entry.getRowIds())) {
                if (Objects.equals(row.getCorpId(), corpId) && !isDeleted(row) && !isReturn(row)) {
                    rows.add(row);
                }
            }
        } else if (entry.getReceiptNos() != null && !entry.getReceiptNos().isEmpty()) {
            List<String> receipts = entry.getReceiptNos().stream()
                    .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                    .distinct().toList();
            if (!receipts.isEmpty()) {
                rows.addAll(itemMapper.selectList(new LambdaQueryWrapper<OpsBusinessData>()
                        .eq(OpsBusinessData::getCorpId, corpId)
                        .in(OpsBusinessData::getReceiptNo, receipts)
                        .eq(OpsBusinessData::getDeleteFlag, 0)));
                rows.removeIf(ScmSaleReturnSyncServiceImpl::isReturn);
            }
        }
        return rows;
    }

    /**
     * 审核通过：锁定订单全部明细 → 校验未上报 → 删除本单旧负数分录 → 按新数量重建。
     * 返回新增负数分录行数。
     */
    private int applyAudit(ScmSaleReturnSyncRequest request, GroupRequest group) {
        if (group.qty <= 0) {
            throw BusinessException.of("退货数量必须大于 0：" + productKey(group.first));
        }
        List<OpsBusinessData> orderItems = lockOrderItems(group.orderId);
        List<OpsBusinessData> positives = positivesOf(orderItems, group.first);
        if (positives.isEmpty()) {
            throw BusinessException.of("退货来源业务数据已删除，请刷新后重试");
        }
        String groupKey = groupKey(group.orderId, group.first);
        int applied = appliedQty(request.getCorpId(), request.getScmReturnId(), groupKey);
        if (applied == group.qty) {
            return 0; // 幂等重放
        }
        int q = positives.stream().mapToInt(ScmSaleReturnSyncServiceImpl::qty).max().orElse(0);
        int returnedBefore = negativeQtyOf(orderItems, group.first, request.getScmReturnId());
        if (group.qty < 0 || returnedBefore + group.qty > q) {
            throw BusinessException.of("累计退货数量(" + (returnedBefore + group.qty)
                    + ")超过原始销售数量(" + q + ")");
        }
        deleteNegativeRows(request.getScmReturnId(), group);
        for (OpsBusinessData source : positives) {
            itemMapper.insert(buildNegativeItem(request, source, group.qty));
        }
        saveApplied(request.getCorpId(), request.getScmReturnId(), groupKey, group.qty);
        return positives.size();
    }

    /** 反审核：删除本单负数分录并清空台账（重复调用幂等）。返回删除行数。 */
    private int applyRevert(ScmSaleReturnSyncRequest request, GroupRequest group) {
        lockOrderItems(group.orderId);
        int deleted = deleteNegativeRows(request.getScmReturnId(), group);
        saveApplied(request.getCorpId(), request.getScmReturnId(), groupKey(group.orderId, group.first), 0);
        // 其它 SCM 退货分录的冲减金额以「当时剩余提成」计算，本单撤销后需按新剩余重算
        jdbcTemplate.update("UPDATE ops_business_order_item SET calc_status = '未计算', modify_time = NOW() "
                        + "WHERE order_id = ? AND delete_flag = 0 AND sale_quantity < 0 "
                        + "AND source_return_id IS NOT NULL AND source_return_id <> 0 "
                        + "AND source_return_id <> ?",
                group.orderId, request.getScmReturnId());
        return deleted;
    }

    /** 锁定订单全部明细（统一按 id 顺序），同时校验没有已上报集团数据。 */
    private List<OpsBusinessData> lockOrderItems(Long orderId) {
        List<OpsBusinessData> items = jdbcTemplate.query(
                "SELECT * FROM ops_business_order_item WHERE order_id = ? AND delete_flag = 0 "
                        + "ORDER BY id FOR UPDATE",
                new BeanPropertyRowMapper<>(OpsBusinessData.class), orderId);
        for (OpsBusinessData item : items) {
            if (REPORTED_STATUS.equals(item.getCalcStatus())) {
                throw BusinessException.of("该订单已上报集团，禁止退货回写/撤销");
            }
        }
        return items;
    }

    /** 订单内同产品的正常销售分录（数量非负）。 */
    private static List<OpsBusinessData> positivesOf(List<OpsBusinessData> orderItems,
                                                     OpsBusinessData ref) {
        return orderItems.stream()
                .filter(x -> !isReturn(x) && sameProduct(ref, x)).toList();
    }

    /**
     * 同一订单同产品下、除当前退货单之外的累计退货数量。
     * 同一退货单按经办人拆成多条负数分录，数量按「退货单」取 MAX，不能逐行相加。
     */
    private static int negativeQtyOf(List<OpsBusinessData> orderItems, OpsBusinessData ref,
                                     Long scmReturnId) {
        Map<String, Integer> perBill = new HashMap<>();
        for (OpsBusinessData x : orderItems) {
            if (!isReturn(x) || !sameProduct(ref, x)) continue;
            if (Objects.equals(x.getSourceReturnId(), scmReturnId)) continue;
            String bill = Objects.toString(x.getSourceReturnId(), "0");
            perBill.merge(bill, Math.abs(qty(x)), Math::max);
        }
        return perBill.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 删除本退货单在该产品组已生成的负数分录。 */
    private int deleteNegativeRows(Long scmReturnId, GroupRequest group) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM ops_business_order_item WHERE order_id = ? AND source_return_id = ? "
                        + "AND sale_quantity < 0 AND category <=> ? AND brand <=> ? "
                        + "AND model <=> ? AND sale_price <=> ?",
                group.orderId, scmReturnId, group.first.getCategory(), group.first.getBrand(),
                group.first.getModel(), group.first.getSalePrice());
        return deleted;
    }

    /** 按原销售分录复制生成负数分录；订单级公共信息原样继承。 */
    private OpsBusinessData buildNegativeItem(ScmSaleReturnSyncRequest request,
                                              OpsBusinessData source, int qty) {
        OpsBusinessData item = new OpsBusinessData();
        copyOrderFields(item, source);
        copyProductFields(item, source, qty);
        copyBusinessFields(item, source);
        item.setRemark("SCM退货：" + Objects.toString(request.getBillNo(), ""));
        item.setSourceReturnId(request.getScmReturnId());
        item.setSourceReturnNo(request.getBillNo() != null
                ? request.getBillNo() : String.valueOf(request.getScmReturnId()));
        item.setDeleteFlag(0);
        return item;
    }

    /** 订单级公共信息（与原销售分录保持一致）。 */
    private static void copyOrderFields(OpsBusinessData item, OpsBusinessData src) {
        item.setOrderId(src.getOrderId());
        item.setCorpId(src.getCorpId());
        item.setBatchNo(src.getBatchNo());
        item.setOrderNo(src.getOrderNo());
        item.setBusinessGroupNo(src.getBusinessGroupNo());
        item.setCompany(src.getCompany());
        item.setCustomer(src.getCustomer());
        item.setCustomerPhone(src.getCustomerPhone());
        item.setPlatformMemberId(src.getPlatformMemberId());
        item.setCommunityId(src.getCommunityId());
        item.setCommunityName(src.getCommunityName());
        item.setDetailedAddress(src.getDetailedAddress());
    }

    /** 产品与金额：数量/收款金额取负，提成由订单提成引擎按剩余提成冲减。 */
    private static void copyProductFields(OpsBusinessData item, OpsBusinessData src, int qty) {
        item.setBrand(src.getBrand());
        item.setCategory(src.getCategory());
        item.setModel(src.getModel());
        item.setSalePrice(src.getSalePrice());
        item.setSaleQuantity(-qty);
        item.setReceiptAmount(amountOf(src.getSalePrice(), qty).negate());
        item.setRetailFloorPrice(src.getRetailFloorPrice());
        item.setGroupLowestPrice(src.getGroupLowestPrice());
        item.setIsSpecial(src.getIsSpecial());
        item.setCommissionRate(src.getCommissionRate());
        item.setCommissionAmount(BigDecimal.ZERO);
        item.setCalcStatus("未计算");
    }

    /** 日期/收款/经办人/凭证等字段继承原销售分录，退货冲减原销售期间。 */
    private static void copyBusinessFields(OpsBusinessData item, OpsBusinessData src) {
        item.setSaleDate(src.getSaleDate());
        item.setReceiptDate(src.getReceiptDate());
        item.setFinanceReceiptDate(src.getFinanceReceiptDate());
        item.setFundDynamicRows(src.getFundDynamicRows());
        item.setReceiptNo(src.getReceiptNo());
        item.setIsReceived(src.getIsReceived());
        item.setVoucherUrl(src.getVoucherUrl());
        item.setDepartment(src.getDepartment());
        item.setAgent(src.getAgent());
        item.setAgentPhone(src.getAgentPhone());
        item.setUsageDate(src.getUsageDate());
        item.setOperatorId(src.getOperatorId());
    }

    private static BigDecimal amountOf(BigDecimal price, int qty) {
        BigDecimal p = price != null ? price : BigDecimal.ZERO;
        return p.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
    }

    /** 本单在该产品组已回写的数量（逻辑删除视为 0）。当前读取，防事务快照重复累加。 */
    private int appliedQty(Long corpId, Long scmReturnId, String groupKey) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT applied_qty FROM ops_scm_return_sync "
                        + "WHERE corp_id = ? AND scm_return_id = ? AND group_key = ? "
                        + "AND delete_flag = 0 FOR UPDATE",
                Integer.class, corpId, scmReturnId, groupKey);
        return rows.stream().mapToInt(Integer::intValue).sum();
    }

    /** 记录本单已回写量；反审核（0）时逻辑删除，便于再次审核时重新回写。 */
    private void saveApplied(Long corpId, Long scmReturnId, String groupKey, int applied) {
        if (applied <= 0) {
            jdbcTemplate.update("UPDATE ops_scm_return_sync SET applied_qty = 0, delete_flag = 1, "
                            + "modify_time = NOW() WHERE corp_id = ? AND scm_return_id = ? AND group_key = ?",
                    corpId, scmReturnId, groupKey);
            return;
        }
        jdbcTemplate.update("INSERT INTO ops_scm_return_sync "
                        + "(corp_id, scm_return_id, group_key, applied_qty, delete_flag, create_time, modify_time) "
                        + "VALUES (?,?,?,?,0,NOW(),NOW()) "
                        + "ON DUPLICATE KEY UPDATE applied_qty = VALUES(applied_qty), delete_flag = 0, "
                        + "modify_time = NOW()",
                corpId, scmReturnId, groupKey, applied);
    }

    private static String groupKey(Long orderId, OpsBusinessData row) {
        return orderId + "|" + productKey(row);
    }

    private static boolean isReturn(OpsBusinessData row) {
        return row.getSaleQuantity() != null && row.getSaleQuantity() < 0;
    }

    private static int qty(OpsBusinessData row) {
        return row.getSaleQuantity() != null ? row.getSaleQuantity() : 1;
    }

    private static boolean isDeleted(OpsBusinessData row) {
        return row.getDeleteFlag() != null && row.getDeleteFlag() != 0;
    }

    private static boolean sameProduct(OpsBusinessData a, OpsBusinessData b) {
        return Objects.equals(a.getCategory(), b.getCategory())
                && Objects.equals(a.getBrand(), b.getBrand())
                && Objects.equals(a.getModel(), b.getModel())
                && (a.getSalePrice() == null ? b.getSalePrice() == null
                    : b.getSalePrice() != null && a.getSalePrice().compareTo(b.getSalePrice()) == 0);
    }

    private static String productKey(OpsBusinessData row) {
        return nullSafe(row.getCategory()) + "|" + nullSafe(row.getBrand()) + "|"
                + nullSafe(row.getModel()) + "|"
                + (row.getSalePrice() == null ? ""
                    : row.getSalePrice().stripTrailingZeros().toPlainString());
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v.trim();
    }

    /** 同一次退货单内同一产品组的数量归并。 */
    private static final class GroupRequest {
        private final Long orderId;
        private final OpsBusinessData first;
        private int qty;

        private GroupRequest(Long orderId, OpsBusinessData first) {
            this.orderId = orderId;
            this.first = first;
        }
    }
}
