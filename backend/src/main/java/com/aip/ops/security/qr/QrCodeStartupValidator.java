package com.aip.ops.security.qr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动时校验收款码完整性。
 *
 * <p>生产默认 fail-fast：图片/签名缺失或验签失败直接拒绝启动，避免带病运行；
 * 开发/测试可通过 {@code security.qr.fail-fast=false} 降级为 ERROR 告警。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QrCodeStartupValidator implements InitializingBean {

    private final QrCodeIntegrityService qrCodeIntegrityService;

    @Value("${security.qr.fail-fast:true}")
    private boolean failFast;

    @Override
    public void afterPropertiesSet() {
        QrCodeIntegrityService.Verification verification = qrCodeIntegrityService.verifyNow();
        if (!verification.valid()) {
            log.error("PAYMENT_QR_INTEGRITY_FAILED startup reason={} imageSha256={} failFast={}",
                    verification.reason(), verification.sha256(), failFast);
            if (failFast) {
                throw new IllegalStateException("收款码完整性校验失败，拒绝启动");
            }
            return;
        }
        log.info("PAYMENT_QR_INTEGRITY_OK startup imageSha256={}", verification.sha256());
    }
}
