package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.config.GasDataSourceConfig;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.ImportTaskService;
import com.aip.sdk.result.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 燃具信息同步 — 外部燃气系统 jinyuan_info.sb_gas_stoves_info 全量同步到 ops_gas_stoves_info。
 * 触发入口：主平台「增值业务 → 燃气用户列表」的「同步燃具信息」按钮。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/gas-stoves")
public class GasStovesController {

    private final ImportTaskService importTaskService;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;
    private final GasDataSourceConfig gasDataSourceConfig;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public GasStovesController(
            ImportTaskService importTaskService,
            DataScopeService dataScopeService,
            JdbcTemplate jdbcTemplate,
            GasDataSourceConfig gasDataSourceConfig,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.importTaskService = importTaskService;
        this.dataScopeService = dataScopeService;
        this.jdbcTemplate = jdbcTemplate;
        this.gasDataSourceConfig = gasDataSourceConfig;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /** 预览：外部燃具总数 + 本地已有 + 预计新增。 */
    @PostMapping("/preview")
    public R<?> preview() {
        try {
            JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
            Integer totalCount = gasJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sb_gas_stoves_info", Integer.class);
            if (totalCount == null) totalCount = 0;

            int existingCount = estimateExistingCount();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", totalCount);              // 主平台前端使用 count
            data.put("totalCount", totalCount);
            data.put("existingCount", existingCount);
            data.put("newCount", Math.max(0, totalCount - existingCount));
            log.info("GasStoves preview: total={} existing={} new={}",
                    totalCount, existingCount, totalCount - existingCount);
            return R.ok(data);
        } catch (Exception e) {
            log.error("GasStoves preview failed: {}", e.getMessage());
            return R.fail("查询外部数据库失败：" + e.getMessage());
        }
    }

    /** 估算外部燃具中本地已存在的数量（按 remote_id = sb_gas_stoves_info.id）。 */
    private int estimateExistingCount() {
        try {
            JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
            List<String> externalIds = gasJdbc.queryForList(
                    "SELECT id FROM sb_gas_stoves_info", String.class);
            if (externalIds.isEmpty()) return 0;
            int existing = 0;
            for (int i = 0; i < externalIds.size(); i += 500) {
                int end = Math.min(i + 500, externalIds.size());
                List<String> batch = externalIds.subList(i, end);
                String placeholders = String.join(",", batch.stream().map(id -> "?").toList());
                Integer cnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ops_gas_stoves_info WHERE delete_flag=0 AND remote_id IN (" + placeholders + ")",
                        Integer.class, batch.toArray());
                existing += (cnt != null ? cnt : 0);
            }
            return existing;
        } catch (Exception e) {
            log.warn("Failed to estimate existing gas stoves count: {}", e.getMessage());
            return 0;
        }
    }

    /** 触发燃具信息全量同步（异步 Kafka 任务）。 */
    @OperationLog(module = "增值业务", action = "同步燃具信息", targetTable = "ops_gas_stoves_info")
    @PostMapping("/submit")
    public R<?> submit() {
        if (importTaskService.hasPendingTask("gas_stoves")) {
            return R.fail("已有同步任务正在执行中，请等待完成后再试");
        }
        SysImportTask task = importTaskService.createTaskWithoutFile(
                "gas_stoves", "同步燃具信息");
        try {
            kafkaTemplate.send("async-import-task",
                    objectMapper.writeValueAsString(Map.of("taskId", task.getId())));
        } catch (Exception e) {
            log.error("Failed to send Kafka message for gas stoves sync task {}", task.getId(), e);
            importTaskService.markFailed(task.getId(), "KAFKA",
                    "消息队列不可用，请稍后重试", 0, 0);
            return R.fail("同步任务提交失败，请稍后重试");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus());
        data.put("async", true);              // 主平台前端据此判断是否轮询
        data.put("message", "同步任务已提交，正在后台处理中...");
        return R.ok(data);
    }

    /** 查询同步任务状态。 */
    @GetMapping("/{taskId}/status")
    public R<?> status(@PathVariable Long taskId) {
        SysImportTask task = importTaskService.getById(taskId);
        if (task == null) return R.fail("任务不存在");
        // Data scope check: only return tasks belonging to the current org
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        if (task.getCorpId() != null && visibleCorpIds != null
                && !visibleCorpIds.contains(task.getCorpId())
                && !visibleCorpIds.contains(0L)) {
            return R.fail("无权限查看该任务");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("status", task.getStatus());
        result.put("phase", task.getPhase());
        result.put("totalRows", task.getTotalRows());
        result.put("successRows", task.getSuccessRows());
        result.put("failRows", task.getFailRows());
        result.put("progressMsg", task.getProgressMsg());
        result.put("errorMsg", task.getErrorMsg());
        result.put("message", task.getProgressMsg());  // human-readable result
        return R.ok(result);
    }
}
