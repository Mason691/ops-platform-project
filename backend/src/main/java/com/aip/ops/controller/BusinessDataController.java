package com.aip.ops.controller;

import com.aip.ops.entity.SysImportTask;
import com.aip.ops.entity.SysExportTask;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.ExportTaskService;
import com.aip.ops.service.ImportTaskService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
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

import org.springframework.core.io.ClassPathResource;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/business-data")
public class BusinessDataController {

    private final BusinessDataService service;
    private final ExportTaskService taskService;
    private final ImportTaskService importTaskService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOPIC_EXPORT_TASK = "async-export-task";
    private static final String TOPIC_IMPORT_TASK = "async-import-task";
    private static final String IMPORT_UPLOAD_DIR = "/tmp/";

    public BusinessDataController(BusinessDataService service, ExportTaskService taskService,
                                  ImportTaskService importTaskService, KafkaTemplate<String, String> kafkaTemplate) {
        this.service = service;
        this.taskService = taskService;
        this.importTaskService = importTaskService;
        this.kafkaTemplate = kafkaTemplate;
    }

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

    /** Async import submit — saves file, creates task, sends Kafka message. */
    @PostMapping("/import/submit")
    public R<Map<String, Object>> importSubmit(@RequestParam("file") MultipartFile file) {
        // Concurrency check
        if (importTaskService.hasPendingTask("business_data")) {
            return R.fail("有导入任务正在执行中，请等待完成后再提交");
        }

        try {
            // Save uploaded file
            String originalName = file.getOriginalFilename();
            if (originalName == null) originalName = "import.xlsx";
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String savedName = "import_business_" + ts + "_" + originalName;
            java.nio.file.Path dest = java.nio.file.Path.of(IMPORT_UPLOAD_DIR, savedName);
            file.transferTo(dest);

            // Create task
            SysImportTask task = importTaskService.createTask("business_data",
                    originalName, dest.toAbsolutePath().toString(), file.getSize());

            // Send to Kafka
            try {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("taskId", task.getId());
                kafkaTemplate.send(TOPIC_IMPORT_TASK, objectMapper.writeValueAsString(msg));
                log.info("Import task submitted: id={} file={}", task.getId(), originalName);
                return R.ok(Map.of("taskId", task.getId(), "status", "PENDING"));
            } catch (Exception e) {
                log.warn("Kafka unavailable for import, fallback to sync: taskId={}", task.getId());
                // Fallback to sync import
                try {
                    Files.deleteIfExists(dest);
                } catch (Exception ignored) {}
                return R.ok(service.importExcel(file,
                        "金厨美家" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                        SecurityUtils.getCurrentCorpId()));
            }
        } catch (Exception e) {
            log.error("Import task submit failed", e);
            return R.fail("导入提交失败: " + e.getMessage());
        }
    }

    /** Poll import task status. */
    @GetMapping("/import/{taskId}/status")
    public R<Map<String, Object>> importStatus(@PathVariable Long taskId) {
        SysImportTask task = importTaskService.getById(taskId);
        if (task == null) return R.fail("任务不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("status", task.getStatus());
        result.put("phase", task.getPhase());
        result.put("progressMsg", task.getProgressMsg());
        result.put("errorMsg", task.getErrorMsg());
        result.put("totalRows", task.getTotalRows());
        result.put("successRows", task.getSuccessRows());
        result.put("failRows", task.getFailRows());
        return R.ok(result);
    }

    /** Import history for current user. */
    @GetMapping("/import/history")
    public R<?> importHistory() {
        Long userId = null;
        try { userId = SecurityUtils.getCurrentUserId(); } catch (Exception ignored) {}
        return R.ok(importTaskService.listHistory(userId, 20));
    }

    @PostMapping("/calc-commission")
    public R<?> calcCommission() { return R.ok(service.calcCommission()); }

    /**
     * Async export: creates a task record and sends to Kafka.
     * Returns immediately with taskId.
     */
    @PostMapping("/export")
    public R<Map<String, Object>> exportAsync(@RequestBody Map<String, String> params) {
        String company = params.get("company");
        String batchNo = params.get("batchNo");
        String agent = params.get("agent");

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "业务数据_" + ts + ".xlsx";

        Map<String, String> filters = new LinkedHashMap<>();
        if (company != null) filters.put("company", company);
        if (batchNo != null) filters.put("batchNo", batchNo);
        if (agent != null) filters.put("agent", agent);
        String filtersJson;
        try { filtersJson = objectMapper.writeValueAsString(filters); } catch (Exception e) { filtersJson = "{}"; }

        SysExportTask task = taskService.createTask("business_data", filtersJson, fileName);

        // Send to Kafka
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("taskId", task.getId());
            msg.put("filters", filtersJson);
            kafkaTemplate.send(TOPIC_EXPORT_TASK, objectMapper.writeValueAsString(msg));
            log.info("Export task submitted: id={} type=business_data kafka=sent", task.getId());
        } catch (Exception e) {
            log.warn("Kafka unavailable, fallback to sync export for taskId={}", task.getId());
            try {
                taskService.updateStatus(task.getId(), "PROCESSING", "Kafka不可用，同步导出中...");
                service.exportExcel(company, batchNo, agent, null);
                taskService.markDone(task.getId(), "/home/jason/exports/" + fileName, 0L);
                return R.ok(Map.of("taskId", task.getId(), "status", "DONE", "fallback", true));
            } catch (Exception ex) {
                taskService.markFailed(task.getId(), ex.getMessage());
                return R.ok(Map.of("taskId", task.getId(), "status", "FAILED", "error", ex.getMessage()));
            }
        }

        return R.ok(Map.of("taskId", task.getId(), "status", "PENDING"));
    }

    /** Poll task status — delegates to generic ExportTaskService. */
    @GetMapping("/export/{taskId}/status")
    public R<Map<String, Object>> exportStatus(@PathVariable Long taskId) {
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

    /** Download exported file — delegates to generic ExportTaskService. */
    @GetMapping("/export/{taskId}/download")
    public void exportDownload(@PathVariable Long taskId, HttpServletResponse response) throws IOException {
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

    @DeleteMapping("/batch")
    public R<?> batchDelete(@RequestBody Map<String, List<Long>> params) {
        service.batchDelete(params.get("ids"));
        return R.ok("删除成功");
    }

    /** Download Excel import template. */
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        ClassPathResource resource = new ClassPathResource("templates/business_data_template.xlsx");
        if (!resource.exists()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write("{\"code\":404,\"msg\":\"模板文件不存在\"}");
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode("业务数据导入模板.xlsx", StandardCharsets.UTF_8));
        try (InputStream in = resource.getInputStream();
             OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
