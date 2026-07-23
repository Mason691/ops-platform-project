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
                     @RequestParam(defaultValue = "false") boolean unassigned) {
        // Data scope isolation
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();

        LambdaQueryWrapper<OpsGasUser> qw = new LambdaQueryWrapper<>();
        if (visibleCorpIds != null) {
            qw.in(OpsGasUser::getCorpId, visibleCorpIds);
        }
        if (unassigned) {
            qw.isNull(OpsGasUser::getCommunityId);
        } else {
            qw.eq(communityId != null, OpsGasUser::getCommunityId, communityId);
        }
        // Fuzzy match community name: resolve matching IDs from sys_community
        if (communityKeyword != null && !communityKeyword.isBlank()) {
            Set<Long> matchedIds = resolveCommunityIdsByKeyword(communityKeyword);
            if (matchedIds.isEmpty()) {
                qw.eq(OpsGasUser::getCommunityId, -1L); // force empty result
            } else {
                qw.in(OpsGasUser::getCommunityId, matchedIds);
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w
                .like(OpsGasUser::getUserNo, keyword)
                .or().like(OpsGasUser::getUserName, keyword)
                .or().like(OpsGasUser::getPhone, keyword)
                .or().like(OpsGasUser::getAddress, keyword)
                .or().like(OpsGasUser::getArea, keyword));
        }
        qw.orderByAsc(OpsGasUser::getUserNo);
        Page<OpsGasUser> result = mapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // Resolve community names via ai_platform.sys_community (same MySQL, cross-db)
        Map<Long, String> nameMap = resolveCommunityNames(result.getRecords());
        for (OpsGasUser u : result.getRecords()) {
            if (u.getCommunityId() != null) u.setCommunityName(nameMap.get(u.getCommunityId()));
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

    /** Bulk convert: match gas users to platform members by phone, auto-create missing members. */
    @PostMapping("/convert-all")
    public R<?> convertAll() {
        int updated = 0, created = 0, failed = 0;
        try {
            // Step 1: match existing sys_member by phone
            updated = jdbcTemplate.update(
                "UPDATE ops_gas_user gu " +
                "JOIN (SELECT phone, MIN(id) AS mid FROM ai_platform.sys_member WHERE phone IS NOT NULL GROUP BY phone) sm " +
                "ON gu.phone = sm.phone " +
                "SET gu.platform_member_id = sm.mid " +
                "WHERE gu.platform_member_id IS NULL");

            // Step 2: for unmatched users, create sys_member records
            List<Map<String, Object>> unmatched = new ArrayList<>();
            jdbcTemplate.query(
                "SELECT gu.id, gu.phone, gu.user_name FROM ops_gas_user gu " +
                "WHERE gu.platform_member_id IS NULL AND gu.phone IS NOT NULL AND gu.phone REGEXP '^[0-9]{11}$' LIMIT 5000",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("id", rs.getLong("id")); r.put("phone", rs.getString("phone"));
                    r.put("name", rs.getString("user_name")); unmatched.add(r);
                });

            int seq = 0;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss");
            for (Map<String, Object> row : unmatched) {
                String ts2 = sdf.format(new java.util.Date()) + String.format("%02d", (seq % 100));
                seq++;
                try {
                    String memberId = "M" + ts2;
                    String phone = (String) row.get("phone");
                    String name = (String) row.getOrDefault("name", "");
                    if (name == null || name.isBlank()) name = phone.substring(0, 3) + "****" + phone.substring(7);

                    // Check if a member with this phone already exists (from a previous run)
                    Long existingId = null;
                    try {
                        existingId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE phone = ? LIMIT 1",
                            Long.class, phone);
                    } catch (Exception ignored) {}

                    if (existingId != null) {
                        jdbcTemplate.update(
                            "UPDATE ops_gas_user SET platform_member_id = ? WHERE id = ?",
                            existingId, row.get("id"));
                        created++;
                    } else {
                        // Create new sys_member
                        jdbcTemplate.update(
                            "INSERT INTO ai_platform.sys_member (member_id, phone, nickname, status) VALUES (?,?,?,1)",
                            memberId, phone, name);
                        Long memberDbId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE member_id = ?", Long.class, memberId);
                        if (memberDbId != null) {
                            jdbcTemplate.update(
                                "UPDATE ops_gas_user SET platform_member_id = ? WHERE id = ?",
                                memberDbId, row.get("id"));
                            created++;
                        }
                    }
                } catch (Exception e) {
                    // Phone unique collision — try one more time to link by phone
                    try {
                        Long existingId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE phone = ? LIMIT 1",
                            Long.class, (String) row.get("phone"));
                        if (existingId != null) {
                            jdbcTemplate.update(
                                "UPDATE ops_gas_user SET platform_member_id = ? WHERE id = ?",
                                existingId, row.get("id"));
                            created++;
                            continue;
                        }
                    } catch (Exception ignored2) {}
                    log.warn("Failed to create member for gas_user id={}: {}", row.get("id"), e.getMessage());
                    failed++;
                }
            }
        } catch (Exception e) {
            return R.fail("批量转化失败: " + e.getMessage());
        }
        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ops_gas_user WHERE platform_member_id IS NULL", Integer.class);
        return R.ok(Map.of("updated", updated, "created", created, "failed", failed,
            "unmatched", remaining != null ? remaining : 0));
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
            qw.like(OpsGasUser::getArea, keyword);
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
        String companyName = body.get("companyName");
        if (companyName == null || companyName.isBlank()) {
            return R.fail("请输入燃气公司名称");
        }
        try {
            JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
            Integer totalCount = gasJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sb_gas_user WHERE area_name LIKE CONCAT(?, '%')",
                    Integer.class, companyName);
            if (totalCount == null) totalCount = 0;

            // Estimate existing count
            int existingCount = estimateExistingCount(companyName);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companyName", companyName);
            data.put("totalCount", totalCount);
            data.put("existingCount", existingCount);
            data.put("newCount", Math.max(0, totalCount - existingCount));
            log.info("GasUser preview: company={} total={} existing={} new={}",
                    companyName, totalCount, existingCount, totalCount - existingCount);
            return R.ok(data);
        } catch (Exception e) {
            log.error("GasUser preview failed for company={}: {}", companyName, e.getMessage());
            return R.fail("查询外部数据库失败：" + e.getMessage());
        }
    }

    /** 估算外部数据中在 ops_gas_user 已存在的数量（按 phone 匹配计数）。 */
    private int estimateExistingCount(String companyName) {
        try {
            // Count external rows whose phone already exists in ops_gas_user
            JdbcTemplate gasJdbc = gasDataSourceConfig.getJdbcTemplate();
            Integer cnt = gasJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sb_gas_user e " +
                    "WHERE e.area_name LIKE CONCAT(?, '%') " +
                    "AND EXISTS (SELECT 1 FROM ops_gas_user g WHERE g.phone = e.phone " +
                    "AND g.gas_supply_certificate_number = e.gas_supply_certificate_number)",
                    Integer.class, companyName);
            return cnt != null ? cnt : 0;
        } catch (Exception e) {
            log.warn("Failed to estimate existing count for company={}: {}", companyName, e.getMessage());
            return 0;
        }
    }

    /** 触发从外部燃气数据库导入。仅限管理员/运营角色操作。 */
    @PostMapping({"/import-from-gas-platform", "/gas-import/start"})
    public R<?> importFromGasPlatform(@RequestBody Map<String, String> body) {
        String companyName = body.get("companyName");
        if (companyName == null || companyName.isBlank()) {
            return R.fail("请输入燃气公司名称");
        }
        if (importTaskService.hasPendingTask("gas_user")) {
            return R.fail("已有导入任务正在执行中，请等待完成后再试");
        }
        SysImportTask task = importTaskService.createTaskWithoutFile(
                "gas_user", "从燃气平台导入", companyName);
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
        data.put("companyName", companyName);
        data.put("message", "导入任务已提交，正在后台处理中...");
        return R.ok(data);
    }

    /** 查询导入任务状态。 */
    @GetMapping("/import/{taskId}/status")
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
