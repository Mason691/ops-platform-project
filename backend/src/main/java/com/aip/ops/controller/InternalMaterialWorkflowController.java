package com.aip.ops.controller;

import com.aip.ops.config.MaterialWorkflowProperties;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.service.MaterialRequisitionService;
import com.aip.ops.service.MaterialWriteOffService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部接口：底座工作流 → 物资领用/核销状态回调。
 *
 * <p>X-Internal-Token fail-closed；无 JWT，服务层按业务 ID 直接更新。
 * NODE_ENTERED 只维护当前节点展示；INSTANCE_COMPLETED/REJECTED/TERMINATED 更新业务状态。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/workflow/material")
@RequiredArgsConstructor
public class InternalMaterialWorkflowController {

    private final MaterialRequisitionService requisitionService;
    private final MaterialWriteOffService writeOffService;
    private final MaterialWorkflowProperties properties;

    @Value("${platform.internal-token:}")
    private String internalToken;

    @PostMapping("/event")
    public R<?> event(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                      @RequestBody WorkflowBusinessEvent event) {
        if (!isValidToken(token)) {
            log.warn("物资工作流回调：X-Internal-Token 校验失败");
            return R.forbidden();
        }
        if (event == null || event.getBusinessType() == null) {
            return R.fail("回调事件为空");
        }
        if (properties.getRequisitionBusinessType().equals(event.getBusinessType())) {
            requisitionService.handleWorkflowEvent(event);
        } else if (properties.getWriteOffBusinessType().equals(event.getBusinessType())) {
            writeOffService.handleWorkflowEvent(event);
        } else {
            return R.ok(Map.of("ignored", true, "businessType", event.getBusinessType()));
        }
        return R.ok(Map.of("accepted", true));
    }

    /** fail-closed：未配置 token 时拒绝，避免内部回调裸奔。 */
    private boolean isValidToken(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            log.error("platform.internal-token 未配置，拒绝物资工作流回调");
            return false;
        }
        return internalToken.equals(token);
    }
}
