package com.aip.ops.service;

import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.service.construct.OrderConstructStatus;
import com.aip.ops.util.OrderPartyText;
import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 派工出库准入的统一校验（所有入口必须调用，后端节点动作是最终防线）。
 *
 * <p><b>收款语义</b>：payment_type 是制单时的付款方式（FULL/DEPOSIT，制单后不变）；
 * is_full_payment 是收款结果（定金支付补录后变 1）。派工必须已收齐全款。</p>
 *
 * <p><b>待办可见性</b>：定金支付且尾款未结清的订单，在财务核对通过后仍进入部长待办，
 * 但 {@code canDispatch=false}、标签为“待收款”；只有尾款结清后才恢复“待派工”。</p>
 *
 * <p><b>财务核对语义</b>：派工节点（biz_status=3）是在财务核对节点（biz_status=2）
 * 通过后才进入的，因此“已财务核对”判定为 biz_status ≥ 2（含派工节点自身）
 * 或存在财务核对时间/结论。不能简单写死 ==2，否则正常派工节点会被误拦。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchEligibilityService {

    private static final String PAY_TYPE_DEPOSIT = "DEPOSIT";
    private static final String PAY_TAG_WAIT_PAY = "待收款";
    private static final String PAY_TAG_WAIT_DISPATCH = "待派工";

    private final BusinessOrderService orderService;

    /** 统一校验：不满足条件抛出带错误码与具体原因的业务异常。 */
    public void assertCanDispatch(OpsBusinessOrder order) {
        Eligibility eligibility = evaluate(order);
        if (eligibility.canDispatch()) {
            return;
        }
        log.warn("派工拦截 code={}, orderNo={}, payType={}, paymentStatus={}, reason={}",
                eligibility.errorCode(), eligibility.orderNo(), eligibility.payType(),
                eligibility.paymentStatus(), eligibility.reason());
        if (isTailUnpaid(eligibility) && isBlank(eligibility.handlerName())) {
            log.warn("派工拦截经办人为空 orderNo={}，提示文案已降级", eligibility.orderNo());
        }
        throw BusinessException.of(409, eligibility.errorCode() + "：" + eligibility.reason());
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
        String payType = payTypeOf(order);
        boolean deposit = PAY_TYPE_DEPOSIT.equals(payType);
        boolean full = Integer.valueOf(1).equals(order.getIsFullPayment());
        boolean finance = financeChecked(order);
        boolean tailUnpaid = deposit && !full;
        boolean canDispatch = full && finance;
        // 定金支付经财务核对后可见（即使尾款未结清）；全款订单核对后可见
        boolean visible = canDispatch || (deposit && finance && !full);
        String handlerName = OrderPartyText.primaryName(order.getAgentNames());
        String reason = blockReason(tailUnpaid, full, finance, handlerName);
        String errorCode = errorCode(tailUnpaid, full, finance);
        String payTag = canDispatch ? PAY_TAG_WAIT_DISPATCH
                : (visible ? PAY_TAG_WAIT_PAY : null);
        return new Eligibility(order.getId(), order.getOrderNo(), canDispatch,
                full, finance, balanceOf(order), reason, errorCode, payType,
                paymentStatusOf(deposit, full), handlerName, payTag, visible);
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

    /** 定金支付未结清时优先展示催收提示；其余按财务核对/支付状态给原因。 */
    private static String blockReason(boolean tailUnpaid, boolean full, boolean finance,
                                      String handlerName) {
        if (tailUnpaid) {
            return handlerName == null || handlerName.isBlank()
                    ? "该订单为订金支付，货款未结清，不能发起派工出库，请提醒经办人及时催收尾款。"
                    : "该订单为订金支付，货款未结清，不能发起派工出库，请提醒经办人【"
                    + handlerName + "】催收尾款。";
        }
        if (!finance) return "该订单尚未财务核对，暂不能派工出库";
        if (!full) return "该订单未支付，不能派工";
        return "";
    }

    private static String errorCode(boolean tailUnpaid, boolean full, boolean finance) {
        if (tailUnpaid) return "DISPATCH_BLOCKED_UNPAID_TAIL";
        if (!finance) return "DISPATCH_BLOCKED_FINANCE_UNCHECKED";
        if (!full) return "DISPATCH_BLOCKED_UNPAID";
        return "";
    }

    private static String payTypeOf(OpsBusinessOrder order) {
        String raw = order.getPaymentType();
        return PAY_TYPE_DEPOSIT.equalsIgnoreCase(raw == null ? "" : raw.trim())
                ? PAY_TYPE_DEPOSIT : "FULL";
    }

    private static String paymentStatusOf(boolean deposit, boolean full) {
        if (deposit) return full ? "TAIL_PAID" : "DEPOSIT_PAID";
        return full ? "FULL_PAID" : "UNPAID";
    }

    private static boolean isTailUnpaid(Eligibility eligibility) {
        return "DISPATCH_BLOCKED_UNPAID_TAIL".equals(eligibility.errorCode());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 单个订单的派工资格结果（供待办过滤、任务页提示与订单详情复用）。 */
    public record Eligibility(Long orderId, String orderNo, boolean canDispatch,
                              boolean fullPayment, boolean financeChecked,
                              BigDecimal balanceAmount, String reason,
                              String errorCode, String payType, String paymentStatus,
                              String handlerName, String payTag,
                              boolean visibleInDispatchTodo) {}
}
