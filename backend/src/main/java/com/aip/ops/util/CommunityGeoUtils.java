package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 员工 H5 小区定位的坐标校验（服务端兜底，前端已先做一次）。
 * 坐标系统一 GCJ-02，WGS-84 必须在 H5 端转换后提交。
 */
public final class CommunityGeoUtils {

    private static final Set<String> SOURCES = Set.of("WECHAT", "H5", "MANUAL", "GEOCODE");
    private static final int MAX_DETAIL_ADDRESS_LENGTH = 120;

    private CommunityGeoUtils() {
    }

    /** 坐标成对出现且范围合法；coordinateType 为空按 GCJ02，非 GCJ02 直接拒绝。 */
    public static void validate(BigDecimal longitude, BigDecimal latitude, String coordinateType) {
        if (longitude == null && latitude == null) return;
        if (longitude == null || latitude == null) {
            throw BusinessException.of("定位坐标不合法");
        }
        double lng = longitude.doubleValue();
        double lat = latitude.doubleValue();
        if (lng < -180 || lng > 180 || lat < -90 || lat > 90 || (lng == 0 && lat == 0)) {
            throw BusinessException.of("定位坐标不合法");
        }
        if (coordinateType != null && !coordinateType.isBlank()
                && !"GCJ02".equalsIgnoreCase(coordinateType.trim())) {
            throw BusinessException.of("定位坐标仅支持 GCJ-02");
        }
    }

    /** 定位来源只允许 WECHAT/H5/MANUAL；空值由调用方按 MANUAL 处理。 */
    public static String normalizeSource(String source) {
        if (source == null || source.isBlank()) return "MANUAL";
        String normalized = source.trim().toUpperCase();
        if (!SOURCES.contains(normalized)) {
            throw BusinessException.of("定位来源不合法");
        }
        return normalized;
    }

    /** 详细地址长度与 sys_community.address VARCHAR(500) 对齐。 */
    public static String normalizeAddress(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 500) {
            throw BusinessException.of("小区详细地址不能超过500个字符");
        }
        return trimmed;
    }

    /** 手动地理编码的输入只允许区县之后的地址，长度与前端输入框一致（1~120）。 */
    public static String normalizeDetailAddress(String detailAddress) {
        String trimmed = detailAddress == null ? "" : detailAddress.trim();
        if (trimmed.isEmpty()) {
            throw BusinessException.of("请填写详细地址（区县之后的部分，如 XX路XX号）");
        }
        if (trimmed.length() > MAX_DETAIL_ADDRESS_LENGTH) {
            throw BusinessException.of("详细地址不能超过" + MAX_DETAIL_ADDRESS_LENGTH + "个字符");
        }
        return trimmed;
    }
}
