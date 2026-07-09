package com.aip.ops.scheduler;

import com.aip.ops.service.BattleMapEtlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 作战地图 ETL 首次初始化：应用就绪后按需全量重算所有活跃组织。
 * 由 {@code battle-map.etl.init-on-startup=true} 开启（单实例部署）；在后台线程执行，
 * 不阻塞启动。定时增量刷新由 {@link BattleMapEtlScheduler} 承担。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BattleMapEtlBootstrap {

    private final BattleMapEtlService etl;

    @Value("${battle-map.etl.init-on-startup:false}")
    private boolean initOnStartup;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!initOnStartup) return;
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                log.info("battle-map init ETL start");
                etl.refreshAllOrgs();
                log.info("battle-map init ETL done in {}ms", System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.warn("battle-map init ETL failed: {}", e.getMessage());
            }
        }, "battle-map-init-etl").start();
    }
}
