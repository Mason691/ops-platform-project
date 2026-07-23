package com.aip.ops.kafka;

import com.aip.ops.entity.SysImportTask;
import com.aip.ops.imports.ImportHandler;
import com.aip.ops.imports.ImportResult;
import com.aip.ops.mapper.SysImportTaskMapper;
import com.aip.ops.service.ImportTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通用异步导入 Kafka 消费者。
 * 消费 async-import-task 消息，根据 task_type 路由到对应的 ImportHandler。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class ImportTaskConsumer {

    private final SysImportTaskMapper taskMapper;
    private final ImportTaskService taskService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, ImportHandler> handlerMap;

    private static final String TOPIC_TASK = "async-import-task";
    private static final String TOPIC_RESULT = "async-import-result";

    public ImportTaskConsumer(
            SysImportTaskMapper taskMapper,
            ImportTaskService taskService,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            List<ImportHandler> handlers) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.handlerMap = new java.util.LinkedHashMap<>();
        for (ImportHandler h : handlers) {
            handlerMap.put(h.getTaskType(), h);
        }
        log.info("ImportTaskConsumer registered handlers: {}", handlerMap.keySet());
    }

    @KafkaListener(topics = TOPIC_TASK, groupId = "ai-platform-group")
    public void handleImportTask(String message) {
        SysImportTask task = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(message, Map.class);
            Long taskId = Long.valueOf(msg.get("taskId").toString());
            task = taskMapper.selectById(taskId);
            if (task == null) {
                log.warn("Import task not found: id={}", taskId);
                return;
            }

            ImportHandler handler = handlerMap.get(task.getTaskType());
            if (handler == null) {
                taskService.markFailed(taskId, "HANDLER", "未找到导入类型[" + task.getTaskType() + "]的处理器", 0, 0);
                return;
            }

            taskService.updateStatus(taskId, "PROCESSING", "ROW_CHECK", "正在校验数据...");
            log.info("Processing import: id={} type={}", taskId, task.getTaskType());

            // Delegate to handler
            ImportResult result = handler.execute(task);

            if ("success".equals(result.getStatus())) {
                String summary = result.getMessage() != null ? result.getMessage()
                        : (result.getCount() + " 条导入成功");
                taskService.markDone(taskId, result.getTotalRows(), result.getCount(), summary);
                sendResult(taskId, "DONE", summary);
            } else {
                // Serialize errors as error_msg
                String errSummary = String.join("; ", result.getErrors());
                taskService.markFailed(taskId, result.getPhase(), errSummary,
                        result.getTotalRows(), result.getFailRows());
                sendResult(taskId, "FAILED", result.getPhase() + ": " + result.getFailRows() + " errors");
            }

        } catch (Exception e) {
            log.error("Import task failed: {}", message, e);
            if (task != null) {
                String err = e.getMessage();
                taskService.markFailed(task.getId(), "EXCEPTION",
                        err != null && err.length() > 1000 ? err.substring(0, 1000) : err, 0, 0);
            }
        }
    }

    private void sendResult(Long taskId, String status, String summary) {
        try {
            Map<String, Object> result = Map.of("taskId", taskId, "status", status, "summary", summary);
            kafkaTemplate.send(TOPIC_RESULT, objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("Failed to send import result notification: {}", e.getMessage());
        }
    }
}
