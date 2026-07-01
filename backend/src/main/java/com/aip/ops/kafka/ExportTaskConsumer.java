package com.aip.ops.kafka;

import com.aip.ops.entity.SysExportTask;
import com.aip.ops.export.ExportHandler;
import com.aip.ops.mapper.SysExportTaskMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.ExportTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 通用异步导出 Kafka 消费者。
 * 消费 async-export-task 消息，根据 task_type 路由到对应的 ExportHandler。
 * 替代原先硬编码业务逻辑的 OpsExportTaskConsumer。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class ExportTaskConsumer {

    private final SysExportTaskMapper taskMapper;
    private final ExportTaskService taskService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, ExportHandler> handlerMap;

    private static final String TOPIC_TASK = "async-export-task";
    private static final String TOPIC_RESULT = "async-export-result";
    private static final String EXPORT_DIR = "/app/uploads/exports/";

    public ExportTaskConsumer(
            SysExportTaskMapper taskMapper,
            ExportTaskService taskService,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            List<ExportHandler> handlers) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        // 预建 taskType → handler 索引
        this.handlerMap = new java.util.LinkedHashMap<>();
        for (ExportHandler h : handlers) {
            handlerMap.put(h.getTaskType(), h);
        }
        log.info("ExportTaskConsumer registered handlers: {}", handlerMap.keySet());
    }

    @KafkaListener(topics = TOPIC_TASK, groupId = "ai-platform-group")
    public void handleExportTask(String message) {
        SysExportTask task = null;
        try {
            Map<String, Object> msg = objectMapper.readValue(message, Map.class);
            Long taskId = Long.valueOf(msg.get("taskId").toString());
            task = taskMapper.selectById(taskId);
            if (task == null) {
                log.warn("Export task not found: id={}", taskId);
                return;
            }

            // 路由到对应 handler
            ExportHandler handler = handlerMap.get(task.getTaskType());
            if (handler == null) {
                taskService.markFailed(taskId, "未找到导出类型[" + task.getTaskType() + "]的处理器");
                return;
            }

            taskService.updateStatus(taskId, "PROCESSING", "正在导出数据...");
            log.info("Processing export: id={} type={}", taskId, task.getTaskType());

            // 确保导出目录存在
            File dir = new File(EXPORT_DIR);
            if (!dir.exists()) dir.mkdirs();

            // 生成文件：先写 TSV，再转 XLSX
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String baseName = (task.getFileName() != null ? task.getFileName().replaceFirst("\\.[^.]+$", "") : "export") + "_" + ts;
            File tsvFile = new File(dir, baseName + ".tsv");

            // 让 handler 写数据到 TSV
            try (OutputStream os = new FileOutputStream(tsvFile);
                 BufferedOutputStream bos = new BufferedOutputStream(os)) {
                handler.export(task, bos);
            }

            // TSV → XLSX via Python openpyxl
            File xlsxFile = new File(dir, baseName + "." + handler.getFileExtension());
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "python3", "-c",
                        "import openpyxl\nwb = openpyxl.Workbook()\nws = wb.active\n" +
                        "with open('" + tsvFile.getAbsolutePath() + "', 'r', encoding='utf-8') as f:\n" +
                        "    for line in f:\n        ws.append(line.rstrip('\\n').split('\\t'))\n" +
                        "wb.save('" + xlsxFile.getAbsolutePath() + "')");
                Process p = pb.start();
                int exitCode = p.waitFor();
                if (exitCode == 0 && xlsxFile.exists()) {
                    tsvFile.delete();
                } else {
                    xlsxFile = tsvFile; // fallback to TSV
                }
            } catch (Exception e) {
                log.warn("Python xlsx conversion failed, using TSV: {}", e.getMessage());
                xlsxFile = tsvFile;
            }

            // 标记完成
            taskService.markDone(taskId, xlsxFile.getAbsolutePath(), xlsxFile.length());

            // 通知结果
            Map<String, Object> result = Map.of(
                    "taskId", taskId,
                    "status", "DONE",
                    "fileName", xlsxFile.getName(),
                    "fileSize", xlsxFile.length()
            );
            try {
                kafkaTemplate.send(TOPIC_RESULT, objectMapper.writeValueAsString(result));
            } catch (Exception e) {
                log.warn("Failed to send export result notification: {}", e.getMessage());
            }
            log.info("Export task completed: id={} file={}", taskId, xlsxFile.getName());

        } catch (Exception e) {
            log.error("Export task failed: {}", message, e);
            if (task != null) {
                String err = e.getMessage();
                taskService.markFailed(task.getId(), err != null && err.length() > 400 ? err.substring(0, 400) : err);
            }
        }
    }
}
