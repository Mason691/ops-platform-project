package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.entity.OpsAnnualPlan;
import com.aip.ops.service.AnnualPlanService;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

/**
 * 年度任务计划管理 — CRUD + 审核 + 汇总 + Excel 导入。
 *
 * <p>权限：运营商/超管可增删改审；城市合伙人仅可查看。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/annual-plans")
@RequiredArgsConstructor
public class AnnualPlanController {

    private final AnnualPlanService service;
    private final OrgRoleService orgRoleService;
    private final DataScopeService dataScopeService;

    // ═══ 列表 ═══

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) Integer year,
                     @RequestParam(required = false) Long orgId,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) Integer status) {
        return R.ok(service.page(pageNum, pageSize, year, orgId, category, status));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        OpsAnnualPlan plan = service.getById(id);
        if (plan == null) return R.fail("记录不存在");
        // Data scope check: ensure the plan's org is visible to current user
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (visibleIds != null && !visibleIds.contains(plan.getOrgId())) {
            return R.fail(403, "无权查看该记录");
        }
        return R.ok(plan);
    }

    // ═══ 写操作（仅运营商） ═══

    @OperationLog(module = "增值业务", action = "新增", targetTable = "ops_annual_plan")
    @PostMapping
    public R<?> create(@RequestBody OpsAnnualPlan plan) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.create(plan);
        return R.ok("创建成功");
    }

    @OperationLog(module = "增值业务", action = "修改", targetTable = "ops_annual_plan")
    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody OpsAnnualPlan plan) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        try {
            service.update(id, plan);
            return R.ok("更新成功");
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    @OperationLog(module = "增值业务", action = "删除", targetTable = "ops_annual_plan")
    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        try {
            service.delete(id);
            return R.ok("删除成功");
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    @OperationLog(module = "增值业务", action = "审核", targetTable = "ops_annual_plan")
    @PutMapping("/{id}/approve")
    public R<?> approve(@PathVariable Long id) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        try {
            service.approve(id);
            return R.ok("审核通过");
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    // ═══ 汇总查询（所有角色可查看） ═══

    @GetMapping("/summary")
    public R<?> summary(@RequestParam(required = false) Integer year) {
        return R.ok(service.summary(year));
    }

    @GetMapping("/summary-by-category")
    public R<?> summaryByCategory(@RequestParam(required = false) Integer year) {
        return R.ok(service.summaryByCategory(year));
    }

    @GetMapping("/org-detail")
    public R<?> orgDetail(@RequestParam(required = false) Integer year) {
        return R.ok(service.orgDetail(year));
    }

    // ═══ Excel 导入（仅运营商） ═══

    @OperationLog(module = "增值业务", action = "导入", targetTable = "ops_annual_plan")
    @PostMapping("/import")
    public R<?> importExcel(@RequestParam("file") MultipartFile file) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        Long corpId = SecurityUtils.getCurrentCorpId();
        Map<String, Object> result = service.importExcel(file, corpId);
        return R.ok(result);
    }
}
