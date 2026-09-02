package com.aip.ops.imports;

import com.aip.ops.entity.SysImportTask;
import com.aip.ops.service.ImportTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 安检设备创建会员台账 — 异步处理器，taskType = "member_ledger_stoves"。
 *
 * 数据链路：ops_gas_stoves_info --gas_user_id--> ops_gas_user --platform_member_id-->
 * ai_platform.sys_member（会员身份确认）→ 逐台设备生成一条 ops_member_ledger 明细。
 *
 * 去重：gas_stoves_id（= 设备 id）为去重键，INSERT 时 NOT EXISTS 过滤 +
 * 唯一索引 (gas_stoves_id, delete_flag) 兜底。重复触发任务 0 新增、全跳过。
 *
 * 组织范围：Controller 提交时把可见 corp 集合 CSV 写入 task.remark，
 * 本 Handler 据此过滤 gu.corp_id（数据归属以燃气用户所属组织为准）。
 *
 * 计数口径（单条 INSERT 原子完成，无逐行回滚问题）：
 *   created = 实际插入行数；skipped = 已有台账（去重命中）；
 *   failed  = 未关联燃气用户 / 未转化会员 / 未知燃具种类（附原因文本）。
 */
@Slf4j
@Component
public class StoveLedgerCreateHandler implements ImportHandler {

    /** 燃具种类 → PRODUCT_CATEGORY 字典 itemKey（1灶具→燃气灶 2热水器 3挂壁炉→壁挂炉） */
    private static final String CLASS_IN = "'1','2','3'";

    private final JdbcTemplate jdbcTemplate;
    private final ImportTaskService taskService;

    public StoveLedgerCreateHandler(JdbcTemplate jdbcTemplate, ImportTaskService taskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskService = taskService;
    }

    @Override
    public String getTaskType() {
        return "member_ledger_stoves";
    }

    @Override
    @Transactional
    public ImportResult execute(SysImportTask task) {
        Long taskId = task.getId();
        String scopeClause = buildScopeClause(task);

        try {
            taskService.updateStatus(taskId, "PROCESSING", "ROW_CHECK", "正在统计安检设备数据...");

            int noUser = count("SELECT COUNT(*) FROM ops_gas_stoves_info s " +
                    "LEFT JOIN ops_gas_user gu ON gu.id=s.gas_user_id AND gu.delete_flag=0 " +
                    "WHERE s.delete_flag=0 AND s.gas_user_id IS NULL" + scopeClause);
            int noMember = count("SELECT COUNT(*) FROM ops_gas_stoves_info s " +
                    "LEFT JOIN ops_gas_user gu ON gu.id=s.gas_user_id AND gu.delete_flag=0 " +
                    "WHERE s.delete_flag=0 AND s.gas_user_id IS NOT NULL " +
                    "AND (gu.id IS NULL OR gu.platform_member_id IS NULL)" + scopeClause);
            int badClass = count("SELECT COUNT(*) FROM ops_gas_stoves_info s " +
                    "JOIN ops_gas_user gu ON gu.id=s.gas_user_id AND gu.delete_flag=0 " +
                    "WHERE s.delete_flag=0 AND gu.platform_member_id IS NOT NULL " +
                    "AND (s.gas_stoves_class IS NULL OR s.gas_stoves_class NOT IN (" + CLASS_IN + "))" + scopeClause);
            int eligible = count("SELECT COUNT(*) FROM ops_gas_stoves_info s " +
                    "JOIN ops_gas_user gu ON gu.id=s.gas_user_id AND gu.delete_flag=0 " +
                    "WHERE s.delete_flag=0 AND gu.platform_member_id IS NOT NULL " +
                    "AND s.gas_stoves_class IN (" + CLASS_IN + ")" + scopeClause);

            taskService.updateStatus(taskId, "PROCESSING", "INSERT",
                    String.format("可创建 %d 台，正在创建台账...", eligible));

            int inserted = insertLedgerRows(task, scopeClause);
            int skipped = eligible - inserted;
            int failed = noUser + noMember + badClass;

            log.info("StoveLedgerCreate: taskId={} eligible={} created={} skipped={} failed={}",
                    taskId, eligible, inserted, skipped, failed);

            ImportResult r = ImportResult.success(inserted, eligible + failed);
            r.setExistingCount(skipped);
            r.setFailRows(failed);
            r.setMessage(buildMessage(inserted, skipped, failed, noUser, noMember, badClass));
            return r;
        } catch (Exception e) {
            log.error("StoveLedgerCreate: taskId={} fatal error", taskId, e);
            return ImportResult.fail("INSERT", List.of("创建异常: " + e.getMessage()), 0);
        }
    }

