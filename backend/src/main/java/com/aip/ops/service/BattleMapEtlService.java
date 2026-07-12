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
 * <b>组织+下级聚合</b>：每个组织的预计算行按「自身 + 所有下级组织」({@code corp_id IN scope}) 聚合，
 * 行主键 {@code org_id} 仍为被计算组织。叶子组织(城市合伙人) scope=自身、行为与单组织一致；
 * 运营商 scope=自身+旗下合伙人，故其看板聚合下级数据。scope 由 {@link DataScopeService} 解析，
 * 平台不可达时回退自身（不越权、不聚合）。
 * 每张表刷新前先按 org_id 物理删除再重写；失败仅记录日志不阻断其余表；完成后清该组织读缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BattleMapEtlService {

    private final JdbcTemplate jdbc;
    private final BattleMapCache cache;
    private final DataScopeService dataScope;

    /** sys_user.phone 可能是逗号分隔的多号码，解析经办人姓名时需同时兼容“整串等于”与“命中其一”。 */
    private static final String USER_MATCH = "(CONCAT(',',phone,',') LIKE CONCAT('%,',?,',%') OR phone=?)";

    @Transactional
    public void refreshOrg(Long corpId) {
        if (corpId == null) return;
        String scope = dataScope.getVisibleCorpIdCsv(corpId); // 自身 + 所有下级 corp_id（内联 IN）
        refreshCommunity(corpId, scope);
        refreshEmployeePerformance(corpId, scope);
        refreshActivity(corpId, scope);
        refreshOrgSummary(corpId, scope);
        refreshGridPerformance(corpId, scope);
        refreshCategorySales(corpId, scope);
        cache.clearOrg(corpId);
        log.info("battle-map ETL refreshed org={} scope=({})", corpId, scope);
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
    private void refreshCommunity(Long corpId, String scope) {
        try {
            jdbc.update("DELETE FROM ops_map_community WHERE org_id=?", corpId);
            jdbc.update(
                "INSERT INTO ops_map_community (org_id,community_id,community_name,address,longitude,latitude," +
                "user_count,activity_count,ledger_count,agent_count,total_sales,grid_manager_names) " +
                "SELECT ?,sc.id,sc.community_name,sc.address,sc.longitude,sc.latitude," +
                "(SELECT COUNT(*) FROM ops_gas_user WHERE community_id=sc.id AND corp_id IN (" + scope + "))," +
                "(SELECT COUNT(DISTINCT activity_id) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id IN (" + scope + ") AND activity_id IS NOT NULL AND delete_flag=0)," +
                "(SELECT COUNT(*) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id IN (" + scope + ") AND delete_flag=0)," +
                "(SELECT COUNT(DISTINCT agent) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id IN (" + scope + ") AND delete_flag=0 AND agent IS NOT NULL AND agent<>'')," +
                "(SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id IN (" + scope + ") AND delete_flag=0)," +
                "(SELECT GROUP_CONCAT(mg.real_name) FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id=mgc.grid_manager_id AND mg.delete_flag=0 AND (mg.end_time IS NULL OR mg.end_time>=CURDATE()) WHERE mgc.community_id=sc.id) " +
                "FROM ai_platform.sys_community sc WHERE sc.delete_flag=0 AND sc.longitude IS NOT NULL AND (" +
                "EXISTS(SELECT 1 FROM ops_gas_user WHERE community_id=sc.id AND corp_id IN (" + scope + ")) OR " +
                "EXISTS(SELECT 1 FROM ops_member_ledger WHERE community_id=sc.id AND corp_id IN (" + scope + ") AND delete_flag=0))",
                corpId);
        } catch (Exception e) { log.warn("ETL community org={} failed: {}", corpId, e.getMessage()); }
    }

    // ── 经办人业绩表（TOP10）────────────────────────────────────────────────────
    private void refreshEmployeePerformance(Long corpId, String scope) {
        try {
            jdbc.update("DELETE FROM ops_map_employee_performance WHERE org_id=?", corpId);
            List<Map<String, Object>> tops = jdbc.queryForList(
                "SELECT ml.agent AS agent_key,COALESCE(SUM(ml.sales_price),0) AS total_sales,COUNT(*) AS sales_count," +
                "COUNT(DISTINCT ml.community_id) AS covered,COUNT(DISTINCT ml.activity_id) AS act " +
                "FROM ops_member_ledger ml WHERE ml.corp_id IN (" + scope + ") AND ml.delete_flag=0 AND ml.agent IS NOT NULL AND ml.agent<>'' " +
                "GROUP BY ml.agent ORDER BY total_sales DESC LIMIT 5");
            int rank = 0;
            for (Map<String, Object> t : tops) {
                rank++;
                insertEmployeeRow(corpId, scope, (String) t.get("agent_key"), t, rank);
            }
        } catch (Exception e) { log.warn("ETL employee org={} failed: {}", corpId, e.getMessage()); }
    }

    private void insertEmployeeRow(Long corpId, String scope, String agentKey, Map<String, Object> t, int rank) {
        String name = agentKey, phone = "";
        // 1) 优先从 sys_user 匹配（agent_key = phone）
        List<Map<String, Object>> u = jdbc.queryForList(
            "SELECT real_name,phone FROM ai_platform.sys_user WHERE " + USER_MATCH + " LIMIT 1", agentKey, agentKey);
        if (!u.isEmpty()) {
            Object rn = u.get(0).get("real_name"), ph = u.get(0).get("phone");
            if (rn != null && !rn.toString().isBlank()) name = rn.toString();
            if (ph != null) phone = ph.toString();
        }
        // 2) 降级：从 ops_business_data 取 agent 姓名（agent_phone 匹配，需处理 collation 差异）
        if (name.equals(agentKey)) {
            try {
                List<Map<String, Object>> bdRows = jdbc.queryForList(
                    "SELECT agent FROM ops_business_data WHERE agent_phone=? AND delete_flag=0 AND agent IS NOT NULL AND agent<>'' LIMIT 1",
                    agentKey);
                if (!bdRows.isEmpty()) {
                    Object agentName = bdRows.get(0).get("agent");
                    if (agentName != null && !agentName.toString().isBlank()) name = agentName.toString();
                }
            } catch (Exception ignored) { /* collation 差异等异常不阻塞主流程 */ }
        }
        // 3) 降级：从 ops_grid_manager 取 real_name（phone 匹配）
        if (name.equals(agentKey)) {
            try {
                List<Map<String, Object>> gmRows = jdbc.queryForList(
                    "SELECT real_name FROM ops_grid_manager WHERE phone=? AND delete_flag=0 AND real_name IS NOT NULL AND real_name<>'' LIMIT 1",
                    agentKey);
                if (!gmRows.isEmpty()) {
                    Object rn = gmRows.get(0).get("real_name");
                    if (rn != null && !rn.toString().isBlank()) name = rn.toString();
                }
            } catch (Exception ignored) { /* 跨表查询异常不阻塞 */ }
        }
        // 解析部门：从当前组织名称获取
        String department = null;
        try {
            List<Map<String, Object>> deptRows = jdbc.queryForList(
                "SELECT short_name FROM ai_platform.sys_corp WHERE id=? LIMIT 1", corpId);
            if (!deptRows.isEmpty()) {
                Object dsn = deptRows.get(0).get("short_name");
                if (dsn != null && !dsn.toString().isBlank()) department = dsn.toString();
            }
        } catch (Exception ignored) { /* 跨库查询失败不影响主流程 */ }
        List<Map<String, Object>> pc = jdbc.queryForList(
            "SELECT sc.community_name cn FROM ops_member_ledger ml JOIN ai_platform.sys_community sc ON sc.id=ml.community_id " +
            "WHERE ml.agent=? AND ml.corp_id IN (" + scope + ") AND ml.delete_flag=0 GROUP BY ml.community_id ORDER BY SUM(ml.sales_price) DESC LIMIT 1",
            agentKey);
        String primaryCommunity = pc.isEmpty() ? null : (String) pc.get(0).get("cn");
        jdbc.update(
            "INSERT INTO ops_map_employee_performance (org_id,agent_key,employee_name,employee_phone,department,primary_community," +
            "activity_count,covered_communities,total_sales,sales_count,rank_order) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            corpId, agentKey, name, phone, department, primaryCommunity,
            t.get("act"), t.get("covered"), t.get("total_sales"), t.get("sales_count"), rank);
    }

    // ── 活动统计表 ─────────────────────────────────────────────────────────────
    private void refreshActivity(Long corpId, String scope) {
        try {
            jdbc.update("DELETE FROM ops_map_activity WHERE org_id=?", corpId);
            jdbc.update(
                "INSERT INTO ops_map_activity (org_id,activity_id,activity_name,activity_type,participant_count," +
                "total_cost,community_count,community_names,sales_amount) " +
                "SELECT ?,a.id,a.title,COALESCE(NULLIF(a.type,''),a.category)," +
                "(SELECT COUNT(*) FROM ops_activity_booking b WHERE b.activity_id=a.id AND b.delete_flag=0)," +
                "(SELECT COALESCE(SUM(c.amount),0) FROM ops_activity_review r JOIN ops_activity_review_cost c ON c.review_id=r.id WHERE r.activity_id=a.id AND r.delete_flag=0)," +
                "(SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE activity_id=a.id AND corp_id IN (" + scope + ") AND delete_flag=0)," +
                "(SELECT GROUP_CONCAT(DISTINCT sc.community_name) FROM ops_member_ledger ml JOIN ai_platform.sys_community sc ON sc.id=ml.community_id WHERE ml.activity_id=a.id AND ml.corp_id IN (" + scope + ") AND ml.delete_flag=0)," +
                "(SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE activity_id=a.id AND corp_id IN (" + scope + ") AND delete_flag=0) " +
                "FROM ops_activity a WHERE a.corp_id IN (" + scope + ") AND a.delete_flag=0",
                corpId);
        } catch (Exception e) { log.warn("ETL activity org={} failed: {}", corpId, e.getMessage()); }
    }

    // ── 组织汇总表 ─────────────────────────────────────────────────────────────
    private void refreshOrgSummary(Long corpId, String scope) {
        try {
            jdbc.update("DELETE FROM ops_map_org_summary WHERE org_id=?", corpId);
            jdbc.update(
                "INSERT INTO ops_map_org_summary (org_id,total_communities,covered_communities,coverage_rate," +
                "total_activities,month_activities,total_sales,total_commission,total_users) SELECT ?," +
                "(SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE corp_id IN (" + scope + "))," +
                "(SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE corp_id IN (" + scope + ") AND delete_flag=0)," +
                "ROUND((SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE corp_id IN (" + scope + ") AND delete_flag=0)*100.0/NULLIF((SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE corp_id IN (" + scope + ")),0),1)," +
                "(SELECT COUNT(*) FROM ops_activity WHERE corp_id IN (" + scope + ") AND delete_flag=0)," +
                "(SELECT COUNT(*) FROM ops_activity WHERE corp_id IN (" + scope + ") AND delete_flag=0 AND create_time>=DATE_FORMAT(CURDATE(),'%Y-%m-01'))," +
                "(SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE corp_id IN (" + scope + ") AND delete_flag=0)," +
                "(SELECT COALESCE(SUM(commission_amount),0) FROM ops_business_data WHERE corp_id IN (" + scope + ") AND delete_flag=0)," +
                "(SELECT COUNT(*) FROM ops_gas_user WHERE corp_id IN (" + scope + "))",
                corpId);
        } catch (Exception e) { log.warn("ETL summary org={} failed: {}", corpId, e.getMessage()); }
    }

    // ── 网格员业绩表（TOP5）─────────────────────────────────────────────────────
    private void refreshGridPerformance(Long corpId, String scope) {
        try {
            jdbc.update("DELETE FROM ops_map_grid_performance WHERE org_id=?", corpId);
            // 从 ops_grid_manager + ops_grid_manager_community 关联台账汇总
            // 网格员业绩 = 其所有服务小区在台账中已计算状态的销售额之和
            List<Map<String, Object>> tops = jdbc.queryForList(
                "SELECT gm.id AS grid_manager_id,gm.real_name,gm.user_id," +
                "COUNT(DISTINCT gmc.community_id) AS community_count," +
                "COALESCE(SUM(ml.sales_price),0) AS total_sales " +
                "FROM ops_grid_manager gm " +
                "JOIN ops_grid_manager_community gmc ON gmc.grid_manager_id=gm.id " +
                "LEFT JOIN ops_member_ledger ml ON ml.community_id=gmc.community_id AND ml.corp_id IN (" + scope + ") AND ml.delete_flag=0 " +
                "WHERE gm.corp_id IN (" + scope + ") AND gm.delete_flag=0 " +
                "AND (gm.end_time IS NULL OR gm.end_time>=CURDATE()) " +
                "GROUP BY gm.id,gm.real_name,gm.user_id " +
                "ORDER BY total_sales DESC LIMIT 5");
            int rank = 0;
            for (Map<String, Object> t : tops) {
                rank++;
                Long gridManagerId = ((Number) t.get("grid_manager_id")).longValue();
                String realName = (String) t.get("real_name");
                // 通过 user_id 查 sys_user 获取姓名（real_name），优先用网格员表已冗余的姓名
                String employeeName = (realName != null && !realName.isBlank()) ? realName : null;
                Long userId = t.get("user_id") != null ? ((Number) t.get("user_id")).longValue() : null;
                if (employeeName == null && userId != null) {
                    try {
                        List<Map<String, Object>> userRows = jdbc.queryForList(
                            "SELECT real_name FROM ai_platform.sys_user WHERE id=? LIMIT 1", userId);
                        if (!userRows.isEmpty()) {
                            Object rn = userRows.get(0).get("real_name");
                            if (rn != null && !rn.toString().isBlank()) employeeName = rn.toString();
                        }
                    } catch (Exception ignored) { /* 跨库查询失败 */ }
                }
                if (employeeName == null) employeeName = "网格员#" + gridManagerId;
                // 部门：取网格员所属组织名称
                String department = null;
                try {
                    List<Map<String, Object>> deptRows = jdbc.queryForList(
                        "SELECT short_name FROM ai_platform.sys_corp WHERE id=? LIMIT 1", corpId);
                    if (!deptRows.isEmpty()) {
                        Object dsn = deptRows.get(0).get("short_name");
                        if (dsn != null && !dsn.toString().isBlank()) department = dsn.toString();
                    }
                } catch (Exception ignored) { /* 跨库查询失败 */ }
                jdbc.update(
                    "INSERT INTO ops_map_grid_performance (org_id,grid_manager_id,employee_name,department," +
                    "total_sales,community_count,rank_order) VALUES (?,?,?,?,?,?,?)",
                    corpId, gridManagerId, employeeName, department,
                    t.get("total_sales"), t.get("community_count"), rank);
            }
        } catch (Exception e) { log.warn("ETL grid performance org={} failed: {}", corpId, e.getMessage()); }
    }

    // ── 品类销售额表（TOP5）─────────────────────────────────────────────────────
    private void refreshCategorySales(Long corpId, String scope) {
        try {
            jdbc.update("DELETE FROM ops_map_category_sales WHERE org_id=?", corpId);
            // 品类中文名优先从数据字典转换，降级用原始 category 值
            jdbc.update(
                "INSERT INTO ops_map_category_sales (org_id,category_key,category_name,total_sales," +
                "sales_count,rank_order) " +
                "SELECT ?,ml.category AS category_key," +
                "COALESCE((SELECT item_value FROM ai_platform.sys_dict_item WHERE type_id=9 AND item_key=ml.category AND delete_flag=0 LIMIT 1),ml.category) AS category_name," +
                "COALESCE(SUM(ml.sales_price),0),COUNT(*)," +
                "ROW_NUMBER() OVER (ORDER BY COALESCE(SUM(ml.sales_price),0) DESC) " +
                "FROM ops_member_ledger ml " +
                "WHERE ml.corp_id IN (" + scope + ") AND ml.delete_flag=0 AND ml.category IS NOT NULL AND ml.category<>'' " +
                "GROUP BY ml.category ORDER BY COALESCE(SUM(ml.sales_price),0) DESC LIMIT 5",
                corpId);
        } catch (Exception e) { log.warn("ETL category sales org={} failed: {}", corpId, e.getMessage()); }
    }
}
