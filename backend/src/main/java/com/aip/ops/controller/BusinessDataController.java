package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.dto.ModelConfirmDecision;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.entity.SysExportTask;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.ExportTaskService;
import com.aip.ops.service.ImportTaskService;
import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final OrgRoleService orgRoleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOPIC_EXPORT_TASK = "async-export-task";
    private static final String TOPIC_IMPORT_TASK = "async-import-task";

    /** Import file upload directory — configurable, defaults to persistent path.
     *  DO NOT use /tmp/ in production: tmpfs / systemd-tmpfiles-clean will
     *  delete files before the async Kafka consumer can process them. */
    @Value("${app.import.upload-dir:#{systemProperties['user.home'] + '/data/imports'}}")
    private String importUploadDir;

    /** 导入模板源文件路径（最新模板，多 sheet 工作簿）——生产容器需挂载该文件 */
    @Value("${app.import.template-path:/home/jason/1.xlsx}")
    private String templatePath;

    /** 上传根目录——同步导出（Kafka 降级）的 XLSX 落盘在 {uploadBaseDir}/exports/ 下 */
    @Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    @PostConstruct
    void ensureImportDir() {
        try {
            Files.createDirectories(Path.of(importUploadDir));
            log.info("Import upload dir ready: {}", importUploadDir);
        } catch (IOException e) {
            log.error("Cannot create import upload dir: {}", importUploadDir, e);
        }
    }

    public BusinessDataController(BusinessDataService service, ExportTaskService taskService,
                                  ImportTaskService importTaskService, KafkaTemplate<String, String> kafkaTemplate,
                                  OrgRoleService orgRoleService) {
        this.service = service;
        this.taskService = taskService;
        this.importTaskService = importTaskService;
        this.kafkaTemplate = kafkaTemplate;
        this.orgRoleService = orgRoleService;
    }

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String company,
                     @RequestParam(required = false) String batchNo,
                     @RequestParam(required = false) String agent,
                     @RequestParam(required = false) String receiptNo,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) String isSpecial,
                     @RequestParam(required = false) String brand,
                     @RequestParam(required = false) String model) {
        return R.ok(service.page(pageNum, pageSize, company, batchNo, agent, receiptNo, category, isSpecial, brand, model));
    }

    @GetMapping("/batches")
    public R<?> batches() { return R.ok(service.getBatches()); }

    /**
     * 导入阶段一：上传文件校验 + 品牌型号匹配解析（不写库）。
     * status: fail（校验失败，errors 列出原因）/ confirm（有模糊型号待人工确认，
     * pendingItems 列出待确认项与候选产品）/ ready（无需确认，可直接导入）。
     * confirm 时前端携带本响应中的 fileMd5 以防御两阶段文件不一致。
     */
    @OperationLog(module = "增值业务", action = "导入校验", targetTable = "ops_business_data")
    @PostMapping("/import/check")
    public R<Map<String, Object>> importCheck(@RequestParam("file") MultipartFile file) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        return R.ok(service.checkImport(file, corpId));
    }

    /**
     * 导入阶段二：携带型号确认决策入库。
     * confirmations 为 JSON 数组 [{key, productId}]，productId 为 null 表示拒绝该匹配。
     * 成功/失败响应格式与历史 /import/submit 一致：
     * success → { status: "success", count: N }
     * failure → { status: "fail", totalErrors: N, errors: [...] }
     */
    @OperationLog(module = "增值业务", action = "导入", targetTable = "ops_business_data")
    @PostMapping("/import/confirm")
    public R<Map<String, Object>> importConfirm(@RequestParam("file") MultipartFile file,
            @RequestParam("confirmations") String confirmationsJson,
            @RequestParam(value = "fileMd5", required = false) String fileMd5) {
        Long corpId = SecurityUtils.getCurrentCorpId();
        List<ModelConfirmDecision> decisions;
        try {
            decisions = objectMapper.readValue(confirmationsJson,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ModelConfirmDecision.class));
        } catch (Exception e) {
            log.warn("确认决策 JSON 解析失败: {}", confirmationsJson, e);
            return R.fail("确认决策数据格式不正确，请重新确认后提交");
        }
        Map<String, Object> result = service.confirmImport(file, corpId, decisions, fileMd5);

        // Save a task record for audit/history (non-blocking, best-effort)
        try {
            String originalName = file.getOriginalFilename();
            if (originalName == null) originalName = "import.xlsx";
            SysImportTask task = importTaskService.createTask("business_data",
                    originalName, "sync://memory", file.getSize());
            if ("success".equals(result.get("status"))) {
                importTaskService.markDone(task.getId(),
                        (int) result.getOrDefault("count", 0),
                        (int) result.getOrDefault("count", 0));
            } else {
                @SuppressWarnings("unchecked")
                List<String> errors = (List<String>) result.getOrDefault("errors", List.of());
                importTaskService.markFailed(task.getId(), "SYNC",
                        String.join("; ", errors),
                        (int) result.getOrDefault("totalErrors", 0),
                        (int) result.getOrDefault("totalErrors", 0));
            }
        } catch (Exception e) {
            log.warn("Failed to save import audit record", e);
        }

        return R.ok(result);
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

    /** 获取所有含"未计算"数据的批次列表，按时间倒序。 */
    @GetMapping("/uncalc-batches")
    public R<List<String>> uncalcBatches() { return R.ok(service.getUncalcBatches()); }

    /** 个人提成计算（按指定批次，batchNo 为空时计算全部）。 */
    @PostMapping("/calc-commission")
    public R<?> calcCommission(@RequestBody Map<String, String> body) {
        String batchNo = body != null ? body.getOrDefault("batchNo", null) : null;
        return R.ok(service.calcCommission(batchNo));
    }

    /**
     * Async export: creates a task record and sends to Kafka.
     * Returns immediately with taskId.
     */
    @PostMapping("/export")
    public R<Map<String, Object>> exportAsync(@RequestBody Map<String, String> params) {
        String company = params.get("company");
        String batchNo = params.get("batchNo");
        String agent = params.get("agent");
        String category = params.get("category");
        String brand = params.get("brand");
        String model = params.get("model");

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "业务数据_" + ts + ".xlsx";

        Map<String, String> filters = new LinkedHashMap<>();
        if (company != null && !company.isBlank()) filters.put("company", company);
        if (batchNo != null && !batchNo.isBlank()) filters.put("batchNo", batchNo);
        if (agent != null && !agent.isBlank()) filters.put("agent", agent);
        if (category != null && !category.isBlank()) filters.put("category", category);
        if (brand != null && !brand.isBlank()) filters.put("brand", brand);
        if (model != null && !model.isBlank()) filters.put("model", model);
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
            return syncExportFallback(task, company, batchNo, agent, category, brand, model);
        }

        return R.ok(Map.of("taskId", task.getId(), "status", "PENDING"));
    }

    /** Kafka 不可用时的同步导出：直接生成 XLSX 落盘并复用下载链路（真实路径+真实大小） */
    private R<Map<String, Object>> syncExportFallback(SysExportTask task, String company, String batchNo,
                                                      String agent, String category, String brand, String model) {
        Path outFile = null;
        try {
            taskService.updateStatus(task.getId(), "PROCESSING", "Kafka不可用，同步导出中...");
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            outFile = Path.of(uploadBaseDir, "exports", "业务数据_" + ts + "_" + task.getId() + ".xlsx");
            service.exportExcel(company, batchNo, agent, category, brand, model, outFile);
            if (!Files.exists(outFile) || Files.size(outFile) == 0) {
                throw new IOException("导出文件生成失败: " + outFile);
            }
            taskService.markDone(task.getId(), outFile.toAbsolutePath().toString(), Files.size(outFile));
            return R.ok(Map.of("taskId", task.getId(), "status", "DONE", "fallback", true));
        } catch (Exception ex) {
            log.error("Sync export fallback failed for taskId={}", task.getId(), ex);
            taskService.markFailed(task.getId(), ex.getMessage());
            return R.ok(Map.of("taskId", task.getId(), "status", "FAILED", "error", ex.getMessage()));
        }
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

    @OperationLog(module = "增值业务", action = "删除", targetTable = "ops_business_data")
    @DeleteMapping("/batch")
    public R<?> batchDelete(@RequestBody Map<String, List<Long>> params) {
        service.batchDelete(params.get("ids"));
        return R.ok("删除成功");
    }

    /**
     * 提成上报集团：将当前筛选条件下的数据锁定为"已上报集团"。
     * 仅运营商管理员/超管可操作；只处理非"已上报集团"的数据；
     * 锁定后所有组织均不可删除/修改/重新计算。
     */
    @OperationLog(module = "增值业务", action = "提成上报集团", targetTable = "ops_business_data")
    @PostMapping("/report-to-group")
    public R<?> reportToGroup(@RequestBody(required = false) Map<String, String> params) {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            return R.fail("仅运营商管理员可执行提成上报集团操作");
        }
        Map<String, String> p = params != null ? params : Map.of();
        int count = service.reportToGroup(p.get("company"), p.get("batchNo"),
                p.get("agent"), p.get("receiptNo"), p.get("category"));
        if (count == 0) return R.fail("当前筛选条件下没有可上报的数据");
        return R.ok(Map.of("reportedCount", count));
    }

    /**
     * 品类提成汇总：按当前筛选条件汇总非"已上报集团"数据（品类×公司×提成金额/销售额），
     * 按模板格式生成 Excel 同步下载。仅运营商管理员/超管可操作。
     */
    @OperationLog(module = "增值业务", action = "品类提成汇总", targetTable = "ops_business_data")
    @GetMapping("/commission-summary")
    public void commissionSummary(@RequestParam(required = false) String company,
                                  @RequestParam(required = false) String batchNo,
                                  @RequestParam(required = false) String agent,
                                  @RequestParam(required = false) String receiptNo,
                                  @RequestParam(required = false) String category,
                                  HttpServletResponse response) throws IOException {
        if (!orgRoleService.isOperatorOrSuperAdmin()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"msg\":\"仅运营商管理员可执行品类提成汇总\"}");
            return;
        }
        service.exportCommissionSummary(company, batchNo, agent, receiptNo, category, response);
    }

    /** 经办人核对：将指定批次已计算数据标记为已核对 */
    @OperationLog(module = "增值业务", action = "核对", targetTable = "ops_business_data")
    @PutMapping("/batch/check")
    public R<?> batchCheck(@RequestBody Map<String, String> params) {
        String batchNo = params.get("batchNo");
        if (batchNo == null || batchNo.isBlank()) return R.fail("批次号不能为空");
        int count = service.batchCheck(batchNo);
        return R.ok(Map.of("checkedCount", count, "message", "已核对 " + count + " 条数据"));
    }

    /** Download Excel import template. 优先读取配置的最新模板（默认 /home/jason/1.xlsx），
     *  缺失时回退 classpath 内置模板；下载文件名固定为 增值业务数据模板.xlsx。 */
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        Path templateFile = Path.of(templatePath);
        InputStream in = null;
        if (Files.exists(templateFile)) {
            log.info("Serve import template from configured path: {}", templateFile);
            in = Files.newInputStream(templateFile);
        } else {
            log.warn("Configured template missing ({}), fallback to classpath", templateFile);
            ClassPathResource resource = new ClassPathResource("templates/business_data_template.xlsx");
            if (!resource.exists()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("{\"code\":404,\"msg\":\"模板文件不存在\"}");
                return;
            }
            in = resource.getInputStream();
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode("增值业务数据模板.xlsx", StandardCharsets.UTF_8));
        try (InputStream is = in; OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
