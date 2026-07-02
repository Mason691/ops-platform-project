package com.aip.ops.kafka;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ai-chat-task Kafka 消息体 — 任务提交。
 */
@Data
public class ChatTaskMessage {

    private String type = "CHAT_REQUEST";
    private String taskId;
    private Long corpId;
    private Long userId;
    private Long memberId;
    private Long conversationId;
    private String message;
    private String subsystem;
    private Map<String, Object> employeeContext;
    private List<Map<String, String>> history;
    private String createdAt;
}
