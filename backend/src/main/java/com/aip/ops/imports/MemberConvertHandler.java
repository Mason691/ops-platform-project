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
            taskService.updateStatus(taskId, "PROCESSING", "MATCH_EXISTING",
                    "正在按手机号匹配已有会员并更新画像...");

            // 1a: 同步已有会员的画像字段
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
            updated = jdbcTemplate.update(matchSql);
            log.info("MemberConvert: taskId={} profileUpdated={} matched={}", taskId, profileUpdated, updated);

            // ── Phase 2: 查询未匹配用户（按手机号去重，每号取 MIN(id)）──
            List<Map<String, Object>> uniquePhones = new ArrayList<>();
            String querySql = "SELECT MIN(gu.id) AS id, gu.phone, gu.corp_id, " +
                "MAX(gu.gender) AS gender, MAX(gu.birth_date) AS birth_date, " +
                "MAX(gu.detailed_address) AS detailed_address, MAX(gu.community_id) AS community_id, " +
                "MAX(gu.full_name) AS full_name, MAX(gu.area_name) AS area_name " +
                "FROM ops_gas_user gu " +
                "WHERE gu.platform_member_id IS NULL AND gu.phone IS NOT NULL " +
                "AND gu.phone REGEXP '^[0-9]{11}$' " + scopeClause + " " +
                "GROUP BY gu.phone, gu.corp_id LIMIT 5000";
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
            log.info("MemberConvert: taskId={} unique phones to process: {}", taskId, uniquePhones.size());

            if (uniquePhones.isEmpty()) {
                return buildResult(updated + profileUpdated, created, skipped, failed, errors, 0);
            }

            // ── Pre-load region path map: area_name → "省+市+区" ──
            Map<String, String> regionPathCache = buildRegionPathCache(uniquePhones);

            // ── Phase 3: 逐号创建会员 + 回写所有同号燃气用户 ──
            SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss");
            int seq = 0;

            for (Map<String, Object> row : uniquePhones) {
                String memberIdSuffix = String.format("%s%03d",
                        sdf.format(new java.util.Date()), seq % 1000);
                seq++;
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
                String rawAddress = (String) row.get("detailed_address");
                Long communityId = (Long) row.get("community_id");

                // 地址拼接：省+市+区县 + detailed_address
                String fullAddress = buildFullAddress(areaName, rawAddress, regionPathCache);

                try {
                    // Step A: 检查 sys_member 中是否已有该手机号
                    Long existingId = null;
                    try {
                        existingId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE phone = ? LIMIT 1",
                            Long.class, phone);
                    } catch (Exception ignored) {}

                    Long memberDbId;
                    if (existingId != null) {
                        // 已有会员：更新画像 + 回写 platform_member_id 到所有同号燃气用户
                        memberDbId = existingId;
                        jdbcTemplate.update(
                            "UPDATE ai_platform.sys_member SET gender=COALESCE(?,gender), " +
                            "birthday=COALESCE(?,birthday), address=COALESCE(NULLIF(?,''),address), " +
                            "community_id=COALESCE(?,community_id), corp_id=COALESCE(?,corp_id), " +
                            "real_name=COALESCE(NULLIF(?,''),real_name) WHERE id=?",
                            genderInt, birthDate, fullAddress, communityId, rowCorpId, name, memberDbId);
                        skipped++;
                    } else {
                        // 新建会员
                        String memberId = "M" + memberIdSuffix;
                        jdbcTemplate.update(
                            "INSERT INTO ai_platform.sys_member " +
                            "(member_id, phone, nickname, real_name, status, gender, birthday, address, community_id, corp_id) " +
                            "VALUES (?,?,?,?,1,?,?,?,?,?)",
                            memberId, phone, name, name, genderInt, birthDate, fullAddress, communityId, rowCorpId);
                        memberDbId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE member_id = ?", Long.class, memberId);
                        created++;
                    }

                    // Step B: 回写 platform_member_id 到所有同手机号、尚未转化的燃气用户
                    if (memberDbId != null) {
                        int linked = jdbcTemplate.update(
                            "UPDATE ops_gas_user SET platform_member_id = ? " +
                            "WHERE phone = ? AND platform_member_id IS NULL",
                            memberDbId, phone);
                        if (linked > 1) {
                            skipped += (linked - 1); // 第一条计入 created/skipped，其余计入 skipped
                        }
                    }
                } catch (Exception e) {
                    log.warn("MemberConvert: failed for phone={}: {}", phone, e.getMessage());
                    String err = "手机号 " + phone + ": " + e.getMessage();
                    if (err.length() > 200) err = err.substring(0, 200);
                    errors.add(err);
                    failed++;
                }

                if (seq % 100 == 0) {
                    taskService.updateStatus(taskId, "PROCESSING", "CREATING_MEMBER",
                            String.format("正在创建会员... 已处理 %d/%d (成功 %d 跳过 %d 失败 %d)",
                                    seq, uniquePhones.size(), created, skipped, failed));
                }
            }
        } catch (Exception e) {
            log.error("MemberConvert: taskId={} fatal error", taskId, e);
            return ImportResult.fail("EXCEPTION", List.of("转化异常: " + e.getMessage()), 0);
        }

        return buildResult(updated + profileUpdated, created, skipped, failed, errors, 0);
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

    /** 拼接完整地址：省+市+区县 + 详细地址 */
    private String buildFullAddress(String areaName, String detailedAddress,
                                     Map<String, String> regionPathCache) {
        StringBuilder sb = new StringBuilder();
        // 省市区前缀
        if (areaName != null && !areaName.isBlank()) {
            String prefix = regionPathCache.get(areaName);
            if (prefix != null && !prefix.isBlank()) {
                sb.append(prefix);
            } else {
                sb.append(areaName); // 回退：直接用 area_name
            }
        }
        // 详细地址
        if (detailedAddress != null && !detailedAddress.isBlank()) {
            sb.append(detailedAddress);
        }
        return sb.toString();
    }

    private ImportResult buildResult(int updated, int created, int skipped, int failed,
                                      List<String> errors, int total) {
        int totalProcessed = updated + created + skipped + failed;
        ImportResult r = ImportResult.success(created + updated, totalProcessed);
        r.setExistingCount(skipped);
        r.setFailRows(failed);
        r.setErrors(errors);
        r.setMessage(String.format("匹配更新 %d 人（含画像同步），新建会员 %d 人，跳过（已有会员）%d 人，失败 %d 人",
                updated, created, skipped, failed));
        return r;
    }
}
