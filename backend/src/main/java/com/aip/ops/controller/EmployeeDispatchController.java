package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.dto.DispatchCreateRequest;
import com.aip.ops.dto.DispatchReasonRequest;
import com.aip.ops.service.EmployeeDispatchService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 公众号 H5 员工端「待处理 / 我的工单」。
 */
@RestController
@RequestMapping("/api/ops/employee/dispatch")
@RequiredArgsConstructor
public class EmployeeDispatchController {

    private final EmployeeDispatchService employeeDispatchService;

    /** 待接单列表（当前员工本人）。 */
    @GetMapping("/pending")
    public R<?> pending(@RequestParam(defaultValue = "1") int pageNum,
                        @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(employeeDispatchService.listPending(pageNum, pageSize));
    }

    /** 我的工单（历史；status 可选）。 */
    @GetMapping("/mine")
    public R<?> mine(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String status) {
        return R.ok(employeeDispatchService.listMine(pageNum, pageSize, status));
    }

    // ═══ 员工 H5 发起派工（任何员工；会员 SSO 令牌拒绝） ═══

    /** 当前员工是否可发起派工（H5 入口显隐）。 */
    @GetMapping("/manage/can-dispatch")
    public R<?> canDispatch() {
        return R.ok(employeeDispatchService.canDispatch());
    }

    /** 本组织可派工订单（已财务核对或户内美装，且无进行中派工）。 */
    @GetMapping("/manage/orders")
    public R<?> dispatchableOrders(@RequestParam(defaultValue = "1") int pageNum,
                                   @RequestParam(defaultValue = "10") int pageSize,
                                   @RequestParam(required = false) String keyword) {
        return R.ok(employeeDispatchService.listDispatchableOrders(pageNum, pageSize, keyword));
    }

    /** 可派工订单详情（客户信息 + 可施工商品分组）。 */
    @GetMapping("/manage/orders/{orderId}")
    public R<?> dispatchableOrder(@PathVariable Long orderId) {
        return R.ok(employeeDispatchService.dispatchableOrder(orderId));
    }

    /** 订单归属组织的可指派员工。 */
    @GetMapping("/manage/assignees")
    public R<?> dispatchAssignees() {
        return R.ok(employeeDispatchService.dispatchAssignees());
    }

    /** 发起派工（仅本组织订单），创建后异步推送公众号通知。 */
    @OperationLog(module = "员工工单", action = "发起派工", targetTable = "ops_dispatch_order")
    @PostMapping("/manage/create")
    public R<?> createDispatch(@RequestBody DispatchCreateRequest request) {
        return R.ok(employeeDispatchService.createDispatch(request));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(employeeDispatchService.detail(id));
    }

    /** 接受任务：施工类型自动生成未审核销售出库单（SCM）。 */
    @OperationLog(module = "员工工单", action = "接受任务", targetTable = "ops_dispatch_order")
    @PostMapping("/{id}/accept")
    public R<?> accept(@PathVariable Long id) {
        return R.ok(employeeDispatchService.accept(id));
    }

    @OperationLog(module = "员工工单", action = "开始处理", targetTable = "ops_dispatch_order")
    @PostMapping("/{id}/start")
    public R<?> start(@PathVariable Long id) {
        return R.ok(employeeDispatchService.start(id));
    }

    @OperationLog(module = "员工工单", action = "完成工单", targetTable = "ops_dispatch_order")
    @PostMapping("/{id}/complete")
    public R<?> complete(@PathVariable Long id) {
        return R.ok(employeeDispatchService.complete(id));
    }

    @OperationLog(module = "员工工单", action = "退回工单", targetTable = "ops_dispatch_order")
    @PostMapping("/{id}/reject")
    public R<?> reject(@PathVariable Long id,
                       @RequestBody(required = false) DispatchReasonRequest request) {
        return R.ok(employeeDispatchService.reject(id,
                request == null ? null : request.getReason()));
    }
}
