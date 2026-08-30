package com.aip.ops.imports;

import com.aip.ops.entity.SysImportTask;
import com.aip.ops.service.ImportTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;

/**
 * 燃气用户批量转化会员 — 异步处理器。
 * 复用 sys_import_task + ImportTaskConsumer 基础设施，taskType = "member_convert"。
 *
 * 数据范围：创建任务时前端不传 corpId（JWT 决定），Controller 调用 DataScopeService
 * 算出可见 corpId 写入 task.remark，本 Handler 从 remark 读取用作 SQL WHERE 过滤。
 *
 * 画像同步：转化时同步 gas_user 的 gender/birth_date/detailed_address/community_id
 * → sys_member 的 gender/birthday/address/community_id。
 * - 已有会员（按手机号匹配）：UPDATE 覆盖
 * - 新创建会员：INSERT 时写入
 *
 * 全量处理：按手机号分批（每批 BATCH_SIZE 个未匹配手机号）循环处理，直到没有
 * 剩余未匹配手机号——单次任务即可转化全部数据。批次查询以 platform_member_id
 * IS NULL 为条件，每批回写后下一批自然递减，无需 OFFSET。
 * 一个手机号只建一个会员：GROUP BY phone（跨组织同号也只建一条），回写时
 * 覆盖该手机号下所有燃气用户行。
 */
@Slf4j
@Component
public class MemberConvertHandler implements ImportHandler {

    private final JdbcTemplate jdbcTemplate;
    private final ImportTaskService taskService;

    public MemberConvertHandler(JdbcTemplate jdbcTemplate, ImportTaskService taskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskService = taskService;
    }

    @Override
    public String getTaskType() {
        return "member_convert";
    }

    @Override
    public ImportResult execute(SysImportTask task) throws Exception {
        Long taskId = task.getId();
        Long corpId = task.getCorpId();
        int updated = 0, profileUpdated = 0, created = 0, skipped = 0, failed = 0;
        List<String> errors = new ArrayList<>();

        String corpIdCsv = task.getRemark();
        String scopeClause = "";
        if (corpId != null && corpId != 0 && corpIdCsv != null && !corpIdCsv.isBlank()) {
            scopeClause = " AND gu.corp_id IN (" + corpIdCsv + ")";
        }

        try {
            // ── Phase 1: 匹配已有会员 + 同步更新画像 ──
            int[] matched = matchExistingMembers(taskId, scopeClause);
            profileUpdated = matched[0];
            updated = matched[1];

            // ── Phase 2+3: 分批处理未匹配手机号，直到全量转化完成 ──
            int[] seqHolder = {0};
            int batches = 0;
            while (batches < MAX_BATCHES) {
                List<Map<String, Object>> uniquePhones = fetchUnlinkedPhones(scopeClause);
                if (uniquePhones.isEmpty()) break;
                batches++;
                log.info("MemberConvert: taskId={} batch {} unique phones: {}",
                        taskId, batches, uniquePhones.size());
                BatchResult r = processBatch(taskId, uniquePhones,
                        buildRegionPathCache(uniquePhones), seqHolder);
                created += r.created;
                skipped += r.skipped;
                failed += r.failed;
                errors.addAll(r.errors);
            }
            if (batches >= MAX_BATCHES) {
                log.error("MemberConvert: taskId={} hit MAX_BATCHES, aborted early (persistent phone failures?)", taskId);
            }
        } catch (Exception e) {
            log.error("MemberConvert: taskId={} fatal error", taskId, e);
            return ImportResult.fail("EXCEPTION", List.of("转化异常: " + e.getMessage()), 0);
        }

        return buildResult(updated + profileUpdated, created, skipped, failed, errors, 0);
    }

    /** 每批处理的未匹配手机号数量。 */
    private static final int BATCH_SIZE = 5000;
    /** 最大批次数（25万手机号上限），防止个别手机号持续失败导致死循环。 */
    private static final int MAX_BATCHES = 100;

