package com.aip.ops.controller;

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
 *
 * <p>财务核对、派工已改为工作流驱动：处理人由工作流节点角色/审批人决定，
 * 不再由 ops 页面按钮权限控制。</p>
 */
@RestController
@RequestMapping("/api/ops/admin/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final OrgRoleService orgRoleService;

    /**
     * 返回当前用户的权限概要。
     * 响应: { corpId, orgRoleType: "operator"|"partner"|"superadmin",
     *         canEditProducts, canEditRules }
     */
    @GetMapping("/profile")
    public R<Map<String, Object>> profile() {
        String roleType = orgRoleService.getOrgRoleType();
        boolean canEdit = orgRoleService.isOperatorOrSuperAdmin();
        return R.ok(Map.of(
                "corpId", SecurityUtils.getCurrentCorpIdOrNull(),
                "orgRoleType", roleType,
                "canEditProducts", canEdit,
                "canEditRules", canEdit
        ));
    }
}
