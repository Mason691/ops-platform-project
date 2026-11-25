package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityGeoUtilsTest {

    @Test
    void acceptsMissingOrValidCoordinates() {
        assertThatCode(() -> CommunityGeoUtils.validate(null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> CommunityGeoUtils.validate(
                new BigDecimal("103.8"), new BigDecimal("36.0"), "gcj02"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPartialOutOfRangeOrZeroCoordinates() {
        assertThatThrownBy(() -> CommunityGeoUtils.validate(new BigDecimal("103.8"), null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("定位坐标不合法");
        assertThatThrownBy(() -> CommunityGeoUtils.validate(
                new BigDecimal("103.8"), new BigDecimal("91"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("定位坐标不合法");
        assertThatThrownBy(() -> CommunityGeoUtils.validate(BigDecimal.ZERO, BigDecimal.ZERO, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("定位坐标不合法");
    }

    @Test
    void rejectsWgs84() {
        assertThatThrownBy(() -> CommunityGeoUtils.validate(
                new BigDecimal("103.8"), new BigDecimal("36.0"), "WGS84"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("GCJ-02");
    }

    @Test
    void normalizesSourceAndAddress() {
        assertThat(CommunityGeoUtils.normalizeSource(null)).isEqualTo("MANUAL");
        assertThat(CommunityGeoUtils.normalizeSource("wechat")).isEqualTo("WECHAT");
        assertThat(CommunityGeoUtils.normalizeAddress("  ")).isNull();
        assertThat(CommunityGeoUtils.normalizeAddress(" 中山路1号 ")).isEqualTo("中山路1号");
        assertThatThrownBy(() -> CommunityGeoUtils.normalizeAddress("x".repeat(501)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("500");
    }
}
