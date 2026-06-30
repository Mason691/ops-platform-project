package com.aip.ops.controller;

import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.service.OrgRoleService;
import com.aip.ops.service.ValueAddedProductService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 增值业务 - 产品管理 CRUD。
 *
 * 权限：运营商管理员可增删改查；城市合伙人仅可查看。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/value-added/product")
@RequiredArgsConstructor
public class ValueAddedProductController {

    private final ValueAddedProductService service;
    private final OrgRoleService orgRoleService;

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) String brand,
                     @RequestParam(required = false) Integer status) {
        return R.ok(service.page(pageNum, pageSize, category, brand, status));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<?> create(@RequestBody OpsValueAddedProduct product) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        product.setCorpId(SecurityUtils.getCurrentCorpId());
        if (product.getOperatorId() == null) product.setOperatorId(product.getCorpId());
        service.create(product);
        return R.ok("创建成功");
    }

    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody OpsValueAddedProduct product) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.update(id, product);
        return R.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.delete(id);
        return R.ok("删除成功");
    }

    /**
     * 当前用户在该模块的权限概要，供前端渲染按钮。
     */
    @GetMapping("/permission/profile")
    public R<Map<String, Object>> permissionProfile() {
        String roleType = orgRoleService.getOrgRoleType();
        boolean canEdit = orgRoleService.isOperatorOrSuperAdmin();
        return R.ok(Map.of(
                "corpId", SecurityUtils.getCurrentCorpIdOrNull(),
                "orgRoleType", roleType,
                "canEditProducts", canEdit
        ));
    }
}
