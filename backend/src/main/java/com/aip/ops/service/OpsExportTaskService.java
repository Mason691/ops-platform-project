package com.aip.ops.service;

import com.aip.ops.entity.OpsExportTask;
import com.baomidou.mybatisplus.extension.service.IService;

public interface OpsExportTaskService extends IService<OpsExportTask> {

    /** Create a new export task record. */
    OpsExportTask createTask(Long corpId, Long userId, String userName,
                             String filters, String fileName);

    /** Update status. */
    void updateStatus(Long taskId, String status, String progressMsg);

    /** Mark as done with file info. */
    void markDone(Long taskId, String filePath, Long fileSize);

    /** Mark as failed with error. */
    void markFailed(Long taskId, String errorMsg);
}
