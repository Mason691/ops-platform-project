package com.aip.ops.util;

/**
 * 订单经办人文本解析：多经办人时取第一个非空值，不猜测、不轮询。
 *
 * <p>完工验收审批人、施工完成提示的经办人必须使用同一口径，
 * 避免不同页面各自拆分 {@code agent_names}/{@code agent_phones} 导致展示不一致。</p>
 */
public final class OrderPartyText {

    private OrderPartyText() {
    }

    public static String primaryName(String names) {
        return firstNonBlank(names);
    }

    public static String primaryPhone(String phones) {
        return firstNonBlank(phones);
    }

    private static String firstNonBlank(String value) {
        String text = value == null ? "" : value;
        for (String item : text.split("[、,，;；]")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }
}
