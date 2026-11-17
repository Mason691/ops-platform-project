package com.aip.ops.controller;

import com.aip.ops.dto.ScmSaleReturnSyncRequest;
import com.aip.ops.service.ScmSaleReturnSyncService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 内部接口：供 SCM 销售退货审核/反审核回写业务数据子表。
 * 通过 X-Internal-Token 认证（与底座 platform.internal-token 一致）。
 */
@RestController
@RequestMapping("/api/internal/business-data")
@RequiredArgsConstructor
public class InternalBusinessDataController {

    private final ScmSaleReturnSyncService scmSaleReturnSyncService;

    @Value("${platform.internal-token}")
    private String internalToken;

    @PostMapping("/scm-sale-return")
    public R<?> syncScmSaleReturn(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody ScmSaleReturnSyncRequest request) {
        if (token == null || !token.equals(internalToken)) {
            return R.forbidden();
        }
        if (request == null || request.getCorpId() == null || request.getCorpId() <= 0) {
            return R.fail("缺少退货组织信息");
        }
        // 内部调用无请求级 SecurityContext：按退货组织重建，否则 TenantLineHandler 跳过租户过滤、
        // DataScopeService 返回空集导致提成规则匹配失效（退回归属组织自身的可见范围）。
        withCorpContext(request.getCorpId());
        try {
            return R.ok(scmSaleReturnSyncService.sync(request));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void withCorpContext(Long corpId) {
        LoginUser user = new LoginUser();
        user.setUserId(0L);
        user.setCorpId(corpId);
        user.setUsername("scm-internal");
        user.setAdmin(false);
        user.setPermissions(List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
