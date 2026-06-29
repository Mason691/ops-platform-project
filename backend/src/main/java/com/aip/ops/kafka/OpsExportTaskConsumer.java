package com.aip.ops.kafka;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsExportTask;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsExportTaskMapper;
import com.aip.ops.service.OpsExportTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class OpsExportTaskConsumer {

    private final OpsExportTaskMapper taskMapper;
    private final OpsExportTaskService taskService;
    private final OpsBusinessDataMapper businessDataMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Export directory — MUST be under /app/uploads/ to persist via Docker volume mount */
    private static final String EXPORT_DIR = "/app/uploads/exports/";

    @KafkaListener(topics = "ops-export-task", groupId = "ai-platform-group")
    public void handleExportTask(String message) {
        OpsExportTask task = null;
        try {
            Map<String, Object> msg = objectMapper.readValue(message, Map.class);
            Long taskId = Long.valueOf(msg.get("taskId").toString());
            task = taskMapper.selectById(taskId);
            if (task == null) {
                log.warn("Export task not found: id={}", taskId);
                return;
            }

            // Update to PROCESSING
            taskService.updateStatus(taskId, "PROCESSING", "正在导出数据...");
            log.info("Processing export task: id={}, filters={}", taskId, task.getFilters());

            // Parse filters from JSON
            String company = null, batchNo = null, agent = null, brand = null, category = null;
            if (task.getFilters() != null && !task.getFilters().isEmpty()) {
                Map<String, String> filters = objectMapper.readValue(task.getFilters(), Map.class);
                company = filters.get("company");
                batchNo = filters.get("batchNo");
                agent = filters.get("agent");
                brand = filters.get("brand");
                category = filters.get("category");
            }

            // Query data
            LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
            if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
            if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
            if (agent != null && !agent.isEmpty()) qw.eq(OpsBusinessData::getAgent, agent);
            if (brand != null && !brand.isEmpty()) qw.eq(OpsBusinessData::getBrand, brand);
            if (category != null && !category.isEmpty()) qw.eq(OpsBusinessData::getCategory, category);
            List<OpsBusinessData> rows = businessDataMapper.selectList(qw);
            log.info("Export query returned {} rows", rows.size());

            // Generate TSV file
            File dir = new File(EXPORT_DIR);
            if (!dir.exists()) dir.mkdirs();
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "业务数据_" + ts + ".tsv";
            File tsvFile = new File(dir, fileName);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tsvFile), StandardCharsets.UTF_8))) {
                writer.write("公司\t批次号\t客户\t品牌\t品类\t型号\t销售价\t销售日期\t收据号\t收款日期\t返利比例\t返利金额\t状态\n");
                for (OpsBusinessData row : rows) {
                    writer.write(String.join("\t",
                            nvl(row.getCompany()),
                            nvl(row.getBatchNo()),
                            nvl(row.getAgent()),
                            nvl(row.getBrand()),
                            nvl(row.getCategory()),
                            nvl(row.getModel()),
                            row.getSalePrice() != null ? row.getSalePrice().toString() : "",
                            nvl(row.getSaleDate()),
                            nvl(row.getReceiptNo()),
                            nvl(row.getReceiptDate()),
                            row.getCommissionRate() != null ? row.getCommissionRate().toString() : "",
                            row.getCommissionAmount() != null ? row.getCommissionAmount().toString() : "",
                            nvl(row.getCalcStatus())));
                    writer.newLine();
                }
            }

            // Try to convert to XLSX via Python
            String xlsxName = "业务数据_" + ts + ".xlsx";
            File xlsxFile = new File(dir, xlsxName);
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "python3", "-c",
                        "import openpyxl\n" +
                        "wb = openpyxl.Workbook()\n" +
                        "ws = wb.active\n" +
                        "with open('" + tsvFile.getAbsolutePath() + "', 'r', encoding='utf-8') as f:\n" +
                        "    for line in f:\n" +
                        "        ws.append(line.rstrip('\\n').split('\\t'))\n" +
                        "wb.save('" + xlsxFile.getAbsolutePath() + "')\n");
                Process p = pb.start();
                int exitCode = p.waitFor();
                if (exitCode == 0 && xlsxFile.exists()) {
                    tsvFile.delete(); // Clean up TSV
                } else {
                    // Fallback: use TSV as the output
                    xlsxFile = tsvFile;
                }
            } catch (Exception e) {
                log.warn("Python xlsx conversion failed, using TSV fallback: {}", e.getMessage());
                xlsxFile = tsvFile;
            }

            // Mark done
            taskService.markDone(taskId, xlsxFile.getAbsolutePath(), xlsxFile.length());

            // Notify via Kafka
            Map<String, Object> result = Map.of(
                    "taskId", taskId,
                    "status", "DONE",
                    "fileName", xlsxFile.getName(),
                    "fileSize", xlsxFile.length()
            );
            kafkaTemplate.send("ops-export-result", objectMapper.writeValueAsString(result));
            log.info("Export task completed: id={}, file={}", taskId, xlsxFile.getName());

        } catch (Exception e) {
            log.error("Export task failed: {}", message, e);
            if (task != null) {
                taskService.markFailed(task.getId(), e.getMessage().length() > 400
                        ? e.getMessage().substring(0, 400) : e.getMessage());
            }
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
