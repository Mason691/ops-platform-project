package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.dto.ConstructActionDtos;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.construct.OrderConstructService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 公众号员工工作台：订单施工状态链操作。
 *
 * <p>均在 {@code /api/ops/employee/**} 下，外部施工员可用；会员 SSO 令牌拒绝。
 * 服务端强校验：当前员工 = 订单施工员、当前组织 = 订单组织、状态流转合法、
 * 开工前必须已打卡且客户已签字。</p>
 */
@RestController
@RequestMapping("/api/ops/employee/business-data/orders/{orderId}/construct")
@RequiredArgsConstructor
public class EmployeeConstructController {

    private final OrderConstructService constructService;
    private final JwtTokenInspector jwtTokenInspector;

    /** 施工流转视图：状态、可操作权限、时间线、告知书与内容预览。 */
    @GetMapping
    public R<?> view(@PathVariable Long orderId) {
        if (isSso()) return R.fail(403, "仅员工可查看施工流程");
        return R.ok(constructService.view(orderId));
    }

    /** 流转日志（时间线）单独获取。 */
    @GetMapping("/logs")
    public R<?> logs(@PathVariable Long orderId) {
        if (isSso()) return R.fail(403, "仅员工可查看施工流程");
        Map<String, Object> view = constructService.view(orderId);
        return R.ok(Map.of("orderId", orderId,
                "logs", view.getOrDefault("logs", java.util.List.of())));
    }

    /** 用户告知书（当前有效 + 历史版本）。 */
    @GetMapping("/notice")
    public R<?> notice(@PathVariable Long orderId) {
        if (isSso()) return R.fail(403, "仅员工可查看用户告知书");
        Map<String, Object> view = constructService.view(orderId);
        // notice 未签署时为 null，不能用 Map.of（null value 会抛 NPE）
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("notice", view.get("notice"));
        result.put("history", view.getOrDefault("noticeHistory", java.util.List.of()));
        result.put("preview", view.getOrDefault("noticePreview", ""));
        return R.ok(result);
    }

    /** 复算签字图片哈希：核验归档凭证未被篡改。 */
    @GetMapping("/notice/verify")
    public R<?> verifyNotice(@PathVariable Long orderId) {
        if (isSso()) return R.fail(403, "仅员工可核验用户告知书");
        return R.ok(constructService.verifyNotice(orderId));
    }

    @OperationLog(module = "运营管理", action = "施工员确认接受", targetTable = "ops_business_order")
    @PostMapping("/accept")
    public R<?> accept(@PathVariable Long orderId) {
        if (isSso()) return R.fail(403, "仅员工可确认接受");
        return R.ok(constructService.accept(orderId));
    }

    @OperationLog(module = "运营管理", action = "施工员拒绝接受", targetTable = "ops_business_order")
    @PostMapping("/reject")
    public R<?> reject(@PathVariable Long orderId,
                       @RequestBody ConstructActionDtos.ReasonRequest request) {
        if (isSso()) return R.fail(403, "仅员工可拒绝接受");
        return R.ok(constructService.reject(orderId, request == null ? null : request.reason()));
    }

    @OperationLog(module = "运营管理", action = "施工上门打卡", targetTable = "ops_business_order")
    @PostMapping("/arrive")
    public R<?> arrive(@PathVariable Long orderId,
                       @RequestBody ConstructActionDtos.ArriveRequest request) {
        if (isSso()) return R.fail(403, "仅员工可上门打卡");
        return R.ok(constructService.arrive(orderId, request));
    }

    // 不进 @OperationLog：请求体含签字图片 Base64，会撑大审计日志；
    // 签字凭证本身已写 ops_customer_notice + ops_order_construct_log
    @PostMapping("/customer-confirm")
    public R<?> customerConfirm(
            @PathVariable Long orderId,
            @RequestBody ConstructActionDtos.CustomerConfirmRequest request) {
        if (isSso()) return R.fail(403, "仅员工可提交客户确认");
        return R.ok(constructService.customerConfirm(orderId, request));
    }

    @OperationLog(module = "运营管理", action = "客户拒绝确认施工",
            targetTable = "ops_business_order")
    @PostMapping("/customer-refuse")
    public R<?> customerRefuse(@PathVariable Long orderId,
                               @RequestBody ConstructActionDtos.ReasonRequest request) {
        if (isSso()) return R.fail(403, "仅员工可记录客户拒绝");
        return R.ok(constructService.customerRefuse(
                orderId, request == null ? null : request.reason()));
    }

    @OperationLog(module = "运营管理", action = "开始施工", targetTable = "ops_business_order")
    @PostMapping("/start")
    public R<?> start(@PathVariable Long orderId) {
        if (isSso()) return R.fail(403, "仅员工可开始施工");
        return R.ok(constructService.start(orderId));
    }

    private boolean isSso() {
        return "sso".equals(jwtTokenInspector.currentTokenType());
    }
}
