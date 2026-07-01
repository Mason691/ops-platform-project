package com.aip.ops.imports;

import com.aip.ops.entity.SysImportTask;

/**
 * 通用异步导入处理策略。
 * 实现类标注 @Component，由 ImportTaskConsumer 按 task_type 自动发现并路由。
 */
public interface ImportHandler {

    /** 任务类型标识（如 "business_data"），用于路由 */
    String getTaskType();

    /**
     * 执行导入。
     * @param task 任务实体（含 file_path、corpId 等上下文）
     * @return 导入结果
     */
    ImportResult execute(SysImportTask task) throws Exception;
}
