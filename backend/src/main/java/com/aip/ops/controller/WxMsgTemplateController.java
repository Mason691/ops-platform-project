package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.entity.OpsWxMsgTemplate;
import com.aip.ops.service.WxMsgTemplateService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 公众号消息模板管理（运营商共享 + 子公司自建）。
 *
 * <p>运营商可维护本组织模板并共享给下属；城市合伙人对共享模板只读，
 * 可维护本公司 PRIVATE 模板。权限与归属校验在 Service 内完成。</p>
 */
@RestController
@RequestMapping("/api/ops/admin/wx-msg-template")
@RequiredArgsConstructor
public class WxMsgTemplateController {

    private final WxMsgTemplateService templateService;

    /** 当前用户可见模板（运营商含下属自建审计；城市合伙人含共享+自建）。 */
    @GetMapping
    public R<?> list(@RequestParam(required = false) String sceneCode,
                     @RequestParam(required = false) Integer status,
                     @RequestParam(required = false) Long ownerCorpId,
                     @RequestParam(required = false) String keyword) {
        return R.ok(templateService.listForCurrentUser(sceneCode, status, ownerCorpId, keyword));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(templateService.detailForCurrentUser(id));
    }

    @GetMapping("/scenes")
    public R<?> scenes() {
        return R.ok(List.of(
                Map.of("code", "DISPATCH_NOTICE", "name", "派工通知"),
                Map.of("code", "DISPATCH_CANCELLED", "name", "派工取消通知")));
    }

    /** 当前组织可选模板（仅启用）：业务配置选择模板用。 */
    @GetMapping("/available")
    public R<?> available(@RequestParam(required = false) String sceneCode) {
        return R.ok(templateService.availableForCurrentUser(sceneCode));
    }

    @OperationLog(module = "运营管理", action = "新增消息模板", targetTable = "ops_wx_msg_template")
    @PostMapping
    public R<?> create(@RequestBody OpsWxMsgTemplate template) {
        return R.ok(templateService.createForCurrentUser(template));
    }

    @OperationLog(module = "运营管理", action = "修改消息模板", targetTable = "ops_wx_msg_template")
    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody OpsWxMsgTemplate template) {
        return R.ok(templateService.updateForCurrentUser(id, template));
    }

    @OperationLog(module = "运营管理", action = "启停消息模板", targetTable = "ops_wx_msg_template")
    @PutMapping("/{id}/status")
    public R<?> status(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        templateService.changeStatusForCurrentUser(id, body == null ? null : body.get("status"));
        return R.ok();
    }

    @OperationLog(module = "运营管理", action = "删除消息模板", targetTable = "ops_wx_msg_template")
    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        templateService.deleteForCurrentUser(id);
        return R.ok();
    }

    /** 测试发送到当前登录员工微信（运营商/归属组织可测试共享/自建模板）。 */
    @OperationLog(module = "运营管理", action = "测试发送消息模板", targetTable = "ops_wx_msg_template")
    @PostMapping("/{id}/test")
    public R<?> test(@PathVariable Long id) {
        return R.ok(templateService.testSendForCurrentUser(id));
    }
}
