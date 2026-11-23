package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 货款（尾款）补录收款信息校验：收款日期、选填收据号。
 *
 * <p>与制单收据号保持同一字符集规则；收款日期必须为 yyyy-MM-dd 且不得晚于今天。</p>
 */
public final class BalanceReceiptRules {

    private static final int MAX_RECEIPT_NO_LENGTH = 50;
    private static final Pattern RECEIPT_NO_PATTERN = Pattern.compile("^[A-Za-z0-9_/-]+$");

    private BalanceReceiptRules() {
    }

    /** 尾款收款日期：必填，yyyy-MM-dd，不得晚于今天。 */
    public static String normalizeDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw BusinessException.of("请选择尾款收款日期");
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.isAfter(LocalDate.now())) {
                throw BusinessException.of("尾款收款日期不能晚于今天");
            }
            return date.toString();
        } catch (DateTimeParseException e) {
            throw BusinessException.of("尾款收款日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    /** 尾款收据号选填；填写时校验长度与字符集（与制单收据号同规则）。 */
    public static String normalizeOptionalReceiptNo(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if (value.length() > MAX_RECEIPT_NO_LENGTH) {
            throw BusinessException.of("尾款收据号不能超过" + MAX_RECEIPT_NO_LENGTH + "个字符");
        }
        if (!RECEIPT_NO_PATTERN.matcher(value).matches()) {
            throw BusinessException.of("尾款收据号只能包含字母、数字、短横线、下划线和斜杠");
        }
        return value;
    }
}
