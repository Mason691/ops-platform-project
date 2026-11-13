package com.aip.ops.controller;

import com.aip.ops.service.FinancePermissionService;
import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 权限查询端点 — 供前端获取当前用户在 ops 模块的操作权限。
 */
@RestController
@RequestMapping("/api/ops/admin/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final OrgRoleService orgRoleService;
    private final FinancePermissionService financePermissionService;

    /**
     * 返回当前用户的权限概要。
     * 响应: { corpId, orgRoleType: "operator"|"partner"|"superadmin",
     *         canEditProducts, canEditRules, canFinanceCheck }
     */
    @GetMapping("/profile")
    public R<Map<String, Object>> profile() {
        String roleType = orgRoleService.getOrgRoleType();
        boolean canEdit = orgRoleService.isOperatorOrSuperAdmin();
        boolean canDispatch = canEdit || financePermissionService.canFinanceCheck();
        boolean canManageWxTemplate = SecurityUtils.isSuperAdmin()
                || SecurityUtils.hasPermission("ops:wx-template:manage");
        return R.ok(Map.of(
                "corpId", SecurityUtils.getCurrentCorpIdOrNull(),
                "orgRoleType", roleType,
                "canEditProducts", canEdit,
                "canEditRules", canEdit,
                "canFinanceCheck", financePermissionService.canFinanceCheck(),
                "canDispatch", canDispatch,
                "canManageWxTemplate", canManageWxTemplate
        ));
    }
}
