package com.aip.ops.scheduler;

import com.aip.ops.service.BattleMapEtlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 作战地图 ETL 定时增量刷新。默认关闭——仅在单实例部署时通过
 * {@code battle-map.etl.auto-refresh=true} 开启，避免多容器共同调度重复重算。
 * 全量首刷 / 数据变更后的即时刷新走 {@code POST /refresh-all}、{@code /refresh-cache}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "battle-map.etl.auto-refresh", havingValue = "true")
public class BattleMapEtlScheduler {

    private final BattleMapEtlService etl;

    @Scheduled(cron = "${battle-map.etl.cron:0 */30 * * * *}")
    public void scheduledRefresh() {
        long t0 = System.currentTimeMillis();
        etl.refreshAllOrgs();
        log.info("battle-map scheduled ETL done in {}ms", System.currentTimeMillis() - t0);
    }
}
