package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.entity.OpsWxMsgTemplate;
import com.aip.ops.service.OrgRoleService;
import com.aip.ops.service.WxMsgTemplateService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 公众号消息模板管理（运营商组织员工/超管）。
 *
 * <p>模板消息限制：员工必须已关注公众号；未绑定微信或未关注时推送会失败，
 * 派工单侧记录原因并支持重发，不阻断派工。</p>
 */
@RestController
@RequestMapping("/api/ops/admin/wx-msg-template")
@RequiredArgsConstructor
public class WxMsgTemplateController {

    private static final String PERMISSION = "ops:wx-template:manage";

    private final WxMsgTemplateService templateService;
    private final OrgRoleService orgRoleService;

    @GetMapping
    public R<?> list() {
        requirePermission();
        return R.ok(templateService.list());
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        requirePermission();
        OpsWxMsgTemplate template = templateService.getById(id);
        if (template == null) return R.fail("模板不存在");
        return R.ok(template);
    }

    @GetMapping("/scenes")
    public R<?> scenes() {
        requirePermission();
        return R.ok(List.of(
                Map.of("code", "DISPATCH_NOTICE", "name", "派工通知"),
                Map.of("code", "DISPATCH_CANCELLED", "name", "派工取消通知")));
    }

    @OperationLog(module = "运营管理", action = "新增消息模板", targetTable = "ops_wx_msg_template")
    @PostMapping
    public R<?> create(@RequestBody OpsWxMsgTemplate template) {
        requirePermission();
        template.setId(null);
        if (template.getCorpId() == null) template.setCorpId(0L);
        return R.ok(templateService.saveTemplate(template));
    }

    @OperationLog(module = "运营管理", action = "修改消息模板", targetTable = "ops_wx_msg_template")
    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody OpsWxMsgTemplate template) {
        requirePermission();
        template.setId(id);
        return R.ok(templateService.saveTemplate(template));
    }

    @OperationLog(module = "运营管理", action = "停用消息模板", targetTable = "ops_wx_msg_template")
    @PutMapping("/{id}/status")
    public R<?> status(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        requirePermission();
        OpsWxMsgTemplate template = templateService.getById(id);
        if (template == null) return R.fail("模板不存在");
        Integer status = body == null ? null : body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            return R.fail("状态值必须为0或1");
        }
        template.setStatus(status);
        return R.ok(templateService.saveTemplate(template));
    }

    @OperationLog(module = "运营管理", action = "删除消息模板", targetTable = "ops_wx_msg_template")
    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        requirePermission();
        templateService.removeById(id);
        return R.ok();
    }

    private void requirePermission() {
        // 运营商组织员工（非城市合伙人）均可维护本组织模板；超管/显式权限码同样放行
        if (!SecurityUtils.isSuperAdmin()
                && !SecurityUtils.hasPermission(PERMISSION)
                && !orgRoleService.isOperatorOrSuperAdmin()) {
            throw BusinessException.forbidden("无权限：" + PERMISSION);
        }
    }
}
