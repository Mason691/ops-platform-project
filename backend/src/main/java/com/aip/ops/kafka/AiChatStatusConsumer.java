package com.aip.ops.kafka;

import com.aip.ops.service.AiChatTaskService;
import com.aip.ops.service.SseEmitterManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * AI 对话状态更新 Kafka 消费者。
 * 消费 ai-chat-status 消息，通过 SseEmitterManager 推送给前端，同时更新 DB 进度。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class AiChatStatusConsumer {

    private final SseEmitterManager sseEmitterManager;
    private final AiChatTaskService taskService;
    private final ObjectMapper objectMapper;

    private static final String TOPIC_AI_CHAT_STATUS = "ai-chat-status";

    public AiChatStatusConsumer(SseEmitterManager sseEmitterManager,
                                 AiChatTaskService taskService,
                                 ObjectMapper objectMapper) {
        this.sseEmitterManager = sseEmitterManager;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC_AI_CHAT_STATUS, groupId = "ai-platform-group")
    public void handleStatus(String message) {
        try {
            ChatStatusMessage status = objectMapper.readValue(message, ChatStatusMessage.class);
            String taskId = status.getTaskId();
            if (taskId == null) {
                log.warn("AI chat status message without taskId: {}", message);
                return;
            }

            log.debug("AI chat status: taskId={} status={} phase={} progress={}",
                    taskId, status.getStatus(), status.getPhase(), status.getProgress());

            // 更新 DB 进度
            try {
                taskService.updateProgress(taskId, status.getPhase(), status.getMessage());
            } catch (Exception e) {
                log.warn("Failed to update AI chat task progress: taskId={} error={}", taskId, e.getMessage());
            }

            // 推送到所有监听此 taskId 的 SSE 客户端
            sseEmitterManager.sendStatus(taskId, status);

        } catch (Exception e) {
            log.error("Failed to process AI chat status message: {}", message, e);
        }
    }
}
