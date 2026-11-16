package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;

/**
 * 支付方式（订单级）：二维码 / 现金 / 转账。
 *
 * <p>前端分段选择器提交中文或英文代码均可，落库统一英文代码；
 * 旧字段 is_received 保留历史，不再用于新单。</p>
 */
public enum PaymentMethod {

    QR("二维码"),
    CASH("现金"),
    TRANSFER("转账");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 必填校验 + 中文/代码归一化。 */
    public static PaymentMethod normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw BusinessException.of("请选择支付方式");
        for (PaymentMethod method : values()) {
            if (method.name().equalsIgnoreCase(value) || method.label.equals(value)) {
                return method;
            }
        }
        throw BusinessException.of("支付方式不合法");
    }

    public static String labelOf(String code) {
        if (code == null || code.isBlank()) return null;
        for (PaymentMethod method : values()) {
            if (method.name().equalsIgnoreCase(code.trim())) return method.label;
        }
        return code;
    }
}
