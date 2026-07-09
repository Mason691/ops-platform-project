package com.aip.ops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 作战地图 ETL：从原始业务表抽取聚合到 4 张预计算表（org 维度隔离）。
 * <ul>
 *   <li>{@link #refreshOrg(Long)} 单组织全量重算（点击刷新 / 数据变更后）</li>
 *   <li>{@link #refreshAllOrgs()} 全量重算所有活跃组织（定时增量入口）</li>
 * </ul>
 * 每张表刷新前先按 org_id 物理删除再重写；失败仅记录日志不阻断其余表。
 * 完成后清除该组织的读缓存，保证前端下一次拉取到最新数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BattleMapEtlService {

    private final JdbcTemplate jdbc;
    private final BattleMapCache cache;

    /** sys_user.phone 可能是逗号分隔的多号码，解析经办人姓名时需同时兼容“整串等于”与“命中其一”。 */
    private static final String USER_MATCH = "(CONCAT(',',phone,',') LIKE CONCAT('%,',?,',%') OR phone=?)";

    @Transactional
    public void refreshOrg(Long corpId) {
        if (corpId == null) return;
        refreshCommunity(corpId);
        refreshEmployeePerformance(corpId);
        refreshActivity(corpId);
        refreshOrgSummary(corpId);
        cache.clearOrg(corpId);
        log.info("battle-map ETL refreshed org={}", corpId);
    }

    /** 全量：对所有有燃气用户或台账的组织逐一重算。 */
    public void refreshAllOrgs() {
        List<Long> orgs = jdbc.queryForList(
            "SELECT corp_id FROM ops_gas_user WHERE corp_id IS NOT NULL " +
            "UNION SELECT corp_id FROM ops_member_ledger WHERE corp_id IS NOT NULL AND delete_flag=0",
            Long.class);
        for (Long org : orgs) {
            try { refreshOrg(org); } catch (Exception e) { log.warn("ETL org={} failed: {}", org, e.getMessage()); }
        }
    }

    // ── 小区分布表 ─────────────────────────────────────────────────────────────
    private void refreshCommunity(Long corpId) {
        try {
            jdbc.update("DELETE FROM ops_map_community WHERE org_id=?", corpId);
            jdbc.update(
                "INSERT INTO ops_map_community (org_id,community_id,community_name,address,longitude,latitude," +
                "user_count,activity_count,ledger_count,agent_count,total_sales,grid_manager_names) " +
                "SELECT ?,sc.id,sc.community_name,sc.address,sc.longitude,sc.latitude," +
                "(SELECT COUNT(*) FROM ops_gas_user WHERE community_id=sc.id AND corp_id=?)," +
                "(SELECT COUNT(DISTINCT activity_id) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND activity_id IS NOT NULL AND delete_flag=0)," +
                "(SELECT COUNT(*) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND delete_flag=0)," +
                "(SELECT COUNT(DISTINCT agent) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND delete_flag=0 AND agent IS NOT NULL AND agent<>'')," +
                "(SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND delete_flag=0)," +
                "(SELECT GROUP_CONCAT(mg.real_name) FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id=mgc.grid_manager_id AND mg.delete_flag=0 AND (mg.end_time IS NULL OR mg.end_time>=CURDATE()) WHERE mgc.community_id=sc.id) " +
                "FROM ai_platform.sys_community sc WHERE sc.delete_flag=0 AND sc.longitude IS NOT NULL AND (" +
                "EXISTS(SELECT 1 FROM ops_gas_user WHERE community_id=sc.id AND corp_id=?) OR " +
                "EXISTS(SELECT 1 FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND delete_flag=0))",
                corpId, corpId, corpId, corpId, corpId, corpId, corpId, corpId);
        } catch (Exception e) { log.warn("ETL community org={} failed: {}", corpId, e.getMessage()); }
    }

    // ── 经办人业绩表（TOP10）────────────────────────────────────────────────────
    private void refreshEmployeePerformance(Long corpId) {
        try {
            jdbc.update("DELETE FROM ops_map_employee_performance WHERE org_id=?", corpId);
            List<Map<String, Object>> tops = jdbc.queryForList(
                "SELECT ml.agent AS agent_key,COALESCE(SUM(ml.sales_price),0) AS total_sales,COUNT(*) AS sales_count," +
                "COUNT(DISTINCT ml.community_id) AS covered,COUNT(DISTINCT ml.activity_id) AS act " +
                "FROM ops_member_ledger ml WHERE ml.corp_id=? AND ml.delete_flag=0 AND ml.agent IS NOT NULL AND ml.agent<>'' " +
                "GROUP BY ml.agent ORDER BY total_sales DESC LIMIT 10", corpId);
            int rank = 0;
            for (Map<String, Object> t : tops) {
                rank++;
                String agentKey = (String) t.get("agent_key");
                insertEmployeeRow(corpId, agentKey, t, rank);
            }
        } catch (Exception e) { log.warn("ETL employee org={} failed: {}", corpId, e.getMessage()); }
    }

    private void insertEmployeeRow(Long corpId, String agentKey, Map<String, Object> t, int rank) {
        String name = agentKey, phone = "";
        List<Map<String, Object>> u = jdbc.queryForList(
            "SELECT real_name,phone FROM ai_platform.sys_user WHERE " + USER_MATCH + " LIMIT 1", agentKey, agentKey);
        if (!u.isEmpty()) {
            Object rn = u.get(0).get("real_name"), ph = u.get(0).get("phone");
            if (rn != null && !rn.toString().isBlank()) name = rn.toString();
            if (ph != null) phone = ph.toString();
        }
        List<Map<String, Object>> pc = jdbc.queryForList(
            "SELECT sc.community_name cn FROM ops_member_ledger ml JOIN ai_platform.sys_community sc ON sc.id=ml.community_id " +
            "WHERE ml.agent=? AND ml.corp_id=? AND ml.delete_flag=0 GROUP BY ml.community_id ORDER BY SUM(ml.sales_price) DESC LIMIT 1",
            agentKey, corpId);
        String primaryCommunity = pc.isEmpty() ? null : (String) pc.get(0).get("cn");
        jdbc.update(
            "INSERT INTO ops_map_employee_performance (org_id,agent_key,employee_name,employee_phone,primary_community," +
            "activity_count,covered_communities,total_sales,sales_count,rank_order) VALUES (?,?,?,?,?,?,?,?,?,?)",
            corpId, agentKey, name, phone, primaryCommunity,
            t.get("act"), t.get("covered"), t.get("total_sales"), t.get("sales_count"), rank);
    }

    // ── 活动统计表 ─────────────────────────────────────────────────────────────
    private void refreshActivity(Long corpId) {
        try {
            jdbc.update("DELETE FROM ops_map_activity WHERE org_id=?", corpId);
            jdbc.update(
                "INSERT INTO ops_map_activity (org_id,activity_id,activity_name,activity_type,participant_count," +
                "total_cost,community_count,community_names,sales_amount) " +
                "SELECT ?,a.id,a.title,COALESCE(NULLIF(a.type,''),a.category)," +
                "(SELECT COUNT(*) FROM ops_activity_booking b WHERE b.activity_id=a.id AND b.delete_flag=0)," +
                "(SELECT COALESCE(SUM(c.amount),0) FROM ops_activity_review r JOIN ops_activity_review_cost c ON c.review_id=r.id AND c.delete_flag=0 WHERE r.activity_id=a.id AND r.delete_flag=0)," +
                "(SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE activity_id=a.id AND corp_id=? AND delete_flag=0)," +
                "(SELECT GROUP_CONCAT(DISTINCT sc.community_name) FROM ops_member_ledger ml JOIN ai_platform.sys_community sc ON sc.id=ml.community_id WHERE ml.activity_id=a.id AND ml.corp_id=? AND ml.delete_flag=0)," +
                "(SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE activity_id=a.id AND corp_id=? AND delete_flag=0) " +
                "FROM ops_activity a WHERE a.corp_id=? AND a.delete_flag=0",
                corpId, corpId, corpId, corpId, corpId);
        } catch (Exception e) { log.warn("ETL activity org={} failed: {}", corpId, e.getMessage()); }
    }

    // ── 组织汇总表 ─────────────────────────────────────────────────────────────
    private void refreshOrgSummary(Long corpId) {
        try {
            jdbc.update("DELETE FROM ops_map_org_summary WHERE org_id=?", corpId);
            jdbc.update(
                "INSERT INTO ops_map_org_summary (org_id,total_communities,covered_communities,coverage_rate," +
                "total_activities,month_activities,total_sales,total_commission,total_users) SELECT ?," +
                "(SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE corp_id=?)," +
                "(SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0)," +
                "ROUND((SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0)*100.0/NULLIF((SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE corp_id=?),0),1)," +
                "(SELECT COUNT(*) FROM ops_activity WHERE corp_id=? AND delete_flag=0)," +
                "(SELECT COUNT(*) FROM ops_activity WHERE corp_id=? AND delete_flag=0 AND create_time>=DATE_FORMAT(CURDATE(),'%Y-%m-01'))," +
                "(SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0)," +
                "(SELECT COALESCE(SUM(commission_amount),0) FROM ops_business_data WHERE corp_id=? AND delete_flag=0)," +
                "(SELECT COUNT(*) FROM ops_gas_user WHERE corp_id=?)",
                corpId, corpId, corpId, corpId, corpId, corpId, corpId, corpId, corpId, corpId);
        } catch (Exception e) { log.warn("ETL summary org={} failed: {}", corpId, e.getMessage()); }
    }
}
