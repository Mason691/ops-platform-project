package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.service.BusinessDataReconcileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主子表对账实现（只读）。
 *
 * <p>核对口径：
 * <ul>
 *   <li>主表 total_amount = 正数分录按 订单+品类+品牌+型号+单价 去重后的 MAX(收款金额)
 *       + 退货负数分录按 退货单+产品组 取 MAX 的冲减额之和；</li>
 *   <li>主表 total_commission = 子表 commission_amount 之和（正负相抵）；</li>
 *   <li>退货分录必须为负数数量且带 source_return_id/历史标记。</li>
 * </ul>
 * 金额容差 0.01，避免 DECIMAL 舍入误报。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessDataReconcileServiceImpl implements BusinessDataReconcileService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int MAX_SAMPLE = 20;

    private final OpsBusinessOrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> reconcile() {
        Map<Long, BigDecimal> amounts = aggregate(
                "SELECT x.order_id, SUM(x.amount) AS amount FROM ("
                        + "  SELECT i.order_id, i.category, i.brand, i.model, i.sale_price, "
                        + "         MAX(i.receipt_amount) AS amount "
                        + "  FROM ops_business_order_item i "
                        + "  WHERE i.delete_flag = 0 AND i.order_id IS NOT NULL "
                        + "    AND COALESCE(i.sale_quantity, 1) >= 0 "
                        + "  GROUP BY i.order_id, i.category, i.brand, i.model, i.sale_price"
                        + "  UNION ALL "
                        + "  SELECT i.order_id, i.category, i.brand, i.model, i.sale_price, "
                        + "         MAX(i.receipt_amount) AS amount "
                        + "  FROM ops_business_order_item i "
                        + "  WHERE i.delete_flag = 0 AND i.order_id IS NOT NULL "
                        + "    AND i.sale_quantity < 0 "
                        + "  GROUP BY i.order_id, COALESCE(i.source_return_id, 0), "
                        + "           i.category, i.brand, i.model, i.sale_price"
                        + ") x GROUP BY x.order_id");
        Map<Long, BigDecimal> commissions = aggregate(
                "SELECT i.order_id, COALESCE(SUM(i.commission_amount),0) AS amount "
                        + "FROM ops_business_order_item i "
                        + "WHERE i.delete_flag = 0 AND i.order_id IS NOT NULL "
                        + "GROUP BY i.order_id");

        List<OpsBusinessOrder> orders = orderMapper.selectList(
                new LambdaQueryWrapper<OpsBusinessOrder>()
                        .eq(OpsBusinessOrder::getDeleteFlag, 0));
        List<Long> amountMismatch = new ArrayList<>();
        List<Long> commissionMismatch = new ArrayList<>();
        for (OpsBusinessOrder order : orders) {
            BigDecimal actualAmount = amounts.getOrDefault(order.getId(), BigDecimal.ZERO);
            BigDecimal masterAmount = order.getTotalAmount() != null
                    ? order.getTotalAmount() : BigDecimal.ZERO;
            if (masterAmount.subtract(actualAmount).abs().compareTo(TOLERANCE) > 0) {
                if (amountMismatch.size() < MAX_SAMPLE) amountMismatch.add(order.getId());
            }
            BigDecimal actualCommission = commissions.getOrDefault(order.getId(), BigDecimal.ZERO);
            BigDecimal masterCommission = order.getTotalCommission() != null
                    ? order.getTotalCommission() : BigDecimal.ZERO;
            if (masterCommission.subtract(actualCommission).abs().compareTo(TOLERANCE) > 0) {
                if (commissionMismatch.size() < MAX_SAMPLE) commissionMismatch.add(order.getId());
            }
        }

        // 退货分录完整性：负数数量必须带退货来源标记（新单 source_return_id 或历史 0/历史单号）
        Long returnMarkerMismatch = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ops_business_order_item "
                        + "WHERE delete_flag = 0 AND sale_quantity < 0 "
                        + "AND source_return_id IS NULL AND source_return_no IS NULL",
                Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", orders.size());
        result.put("totalAmountMismatch", amountMismatch.size());
        result.put("totalCommissionMismatch", commissionMismatch.size());
        result.put("returnMarkerMismatch",
                returnMarkerMismatch != null ? returnMarkerMismatch : 0);
        result.put("amountMismatchOrderIds", amountMismatch);
        result.put("commissionMismatchOrderIds", commissionMismatch);
        if (amountMismatch.size() > 0 || commissionMismatch.size() > 0
                || (returnMarkerMismatch != null && returnMarkerMismatch > 0)) {
            log.warn("业务数据对账发现差异: {}", result);
        } else {
            log.info("业务数据对账通过: orders={}", orders.size());
        }
        return result;
    }

    private Map<Long, BigDecimal> aggregate(String sql) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql)) {
            Object id = row.get("order_id");
            Object amount = row.get("amount");
            if (id instanceof Number n) {
                map.put(n.longValue(), amount instanceof BigDecimal b ? b
                        : amount instanceof Number num ? new BigDecimal(num.toString())
                        : BigDecimal.ZERO);
            }
        }
        return map;
    }
}
