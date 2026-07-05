package com.aip.ops.kafka;

import com.aip.ops.service.AiChatTaskService;
import com.aip.ops.service.SseEmitterManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * AI 对话最终结果 Kafka 消费者。
 * 消费 ai-chat-result 消息，通过 SseEmitterManager 推送最终回复给前端，并更新 DB。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class AiChatResultConsumer {

    private final SseEmitterManager sseEmitterManager;
    private final AiChatTaskService taskService;
    private final ObjectMapper objectMapper;

    private static final String TOPIC_AI_CHAT_RESULT = "ai-chat-result";

    public AiChatResultConsumer(SseEmitterManager sseEmitterManager,
                                 AiChatTaskService taskService,
                                 ObjectMapper objectMapper) {
        this.sseEmitterManager = sseEmitterManager;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC_AI_CHAT_RESULT, groupId = "ai-platform-group")
    public void handleResult(String message) {
        try {
            ChatResultMessage result = objectMapper.readValue(message, ChatResultMessage.class);
            String taskId = result.getTaskId();
            if (taskId == null) {
                log.warn("AI chat result message without taskId: {}", message);
                return;
            }

            log.info("AI chat result: taskId={} status={} partial={}",
                    taskId, result.getStatus(), result.getPartialResults());

            // 更新 DB 为最终状态
            try {
                if ("COMPLETED".equals(result.getStatus())) {
                    boolean partial = Boolean.TRUE.equals(result.getPartialResults());
                    taskService.markDone(taskId, result.getReply(), result.getConversationId(),
                            partial, result.getReportToken());
                } else if ("FAILED".equals(result.getStatus()) || "TIMEOUT".equals(result.getStatus())) {
                    String errorMsg = result.getReply() != null ? result.getReply() : "AI 服务异常";
                    taskService.markFailed(taskId, errorMsg);
                }
            } catch (Exception e) {
                log.warn("Failed to update AI chat task result: taskId={} error={}", taskId, e.getMessage());
            }

            // 推送到所有监听此 taskId 的 SSE 客户端，并完成连接
            sseEmitterManager.sendResult(taskId, result);

        } catch (Exception e) {
            log.error("Failed to process AI chat result message: {}", message, e);
        }
    }
}
