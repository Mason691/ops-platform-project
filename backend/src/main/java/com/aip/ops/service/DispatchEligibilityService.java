package com.aip.ops.service;

import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.service.construct.OrderConstructStatus;
import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 派工出库准入的统一校验（所有入口必须调用，后端节点动作是最终防线）。
 *
 * <p><b>收款语义</b>：payment_type 是制单时的付款方式（FULL/DEPOSIT，制单后不变）；
 * is_full_payment 是收款结果（定金支付补录后变 1）。派工只看 is_full_payment，
 * 不看 payment_type——定金支付补录完成后同样可以派工。</p>
 *
 * <p><b>财务核对语义</b>：派工节点（biz_status=3）是在财务核对节点（biz_status=2）
 * 通过后才进入的，因此“已财务核对”判定为 biz_status ≥ 2（含派工节点自身）
 * 或存在财务核对时间/结论。不能简单写死 ==2，否则正常派工节点会被误拦。</p>
 */
@Service
@RequiredArgsConstructor
public class DispatchEligibilityService {

    private final BusinessOrderService orderService;

    /** 统一校验：不满足条件抛出带具体原因的业务异常。 */
    public void assertCanDispatch(OpsBusinessOrder order) {
        Eligibility eligibility = evaluate(order);
        if (!eligibility.canDispatch()) {
            throw BusinessException.of(eligibility.reason());
        }
    }

    public void assertCanDispatch(Long orderId) {
        assertCanDispatch(orderService.getById(orderId));
    }

    public Eligibility evaluate(Long orderId) {
        return evaluate(orderService.getById(orderId));
    }

    public Eligibility evaluate(OpsBusinessOrder order) {
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        boolean full = Integer.valueOf(1).equals(order.getIsFullPayment());
        boolean finance = financeChecked(order);
        BigDecimal balance = balanceOf(order);
        String reason = reason(full, finance, balance);
        return new Eligibility(order.getId(), order.getOrderNo(), full && finance,
                full, finance, balance, reason);
    }

    /** 财务核对已完成：状态 2（财务核对）或 3+（已进入派工/后续节点），或已落核对时间/结论。 */
    private boolean financeChecked(OpsBusinessOrder order) {
        String raw = order.getBizStatus() == null ? "" : order.getBizStatus().trim();
        // 新施工状态链是字符串 key，不能用 parseInt 判断；它们都在财务核对之后
        if (OrderConstructStatus.isPreStart(raw) || OrderConstructStatus.isConstructing(raw)
                || "6".equals(raw) || "7".equals(raw)) {
            return true;
        }
        int status = parseInt(raw, -1);
        if (status >= 2) return true;
        if (order.getFinanceCheckTime() != null) return true;
        return "PASS".equalsIgnoreCase(order.getFinanceCheckConclusion());
    }

    private BigDecimal balanceOf(OpsBusinessOrder order) {
        BigDecimal balance = order.getBalanceAmount();
        if (balance == null) {
            BigDecimal total = order.getTotalAmount() == null
                    ? BigDecimal.ZERO : order.getTotalAmount();
            BigDecimal deposit = order.getDepositAmount() == null
                    ? BigDecimal.ZERO : order.getDepositAmount();
            balance = total.subtract(deposit);
        }
        return balance.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private String reason(boolean full, boolean finance, BigDecimal balance) {
        if (full && finance) return "";
        String noFull = "该订单为定金支付，尚有 ¥" + balance.toPlainString()
                + " 余款未收，请先在【货款补录】完成收款";
        String noFinance = "该订单尚未财务核对，暂不能派工出库";
        if (!full && !finance) return noFull + "；且" + noFinance;
        return full ? noFinance : noFull;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 单个订单的派工资格结果（供 H5/PC 待办过滤与任务页提示复用）。 */
    public record Eligibility(Long orderId, String orderNo, boolean canDispatch,
                              boolean fullPayment, boolean financeChecked,
                              BigDecimal balanceAmount, String reason) {}
}
