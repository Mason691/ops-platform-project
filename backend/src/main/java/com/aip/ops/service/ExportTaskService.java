package com.aip.ops.service;

import com.aip.ops.entity.SysExportTask;

/**
 * 通用异步导出任务生命周期管理。
 * 不感知具体导出逻辑，只管理任务记录的状态流转。
 */
public interface ExportTaskService {

    /**
     * 创建导出任务记录。
     * @param taskType 导出类型（如 business_data）
     * @param filters  筛选条件 JSON
     * @param fileName 导出文件名
     * @return 已持久化的任务实体
     */
    SysExportTask createTask(String taskType, String filters, String fileName);

    /** 根据 ID 查询任务 */
    SysExportTask getById(Long taskId);

    /** 更新任务状态 + 进度描述 */
    void updateStatus(Long taskId, String status, String progressMsg);

    /** 标记任务完成（含文件路径和大小） */
    void markDone(Long taskId, String filePath, Long fileSize);

    /** 标记任务失败（含错误信息） */
    void markFailed(Long taskId, String errorMsg);

    /** 查当前用户最近导出任务 */
    java.util.List<SysExportTask> listHistory(Long userId, int limit);
}
