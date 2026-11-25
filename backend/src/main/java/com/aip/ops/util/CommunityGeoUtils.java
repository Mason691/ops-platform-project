package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 员工 H5 小区定位的坐标校验（服务端兜底，前端已先做一次）。
 * 坐标系统一 GCJ-02，WGS-84 必须在 H5 端转换后提交。
 */
public final class CommunityGeoUtils {

    private static final Set<String> SOURCES = Set.of("WECHAT", "H5", "MANUAL");

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
}
