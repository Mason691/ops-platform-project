package com.aip.ops.imports;

import com.aip.ops.entity.SysImportTask;
import com.aip.ops.service.ImportTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 燃气用户批量转化会员 — 异步处理器。
 * 复用 sys_import_task + ImportTaskConsumer 基础设施，taskType = "member_convert"。
 *
 * 数据范围：创建任务时前端不传 corpId（JWT 决定），Controller 调用 DataScopeService
 * 算出可见 corpId 写入 task.remark，本 Handler 从 remark 读取用作 SQL WHERE 过滤。
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
        int updated = 0, created = 0, skipped = 0, failed = 0;
        List<String> errors = new ArrayList<>();

        // 数据范围：从 remark 读取逗号分隔的 corpId 列表（由 Controller 在提交时写入）
        String corpIdCsv = task.getRemark();
        String scopeClause = "";
        if (corpId != null && corpId != 0 && corpIdCsv != null && !corpIdCsv.isBlank()) {
            scopeClause = " AND gu.corp_id IN (" + corpIdCsv + ")";
        }

        try {
            // ── Phase 1: 匹配已有会员（批量 UPDATE）──
            taskService.updateStatus(taskId, "PROCESSING", "MATCH_EXISTING",
                    "正在按手机号匹配已有会员...");
            String matchSql = "UPDATE ops_gas_user gu " +
                "JOIN (SELECT phone, MIN(id) AS mid FROM ai_platform.sys_member WHERE phone IS NOT NULL GROUP BY phone) sm " +
                "ON gu.phone = sm.phone " +
                "SET gu.platform_member_id = sm.mid " +
                "WHERE gu.platform_member_id IS NULL" + scopeClause;
            updated = jdbcTemplate.update(matchSql);
            log.info("MemberConvert: taskId={} matched {} existing members by phone", taskId, updated);

            // ── Phase 2: 查询未匹配用户 ──
            List<Map<String, Object>> unmatched = new ArrayList<>();
            String querySql = "SELECT gu.id, gu.phone, gu.full_name, gu.corp_id FROM ops_gas_user gu " +
                "WHERE gu.platform_member_id IS NULL AND gu.phone IS NOT NULL " +
                "AND gu.phone REGEXP '^[0-9]{11}$' " + scopeClause.replace("gu.", "gu.") + " LIMIT 5000";
            jdbcTemplate.query(querySql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", rs.getLong("id"));
                r.put("phone", rs.getString("phone"));
                r.put("name", rs.getString("full_name"));
                r.put("corp_id", rs.getLong("corp_id"));
                unmatched.add(r);
            });
            log.info("MemberConvert: taskId={} unmatched users with valid phone: {}", taskId, unmatched.size());

            if (unmatched.isEmpty()) {
                return buildResult(updated, created, skipped, failed, errors, 0);
            }

            // ── Phase 3: 逐条创建会员 ──
            int total = unmatched.size();
            SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss");
            int seq = 0;

            for (Map<String, Object> row : unmatched) {
                String ts = sdf.format(new Date()) + String.format("%02d", (seq % 100));
                seq++;
                String phone = (String) row.get("phone");
                String name = (String) row.getOrDefault("name", "");
                if (name == null || name.isBlank()) name = phone.substring(0, 3) + "****" + phone.substring(7);

                try {
                    // 先查是否已有同手机号的会员（上次批量匹配后的竞争条件 + 历史遗留）
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
                        skipped++;
                    } else {
                        String memberId = "M" + ts;
                        jdbcTemplate.update(
                            "INSERT INTO ai_platform.sys_member (member_id, phone, nickname, status, corp_id) VALUES (?,?,?,1,?)",
                            memberId, phone, name, row.get("corp_id"));
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
                    // 最后尝试回退查询
                    try {
                        Long fbId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE phone = ? LIMIT 1",
                            Long.class, phone);
                        if (fbId != null) {
                            jdbcTemplate.update(
                                "UPDATE ops_gas_user SET platform_member_id = ? WHERE id = ?",
                                fbId, row.get("id"));
                            skipped++;
                            continue;
                        }
                    } catch (Exception ignored2) {}
                    String err = "手机号 " + phone + ": " + e.getMessage();
                    if (err.length() > 200) err = err.substring(0, 200);
                    errors.add(err);
                    failed++;
                }

                // 每 100 条更新一次进度
                if (seq % 100 == 0) {
                    taskService.updateStatus(taskId, "PROCESSING", "CREATING_MEMBER",
                            String.format("正在创建会员... 已处理 %d/%d (成功 %d 跳过 %d 失败 %d)",
                                    seq, total, created, skipped, failed));
                }
            }
        } catch (Exception e) {
            log.error("MemberConvert: taskId={} fatal error", taskId, e);
            return ImportResult.fail("EXCEPTION", List.of("转化异常: " + e.getMessage()), 0);
        }

        return buildResult(updated, created, skipped, failed, errors, 0);
    }

    private ImportResult buildResult(int updated, int created, int skipped, int failed,
                                      List<String> errors, int total) {
        int totalProcessed = updated + created + skipped + failed;
        ImportResult r = ImportResult.success(created + updated, totalProcessed);
        r.setExistingCount(skipped); // 复用 existingCount 字段表示"跳过（已有会员）"
        r.setFailRows(failed);
        r.setErrors(errors);
        r.setMessage(String.format("匹配更新 %d 人，新建会员 %d 人，跳过（已有会员）%d 人，失败 %d 人",
                updated, created, skipped, failed));
        return r;
    }
}