    /** Phase 1: 按手机号匹配已有会员 → 同步画像 + 回写 platform_member_id。返回 [profileUpdated, updated]。 */
    private int[] matchExistingMembers(Long taskId, String scopeClause) {
        taskService.updateStatus(taskId, "PROCESSING", "MATCH_EXISTING",
                "正在按手机号匹配已有会员并更新画像...");

        // 1a: 同步已有会员的画像字段
        int profileUpdated = 0;
        String syncProfileSql = "UPDATE ai_platform.sys_member sm " +
            "JOIN (SELECT gu.phone, gu.gender, gu.birth_date, gu.detailed_address, gu.community_id, gu.corp_id " +
            "      FROM ops_gas_user gu " +
            "      WHERE gu.platform_member_id IS NULL AND gu.phone IS NOT NULL" + scopeClause + ") src " +
            "  ON sm.phone = src.phone " +
            "SET sm.gender = CASE WHEN src.gender IS NOT NULL AND src.gender != '' " +
            "    THEN CAST(src.gender AS SIGNED) ELSE sm.gender END, " +
            "    sm.birthday = COALESCE(src.birth_date, sm.birthday), " +
            "    sm.address = CASE WHEN src.detailed_address IS NOT NULL AND src.detailed_address != '' " +
            "    THEN src.detailed_address ELSE sm.address END, " +
            "    sm.community_id = COALESCE(src.community_id, sm.community_id), " +
            "    sm.corp_id = COALESCE(src.corp_id, sm.corp_id)";
        try {
            profileUpdated = jdbcTemplate.update(syncProfileSql);
        } catch (Exception e) {
            log.warn("MemberConvert: profile sync failed (non-fatal): {}", e.getMessage());
        }

        // 1b: 回写 platform_member_id
        String matchSql = "UPDATE ops_gas_user gu " +
            "JOIN (SELECT phone, MIN(id) AS mid FROM ai_platform.sys_member WHERE phone IS NOT NULL GROUP BY phone) sm " +
            "ON gu.phone = sm.phone " +
            "SET gu.platform_member_id = sm.mid " +
            "WHERE gu.platform_member_id IS NULL" + scopeClause;
        int updated = jdbcTemplate.update(matchSql);
        log.info("MemberConvert: taskId={} profileUpdated={} matched={}", taskId, profileUpdated, updated);
        return new int[]{profileUpdated, updated};
    }

