package com.aip.ops.config;

import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.SysImportTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.concurrent.*;

/**
 * 业务数据两段式导入（check/confirm）的异步执行基础设施：
 * <ul>
 *   <li>importExecutor：后台处理线程池（2~4 线程 + 有界队列，满载拒绝并提示重试）</li>
 *   <li>启动清扫：进程重启后上一进程遗留的 PENDING/PROCESSING 任务无人消费，
 *       且 hasPendingTask 并发守卫会永久阻塞后续导入 —— 启动时统一标记失败</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AsyncImportSupport {

    private final SysImportTaskMapper taskMapper;

    public AsyncImportSupport(SysImportTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Bean(name = "importExecutor")
    public ExecutorService importExecutor() {
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "import-worker");
                    t.setDaemon(false);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    void sweepInterruptedTasks() {
        int n = taskMapper.update(null, Wrappers.<SysImportTask>lambdaUpdate()
                .in(SysImportTask::getTaskType, "business_data_check", "business_data_confirm")
                .in(SysImportTask::getStatus, "PENDING", "PROCESSING")
                .set(SysImportTask::getStatus, "FAILED")
                .set(SysImportTask::getPhase, "RESTART")
                .set(SysImportTask::getErrorMsg, "服务重启导致任务中断，请重新导入")
                .set(SysImportTask::getFinishTime, LocalDateTime.now())
                .set(SysImportTask::getModifyTime, LocalDateTime.now()));
        if (n > 0) log.warn("Swept {} interrupted import tasks to FAILED on startup", n);
    }
}
