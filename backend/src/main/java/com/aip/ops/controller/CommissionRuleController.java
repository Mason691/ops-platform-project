package com.aip.ops.controller;

import com.aip.ops.entity.OpsCompanyCommissionRule;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.service.CommissionRuleService;
import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 提成规则管理 — 个人/公司双表 CRUD。
 *
 * 权限：运营商管理员可增删改查；城市合伙人仅可查看。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/commission")
@RequiredArgsConstructor
public class CommissionRuleController {

    private final CommissionRuleService service;
    private final OrgRoleService orgRoleService;

    // ═══ 个人提成规则 ═══

    @GetMapping("/personal")
    public R<?> personalList(@RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "10") int pageSize,
                             @RequestParam(required = false) String category,
                             @RequestParam(required = false) Integer status) {
        Map<String, Object> page = service.personalPage(pageNum, pageSize, category, status);
        return R.ok(page);
    }

    @GetMapping("/personal/{id}")
    public R<?> personalDetail(@PathVariable Long id) {
        return R.ok(service.personalGetById(id));
    }

    @PostMapping("/personal")
    public R<?> personalCreate(@RequestBody OpsPersonalCommissionRule rule) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        Long corpId = SecurityUtils.getCurrentCorpId();
        rule.setCorpId(corpId);
        if (rule.getOperatorId() == null) rule.setOperatorId(corpId);
        service.personalCreate(rule);
        return R.ok("创建成功");
    }

    @PutMapping("/personal/{id}")
    public R<?> personalUpdate(@PathVariable Long id, @RequestBody OpsPersonalCommissionRule rule) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.personalUpdate(id, rule);
        return R.ok("更新成功");
    }

    @DeleteMapping("/personal/{id}")
    public R<?> personalDelete(@PathVariable Long id) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.personalDelete(id);
        return R.ok("删除成功");
    }

    // ═══ 公司提成规则 ═══

    @GetMapping("/company")
    public R<?> companyList(@RequestParam(defaultValue = "1") int pageNum,
                            @RequestParam(defaultValue = "10") int pageSize,
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) Integer status) {
        Map<String, Object> page = service.companyPage(pageNum, pageSize, category, status);
        return R.ok(page);
    }

    @GetMapping("/company/{id}")
    public R<?> companyDetail(@PathVariable Long id) {
        return R.ok(service.companyGetById(id));
    }

    @PostMapping("/company")
    public R<?> companyCreate(@RequestBody OpsCompanyCommissionRule rule) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        Long corpId = SecurityUtils.getCurrentCorpId();
        rule.setCorpId(corpId);
        if (rule.getOperatorId() == null) rule.setOperatorId(corpId);
        service.companyCreate(rule);
        return R.ok("创建成功");
    }

    @PutMapping("/company/{id}")
    public R<?> companyUpdate(@PathVariable Long id, @RequestBody OpsCompanyCommissionRule rule) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.companyUpdate(id, rule);
        return R.ok("更新成功");
    }

    @DeleteMapping("/company/{id}")
    public R<?> companyDelete(@PathVariable Long id) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        service.companyDelete(id);
        return R.ok("删除成功");
    }

    // ═══ 品类字典（供前端下拉框） ═══

    @GetMapping("/categories")
    public R<?> categories() {
        return R.ok(service.getCategories());
    }
}
