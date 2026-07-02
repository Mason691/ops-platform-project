package com.aip.ops.service;

import com.aip.ops.kafka.ChatResultMessage;
import com.aip.ops.kafka.ChatStatusMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接管理器。
 * 按 taskId 管理 SseEmitter 实例，支持多端同时监听同一任务。
 * 消费方（AiChatStatusConsumer / AiChatResultConsumer）通过此组件推送事件。
 */
@Slf4j
@Component
public class SseEmitterManager {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 默认 SSE 超时（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    public SseEmitterManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 为指定 taskId 创建一个新的 SseEmitter 并注册。
     * 调用方（Controller）返回此 emitter 给前端。
     */
    public SseEmitter createEmitter(String taskId) {
        return createEmitter(taskId, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 为指定 taskId 创建一个新的 SseEmitter 并注册。
     */
    public SseEmitter createEmitter(String taskId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        List<SseEmitter> list = emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE timeout for taskId={}", taskId);
            removeEmitter(taskId, emitter);
        });
        emitter.onError(e -> {
            log.debug("SSE error for taskId={}: {}", taskId, e.getMessage());
            removeEmitter(taskId, emitter);
        });

        log.debug("SSE emitter created for taskId={}, total emitters for task: {}", taskId, list.size());
        return emitter;
    }

    /**
     * 推送状态事件到指定 taskId 的所有监听方。
     */
    public void sendStatus(String taskId, ChatStatusMessage status) {
        sendEvent(taskId, "status", status);
    }

    /**
     * 推送结果事件到指定 taskId 的所有监听方，并完成所有 emitter。
     */
    public void sendResult(String taskId, ChatResultMessage result) {
        sendEvent(taskId, "result", result);
        completeAll(taskId);
    }

    /**
     * 推送通用事件。
     */
    private void sendEvent(String taskId, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(eventName)
                            .data(json));
                } catch (IOException e) {
                    log.debug("Failed to send SSE for taskId={}: {}", taskId, e.getMessage());
                    removeEmitter(taskId, emitter);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to serialize SSE event for taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 完成指定 taskId 的所有 emitter。
     */
    private void completeAll(String taskId) {
        List<SseEmitter> list = emitters.remove(taskId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // emitter may already be completed
                }
            }
            log.debug("All SSE emitters completed for taskId={}", taskId);
        }
    }

    /**
     * 从注册表中移除单个 emitter。
     */
    private void removeEmitter(String taskId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(taskId);
            }
        }
    }

    /**
     * 返回当前活跃的 emitter 数量（用于监控）。
     */
    public int activeEmitterCount() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 返回当前活跃的 taskId 数量（用于监控）。
     */
    public int activeTaskCount() {
        return emitters.size();
    }
}
