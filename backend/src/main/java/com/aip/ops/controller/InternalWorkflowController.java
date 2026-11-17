package com.aip.ops.controller;

import com.aip.ops.dto.WorkflowBusinessEvent;
import com.aip.ops.service.BusinessOrderFlowActionService;
import com.aip.ops.service.BusinessOrderWorkflowService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 内部接口：平台工作流组件 → ops 业务回调。
 *
 * <p>X-Internal-Token 认证；不依赖 JWT（平台异步线程没有员工身份）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/workflow")
@RequiredArgsConstructor
public class InternalWorkflowController {

    private final BusinessOrderFlowActionService actionService;

    @Value("${platform.internal-token:}")
    private String internalToken;

    @PostMapping("/business-order/event")
    public R<?> businessOrderEvent(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody WorkflowBusinessEvent event) {
        if (!isValidToken(token)) {
            log.warn("工作流业务回调：X-Internal-Token 校验失败");
            return R.forbidden();
        }
        if (!BusinessOrderWorkflowService.BUSINESS_TYPE.equals(event.getBusinessType())) {
            return R.fail("不支持的业务类型：" + event.getBusinessType());
        }
        Map<String, Object> result = actionService.handle(event);
        return R.ok(result);
    }

    private boolean isValidToken(String token) {
        if (internalToken == null || internalToken.isEmpty()) {
            log.warn("platform.internal-token 未配置，接受内部工作流回调");
            return true;
        }
        return internalToken.equals(token);
    }
}
