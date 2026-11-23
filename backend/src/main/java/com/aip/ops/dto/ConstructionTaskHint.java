package com.aip.ops.dto;

/**
 * 施工完成节点的页面文案（后端唯一来源，前端只渲染不拼接）。
 *
 * <p>施工员取订单已指派的唯一施工员（{@code order.worker_id}），经办人取订单主经办人，
 * 与完工验收审批人使用同一份订单数据，保证页面、按钮、成功提示与公众号消息口径一致。</p>
 */
public record ConstructionTaskHint(
        String workerName,
        String orderNo,
        String handlerName,
        String buttonText,
        String hintText) {

    public static final String BUTTON_SUBMIT_ACCEPTANCE = "提交验收申请";

    public static ConstructionTaskHint of(String workerName, String orderNo, String handlerName) {
        String worker = blankToDefault(workerName, "未指派");
        String order = blankToDefault(orderNo, "");
        String handler = blankToDefault(handlerName, "订单经办人");
        String hint = "施工任务完成后，施工员（" + worker + "）需向订单编号（" + order
                + "）经办人：" + handler + " 提交施工完成验收，确认施工完成。";
        return new ConstructionTaskHint(worker, order, handler,
                BUTTON_SUBMIT_ACCEPTANCE, hint);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
