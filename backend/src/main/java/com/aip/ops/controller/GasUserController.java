package com.aip.ops.controller;

import com.aip.ops.config.GasDataSourceConfig;
import com.aip.ops.entity.OpsGasUser;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.mapper.OpsGasUserMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.ImportTaskService;
import com.aip.sdk.result.R;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/gas-user")
public class GasUserController {

    private final OpsGasUserMapper mapper;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;
    private final ImportTaskService importTaskService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final GasDataSourceConfig gasDataSourceConfig;

    public GasUserController(
            OpsGasUserMapper mapper,
            DataScopeService dataScopeService,
            JdbcTemplate jdbcTemplate,
            ImportTaskService importTaskService,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            GasDataSourceConfig gasDataSourceConfig) {
        this.mapper = mapper;
        this.dataScopeService = dataScopeService;
        this.jdbcTemplate = jdbcTemplate;
        this.importTaskService = importTaskService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.gasDataSourceConfig = gasDataSourceConfig;
    }

    /** Paginated list with optional community filter and keyword search. */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) Long communityId,
                     @RequestParam(required = false) String communityKeyword,
                     @RequestParam(required = false) String keyword,
                     @RequestParam(defaultValue = "false") boolean unassigned,
                     @RequestParam(required = false) String gender,
                     @RequestParam(required = false) Integer ageMin,
                     @RequestParam(required = false) String phone,
                     @RequestParam(required = false) String openStart,
                     @RequestParam(required = false) String openEnd,
                     @RequestParam(required = false) Integer accountYearsMin,
                     @RequestParam(required = false) Boolean isMember) {
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();

        LambdaQueryWrapper<OpsGasUser> qw = new LambdaQueryWrapper<>();
        if (visibleCorpIds != null) qw.in(OpsGasUser::getCorpId, visibleCorpIds);
        if (unassigned) qw.isNull(OpsGasUser::getCommunityId);
        else if (communityId != null) qw.eq(OpsGasUser::getCommunityId, communityId);

        // Community keyword → resolve IDs
        if (communityKeyword != null && !communityKeyword.isBlank()) {
            Set<Long> ids = resolveCommunityIdsByKeyword(communityKeyword);
            qw.in(ids.isEmpty() ? OpsGasUser::getCommunityId : OpsGasUser::getCommunityId,
                    ids.isEmpty() ? List.of(-1L) : new ArrayList<>(ids));
        }
        // Gender filter
        if (gender != null && !gender.isBlank()) qw.eq(OpsGasUser::getGender, gender);
        // Age filter: birth_date <= DATE_SUB(CURDATE(), INTERVAL ageMin YEAR)
        if (ageMin != null && ageMin > 0)
            qw.le(OpsGasUser::getBirthDate, java.time.LocalDate.now().minusYears(ageMin));
        // Phone fuzzy search
        if (phone != null && !phone.isBlank()) qw.like(OpsGasUser::getPhone, phone);
        // Account open time range
        if (openStart != null && !openStart.isBlank())
            qw.ge(OpsGasUser::getAccountOpeningTime, java.time.LocalDate.parse(openStart).atStartOfDay());
        if (openEnd != null && !openEnd.isBlank())
            qw.le(OpsGasUser::getAccountOpeningTime, java.time.LocalDate.parse(openEnd).atTime(23,59,59));
        // Account years filter
        if (accountYearsMin != null && accountYearsMin > 0)
            qw.le(OpsGasUser::getAccountOpeningTime, java.time.LocalDateTime.now().minusYears(accountYearsMin));
        // Member filter
        if (isMember != null) {
            if (isMember) qw.isNotNull(OpsGasUser::getPlatformMemberId);
            else qw.isNull(OpsGasUser::getPlatformMemberId);
        }
        // General keyword
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(OpsGasUser::getUserNo, keyword)
                .or().like(OpsGasUser::getFullName, keyword)
                .or().like(OpsGasUser::getPhone, keyword)
                .or().like(OpsGasUser::getDetailedAddress, keyword)
                .or().like(OpsGasUser::getAreaName, keyword));
        }
        qw.orderByAsc(OpsGasUser::getUserNo);
        Page<OpsGasUser> result = mapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // Community names
        Map<Long, String> nameMap = resolveCommunityNames(result.getRecords());
        java.time.LocalDate today = java.time.LocalDate.now();
        for (OpsGasUser u : result.getRecords()) {
            if (u.getCommunityId() != null) u.setCommunityName(nameMap.get(u.getCommunityId()));
            // Computed fields
            u.setIsMember(u.getPlatformMemberId() != null);
            if (u.getBirthDate() != null)
                u.setAge(java.time.Period.between(u.getBirthDate(), today).getYears());
            if (u.getAccountOpeningTime() != null)
                u.setAccountYears(java.time.Period.between(u.getAccountOpeningTime().toLocalDate(), today).getYears());
            // Phone masking: 138****5678
            if (u.getPhone() != null && u.getPhone().length() == 11)
                u.setMaskedPhone(u.getPhone().substring(0, 3) + "****" + u.getPhone().substring(7));
            else u.setMaskedPhone(u.getPhone());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        return R.ok(data);
    }

    private Map<Long, String> resolveCommunityNames(List<OpsGasUser> users) {
        Set<Long> ids = new LinkedHashSet<>();
        for (OpsGasUser u : users) if (u.getCommunityId() != null) ids.add(u.getCommunityId());
        if (ids.isEmpty()) return Map.of();

        Map<Long, String> map = new LinkedHashMap<>();
        try {
            String inClause = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            jdbcTemplate.query(
                    "SELECT id, community_name FROM ai_platform.sys_community WHERE id IN (" + inClause + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> map.put(rs.getLong("id"), rs.getString("community_name")));
        } catch (Exception e) {
            log.warn("Failed to resolve community names: {}", e.getMessage());
        }
        return map;
    }

    /** Resolve community IDs whose name matches the keyword (LIKE). */
    private Set<Long> resolveCommunityIdsByKeyword(String keyword) {
        Set<Long> ids = new LinkedHashSet<>();
        try {
            String likeKw = keyword.replace("'", "''");
            jdbcTemplate.query(
                    "SELECT id FROM ai_platform.sys_community WHERE community_name LIKE '%" + likeKw + "%'",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> ids.add(rs.getLong("id")));
        } catch (Exception e) {
            log.warn("Failed to resolve community IDs by keyword: {}", e.getMessage());
        }
        return ids;
    }

    /** Get all community id->name pairs for the community selector dropdown. */
    @GetMapping("/communities")
    public R<?> communities() {
        Map<Long, String> map = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT id, community_name FROM ai_platform.sys_community WHERE delete_flag=0 ORDER BY community_name",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> map.put(rs.getLong("id"), rs.getString("community_name")));
        } catch (Exception e) {
            log.warn("Failed to load communities: {}", e.getMessage());
        }
        return R.ok(map.entrySet().stream()
                .map(e -> Map.of("id", e.getKey(), "name", e.getValue()))
                .toList());
    }

    /**
     * Async batch convert: submit a member_convert task to Kafka.
     * Returns taskId immediately; client polls GET /convert/{taskId}/status for progress.
     */
    @PostMapping("/convert/submit")
    public R<?> convertSubmit() {
        return submitConvertTask();
    }

    /** Same as /convert/submit — canonical path. */
    @PostMapping("/convert-all")
    public R<?> convertAll() {
        return submitConvertTask();
    }

    private R<?> submitConvertTask() {
        if (importTaskService.hasPendingTask("member_convert")) {
            return R.fail("已有进行中的转化任务，请等待完成后重试");
        }
        // 算数据范围：可见 corpId CSV 写入 remark，供 MemberConvertHandler 过滤
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        String scope = visibleCorpIds != null
                ? visibleCorpIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")
                : "";
        SysImportTask task = importTaskService.createTaskWithoutFile(
                "member_convert", "批量转化会员", scope);
        try {
            kafkaTemplate.send("async-import-task",
                    objectMapper.writeValueAsString(Map.of("taskId", task.getId())));
        } catch (Exception e) {
            log.error("Failed to send member_convert kafka message for taskId={}", task.getId(), e);
            importTaskService.markFailed(task.getId(), "KAFKA_SEND",
                    "消息队列发送失败: " + e.getMessage(), 0, 0);
            return R.fail("任务提交失败，请重试");
        }
        return R.ok(Map.of("taskId", task.getId()));
    }

    /** Poll convert task status. Returns detail counts when done. */
    @GetMapping("/convert/{taskId}/status")
    public R<?> convertStatus(@PathVariable Long taskId) {
        SysImportTask task = importTaskService.getById(taskId);
        if (task == null) return R.fail("任务不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("status", task.getStatus());
        result.put("phase", task.getPhase());
        result.put("progressMsg", task.getProgressMsg());
        result.put("totalRows", task.getTotalRows());
        result.put("successRows", task.getSuccessRows());
        result.put("failRows", task.getFailRows());
        result.put("errorMsg", task.getErrorMsg());
        result.put("finishTime", task.getFinishTime() != null ? task.getFinishTime().toString() : null);
        return R.ok(result);
    }

    /** Single manual conversion: force associate a gas user with a specific platform member. */
    @PostMapping("/{id}/convert")
    public R<?> convertOne(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        Long memberId = body.get("platformMemberId");
        if (memberId == null) return R.fail("platformMemberId 不能为空");
        OpsGasUser gu = mapper.selectById(id);
        if (gu == null) return R.fail("燃气用户不存在");
        gu.setPlatformMemberId(memberId);
        mapper.updateById(gu);
        return R.ok();
    }

    /** Search gas users by area keyword (fuzzy match), with pagination and community names. */
    @GetMapping("/search-by-area")
    public R<?> searchByArea(@RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "10") int pageSize,
                             @RequestParam String keyword) {
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();

        LambdaQueryWrapper<OpsGasUser> qw = new LambdaQueryWrapper<>();
        if (visibleCorpIds != null) {
            qw.in(OpsGasUser::getCorpId, visibleCorpIds);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like(OpsGasUser::getAreaName, keyword);
        }
        qw.orderByAsc(OpsGasUser::getUserNo);
        Page<OpsGasUser> result = mapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // Resolve community names
        Map<Long, String> nameMap = resolveCommunityNames(result.getRecords());
        for (OpsGasUser u : result.getRecords()) {
            if (u.getCommunityId() != null) u.setCommunityName(nameMap.get(u.getCommunityId()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        return R.ok(data);
    }

    /** Get single gas user detail with community name resolved. */
    @GetMapping("/{id:\\d+}")
    public R<?> detail(@PathVariable Long id) {
        OpsGasUser user = mapper.selectById(id);
        if (user == null) return R.fail("燃气用户不存在");
        // Data scope check
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        if (visibleCorpIds != null && !visibleCorpIds.contains(user.getCorpId())) {
            return R.fail("无权限查看该用户");
        }
        // Resolve community name
        if (user.getCommunityId() != null) {
            user.setCommunityName(resolveSingleCommunityName(user.getCommunityId()));
        }
        return R.ok(user);
    }

    /** Update a single gas user (editable: communityId). */
    @PutMapping("/{id:\\d+}")
    public R<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        OpsGasUser user = mapper.selectById(id);
        if (user == null) return R.fail("燃气用户不存在");
        // Data scope check
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        if (visibleCorpIds != null && !visibleCorpIds.contains(user.getCorpId())) {
            return R.fail("无权限修改该用户");
        }
        // Only update communityId
        if (body.containsKey("communityId")) {
            Long communityId = body.get("communityId") != null
                    ? ((Number) body.get("communityId")).longValue() : null;
            LambdaUpdateWrapper<OpsGasUser> uw = new LambdaUpdateWrapper<>();
            uw.eq(OpsGasUser::getId, id).set(OpsGasUser::getCommunityId, communityId);
            mapper.update(null, uw);
        }
        return R.ok();
    }

    /** Batch assign community to selected gas users. */
    @PutMapping("/batch-assign-community")
    public R<?> batchAssignCommunity(@RequestBody Map<String, Object> body) {
        // Validate communityId
        Object cidObj = body.get("communityId");
        if (cidObj == null) return R.fail("目标小区不能为空");
        Long communityId = ((Number) cidObj).longValue();

        // Validate userIds
        @SuppressWarnings("unchecked")
        List<Integer> userIdsRaw = (List<Integer>) body.get("userIds");
        if (userIdsRaw == null || userIdsRaw.isEmpty()) return R.fail("请至少选择一个用户");

        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();

        // Build IN clause for update
        String inClause = userIdsRaw.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        StringBuilder sql = new StringBuilder("UPDATE ops_gas_user SET community_id = ? WHERE id IN (").append(inClause).append(")");
        if (visibleCorpIds != null && !visibleCorpIds.isEmpty()) {
            String corpIn = visibleCorpIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            sql.append(" AND corp_id IN (").append(corpIn).append(")");
        }
        int updated = jdbcTemplate.update(sql.toString(), communityId);

        return R.ok(Map.of("updated", updated));
    }

    /** Resolve a single community name by ID. */
    private String resolveSingleCommunityName(Long communityId) {
        if (communityId == null) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT community_name FROM ai_platform.sys_community WHERE id = ?",
                    String.class, communityId);
        } catch (Exception e) {
            log.warn("Failed to resolve community name for id={}: {}", communityId, e.getMessage());
            return null;
        }
    }

    // ── 外部燃气平台导入 ──

    /** 预览：查询外部数据库匹配数量 + 预计去重后新增数量。 */
    @PostMapping({"/preview-from-gas-platform", "/gas-import/query"})
    public R<?> previewFromGasPlatform(@RequestBody Map<String, String> body) {
        String areaName = body.getOrDefault("areaName", body.get("companyName"));
        if (areaName == null || areaName.isBlank()) {
            return R.fail("请输入地区名称");
        }
        try {
            JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
            Integer totalCount = gasJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sb_gas_user WHERE area_name LIKE CONCAT(?, '%')",
                    Integer.class, areaName);
            if (totalCount == null) totalCount = 0;

            // Estimate existing count
            int existingCount = estimateExistingCount(areaName);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("areaName", areaName);
            data.put("count", totalCount);              // 主平台前端使用 count
            data.put("totalCount", totalCount);
            data.put("existingCount", existingCount);
            data.put("newCount", Math.max(0, totalCount - existingCount));
            log.info("GasUser preview: area={} total={} existing={} new={}",
                    areaName, totalCount, existingCount, totalCount - existingCount);
            return R.ok(data);
        } catch (Exception e) {
            log.error("GasUser preview failed for area={}: {}", areaName, e.getMessage());
            return R.fail("查询外部数据库失败：" + e.getMessage());
        }
    }

    /** 估算外部数据中在 ops_gas_user 已存在的数量（按 user_no = sb_gas_user.id）。 */
    private int estimateExistingCount(String areaName) {
        try {
            // 1. 从 Polardb 获取所有 sb_gas_user.id
            JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
            List<String> externalIds = gasJdbc.queryForList(
                    "SELECT id FROM sb_gas_user WHERE area_name LIKE CONCAT(?, '%')",
                    String.class, areaName);
            if (externalIds.isEmpty()) return 0;
            // 2. 分批查询 ops_gas_user 中匹配的 user_no 数量
            int existing = 0;
            for (int i = 0; i < externalIds.size(); i += 500) {
                int end = Math.min(i + 500, externalIds.size());
                List<String> batch = externalIds.subList(i, end);
                String placeholders = String.join(",", batch.stream().map(id -> "?").toList());
                Integer cnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ops_gas_user WHERE delete_flag=0 AND user_no IN (" + placeholders + ")",
                        Integer.class, batch.toArray());
                existing += (cnt != null ? cnt : 0);
            }
            return existing;
        } catch (Exception e) {
            log.warn("Failed to estimate existing count for area={}: {}", areaName, e.getMessage());
            return 0;
        }
    }

    /** 触发从外部燃气数据库导入。仅限管理员/运营角色操作。 */
    @PostMapping({"/import-from-gas-platform", "/gas-import/start", "/gas-import/submit"})
    public R<?> importFromGasPlatform(@RequestBody Map<String, String> body) {
        String areaName = body.getOrDefault("areaName", body.get("companyName"));
        if (areaName == null || areaName.isBlank()) {
            return R.fail("请输入地区名称");
        }
        if (importTaskService.hasPendingTask("gas_user")) {
            return R.fail("已有导入任务正在执行中，请等待完成后再试");
        }
        SysImportTask task = importTaskService.createTaskWithoutFile(
                "gas_user", "从燃气平台导入", areaName);
        try {
            kafkaTemplate.send("async-import-task",
                    objectMapper.writeValueAsString(Map.of("taskId", task.getId())));
        } catch (Exception e) {
            log.error("Failed to send Kafka message for gas import task {}", task.getId(), e);
            importTaskService.markFailed(task.getId(), "KAFKA",
                    "消息队列不可用，请稍后重试", 0, 0);
            return R.fail("导入任务提交失败，请稍后重试");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus());
        data.put("areaName", areaName);
        data.put("async", true);              // 主平台前端据此判断是否轮询
        data.put("message", "导入任务已提交，正在后台处理中...");
        return R.ok(data);
    }

    /** 查询导入任务状态。 */
    @GetMapping({"/import/{taskId}/status", "/gas-import/{taskId}/status"})
    public R<?> importStatus(@PathVariable Long taskId) {
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
