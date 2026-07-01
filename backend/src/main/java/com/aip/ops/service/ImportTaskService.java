package com.aip.ops.service;

import com.aip.ops.entity.SysImportTask;
import java.util.List;

/**
 * 通用异步导入任务生命周期管理。
 */
public interface ImportTaskService {

    /** 创建导入任务记录 */
    SysImportTask createTask(String taskType, String fileName, String filePath, Long fileSize);

    /** 根据ID查询 */
    SysImportTask getById(Long taskId);

    /** 更新状态+进度+阶段 */
    void updateStatus(Long taskId, String status, String phase, String progressMsg);

    /** 标记完成 */
    void markDone(Long taskId, int totalRows, int successRows);

    /** 标记失败 */
    void markFailed(Long taskId, String phase, String errorMsg, int totalRows, int failRows);

    /** 查询是否有进行中的任务 */
    boolean hasPendingTask(String taskType);

    /** 历史记录 */
    List<SysImportTask> listHistory(Long userId, int limit);
}