    /**
     * 查询一批未匹配手机号（每批 BATCH_SIZE 个）。
     * GROUP BY phone：一个手机号只建一个会员，跨组织同号也归并为一条。
     * 批处理过的手机号已回写 platform_member_id，下一批查询自然跳过，无需 OFFSET。
     */
    private List<Map<String, Object>> fetchUnlinkedPhones(String scopeClause) {
        List<Map<String, Object>> uniquePhones = new ArrayList<>();
        String querySql = "SELECT MIN(gu.id) AS id, gu.phone, MIN(gu.corp_id) AS corp_id, " +
            "MAX(gu.gender) AS gender, MAX(gu.birth_date) AS birth_date, " +
            "MAX(gu.detailed_address) AS detailed_address, MAX(gu.community_id) AS community_id, " +
            "MAX(gu.full_name) AS full_name, MAX(gu.area_name) AS area_name " +
            "FROM ops_gas_user gu " +
            "WHERE gu.platform_member_id IS NULL AND gu.phone IS NOT NULL " +
            "AND gu.phone REGEXP '^[0-9]{11}$' " + scopeClause + " " +
            "GROUP BY gu.phone LIMIT " + BATCH_SIZE;
        jdbcTemplate.query(querySql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rs.getLong("id"));
            r.put("phone", rs.getString("phone"));
            r.put("corp_id", rs.getLong("corp_id"));
            r.put("name", rs.getString("full_name"));
            r.put("gender", rs.getString("gender"));
            r.put("birth_date", rs.getDate("birth_date") != null
                    ? rs.getDate("birth_date").toLocalDate() : null);
            r.put("detailed_address", rs.getString("detailed_address"));
            r.put("area_name", rs.getString("area_name"));
            long cid = rs.getLong("community_id");
            r.put("community_id", rs.wasNull() ? null : cid);
            uniquePhones.add(r);
        });
        return uniquePhones;
    }

    /** 处理一批手机号：逐号建会员（已有则更新画像），并回写该号下所有燃气用户。 */
    private BatchResult processBatch(Long taskId, List<Map<String, Object>> uniquePhones,
                                     Map<String, String> regionPathCache, int[] seqHolder) {
        BatchResult result = new BatchResult();
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss");
        int total = uniquePhones.size();
        int done = 0;

        for (Map<String, Object> row : uniquePhones) {
            done++;
            String memberIdSuffix = String.format("%s%03d",
                    sdf.format(new java.util.Date()), seqHolder[0] % 1000);
            seqHolder[0]++;
            handleOnePhone(result, row, regionPathCache, memberIdSuffix);

            if (done % 100 == 0) {
                taskService.updateStatus(taskId, "PROCESSING", "CREATING_MEMBER",
                        String.format("正在创建会员... 本批已处理 %d/%d (新建 %d 跳过 %d 失败 %d)",
                                done, total, result.created, result.skipped, result.failed));
            }
        }
        return result;
    }

    /** 单个手机号：解析字段 → 建会员或更新已有会员 → 回写该号全部燃气用户。 */
    private void handleOnePhone(BatchResult result, Map<String, Object> row,
                                Map<String, String> regionPathCache, String memberIdSuffix) {
        String phone = (String) row.get("phone");
        String name = (String) row.getOrDefault("name", "");
        if (name == null || name.isBlank()) name = phone.substring(0, 3) + "****" + phone.substring(7);
        Long rowCorpId = (Long) row.get("corp_id");

        Integer genderInt = null;
        String genderStr = (String) row.get("gender");
        if (genderStr != null && !genderStr.isBlank()) {
            try { genderInt = Integer.parseInt(genderStr); } catch (NumberFormatException ignored) {}
        }
        LocalDate birthDate = (LocalDate) row.get("birth_date");
        String areaName = (String) row.get("area_name");
        Long communityId = (Long) row.get("community_id");

        // 地址 = 省+市+区县
        String fullAddress = regionPathCache.getOrDefault(areaName, areaName != null ? areaName : "");

        try {
            Long memberDbId = createOrUpdateMember(phone, name, genderInt, birthDate,
                    fullAddress, communityId, rowCorpId, memberIdSuffix, result);
            backfillMemberId(memberDbId, phone, result);
        } catch (Exception e) {
            log.warn("MemberConvert: failed for phone={}: {}", phone, e.getMessage());
            String err = "手机号 " + phone + ": " + e.getMessage();
            if (err.length() > 200) err = err.substring(0, 200);
            result.errors.add(err);
            result.failed++;
        }
    }

    /** Step A: sys_member 中已有该手机号则更新画像，否则新建会员。返回会员主键 id。 */
    private Long createOrUpdateMember(String phone, String name, Integer genderInt, LocalDate birthDate,
                                      String fullAddress, Long communityId, Long rowCorpId,
                                      String memberIdSuffix, BatchResult result) {
        Long existingId = null;
        try {
            existingId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_platform.sys_member WHERE phone = ? LIMIT 1",
                Long.class, phone);
        } catch (Exception ignored) {}

        if (existingId != null) {
            jdbcTemplate.update(
                "UPDATE ai_platform.sys_member SET gender=COALESCE(?,gender), " +
                "birthday=COALESCE(?,birthday), address=COALESCE(NULLIF(?,''),address), " +
                "community_id=COALESCE(?,community_id), corp_id=COALESCE(?,corp_id), " +
                "real_name=COALESCE(NULLIF(?,''),real_name) WHERE id=?",
                genderInt, birthDate, fullAddress, communityId, rowCorpId, name, existingId);
            result.skipped++;
            return existingId;
        }

        String memberId = "M" + memberIdSuffix;
        jdbcTemplate.update(
            "INSERT INTO ai_platform.sys_member " +
            "(member_id, phone, nickname, real_name, status, gender, birthday, address, community_id, corp_id) " +
            "VALUES (?,?,?,?,1,?,?,?,?,?)",
            memberId, phone, name, name, genderInt, birthDate, fullAddress, communityId, rowCorpId);
        result.created++;
        return jdbcTemplate.queryForObject(
            "SELECT id FROM ai_platform.sys_member WHERE member_id = ?", Long.class, memberId);
    }

    /** Step B: 回写 platform_member_id 到所有同手机号、尚未转化的燃气用户。 */
    private void backfillMemberId(Long memberDbId, String phone, BatchResult result) {
        if (memberDbId == null) return;
        int linked = jdbcTemplate.update(
            "UPDATE ops_gas_user SET platform_member_id = ? " +
            "WHERE phone = ? AND platform_member_id IS NULL",
            memberDbId, phone);
        if (linked > 1) {
            result.skipped += (linked - 1); // 第一条计入 created/skipped，其余计入 skipped
        }
    }

    /** 单批处理结果计数器。 */
    private static class BatchResult {
        int created = 0;
        int skipped = 0;
        int failed = 0;
        final List<String> errors = new ArrayList<>();
    }

    /**
     * 构建 area_name → "省+市+区" 缓存。
     * 从 sys_region 表三级自联查出每个区县的全路径。
     */
    private Map<String, String> buildRegionPathCache(List<Map<String, Object>> rows) {
        Map<String, String> cache = new LinkedHashMap<>();
        // 收集所有非空 area_name
        List<String> areas = rows.stream()
                .map(r -> (String) r.get("area_name"))
                .filter(a -> a != null && !a.isBlank())
                .distinct()
                .toList();
        if (areas.isEmpty()) return cache;

        // 批量查：区县名 → 省+市+区县
        String placeholders = String.join(",", areas.stream().map(a -> "?").toArray(String[]::new));
        String sql = "SELECT r3.region_name AS district, " +
                "CONCAT_WS('', r1.region_name, r2.region_name, r3.region_name) AS full_path " +
                "FROM ai_platform.sys_region r3 " +
                "LEFT JOIN ai_platform.sys_region r2 ON r2.id = r3.parent_id AND r2.region_level = 2 " +
                "LEFT JOIN ai_platform.sys_region r1 ON r1.id = r2.parent_id AND r1.region_level = 1 " +
                "WHERE r3.region_level = 3 AND r3.region_name IN (" + placeholders + ")";
        try {
            jdbcTemplate.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                cache.put(rs.getString("district"), rs.getString("full_path"));
            }, areas.toArray());
        } catch (Exception e) {
            log.warn("MemberConvert: failed to load region path cache: {}", e.getMessage());
        }
        log.info("MemberConvert: region path cache loaded {} entries", cache.size());
        return cache;
    }

    private ImportResult buildResult(int updated, int created, int skipped, int failed,
                                      List<String> errors, int total) {
        int totalProcessed = updated + created + skipped + failed;
        ImportResult r = ImportResult.success(created + updated, totalProcessed);
        r.setExistingCount(skipped);
        r.setFailRows(failed);
        r.setErrors(errors);
        // 结构化 JSON 供 status 接口解析 → 前端展示精确计数
        String json = String.format("{\"updated\":%d,\"created\":%d,\"skipped\":%d,\"failed\":%d}",
                updated, created, skipped, failed);
        r.setMessage(json + "|" + String.format("匹配更新 %d 人（含画像同步），新建会员 %d 人，跳过（已有会员）%d 人，失败 %d 人",
                updated, created, skipped, failed));
        return r;
    }
}
