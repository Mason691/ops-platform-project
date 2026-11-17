package com.aip.ops.scheduler;

import com.aip.ops.service.BusinessDataReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日业务数据主子表对账（默认 01:30）。只读，发现差异仅记录告警日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessDataReconcileScheduler {

    private final BusinessDataReconcileService reconcileService;

    @Scheduled(cron = "${business-data.reconcile.cron:0 30 1 * * *}")
    public void reconcile() {
        try {
            reconcileService.reconcile();
        } catch (Exception e) {
            log.error("业务数据每日对账失败", e);
        }
    }
}
