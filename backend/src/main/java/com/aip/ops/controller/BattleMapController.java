package com.aip.ops.controller;

import com.aip.ops.service.BattleMapCache;
import com.aip.ops.service.BattleMapEtlService;
import com.aip.ops.service.DataScopeService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 作战地图看板：只读聚合接口读取 ETL 预计算表（{@code ops_map_*}），
 * ETL 重算与缓存清除委托 {@link BattleMapEtlService} / {@link BattleMapCache}。
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/battle-map")
@RequiredArgsConstructor
public class BattleMapController {

    private final JdbcTemplate jdbcTemplate;
    private final BattleMapEtlService etl;
    private final BattleMapCache cache;
    private final DataScopeService dataScope;

    /**
     * 组织数据主权：仅允许查看当前用户可见范围内的组织数据。超管(null)不限；
     * 非可见组织按“无数据”处理，避免跨组织读取。scope 服务本身已带自我兜底、不抛异常，
     * 极端异常时放行以不阻断看板可用性。
     */
    private boolean canView(Long corpId) {
        try {
            Set<Long> visible = dataScope.getVisibleCorpIds();
            return visible == null || corpId == null || visible.contains(corpId);
        } catch (Exception e) { return true; }
    }

    // ── ETL 触发 ───────────────────────────────────────────────────────────────
    @PostMapping("/refresh-cache")
    public R<?> refreshCache(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("refreshed", false));
        etl.refreshOrg(corpId);
        return R.ok(Map.of("refreshed", true, "timestamp", now()));
    }

    /** 全量重算所有活跃组织（初始化 / 手动全量）。 */
    @PostMapping("/refresh-all")
    public R<?> refreshAll() {
        etl.refreshAllOrgs();
        return R.ok(Map.of("refreshed", true, "timestamp", now()));
    }

    // ── 只读聚合 ───────────────────────────────────────────────────────────────
    @GetMapping("/employee-performance")
    public R<?> employeePerformance(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("records", List.of()));
        try {
            return R.ok(Map.of("records", jdbcTemplate.queryForList(
                "SELECT * FROM ops_map_employee_performance WHERE org_id=? ORDER BY rank_order", corpId)));
        } catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    @GetMapping("/category-sales")
    public R<?> categorySales(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("records", List.of()));
        String k = cache.key(corpId, "cat");
        List<Map<String, Object>> c = cache.get(k);
        if (c != null) return R.ok(Map.of("records", c, "cached", true));
        try {
            c = jdbcTemplate.queryForList("SELECT category,COUNT(*) cnt,COALESCE(SUM(sales_price),0) total " +
                "FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0 AND category IS NOT NULL GROUP BY category ORDER BY total DESC", corpId);
            cache.set(k, c);
            return R.ok(Map.of("records", c, "cached", false));
        } catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    @GetMapping("/community-stats")
    public R<?> communityStats(@RequestParam(required = false) Long corpId) {
        Long oid = corpId != null ? corpId : 10L;
        if (!canView(oid)) return R.ok(Map.of("records", List.of()));
        String k = cache.key(oid, "comm");
        List<Map<String, Object>> c = cache.get(k);
        if (c != null) return R.ok(Map.of("records", c, "cached", true));
        try {
            c = jdbcTemplate.queryForList("SELECT * FROM ops_map_community WHERE org_id=? ORDER BY total_sales DESC", oid);
            cache.set(k, c);
            return R.ok(Map.of("records", c, "cached", false));
        } catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    @GetMapping("/summary")
    public R<?> summary(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("cached", false));
        String k = cache.key(corpId, "sum");
        Map<String, Object> cached = cache.get(k);
        if (cached != null) { Map<String, Object> hit = new LinkedHashMap<>(cached); hit.put("cached", true); return R.ok(hit); }
        try {
            Map<String, Object> s = new LinkedHashMap<>();
            resolveOrgRegion(corpId, s);
            // 标题以组织简称(sys_corp.short_name)为准，而非地区名(region_name 末级=区县"广河县")
            try {
                Map<String, Object> corp = jdbcTemplate.queryForMap("SELECT short_name, name FROM ai_platform.sys_corp WHERE id=?", corpId);
                Object sn = corp.get("short_name");
                s.put("shortName", (sn != null && !sn.toString().isBlank()) ? sn : corp.get("name"));
            } catch (Exception ignore) { /* 组织不存在时不填 shortName */ }
            try {
                Map<String, Object> os = jdbcTemplate.queryForMap("SELECT * FROM ops_map_org_summary WHERE org_id=?", corpId);
                s.putAll(os);
            } catch (Exception ignore) { /* 尚未 ETL 的组织无汇总行 */ }
            Long rt = ((Number) s.getOrDefault("total_activities", 0)).longValue();
            Long ra = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE corp_id=? AND review_status=1", Long.class, corpId);
            s.put("reviewApproved", ra != null ? ra : 0);
            s.put("reviewPending", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE corp_id=? AND review_status=0", Long.class, corpId));
            s.put("reviewRate", rt > 0 ? Math.round(100.0 * (ra != null ? ra : 0) / rt) + "%" : "-");
            cache.set(k, s);
            Map<String, Object> out = new LinkedHashMap<>(s); out.put("cached", false); return R.ok(out);
        } catch (Exception e) { return R.ok(Map.of("cached", false)); }
    }

    /**
     * 小区详情：数据源为 ETL 小区分布表 + 台账明细。id 为真实小区 ID(sys_community.id)。
     * 小区无业务数据时返回空结构（community=null / agents=[]），不再报错。
     */
    @GetMapping("/community/{id}/detail")
    public R<?> communityDetail(@PathVariable Long id, @RequestParam(defaultValue = "10") Long corpId) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (!canView(corpId)) { d.put("community", null); d.put("agents", List.of()); return R.ok(d); }
        try {
            List<Map<String, Object>> comm = jdbcTemplate.queryForList(
                "SELECT * FROM ops_map_community WHERE community_id=? AND org_id=? LIMIT 1", id, corpId);
            d.put("community", comm.isEmpty() ? null : comm.get(0));
            d.put("agents", jdbcTemplate.queryForList(
                "SELECT ml.agent AS agent_key," +
                "COALESCE((SELECT real_name FROM ai_platform.sys_user su WHERE CONCAT(',',su.phone,',') LIKE CONCAT('%,',ml.agent,',%') OR su.phone=ml.agent LIMIT 1),SUBSTRING_INDEX(ml.agent,',',1)) AS employee_name," +
                "COUNT(*) AS sales_count,COALESCE(SUM(ml.sales_price),0) AS total_sales " +
                "FROM ops_member_ledger ml WHERE ml.community_id=? AND ml.corp_id=? AND ml.delete_flag=0 AND ml.agent IS NOT NULL AND ml.agent<>'' " +
                "GROUP BY ml.agent ORDER BY total_sales DESC", id, corpId));
            d.put("recentLedger", jdbcTemplate.queryForList(
                "SELECT id,category,brand,model,sales_price,usage_date,agent FROM ops_member_ledger " +
                "WHERE community_id=? AND corp_id=? AND delete_flag=0 ORDER BY create_time DESC LIMIT 10", id, corpId));
            d.put("salesByCategory", jdbcTemplate.queryForList(
                "SELECT category,COUNT(*) cnt,COALESCE(SUM(sales_price),0) total FROM ops_member_ledger " +
                "WHERE community_id=? AND corp_id=? AND delete_flag=0 GROUP BY category", id, corpId));
            return R.ok(d);
        } catch (Exception e) {
            log.warn("communityDetail id={} corp={} failed: {}", id, corpId, e.getMessage());
            d.putIfAbsent("community", null);
            d.putIfAbsent("agents", List.of());
            return R.ok(d);
        }
    }

    /**
     * 经办人详情：负责小区业绩 + 参与活动业绩，供 TOP10 点击下钻。
     * agentKey 为台账 agent 原始标识（经办人业绩表 agent_key）。
     */
    @GetMapping("/employee/detail")
    public R<?> employeeDetail(@RequestParam(defaultValue = "10") Long corpId, @RequestParam String agentKey) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (!canView(corpId)) { d.put("communities", List.of()); d.put("activities", List.of()); return R.ok(d); }
        try {
            d.put("communities", jdbcTemplate.queryForList(
                "SELECT sc.community_name,COUNT(*) sales_count,COALESCE(SUM(ml.sales_price),0) total_sales " +
                "FROM ops_member_ledger ml JOIN ai_platform.sys_community sc ON sc.id=ml.community_id " +
                "WHERE ml.agent=? AND ml.corp_id=? AND ml.delete_flag=0 GROUP BY ml.community_id ORDER BY total_sales DESC", agentKey, corpId));
            d.put("activities", jdbcTemplate.queryForList(
                "SELECT a.title,COUNT(*) sales_count,COALESCE(SUM(ml.sales_price),0) total_sales " +
                "FROM ops_member_ledger ml JOIN ops_activity a ON a.id=ml.activity_id " +
                "WHERE ml.agent=? AND ml.corp_id=? AND ml.activity_id IS NOT NULL AND ml.delete_flag=0 GROUP BY ml.activity_id ORDER BY total_sales DESC", agentKey, corpId));
            d.put("summary", jdbcTemplate.queryForMap(
                "SELECT COUNT(*) sales_count,COALESCE(SUM(sales_price),0) total_sales,COUNT(DISTINCT community_id) covered " +
                "FROM ops_member_ledger WHERE agent=? AND corp_id=? AND delete_flag=0", agentKey, corpId));
            return R.ok(d);
        } catch (Exception e) {
            log.warn("employeeDetail agent={} corp={} failed: {}", agentKey, corpId, e.getMessage());
            return R.ok(Map.of("communities", List.of(), "activities", List.of()));
        }
    }

    private String now() { return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); }

    /**
     * 作战地图边界以「组织的业务地区」为准。sys_corp 无 region_id 列，故从该组织名下
     * 小区（ai_platform.sys_community.region_id）出现最多的地区推导业务区域，向上归一到
     * 区县级后取高德 adcode（sys_region.region_code）供前端定位边界。无小区时不填 region，
     * 前端降级为全国视图。
     */
    private void resolveOrgRegion(Long corpId, Map<String, Object> s) {
        try {
            List<Map<String, Object>> top = jdbcTemplate.queryForList(
                "SELECT region_id FROM ai_platform.sys_community WHERE corp_id=? AND delete_flag=0 AND region_id IS NOT NULL GROUP BY region_id ORDER BY COUNT(*) DESC LIMIT 1", corpId);
            if (top.isEmpty()) return;
            Long regionId = ((Number) top.get(0).get("region_id")).longValue();
            Map<String, Object> region = climbToCounty(regionId);
            if (region == null) return;
            String name = (String) region.get("region_name");
            String code = (String) region.get("region_code");
            if (name != null) { s.put("regionName", name); s.put("cityName", name); }
            if (code != null && !code.isBlank()) s.put("adcode", code);
        } catch (Exception e) { log.warn("resolveOrgRegion({}) failed: {}", corpId, e.getMessage()); }
    }

    /** 从任意层级地区沿 parent_id 向上归一到区县级（region_level<=3），用于县域边界最大化展示。 */
    private Map<String, Object> climbToCounty(Long regionId) {
        Long id = regionId;
        for (int i = 0; i < 6 && id != null; i++) {
            List<Map<String, Object>> r = jdbcTemplate.queryForList(
                "SELECT id,parent_id,region_name,region_level,region_code FROM ai_platform.sys_region WHERE id=?", id);
            if (r.isEmpty()) return null;
            Map<String, Object> row = r.get(0);
            Object lv = row.get("region_level");
            int level = lv == null ? 3 : ((Number) lv).intValue();
            if (level <= 3) return row;
            Object pid = row.get("parent_id");
            id = pid == null ? null : ((Number) pid).longValue();
        }
        return null;
    }
}
