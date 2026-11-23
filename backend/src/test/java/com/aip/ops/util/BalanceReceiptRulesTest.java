package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceReceiptRulesTest {

    @Test
    void acceptsTodayAndPastDate() {
        String today = LocalDate.now().toString();
        assertThat(BalanceReceiptRules.normalizeDate(today)).isEqualTo(today);
        assertThat(BalanceReceiptRules.normalizeDate("2026-01-01")).isEqualTo("2026-01-01");
        assertThat(BalanceReceiptRules.normalizeDate(" " + today + " ")).isEqualTo(today);
    }

    @Test
    void rejectsFutureOrMalformedDate() {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        assertThatThrownBy(() -> BalanceReceiptRules.normalizeDate(tomorrow))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能晚于今天");
        assertThatThrownBy(() -> BalanceReceiptRules.normalizeDate("2026/01/01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式不正确");
        assertThatThrownBy(() -> BalanceReceiptRules.normalizeDate(" "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择尾款收款日期");
    }

    @Test
    void optionalReceiptNoAllowsBlankAndValidValue() {
        assertThat(BalanceReceiptRules.normalizeOptionalReceiptNo(null)).isNull();
        assertThat(BalanceReceiptRules.normalizeOptionalReceiptNo("  ")).isNull();
        assertThat(BalanceReceiptRules.normalizeOptionalReceiptNo(" SK-2026/001 ")).isEqualTo("SK-2026/001");
    }

    @Test
    void optionalReceiptNoRejectsBadCharsAndOverlongValue() {
        assertThatThrownBy(() -> BalanceReceiptRules.normalizeOptionalReceiptNo("SK 001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能包含");
        assertThatThrownBy(() -> BalanceReceiptRules.normalizeOptionalReceiptNo("A".repeat(51)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过");
    }
}
