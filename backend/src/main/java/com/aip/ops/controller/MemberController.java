package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.service.MemberService;
import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 会员管理 CRUD。
 *
 * <p>权限：运营商管理员可查看/启用禁用；城市合伙人仅可查看与自己相关的会员。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final OrgRoleService orgRoleService;

    /** 分页查询会员列表，支持手机号、注册时间、状态筛选 */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String phone,
                     @RequestParam(required = false) String startDate,
                     @RequestParam(required = false) String endDate,
                     @RequestParam(required = false) Integer status) {
        return R.ok(memberService.page(pageNum, pageSize, phone, startDate, endDate, status));
    }

    /** 查询会员详情（含底座 sys_member 信息） */
    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(memberService.getDetail(id));
    }

    /** 启用/禁用会员 */
    @OperationLog(module = "运营管理", action = "修改", targetTable = "ops_member")
    @PutMapping("/{id}/status")
    public R<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        // 仅运营商/超管可操作状态
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail(403, "仅运营商管理员可操作");
        }
        Integer status = body.get("status");
        memberService.updateStatus(id, status);
        return R.ok(status != null && status == 1 ? "已启用" : "已禁用");
    }
}
