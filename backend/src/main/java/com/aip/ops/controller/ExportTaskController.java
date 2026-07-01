package com.aip.ops.controller;

import com.aip.ops.entity.SysExportTask;
import com.aip.ops.service.ExportTaskService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 通用异步导出控制器。
 * 提供统一的导出任务提交 / 状态查询 / 文件下载接口。
 * 具体导出逻辑由 ExportHandler 实现，通过 taskType 路由。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/export")
@RequiredArgsConstructor
public class ExportTaskController {

    private final ExportTaskService taskService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC_EXPORT_TASK = "async-export-task";

    /**
     * 提交异步导出任务。
     * Body: { taskType: "business_data", filters: { company, batchNo, ... }, fileName: "xxx" }
     * Response: { taskId, status: "PENDING" }
     */
    @PostMapping("/submit")
    public R<Map<String, Object>> submitExport(@RequestBody Map<String, Object> params) {
        String taskType = (String) params.getOrDefault("taskType", "business_data");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) params.getOrDefault("filters", Collections.emptyMap());
        String fileName = (String) params.getOrDefault("fileName", "export.xlsx");

        String filtersJson;
        try {
            filtersJson = objectMapper.writeValueAsString(filters);
        } catch (Exception e) {
            filtersJson = "{}";
        }

        SysExportTask task = taskService.createTask(taskType, filtersJson, fileName);

        // 发送到 Kafka
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("taskId", task.getId());
            msg.put("filters", filtersJson);
            kafkaTemplate.send(TOPIC_EXPORT_TASK, objectMapper.writeValueAsString(msg));
            log.info("Export submitted: id={} type={}", task.getId(), taskType);
        } catch (Exception e) {
            log.warn("Kafka unavailable for export id={}: {}", task.getId(), e.getMessage());
            taskService.markFailed(task.getId(), "消息队列不可用: " + e.getMessage());
            return R.ok(Map.of("taskId", task.getId(), "status", "FAILED",
                    "error", "Kafka unavailable"));
        }

        return R.ok(Map.of("taskId", task.getId(), "status", "PENDING"));
    }

    /**
     * 查询导出任务状态。
     */
    @GetMapping("/{taskId}/status")
    public R<Map<String, Object>> taskStatus(@PathVariable Long taskId) {
        SysExportTask task = taskService.getById(taskId);
        if (task == null) return R.fail("任务不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("status", task.getStatus());
        result.put("progressMsg", task.getProgressMsg());
        result.put("errorMsg", task.getErrorMsg());
        if ("DONE".equals(task.getStatus())) {
            result.put("fileName", task.getFileName());
            result.put("fileSize", task.getFileSize());
        }
        return R.ok(result);
    }

    /**
     * 下载已完成的导出文件。
     */
    @GetMapping("/{taskId}/download")
    public void downloadFile(@PathVariable Long taskId, HttpServletResponse response) throws IOException {
        SysExportTask task = taskService.getById(taskId);
        if (task == null || !"DONE".equals(task.getStatus())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write("{\"code\":404,\"msg\":\"文件不存在或尚未生成\"}");
            return;
        }
        File file = new File(task.getFilePath());
        if (!file.exists()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write("{\"code\":404,\"msg\":\"文件已被清理，请重新导出\"}");
            return;
        }
        String downloadName = task.getFileName() != null ? task.getFileName() : file.getName();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(downloadName, StandardCharsets.UTF_8));
        try (InputStream in = new FileInputStream(file);
             OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /**
     * 当前用户的历史导出记录。
     */
    @GetMapping("/history")
    public R<List<SysExportTask>> history(@RequestParam(defaultValue = "20") int limit) {
        Long userId = null;
        try { userId = SecurityUtils.getCurrentUserId(); } catch (Exception ignored) {}
        return R.ok(taskService.listHistory(userId, limit));
    }
}
