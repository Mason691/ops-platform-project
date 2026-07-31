package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.entity.OpsCategoryLifecycleRule;
import com.aip.ops.service.CategoryLifecycleRuleService;
import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 品类使用年限规则管理 — CRUD + 品类下拉选项 + 按品类查询。
 *
 * 权限：运营商/超管可增删改查；城市合伙人仅可查看。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/category-lifecycle-rules")
@RequiredArgsConstructor
public class CategoryLifecycleRuleController {

    private final CategoryLifecycleRuleService service;
    private final OrgRoleService orgRoleService;

    /** 分页列表 */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String category) {
        return R.ok(service.page(pageNum, pageSize, category));
    }

    /** 品类下拉选项（供前端筛选框使用） */
    @GetMapping("/category-options")
    public R<?> categoryOptions() {
        return R.ok(service.getCategoryOptions());
    }

    /** 按品类查询规则 */
    @GetMapping("/by-category/{category}")
    public R<?> byCategory(@PathVariable String category) {
        return R.ok(service.getByCategory(category));
    }

    /** 详情 */
    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.getById(id));
    }

    /** 新增 */
    @OperationLog(module = "增值业务", action = "新增", targetTable = "ops_category_lifecycle_rule")
    @PostMapping
    public R<?> create(@RequestBody OpsCategoryLifecycleRule rule) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        rule.setCorpId(SecurityUtils.getCurrentCorpId());
        service.create(rule);
        return R.ok("创建成功");
    }

    /** 编辑 */
    @OperationLog(module = "增值业务", action = "修改", targetTable = "ops_category_lifecycle_rule")
    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody OpsCategoryLifecycleRule rule) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.update(id, rule);
        return R.ok("更新成功");
    }

    /** 单个删除 */
    @OperationLog(module = "增值业务", action = "删除", targetTable = "ops_category_lifecycle_rule")
    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.delete(id);
        return R.ok("删除成功");
    }

    /** 批量删除 */
    @OperationLog(module = "增值业务", action = "批量删除", targetTable = "ops_category_lifecycle_rule")
    @DeleteMapping("/batch")
    public R<?> batchDelete(@RequestBody java.util.Map<String, List<Long>> body) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.batchDelete(body.get("ids"));
        return R.ok("批量删除成功");
    }
}
