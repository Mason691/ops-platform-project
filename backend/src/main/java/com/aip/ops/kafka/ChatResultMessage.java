package com.aip.ops.kafka;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ai-chat-result Kafka 消息体 — 最终汇总结果。
 * 由 Platform 端的 AsyncChatService / AgentGraphBuilder 发布，
 * 由 ops-backend 的 AiChatResultConsumer 消费后通过 SSE 推送或更新 DB。
 */
@Data
public class ChatResultMessage {

    private String type = "RESULT";
    private String taskId;

    /** COMPLETED | FAILED | TIMEOUT | CANCELLED */
    private String status;

    /** AI 最终回复文本（Markdown） */
    private String reply;

    private Long conversationId;

    private List<Map<String, Object>> toolCalls;

    /** 报告下载 token */
    private String reportToken;

    /** 是否需要人工确认 */
    private Boolean requiresConfirm;

    /** 确认描述 */
    private String confirmDescription;

    /** 表单填充 JSON */
    private String detectedForm;

    /** true = 超时导致部分结果 */
    private Boolean partialResults;

    /** 失败/超时的子任务描述 */
    private List<String> failedSubTasks;

    private String completedAt;
}
