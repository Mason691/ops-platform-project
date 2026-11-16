package com.aip.ops.controller;

import com.aip.sdk.exception.BusinessException;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.OrgRoleService;
import com.aip.ops.service.PaymentQrService;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工 H5 - 收款码取图。
 *
 * <p>员工 JWT 鉴权；取员工所属运营商的收款码，每次读取重新计算 SHA-256，
 * 与登记值不一致时返回 JSON 业务错误并记录告警。</p>
 */
@RestController
@RequestMapping("/api/ops/employee/business-data")
@RequiredArgsConstructor
public class EmployeePaymentQrController {

    private final PaymentQrService paymentQrService;
    private final OrgRoleService orgRoleService;
    private final JwtTokenInspector jwtTokenInspector;

    @GetMapping("/payment-qr")
    public ResponseEntity<byte[]> paymentQr() {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            throw BusinessException.forbidden("仅员工可查看收款码");
        }
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("登录组织信息无效，请重新登录");
        }
        Long owner = orgRoleService.getOperatorCorpId(corpId);
        PaymentQrService.VerifiedQr qr = paymentQrService.loadVerified(owner);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(qr.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(qr.bytes());
    }
}
