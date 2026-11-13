package com.aip.ops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 派工消息推送线程池。
 *
 * <p>与导入线程池隔离：消息推送是轻量 HTTP 调用，但微信接口偶发超时，
 * 不能阻塞派工单创建或业务导入。队列满时由调用线程执行（CallerRuns），
 * 保证消息不丢；推送失败只记录，不影响主流程。</p>
 */
@Configuration
public class DispatchAsyncConfig {

    @Bean(name = "dispatchMessageExecutor")
    public Executor dispatchMessageExecutor() {
        AtomicInteger index = new AtomicInteger(1);
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "dispatch-msg-" + index.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
