package com.aip.ops.export;

import com.aip.ops.entity.SysExportTask;

import java.io.OutputStream;

/**
 * 导出处理器接口 — 策略模式。
 * 每种导出类型实现此接口，由 ExportTaskConsumer 根据 taskType 路由。
 * Spring 自动收集所有 @Component 的 ExportHandler 实现。
 */
public interface ExportHandler {

    /** 该处理器对应的导出类型（如 "business_data"），用于 Kafka 消费时路由 */
    String getTaskType();

    /**
     * 执行导出逻辑：查询数据并写入 OutputStream。
     * 调用方负责打开/关闭流，handler 只关心「写数据」。
     *
     * @param task   导出任务实体（含 filters JSON、corpId 等）
     * @param out    输出流（通常是 FileOutputStream）
     */
    void export(SysExportTask task, OutputStream out) throws Exception;

    /** 导出文件扩展名（默认 xlsx） */
    default String getFileExtension() {
        return "xlsx";
    }
}
