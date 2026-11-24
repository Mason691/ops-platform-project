package com.aip.ops.controller;

import com.aip.ops.dto.UpwardPurchaseDtos;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.UpwardPurchaseService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 员工工作台「向上采购申请」：列表、待发起角标、发起后状态回写。
 *
 * <p>权限沿用原「已核对业务」：内部用户 + {@code scm:pur-order:h5:create}；
 * 业务员基础角色默认不授予，外部用户即使误配权限也拒绝。</p>
 */
@RestController
@RequestMapping("/api/ops/employee/upward-purchase")
@RequiredArgsConstructor
public class EmployeeUpwardPurchaseController {

    private static final String PERMISSION = "scm:pur-order:h5:create";

    private final UpwardPurchaseService upwardPurchaseService;
    private final JwtTokenInspector jwtTokenInspector;

    @GetMapping("/page")
    public R<?> page(@RequestParam(required = false) String status,
                     @RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "50") int pageSize) {
        requirePermission();
        return R.ok(upwardPurchaseService.page(
                SecurityUtils.getCurrentCorpId(), status, pageNum, pageSize));
    }

    @GetMapping("/pending-count")
    public R<?> pendingCount() {
        requirePermission();
        return R.ok(new UpwardPurchaseDtos.PendingCount(
                upwardPurchaseService.pendingCount(SecurityUtils.getCurrentCorpId())));
    }

    /** 发起前取该订单全部待发起明细（不受列表分页影响）。 */
    @GetMapping("/pending-items")
    public R<?> pendingItems(@RequestParam Long orderId) {
        requirePermission();
        return R.ok(upwardPurchaseService.pendingItems(
                SecurityUtils.getCurrentCorpId(), orderId));
    }

    /** H5 调用 SCM 发起采购申请成功后回写状态；SCM 已存在申请时也调用本接口对齐。 */
    @PostMapping("/requested")
    public R<?> markRequested(@RequestBody UpwardPurchaseDtos.MarkRequestedRequest request) {
        requirePermission();
        int updated = upwardPurchaseService.markRequested(
                SecurityUtils.getCurrentCorpId(), request);
        return R.ok(Map.of("updated", updated));
    }

    private void requirePermission() {
        if (jwtTokenInspector.isExternalUser()) {
            throw BusinessException.forbidden("外部用户无权限访问向上采购申请");
        }
        if (!SecurityUtils.isSuperAdmin() && !SecurityUtils.hasPermission(PERMISSION)) {
            throw BusinessException.forbidden("无权限访问向上采购申请");
        }
    }
}
