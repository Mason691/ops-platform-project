package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.service.BattleMapCache;
import com.aip.ops.service.BattleMapEtlService;
import com.aip.ops.service.DataScopeService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    /**
     * 数据聚合范围：选中组织「自身 + 所有下级组织」的 corp_id 内联 IN 列表。
     * 叶子组织（城市合伙人）返回自身；运营商返回自身 + 旗下合伙人。用于直查台账/活动的 corp 过滤。
     */
    private String scope(Long corpId) { return dataScope.getVisibleCorpIdCsv(corpId); }

    // ── ETL 触发 ───────────────────────────────────────────────────────────────
    @OperationLog(module = "增值业务", action = "刷新缓存", targetTable = "ops_map_*")
    @PostMapping("/refresh-cache")
    public R<?> refreshCache(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("refreshed", false));
        etl.refreshOrg(corpId);
        return R.ok(Map.of("refreshed", true, "timestamp", now()));
    }

    /** 全量重算所有活跃组织（初始化 / 手动全量）。 */
    @OperationLog(module = "增值业务", action = "全量刷新", targetTable = "ops_map_*")
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
                "SELECT * FROM ops_map_employee_performance WHERE org_id=? ORDER BY rank_order LIMIT 5", corpId)));
        } catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    /** 网格员业绩 TOP5：按负责小区的台账销售额汇总排名。 */
    @GetMapping("/grid-performance")
    public R<?> gridPerformance(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("records", List.of()));
        try {
            return R.ok(Map.of("records", jdbcTemplate.queryForList(
                "SELECT * FROM ops_map_grid_performance WHERE org_id=? ORDER BY rank_order", corpId)));
        } catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    /** 品类销售额 TOP5：按台账品类分组汇总排名，品类名已从字典转为中文。 */
    @GetMapping("/category-sales")
    public R<?> categorySales(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("records", List.of()));
        try {
            return R.ok(Map.of("records", jdbcTemplate.queryForList(
                "SELECT * FROM ops_map_category_sales WHERE org_id=? ORDER BY rank_order", corpId)));
        } catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    /** 小区业绩 TOP5：取小区分布表中销售额最高的 5 个小区。 */
    @GetMapping("/community-top5")
    public R<?> communityTop5(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("records", List.of()));
        String k = cache.key(corpId, "comm_top5");
        List<Map<String, Object>> c = cache.get(k);
        if (c != null) return R.ok(Map.of("records", c, "cached", true));
        try {
            c = jdbcTemplate.queryForList(
                "SELECT community_id,community_name,total_sales,grid_manager_names " +
                "FROM ops_map_community WHERE org_id=? ORDER BY total_sales DESC LIMIT 5", corpId);
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
            String sc = scope(corpId);
            Map<String, Object> s = new LinkedHashMap<>();
            resolveOrgRegion(corpId, s);
            try {
                Map<String, Object> corp = jdbcTemplate.queryForMap("SELECT short_name, name FROM ai_platform.sys_corp WHERE id=?", corpId);
                Object sn = corp.get("short_name");
                s.put("shortName", (sn != null && !sn.toString().isBlank()) ? sn : corp.get("name"));
            } catch (Exception ignore) { /* 组织不存在时不填 shortName */ }
            try {
                Map<String, Object> os = jdbcTemplate.queryForMap("SELECT * FROM ops_map_org_summary WHERE org_id=?", corpId);
                s.putAll(os);
            } catch (Exception ignore) { /* 尚未 ETL 的组织无汇总行 */ }
            // YTD total: ops_business_data (已计算) + ops_member_ledger (本年)
            try {
                String year = Integer.toString(LocalDate.now().getYear());
                Double bd = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(sale_price),0) FROM ops_business_data " +
                    "WHERE corp_id IN (" + sc + ") AND delete_flag=0 AND calc_status IN ('已计算','经办人已核对') AND sale_date>=?",
                    Double.class, year + "-01-01");
                Double ml = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger " +
                    "WHERE corp_id IN (" + sc + ") AND delete_flag=0 AND usage_date>=?",
                    Double.class, year + "-01-01");
                s.put("ytdAmount", (bd != null ? bd : 0d) + (ml != null ? ml : 0d));
                // 销售金额：ops_business_data（已计算 + 经办人已核对），用于指标卡片
                s.put("salesAmount", bd != null ? bd : 0d);
            } catch (Exception ignore) { s.put("ytdAmount", 0); s.put("salesAmount", 0); }

            // 年度任务计划总金额 (ops_annual_plan, 已审核)
            try {
                String year = Integer.toString(LocalDate.now().getYear());
                Double ap = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(plan_amount),0) FROM ops_annual_plan " +
                    "WHERE org_id IN (" + sc + ") AND delete_flag=0 AND year=? AND status=1",
                    Double.class, year);
                s.put("annualPlanAmount", ap != null ? ap : 0d);
            } catch (Exception ignore) { s.put("annualPlanAmount", 0); }
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
        String sc = scope(corpId);
        try {
            List<Map<String, Object>> comm = jdbcTemplate.queryForList(
                "SELECT * FROM ops_map_community WHERE community_id=? AND org_id=? LIMIT 1", id, corpId);
            d.put("community", comm.isEmpty() ? null : comm.get(0));
            d.put("agents", jdbcTemplate.queryForList(
                "SELECT ml.agent AS agent_key," +
                "COALESCE(" +
                "(SELECT bd.agent FROM ops_business_data bd WHERE bd.agent_phone COLLATE utf8mb4_0900_ai_ci=ml.agent AND bd.delete_flag=0 LIMIT 1)," +
                "(SELECT real_name FROM ai_platform.sys_user su WHERE CONCAT(',',su.phone,',') LIKE CONCAT('%,',ml.agent,',%') OR su.phone=ml.agent LIMIT 1)," +
                "ml.agent" +
                ") AS employee_name," +
                "COUNT(*) AS sales_count,COALESCE(SUM(ml.sales_price),0) AS total_sales " +
                "FROM ops_member_ledger ml WHERE ml.community_id=? AND ml.corp_id IN (" + sc + ") AND ml.delete_flag=0 AND ml.agent IS NOT NULL AND ml.agent<>'' " +
                "GROUP BY ml.agent ORDER BY total_sales DESC", id));
            d.put("recentLedger", jdbcTemplate.queryForList(
                "SELECT id,category,brand,model,usage_date,agent FROM ops_member_ledger " +
                "WHERE community_id=? AND corp_id IN (" + sc + ") AND delete_flag=0 ORDER BY create_time DESC LIMIT 10", id));
            d.put("salesByCategory", jdbcTemplate.queryForList(
                "SELECT category,COUNT(*) cnt,COALESCE(SUM(sales_price),0) total FROM ops_member_ledger " +
                "WHERE community_id=? AND corp_id IN (" + sc + ") AND delete_flag=0 GROUP BY category", id));
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
        String sc = scope(corpId);
        try {
            d.put("communities", jdbcTemplate.queryForList(
                "SELECT sc.community_name,COUNT(*) sales_count,COALESCE(SUM(ml.sales_price),0) total_sales " +
                "FROM ops_member_ledger ml JOIN ai_platform.sys_community sc ON sc.id=ml.community_id " +
                "WHERE ml.agent=? AND ml.corp_id IN (" + sc + ") AND ml.delete_flag=0 GROUP BY ml.community_id ORDER BY total_sales DESC", agentKey));
            d.put("activities", jdbcTemplate.queryForList(
                "SELECT a.title,COUNT(*) sales_count,COALESCE(SUM(ml.sales_price),0) total_sales " +
                "FROM ops_member_ledger ml JOIN ops_activity a ON a.id=ml.activity_id " +
                "WHERE ml.agent=? AND ml.corp_id IN (" + sc + ") AND ml.activity_id IS NOT NULL AND ml.delete_flag=0 GROUP BY ml.activity_id ORDER BY total_sales DESC", agentKey));
            d.put("summary", jdbcTemplate.queryForMap(
                "SELECT COUNT(*) sales_count,COALESCE(SUM(sales_price),0) total_sales,COUNT(DISTINCT community_id) covered " +
                "FROM ops_member_ledger WHERE agent=? AND corp_id IN (" + sc + ") AND delete_flag=0", agentKey));
            return R.ok(d);
        } catch (Exception e) {
            log.warn("employeeDetail agent={} corp={} failed: {}", agentKey, corpId, e.getMessage());
            return R.ok(Map.of("communities", List.of(), "activities", List.of()));
        }
    }

    private String now() { return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); }

    /**
     * 近 6 个月销售趋势：优先 ops_business_data (calc_status='已计算') 按月汇总 sale_price，
     * 月内无数据或为零时从 ops_member_ledger 取 sales_price 补充；仍无数据补 0。
     * 保证连续 6 个桶不缺月。
     */
    @GetMapping("/trend")
    public R<?> trend(@RequestParam(defaultValue = "10") Long corpId) {
        if (!canView(corpId)) return R.ok(Map.of("records", List.of()));
        String k = cache.key(corpId, "trend");
        List<Map<String, Object>> cached = cache.get(k);
        if (cached != null) return R.ok(Map.of("records", cached, "cached", true));
        try {
            DateTimeFormatter ym = DateTimeFormatter.ofPattern("yyyy-MM");
            LocalDate first = LocalDate.now().withDayOfMonth(1).minusMonths(5);
            Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
            for (int i = 0; i < 6; i++) {
                String m = first.plusMonths(i).format(ym);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("month", m); r.put("salesAmount", 0); r.put("source", "none");
                buckets.put(m, r);
            }
            String since = first.format(ym) + "-01";
            String sc = scope(corpId);

            // 1) 主数据源：ops_business_data (已计算 + 经办人已核对)
            jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(sale_date,'%Y-%m') ym, COALESCE(SUM(sale_price),0) total " +
                "FROM ops_business_data WHERE corp_id IN (" + sc + ") AND delete_flag=0 AND calc_status IN ('已计算','经办人已核对') AND sale_date>=? GROUP BY ym", since)
                .forEach(row -> {
                    Map<String, Object> r = buckets.get((String) row.get("ym"));
                    if (r != null) {
                        Object v = row.get("total");
                        double val = v == null ? 0 : ((Number) v).doubleValue();
                        if (val > 0) { r.put("salesAmount", val); r.put("source", "business"); }
                    }
                });

            // 2) 补齐：对 business_data 为 0 的月份，从 ops_member_ledger 取
            buckets.forEach((month, r) -> {
                double current = ((Number) r.get("salesAmount")).doubleValue();
                if (current > 0) return;
                try {
                    Map<String, Object> fallback = jdbcTemplate.queryForMap(
                        "SELECT COALESCE(SUM(sales_price),0) total FROM ops_member_ledger " +
                        "WHERE corp_id IN (" + sc + ") AND delete_flag=0 AND DATE_FORMAT(usage_date,'%Y-%m')=?",
                        month);
                    if (fallback != null && fallback.get("total") != null) {
                        double val = ((Number) fallback.get("total")).doubleValue();
                        if (val > 0) { r.put("salesAmount", val); r.put("source", "ledger"); }
                    }
                } catch (Exception ignored) { /* 无数据则保持 0 */ }
            });

            List<Map<String, Object>> records = new ArrayList<>(buckets.values());
            cache.set(k, records);
            return R.ok(Map.of("records", records, "cached", false));
        } catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    /**
     * 组织业务地区直接取自 ai_platform.sys_corp（region_id / region_name，组织自身声明的业务区域）。
     * region_id 存在时经 sys_region 归一到区县级取高德 adcode（region_code）供地图边界兜底；
     * region_id 为空（如仅填了 region_name 的组织）则只保留 region_name，前端按小区坐标定位。
     */
    private void resolveOrgRegion(Long corpId, Map<String, Object> s) {
        try {
            Map<String, Object> corp = jdbcTemplate.queryForMap(
                "SELECT region_id, region_name FROM ai_platform.sys_corp WHERE id=?", corpId);
            Object rn = corp.get("region_name");
            if (rn != null && !rn.toString().isBlank()) { s.put("regionName", rn.toString()); s.put("cityName", rn.toString()); }
            Object rid = corp.get("region_id");
            if (rid != null) {
                Map<String, Object> region = climbToCounty(((Number) rid).longValue());
                if (region != null) {
                    String code = (String) region.get("region_code");
                    if (code != null && !code.isBlank()) s.put("adcode", code);
                }
            }
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
