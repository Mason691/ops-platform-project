package com.aip.ops.scheduler;

import com.aip.ops.entity.OpsDispatchOrder;
import com.aip.ops.mapper.OpsDispatchOrderMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 接单处理中（ACCEPTING）超时恢复。
 *
 * <p>员工接单先占用 ACCEPTING，再调用 SCM 生成出库单；若进程在调用期间崩溃，
 * 工单会停留在 ACCEPTING。超过 10 分钟自动回退 PENDING，员工重试时 SCM 按
 * dispatchId 幂等返回已生成的出库单，不会重复出库。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchAcceptRecoveryScheduler {

    private static final int STALE_MINUTES = 10;

    private final OpsDispatchOrderMapper dispatchMapper;

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void recoverStaleAccepting() {
        int updated = dispatchMapper.update(null, new LambdaUpdateWrapper<OpsDispatchOrder>()
                .eq(OpsDispatchOrder::getStatus, "ACCEPTING")
                .lt(OpsDispatchOrder::getAcceptLockTime,
                        LocalDateTime.now().minusMinutes(STALE_MINUTES))
                .set(OpsDispatchOrder::getStatus, "PENDING")
                .set(OpsDispatchOrder::getAcceptLockTime, null));
        if (updated > 0) {
            log.warn("已回退超时接单处理中的派工单 {} 条（ACCEPTING → PENDING）", updated);
        }
    }
}
