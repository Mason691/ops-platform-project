package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.sdk.exception.BusinessException;
import com.aip.ops.service.OrgRoleService;
import com.aip.ops.service.PaymentQrService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 平台后台 - 收款码配置（运营商维护，城市合伙人只读查看所属运营商）。
 */
@RestController
@RequestMapping("/api/ops/admin/payment-qr")
@RequiredArgsConstructor
public class AdminPaymentQrController {

    private final PaymentQrService paymentQrService;
    private final OrgRoleService orgRoleService;

    @GetMapping
    public R<?> metadata() {
        return R.ok(paymentQrService.metadata(ownerForRead()));
    }

    @OperationLog(module = "增值业务", action = "上传收款码", targetTable = "ops_payment_qr_config")
    @PostMapping("/upload")
    public R<?> upload(@RequestParam("file") MultipartFile file) {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请切换到具体运营商组织后上传收款码");
        }
        if (!orgRoleService.isOperatorOrSuperAdmin() || orgRoleService.isCityPartner(corpId)) {
            throw BusinessException.forbidden("仅运营商管理员可上传收款码");
        }
        return R.ok(paymentQrService.upload(file, corpId));
    }

    /** 后台预览：同样执行哈希校验。 */
    @GetMapping("/image")
    public ResponseEntity<byte[]> image() {
        PaymentQrService.VerifiedQr qr = paymentQrService.loadVerified(ownerForRead());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(qr.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(qr.bytes());
    }

    /** 运营商看自身，城市合伙人看父运营商，超管需切换组织。 */
    private Long ownerForRead() {
        Long current = SecurityUtils.getCurrentCorpIdOrNull();
        if (current == null || current <= 0) {
            throw BusinessException.of("请切换到具体运营商组织后维护收款码");
        }
        return orgRoleService.getOperatorCorpId(current);
    }
}
