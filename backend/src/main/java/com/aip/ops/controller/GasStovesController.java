package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.config.GasDataSourceConfig;
import com.aip.ops.entity.OpsGasStovesInfo;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsGasStovesInfoMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.ImportTaskService;
import com.aip.ops.service.PlatformMerchantService;
import com.aip.sdk.result.R;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 燃具信息 — 外部燃气系统 jinyuan_info.sb_gas_stoves_info 全量迁移到 ops_gas_stoves_info。
 * 触发入口：主平台「增值业务 → 安检采集设备」列表的「迁移导入」按钮。
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
    private final OpsGasStovesInfoMapper gasStovesInfoMapper;
    private final PlatformMerchantService platformMerchantService;

    public GasStovesController(
            ImportTaskService importTaskService,
            DataScopeService dataScopeService,
            JdbcTemplate jdbcTemplate,
            GasDataSourceConfig gasDataSourceConfig,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            OpsGasStovesInfoMapper gasStovesInfoMapper,
            PlatformMerchantService platformMerchantService) {
        this.importTaskService = importTaskService;
        this.dataScopeService = dataScopeService;
        this.jdbcTemplate = jdbcTemplate;
        this.gasDataSourceConfig = gasDataSourceConfig;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.gasStovesInfoMapper = gasStovesInfoMapper;
        this.platformMerchantService = platformMerchantService;
    }

    /** 分页列表：安检采集燃气设备（含用户号/燃具种类/品牌筛选，组织数据范围隔离）。 */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String userNumber,
                     @RequestParam(required = false) String gasStovesClass,
                     @RequestParam(required = false) String brand) {
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();

        LambdaQueryWrapper<OpsGasStovesInfo> qw = new LambdaQueryWrapper<>();
        if (visibleCorpIds != null) qw.in(OpsGasStovesInfo::getCorpId, visibleCorpIds);
        if (userNumber != null && !userNumber.isBlank())
            qw.like(OpsGasStovesInfo::getUserNumber, userNumber.trim());
        if (gasStovesClass != null && !gasStovesClass.isBlank())
            qw.eq(OpsGasStovesInfo::getGasStovesClass, gasStovesClass.trim());
        if (brand != null && !brand.isBlank())
            qw.like(OpsGasStovesInfo::getGasStovesBrand, brand.trim());
        qw.orderByAsc(OpsGasStovesInfo::getId);

        Page<OpsGasStovesInfo> page = gasStovesInfoMapper.selectPage(new Page<>(pageNum, pageSize), qw);

        enrichList(page.getRecords());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", page.getRecords());
        data.put("total", page.getTotal());
        return R.ok(data);
    }

    /** 列表富化：用户姓名（ops_gas_user.full_name 按 gas_user_id）+ 组织名称（平台批量接口）。 */
    private void enrichList(List<OpsGasStovesInfo> records) {
        if (records.isEmpty()) return;
        Set<Long> gasUserIds = records.stream()
                .map(OpsGasStovesInfo::getGasUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> fullNames = queryUserFullNamesByIds(gasUserIds);
        Set<Long> corpIds = records.stream()
                .map(OpsGasStovesInfo::getCorpId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> corpNames = platformMerchantService.getCorpNames(corpIds);
        for (var r : records) {
            r.setUserFullName(fullNames.get(r.getGasUserId()));
            r.setCorpName(corpNames.get(r.getCorpId()));
        }
    }

    /** 批量查燃气用户姓名（按 gas_user_id，本地表，分块 IN 防止参数过多）。 */
    private Map<Long, String> queryUserFullNamesByIds(Set<Long> gasUserIds) {
        Map<Long, String> map = new HashMap<>();
        if (gasUserIds.isEmpty()) return map;
        List<Long> all = new ArrayList<>(gasUserIds);
        for (int i = 0; i < all.size(); i += 500) {
            int end = Math.min(i + 500, all.size());
            List<Long> batch = all.subList(i, end);
            String ph = String.join(",", batch.stream().map(u -> "?").toList());
            jdbcTemplate.query(
                    "SELECT id, full_name FROM ops_gas_user " +
                    "WHERE delete_flag = 0 AND id IN (" + ph + ")",
                    rs -> {
                        Long uid = rs.getLong(1);
                        String name = rs.getString(2);
                        if (uid != null) map.put(uid, name);
                    }, batch.toArray());
        }
        return map;
    }

    /** 预览：外部燃具总数 + 本地已有 + 预计新增 + 待补全关联数。 */
    @PostMapping("/preview")
    public R<?> preview() {
        try {
            JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
            Integer totalCount = gasJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sb_gas_stoves_info", Integer.class);
            if (totalCount == null) totalCount = 0;

            int existingCount = estimateExistingCount();
            int gasUserIdEmptyCount = countGasUserIdEmpty();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", totalCount);              // 主平台前端使用 count
            data.put("totalCount", totalCount);
            data.put("existingCount", existingCount);
            data.put("newCount", Math.max(0, totalCount - existingCount));
            data.put("gasUserIdEmptyCount", gasUserIdEmptyCount);   // 本地未关联燃气用户（本次迁移将尝试匹配）
            log.info("GasStoves preview: total={} existing={} new={} gasUserIdEmpty={}",
                    totalCount, existingCount, totalCount - existingCount, gasUserIdEmptyCount);
            return R.ok(data);
        } catch (Exception e) {
            log.error("GasStoves preview failed: {}", e.getMessage());
            return R.fail("查询外部数据库失败：" + e.getMessage());
        }
    }

    /** 估算外部燃具中本地已存在的数量（本地 id 即外部 sb_gas_stoves_info.id）。 */
    private int estimateExistingCount() {
        try {
            JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
            List<String> externalIds = gasJdbc.queryForList(
                    "SELECT id FROM sb_gas_stoves_info", String.class)
                    .stream().map(String::trim).toList();
            if (externalIds.isEmpty()) return 0;
            int existing = 0;
            for (int i = 0; i < externalIds.size(); i += 500) {
                int end = Math.min(i + 500, externalIds.size());
                List<String> batch = externalIds.subList(i, end);
                String placeholders = String.join(",", batch.stream().map(id -> "?").toList());
                Integer cnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ops_gas_stoves_info WHERE delete_flag=0 AND id IN (" + placeholders + ")",
                        Integer.class, batch.toArray());
                existing += (cnt != null ? cnt : 0);
            }
            return existing;
        } catch (Exception e) {
            log.warn("Failed to estimate existing gas stoves count: {}", e.getMessage());
            return 0;
        }
    }

    /** 本地未关联燃气用户的记录数（本次迁移将尝试匹配补全）。 */
    private int countGasUserIdEmpty() {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ops_gas_stoves_info " +
                    "WHERE delete_flag=0 AND gas_user_id IS NULL",
                    Integer.class);
            return cnt != null ? cnt : 0;
        } catch (Exception e) {
            // DDL 未执行时无 gas_user_id 列，待补全数按 0 处理（preview 不阻断提交）
            log.warn("Failed to count empty gas_user_id rows: {}", e.getMessage());
            return 0;
        }
    }

    /** 触发燃具信息全量迁移导入（异步 Kafka 任务）。 */
    @OperationLog(module = "增值业务", action = "迁移导入燃具信息", targetTable = "ops_gas_stoves_info")
    @PostMapping("/submit")
    public R<?> submit() {
        if (importTaskService.hasPendingTask("gas_stoves")) {
            return R.fail("已有迁移任务正在执行中，请等待完成后再试");
        }
        SysImportTask task = importTaskService.createTaskWithoutFile(
                "gas_stoves", "迁移导入燃具信息");
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
        data.put("message", "迁移任务已提交，正在后台处理中...");
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
