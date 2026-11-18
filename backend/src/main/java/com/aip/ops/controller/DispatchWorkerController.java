package com.aip.ops.controller;

import com.aip.ops.dto.DispatchWorkerDtos;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.DispatchEligibilityService;
import com.aip.ops.service.DispatchWorkerAuditService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 派工出库 — 施工员搜索与现场新增外部施工员。
 *
 * <p>corpId 一律从订单解析（服务端校验数据范围），不接收前端传参；
 * 用户类型固定外部、角色固定施工员，由平台内部接口落库。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/business-data/dispatch-worker")
@RequiredArgsConstructor
public class DispatchWorkerController {

    private static final String ASSIGN_PERMISSION = "biz:dispatch:assign";

    private final BusinessDataService businessDataService;
    private final DispatchEligibilityService dispatchEligibilityService;
    private final DispatchWorkerAuditService auditService;
    private final PlatformInternalClient platformClient;
    private final ObjectMapper objectMapper;

    @GetMapping("/check-phone")
    public R<DispatchWorkerDtos.WorkerCheckResp> checkPhone(
            @RequestParam Long orderId,
            @RequestParam String phone) {
        requireAssignPermission(orderId);
        Long corpId = resolveOrderCorp(orderId);
        String path = "/api/internal/dispatch-worker/check-phone?corpId=" + corpId
                + "&phone=" + URLEncoder.encode(phone, StandardCharsets.UTF_8);
        Map<String, Object> data = unwrap(platformClient.get(path, null));
        return R.ok(objectMapper.convertValue(data, DispatchWorkerDtos.WorkerCheckResp.class));
    }

    @PostMapping("/external")
    public R<DispatchWorkerDtos.ExternalWorkerCreateResp> createExternal(
            @RequestBody DispatchWorkerDtos.ExternalWorkerCreateReq req) {
        if (req == null || req.orderId() == null) {
            throw BusinessException.of("缺少订单ID");
        }
        requireAssignPermission(req.orderId());
        Long corpId = resolveOrderCorp(req.orderId());
        // 与派工节点同一准入：不可派工的订单不允许在现场新增外部施工员
        dispatchEligibilityService.assertCanDispatch(req.orderId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corpId", corpId);
        body.put("phone", req.phone());
        body.put("realName", req.name());
        body.put("confirmSameName", Boolean.TRUE.equals(req.confirmSameName()));
        body.put("operatorUserId", SecurityUtils.getCurrentUserId());
        body.put("operatorName", safeOperatorName(SecurityUtils.getCurrentUser() == null
                ? null : SecurityUtils.getCurrentUser().getUsername()));
        Map<String, Object> data = unwrap(platformClient.post(
                "/api/internal/dispatch-worker/create", body, null));
        DispatchWorkerDtos.ExternalWorkerCreateResp result = objectMapper.convertValue(data,
                DispatchWorkerDtos.ExternalWorkerCreateResp.class);
        // 操作审计（@OperationLog）不落明文手机号；结果与脱敏手机号留在应用日志便于追查
        log.info("新增外部施工员结果: orderId={}, corpId={}, phone={}, userId={}, reused={}, "
                        + "newNatural={}, sameNameConfirmRequired={}, operatorUserId={}",
                req.orderId(), corpId, result.phoneMasked(), result.userId(), result.reused(),
                result.newNaturalPerson(), result.sameNameConfirmRequired(),
                SecurityUtils.getCurrentUserId());
        if (!result.sameNameConfirmRequired()) {
            auditService.record(new DispatchWorkerDtos.ExternalWorkerAuditEvent(
                    req.orderId(), corpId, result.userId(), result.userName(),
                    result.phoneMasked(), result.reused(), result.newNaturalPerson(),
                    Boolean.TRUE.equals(req.confirmSameName())));
        }
        return R.ok(result);
    }

    /** 数据范围与订单存在性由 orderDetail 校验，返回订单归属组织。 */
    private Long resolveOrderCorp(Long orderId) {
        Map<String, Object> detail = businessDataService.orderDetail(orderId);
        Object corp = detail.get("corpId");
        if (corp instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        throw BusinessException.of("订单归属组织不存在，无法新增施工员");
    }

    /**
     * 派工权限：超管或显式权限码直接放行；否则必须是当前派工节点处理人。
     * 仅依赖角色菜单授权会在新组织/历史角色未初始化时把真正的派工员挡在门外，
     * 因此把「待办任务归属」作为等价的服务端授权依据。
     */
    private void requireAssignPermission(Long orderId) {
        if (SecurityUtils.isSuperAdmin()) return;
        if (SecurityUtils.hasPermission(ASSIGN_PERMISSION)) return;
        businessDataService.assertPendingDispatchTask(orderId, SecurityUtils.getCurrentUserId());
    }

    /** 员工 username 通常是手机号；落日志前脱敏，避免把操作人手机号写进明文。 */
    private static String safeOperatorName(String username) {
        if (username == null) return null;
        return username.matches("^1\\d{10}$")
                ? com.aip.common.util.MaskUtil.maskPhone(username) : username;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrap(Map<String, Object> resp) {
        Object code = resp == null ? null : resp.get("code");
        if (code instanceof Number n && n.intValue() != 200) {
            Object msg = resp.get("msg");
            throw BusinessException.of(msg instanceof String s && !s.isBlank()
                    ? s : "平台处理失败");
        }
        Object data = resp == null ? null : resp.get("data");
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
