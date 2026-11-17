package com.aip.ops.security.qr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 收款码每日巡检：绕过缓存重新验签，校验失败记 ERROR 供日志平台告警。
 *
 * <p>plan A 下资源在 jar 内不可运行期替换，但每日巡检可发现“jar 被替换/资源损坏”
 * 等异常，并与启动校验、每次取图校验形成三层防线。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QrCodeIntegrityScheduler {

    private final QrCodeIntegrityService qrCodeIntegrityService;

    @Scheduled(cron = "${security.qr.patrol-cron:0 0 3 * * *}")
    public void patrol() {
        QrCodeIntegrityService.Verification verification = qrCodeIntegrityService.verifyNow();
        if (!verification.valid()) {
            log.error("PAYMENT_QR_INTEGRITY_PATROL_FAILED reason={} imageSha256={}",
                    verification.reason(), verification.sha256());
            return;
        }
        log.info("PAYMENT_QR_INTEGRITY_PATROL_OK imageSha256={}", verification.sha256());
    }
}
