package com.aip.ops.service.construct;

import com.aip.sdk.exception.BusinessException;

import java.util.Map;
import java.util.Set;

/**
 * 业务订单施工状态机（派工 → 待接受 → 已接受 → 已上门 → 客户已确认 → 施工中 → 待验收）。
 *
 * <p>持久化值：新状态用字符串 key（字典 business_order_status），"施工中"沿用历史
 * key <b>4</b>，保证存量订单、工作流节点配置与数值比较零迁移。客户签字后自动进入
 * 施工中，无需单独的“确认开工”。合法流转集中在 {@link #TRANSITIONS}，服务端任何
 * 跳跃都会被 {@link #requireTransition} 拒绝。</p>
 */
public final class OrderConstructStatus {

    private OrderConstructStatus() {
    }

    /** 派工出库（工作流节点 4 进入时写入）。 */
    public static final String DISPATCH = "3";
    public static final String WAIT_ACCEPT = "WAIT_ACCEPT";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String ARRIVED = "ARRIVED";
    public static final String CUSTOMER_CONFIRMED = "CUSTOMER_CONFIRMED";
    public static final String CUSTOMER_REFUSED = "CUSTOMER_REFUSED";
    /** 已开工（历史 key=4，含义收窄为「已开工」）。 */
    public static final String CONSTRUCTING = "4";
    /** 施工完成/待验收（提交验收申请后写入）。 */
    public static final String CONSTRUCTION_DONE = "5";

    public static final String LOCATION_SUCCESS = "SUCCESS";
    public static final String LOCATION_FAILED = "FAILED";
    public static final String LOCATION_MANUAL = "MANUAL";

    /** 状态流转矩阵：from → 允许到达的 to。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            // 入户随访无施工确认链：派工后保持旧口径直接进入施工中
            DISPATCH, Set.of(WAIT_ACCEPT, CONSTRUCTING),
            WAIT_ACCEPT, Set.of(ACCEPTED, DISPATCH),
            ACCEPTED, Set.of(ARRIVED),
            ARRIVED, Set.of(CUSTOMER_CONFIRMED, CUSTOMER_REFUSED),
            CUSTOMER_REFUSED, Set.of(CUSTOMER_CONFIRMED),
            CUSTOMER_CONFIRMED, Set.of(CONSTRUCTING, CUSTOMER_CONFIRMED),
            CONSTRUCTING, Set.of(CONSTRUCTION_DONE));

    /** 不满足流转规则时抛出统一错误（前端按 msg 直接提示）。 */
    public static void requireTransition(String from, String to) {
        String current = from == null ? "" : from.trim();
        Set<String> allowed = TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(to)) {
            throw BusinessException.of(
                    "INVALID_STATUS_TRANSITION：当前状态不允许此操作，请刷新后重试");
        }
    }

    /** 当前是否处于施工阶段（已开工/施工完成返工）。 */
    public static boolean isConstructing(String status) {
        return CONSTRUCTING.equals(status) || CONSTRUCTION_DONE.equals(status);
    }

    /** 是否为本次新增的开工前状态。 */
    public static boolean isPreStart(String status) {
        return WAIT_ACCEPT.equals(status) || ACCEPTED.equals(status)
                || ARRIVED.equals(status) || CUSTOMER_CONFIRMED.equals(status)
                || CUSTOMER_REFUSED.equals(status);
    }
}
