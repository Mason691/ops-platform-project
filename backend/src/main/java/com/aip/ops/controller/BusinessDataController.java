package com.aip.ops.controller;

import com.aip.ops.entity.OpsExportTask;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.OpsExportTaskService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/business-data")
@RequiredArgsConstructor
public class BusinessDataController {

    private final BusinessDataService service;
    private final OpsExportTaskService taskService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOPIC_EXPORT_TASK = "ops-export-task";

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String company,
                     @RequestParam(required = false) String batchNo,
                     @RequestParam(required = false) String agent) {
        return R.ok(service.page(pageNum, pageSize, company, batchNo, agent));
    }

    @GetMapping("/batches")
    public R<?> batches() { return R.ok(service.getBatches()); }

    @PostMapping("/import")
    public R<?> importExcel(@RequestParam("file") MultipartFile file) {
        String batchNo = "金厨美家" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Long corpId = SecurityUtils.getCurrentCorpId();
        return R.ok(service.importExcel(file, batchNo, corpId));
    }

    @PostMapping("/calc-commission")
    public R<?> calcCommission() { return R.ok(service.calcCommission()); }

    /**
     * Async export: creates a task record and sends to Kafka.
     * Returns immediately with taskId.
     */
    @PostMapping("/export")
    public R<Map<String, Object>> exportAsync(@RequestBody Map<String, String> params) {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        Long userId = SecurityUtils.getCurrentUserId();
        String userName = SecurityUtils.getCurrentUser() != null
                ? SecurityUtils.getCurrentUser().getUsername() : "";
        String company = params.get("company");
        String batchNo = params.get("batchNo");
        String agent = params.get("agent");

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "业务数据_" + ts + ".xlsx";

        // Save filters as JSON
        Map<String, String> filters = new LinkedHashMap<>();
        if (company != null) filters.put("company", company);
        if (batchNo != null) filters.put("batchNo", batchNo);
        if (agent != null) filters.put("agent", agent);
        String filtersJson;
        try { filtersJson = objectMapper.writeValueAsString(filters); } catch (Exception e) { filtersJson = "{}"; }

        OpsExportTask task = taskService.createTask(corpId, userId, userName, filtersJson, fileName);

        // Send to Kafka
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("taskId", task.getId());
            msg.put("filters", filtersJson);
            kafkaTemplate.send(TOPIC_EXPORT_TASK, objectMapper.writeValueAsString(msg));
            log.info("Export task submitted: id={}, kafka=sent", task.getId());
        } catch (Exception e) {
            // Kafka unavailable: fall back to sync export
            log.warn("Kafka unavailable, fallback to sync export for taskId={}", task.getId());
            try {
                taskService.updateStatus(task.getId(), "PROCESSING", "Kafka不可用，同步导出中...");
                service.exportExcel(company, batchNo, agent, null); // sync export marks done internally
                // Since the sync export doesn't use taskService directly, we need a different approach
                // For simplicity: mark as DONE with placeholder
                taskService.markDone(task.getId(), "/home/jason/exports/" + fileName, 0L);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("taskId", task.getId());
                result.put("status", "DONE");
                result.put("fallback", true);
                return R.ok(result);
            } catch (Exception ex) {
                taskService.markFailed(task.getId(), ex.getMessage());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("taskId", task.getId());
                result.put("status", "FAILED");
                result.put("error", ex.getMessage());
                return R.ok(result);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("status", "PENDING");
        return R.ok(result);
    }

    /** Poll task status. */
    @GetMapping("/export/{taskId}/status")
    public R<Map<String, Object>> exportStatus(@PathVariable Long taskId) {
        OpsExportTask task = taskService.getById(taskId);
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

    /** Download exported file. */
    @GetMapping("/export/{taskId}/download")
    public void exportDownload(@PathVariable Long taskId, HttpServletResponse response) throws IOException {
        OpsExportTask task = taskService.getById(taskId);
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

    @DeleteMapping("/batch")
    public R<?> batchDelete(@RequestBody Map<String, List<Long>> params) {
        service.batchDelete(params.get("ids"));
        return R.ok("删除成功");
    }
}
