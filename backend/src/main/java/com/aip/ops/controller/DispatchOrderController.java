package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.dto.DispatchCreateRequest;
import com.aip.ops.dto.DispatchReasonRequest;
import com.aip.ops.service.DispatchMessageService;
import com.aip.ops.service.DispatchOrderService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 派工单管理（后台运营/财务）。
 */
@RestController
@RequestMapping("/api/ops/admin/dispatch-order")
@RequiredArgsConstructor
public class DispatchOrderController {

    private final DispatchOrderService dispatchOrderService;
    private final DispatchMessageService dispatchMessageService;

    /** 发起派工；消息异步推送，不阻塞创建。 */
    @OperationLog(module = "增值业务", action = "发起派工", targetTable = "ops_dispatch_order")
    @PostMapping
    public R<?> create(@RequestBody DispatchCreateRequest request) {
        Map<String, Object> result = dispatchOrderService.create(request);
        Object id = result.get("id");
        if (id instanceof Number n) {
            dispatchMessageService.sendDispatchNoticeAsync(n.longValue());
        }
        return R.ok(result);
    }

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String status,
                     @RequestParam(required = false) String dispatchType,
                     @RequestParam(required = false) String keyword,
                     @RequestParam(required = false) Long corpId) {
        return R.ok(dispatchOrderService.list(pageNum, pageSize, status,
                dispatchType, keyword, corpId));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(dispatchOrderService.detail(id));
    }

    /** 取消待接单派工单，并异步通知员工。 */
    @OperationLog(module = "增值业务", action = "取消派工", targetTable = "ops_dispatch_order")
    @PutMapping("/{id}/cancel")
    public R<?> cancel(@PathVariable Long id,
                       @RequestBody(required = false) DispatchReasonRequest request) {
        String reason = request == null ? null : request.getReason();
        dispatchOrderService.cancel(id, reason);
        dispatchMessageService.sendCancelNoticeAsync(id);
        return R.ok();
    }

    /** 可选员工（订单归属组织的 sys_user + sys_user_corp）。 */
    @GetMapping("/assignees")
    public R<?> assignees(@RequestParam(required = false) Long corpId) {
        return R.ok(dispatchOrderService.assignees(corpId));
    }

    /** 按来源订单批量查询进行中的派工单，供业务数据列表标记「已派工」。 */
    @GetMapping("/active-by-orders")
    public R<?> activeByOrders(@RequestParam String orderIds) {
        List<Long> ids = Arrays.stream(orderIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(DispatchOrderController::parseLong)
                .filter(java.util.Objects::nonNull)
                .limit(200).toList();
        return R.ok(dispatchOrderService.activeByOrderIds(ids));
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 手动重发派工通知（员工未绑定微信等失败场景）。 */
    @PostMapping("/{id}/resend-message")
    public R<?> resendMessage(@PathVariable Long id) {
        dispatchOrderService.detail(id); // 组织数据范围校验
        dispatchMessageService.resendDispatchNotice(id);
        return R.ok();
    }

    @GetMapping("/{id}/messages")
    public R<?> messages(@PathVariable Long id) {
        Map<String, Object> detail = dispatchOrderService.detail(id);
        Object corpId = detail.get("corpId");
        Long corp = corpId instanceof Number n ? n.longValue() : null;
        return R.ok(dispatchMessageService.listByDispatch(id, corp));
    }
}
