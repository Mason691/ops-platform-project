package com.aip.ops.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 对话异步任务 Kafka 生产者。
 * 将用户提交的对话请求序列化为 ai-chat-task 消息发送到 Kafka。
 */
@Slf4j
@Component
public class AiChatTaskProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC_AI_CHAT_TASK = "ai-chat-task";

    public AiChatTaskProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交异步 AI 对话任务到 Kafka。
     *
     * @param corpId           租户 ID
     * @param userId           用户 ID
     * @param memberId         统一会员 ID（会员端传，员工端不传）
     * @param conversationId   对话 ID（多轮对话）
     * @param message          用户消息原文
     * @param subsystem        来源子系统标识
     * @param employeeContext  员工上下文（H5 端可能为 null）
     * @param history          对话历史
     * @return taskId
     */
    public String publishTask(Long corpId, Long userId, Long memberId,
                               Long conversationId, String message,
                               String subsystem, Map<String, Object> employeeContext,
                               List<Map<String, String>> history) {
        String taskId = UUID.randomUUID().toString();

        ChatTaskMessage taskMsg = new ChatTaskMessage();
        taskMsg.setTaskId(taskId);
        taskMsg.setCorpId(corpId);
        taskMsg.setUserId(userId);
        taskMsg.setMemberId(memberId);
        taskMsg.setConversationId(conversationId);
        taskMsg.setMessage(message);
        taskMsg.setSubsystem(subsystem);
        taskMsg.setEmployeeContext(employeeContext);
        taskMsg.setHistory(history);
        taskMsg.setCreatedAt(Instant.now().toString());

        try {
            String json = objectMapper.writeValueAsString(taskMsg);
            kafkaTemplate.send(TOPIC_AI_CHAT_TASK, taskId, json);
            log.info("AI chat task published: taskId={} corpId={} userId={}", taskId, corpId, userId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AI chat task: taskId={}", taskId, e);
            throw new RuntimeException("Failed to publish AI chat task", e);
        }

        return taskId;
    }
}
