package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinuteRateLimiterTest {

    @Test
    void allowsUpToLimitThenRejectsSameKey() {
        MinuteRateLimiter limiter = new MinuteRateLimiter(2);
        assertThatCode(() -> limiter.check(1L)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check(1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过于频繁");
    }

    @Test
    void differentKeysHaveIndependentWindows() {
        MinuteRateLimiter limiter = new MinuteRateLimiter(1);
        assertThatCode(() -> limiter.check(1L)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check(2L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(1L))
                .isInstanceOf(BusinessException.class);
    }
}
