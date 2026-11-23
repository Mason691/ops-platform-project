package com.aip.ops.service;

import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.sdk.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchEligibilityServiceTest {

    private final DispatchEligibilityService service = new DispatchEligibilityService(null);

    @Test
    void depositUnpaidAfterFinanceIsVisibleButCannotDispatch() {
        OpsBusinessOrder order = order("DEPOSIT", 0, "3", "江清兵");

        DispatchEligibilityService.Eligibility e = service.evaluate(order);

        assertThat(e.visibleInDispatchTodo()).isTrue();
        assertThat(e.canDispatch()).isFalse();
        assertThat(e.payTag()).isEqualTo("待收款");
        assertThat(e.errorCode()).isEqualTo("DISPATCH_BLOCKED_UNPAID_TAIL");
        assertThat(e.handlerName()).isEqualTo("江清兵");
        assertThat(e.reason()).contains("订金支付", "货款未结清", "【江清兵】催收尾款");
    }

    @Test
    void depositUnpaidBeforeFinanceIsHidden() {
        OpsBusinessOrder order = order("DEPOSIT", 0, "1", "江清兵");

        DispatchEligibilityService.Eligibility e = service.evaluate(order);

        assertThat(e.visibleInDispatchTodo()).isFalse();
        assertThat(e.canDispatch()).isFalse();
        assertThat(e.payTag()).isNull();
        assertThat(e.financeChecked()).isFalse();
    }

    @Test
    void depositTailPaidCanDispatch() {
        OpsBusinessOrder order = order("DEPOSIT", 1, "3", "江清兵");

        DispatchEligibilityService.Eligibility e = service.evaluate(order);

        assertThat(e.visibleInDispatchTodo()).isTrue();
        assertThat(e.canDispatch()).isTrue();
        assertThat(e.payTag()).isEqualTo("待派工");
        assertThat(e.paymentStatus()).isEqualTo("TAIL_PAID");
        assertThat(e.reason()).isEmpty();
    }

    @Test
    void fullPaidCanDispatch() {
        OpsBusinessOrder order = order("FULL", 1, "3", "江清兵");

        DispatchEligibilityService.Eligibility e = service.evaluate(order);

        assertThat(e.canDispatch()).isTrue();
        assertThat(e.payTag()).isEqualTo("待派工");
        assertThat(e.paymentStatus()).isEqualTo("FULL_PAID");
    }

    @Test
    void blankHandlerDegradesPromptWithoutEmptyBrackets() {
        OpsBusinessOrder order = order("DEPOSIT", 0, "3", null);

        DispatchEligibilityService.Eligibility e = service.evaluate(order);

        assertThat(e.handlerName()).isEmpty();
        assertThat(e.reason()).contains("请提醒经办人及时催收尾款");
        assertThat(e.reason()).doesNotContain("【】");
    }

    @Test
    void assertCanDispatchThrowsCodeAndPrompt() {
        OpsBusinessOrder order = order("DEPOSIT", 0, "3", "江清兵");

        assertThatThrownBy(() -> service.assertCanDispatch(order))
                .isInstanceOf(BusinessException.class)
                .hasMessageStartingWith("DISPATCH_BLOCKED_UNPAID_TAIL：")
                .hasMessageContaining("【江清兵】催收尾款")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    private static OpsBusinessOrder order(String payType, int full, String bizStatus,
                                          String agentNames) {
        OpsBusinessOrder order = new OpsBusinessOrder();
        order.setId(8818L);
        order.setOrderNo("23005");
        order.setPaymentType(payType);
        order.setIsFullPayment(full);
        order.setBizStatus(bizStatus);
        order.setAgentNames(agentNames);
        order.setTotalAmount(new BigDecimal("3890.00"));
        order.setDepositAmount(new BigDecimal("1000.00"));
        order.setBalanceAmount(new BigDecimal("2890.00"));
        order.setDeleteFlag(0);
        return order;
    }
}
