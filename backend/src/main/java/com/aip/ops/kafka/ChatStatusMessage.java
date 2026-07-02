package com.aip.ops.kafka;

import lombok.Data;

/**
 * ai-chat-status Kafka 消息体 — 处理进度更新。
 * 由 Platform 端的 HermesCoreAgent / Worker Node 发布，
 * 由 ops-backend 的 AiChatStatusConsumer 消费后通过 SSE 推送给前端。
 */
@Data
public class ChatStatusMessage {

    private String type = "STATUS";
    private String taskId;

    /** PENDING | PROCESSING | WAITING_CONFIRM | COMPLETED | FAILED | TIMEOUT */
    private String status;

    /** INTENT | SPLIT | SUB_TASK | TOOL_CALL | AGGREGATE | LLM */
    private String phase;

    /** 用户可读的状态描述（前端直接展示） */
    private String message;

    /** 0-100 进度估算 */
    private Integer progress;

    private String timestamp;
}
