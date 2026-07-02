package com.aip.ops.service;

import com.aip.ops.entity.AiChatTask;

/**
 * AI 对话异步任务生命周期管理。
 * 不感知 AI 处理逻辑，只管理任务记录的状态流转。
 */
public interface AiChatTaskService {

    /**
     * 创建异步任务记录（状态 = PENDING）。
     * @return 已持久化的任务实体
     */
    AiChatTask createTask(String taskId, Long corpId, Long userId, Long memberId,
                          Long conversationId, String message, String subsystem,
                          String employeeContext);

    /** 根据 taskId 查询任务 */
    AiChatTask getByTaskId(String taskId);

    /** 更新任务状态 + 阶段 + 进度描述 */
    void updateStatus(String taskId, String status, String phase, String progressMsg);

    /** 标记任务完成（含 AI 回复） */
    void markDone(String taskId, String reply, Long conversationId, boolean partial);

    /** 标记任务失败（含错误信息） */
    void markFailed(String taskId, String errorMsg);

    /** 更新进度消息（不改变主状态） */
    void updateProgress(String taskId, String phase, String progressMsg);
}