    /** 逐台设备 INSERT 台账明细；NOT EXISTS 跳过已有台账（gas_stoves_id 去重）。 */
    private int insertLedgerRows(SysImportTask task, String scopeClause) {
        String sql = "INSERT INTO ops_member_ledger " +
            "(corp_id, member_id, platform_member_id, gas_stoves_id, community_id, category, " +
            " usage_date, product_name, brand, model, remark, create_user, create_user_name) " +
            "SELECT COALESCE(gu.corp_id, s.corp_id), gu.id, gu.platform_member_id, s.id, gu.community_id, " +
            "  CASE s.gas_stoves_class WHEN '1' THEN '1' WHEN '2' THEN '3' WHEN '3' THEN '4' END, " +
            "  COALESCE(DATE(s.installation_time), DATE(s.buy_time), DATE(s.startup_time)), " +
            "  CASE s.gas_stoves_class WHEN '1' THEN '燃气灶' WHEN '2' THEN '热水器' WHEN '3' THEN '壁挂炉' END, " +
            "  s.gas_stoves_brand, s.gas_stoves_type, " +
            "  CONCAT_WS('；', " +
            "    CASE s.installation_position WHEN '1' THEN '安装位置:厨房' WHEN '2' THEN '安装位置:过道' " +
            "         WHEN '3' THEN '安装位置:阳台' WHEN '4' THEN '安装位置:其他' END, " +
            "    NULLIF(CONCAT('灶眼:', s.focal_eye), '灶眼:'), " +
            "    NULLIF(CONCAT('功率:', s.power), '功率:')), " +
            "  ?, ? " +
            "FROM ops_gas_stoves_info s " +
            "JOIN ops_gas_user gu ON gu.id = s.gas_user_id AND gu.delete_flag = 0 " +
            "WHERE s.delete_flag = 0 AND gu.platform_member_id IS NOT NULL " +
            "AND s.gas_stoves_class IN (" + CLASS_IN + ")" + scopeClause + " " +
            "AND NOT EXISTS (SELECT 1 FROM ops_member_ledger l " +
            "                WHERE l.gas_stoves_id = s.id AND l.delete_flag = 0)";
        return jdbcTemplate.update(sql, task.getUserId(), task.getUserName());
    }

    /**
     * 可见组织过滤（task.remark 为 Controller 写入的 corp CSV；空=全量）。
     * 设备自身 corp 或关联燃气用户 corp 任一可见即算在范围内
     * （未关联用户的设备只有 s.corp_id，corp 匹配异常的设备 s.corp_id=0 但用户 corp 有效）。
     */
    private String buildScopeClause(SysImportTask task) {
        String csv = task.getRemark();
        return (csv == null || csv.isBlank()) ? "" : " AND (s.corp_id IN (" + csv + ") OR gu.corp_id IN (" + csv + "))";
    }

    private int count(String sql) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
        return n != null ? n : 0;
    }

    /** 结构化结果：JSON（前端解析精确计数+原因）| 人类可读摘要。 */
    private String buildMessage(int created, int skipped, int failed,
                                int noUser, int noMember, int badClass) {
        List<String> reasons = new ArrayList<>();
        if (noUser > 0) reasons.add("未关联燃气用户 " + noUser + " 台");
        if (noMember > 0) reasons.add("未转化会员 " + noMember + " 台");
        if (badClass > 0) reasons.add("未知燃具种类 " + badClass + " 台");
        String reasonText = String.join("；", reasons);
        String json = String.format(
                "{\"created\":%d,\"skipped\":%d,\"failed\":%d,\"reasons\":\"%s\"}",
                created, skipped, failed, reasonText);
        return json + "|" + String.format("创建成功 %d 条，跳过（已有台账）%d 条，失败 %d 条%s",
                created, skipped, failed, failed > 0 ? "（" + reasonText + "）" : "");
    }
}
