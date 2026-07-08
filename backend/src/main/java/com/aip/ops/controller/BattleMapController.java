package com.aip.ops.controller;

import com.aip.ops.service.DataScopeService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/battle-map")
@RequiredArgsConstructor
public class BattleMapController {

    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    // In-memory cache (30min TTL)
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000;
    private static class CacheEntry { final Object data; final long ts; CacheEntry(Object d) { this.data = d; this.ts = System.currentTimeMillis(); } }

    private String ck(Long corpId, String suffix) { return "ops_dashboard_data:" + (corpId != null ? corpId : 0) + ":" + suffix; }

    @SuppressWarnings("unchecked")
    private <T> T cacheGet(String key) { CacheEntry e = cache.get(key); if (e != null && (System.currentTimeMillis() - e.ts) < CACHE_TTL_MS) return (T) e.data; if (e != null) cache.remove(key); return null; }
    private void cacheSet(String key, Object value) { cache.put(key, new CacheEntry(value)); }

    private void clearCache(Long corpId) {
        String prefix = "ops_dashboard_data:" + (corpId != null ? corpId : 0) + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Run ETL to refresh all summary tables for a given corp, then clear API cache. */
    @PostMapping("/refresh-cache")
    public R<?> refreshCache(@RequestParam(required = false, defaultValue = "10") Long corpId) {
        try {
            // Rebuild community_distribution for this corp
            jdbcTemplate.update("DELETE FROM ops_map_community_distribution WHERE corp_id = ?", corpId);
            jdbcTemplate.update(
                "INSERT INTO ops_map_community_distribution (community_id, community_name, corp_id, longitude, latitude, gas_users, ledger_count, total_sales, activity_count, grid_manager_names) " +
                "SELECT sc.id, sc.community_name, ? AS corp_id, sc.longitude, sc.latitude, " +
                "COALESCE((SELECT COUNT(*) FROM ops_gas_user WHERE community_id=sc.id AND corp_id=?),0), " +
                "COALESCE((SELECT COUNT(*) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND delete_flag=0),0), " +
                "COALESCE((SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND delete_flag=0),0), " +
                "COALESCE((SELECT COUNT(DISTINCT activity_id) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND activity_id IS NOT NULL AND delete_flag=0),0), " +
                "(SELECT GROUP_CONCAT(mg.real_name) FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id=mgc.grid_manager_id AND mg.delete_flag=0 AND (mg.end_time IS NULL OR mg.end_time>=CURDATE()) WHERE mgc.community_id=sc.id) " +
                "FROM ai_platform.sys_community sc JOIN (SELECT DISTINCT community_id FROM ops_gas_user WHERE corp_id=?) gu ON gu.community_id=sc.id WHERE sc.delete_flag=0 AND sc.longitude IS NOT NULL",
                corpId, corpId, corpId, corpId, corpId, corpId);

            // Rebuild org_coverage
            jdbcTemplate.update("DELETE FROM ops_map_org_coverage WHERE corp_id = ?", corpId);
            jdbcTemplate.update(
                "INSERT INTO ops_map_org_coverage (corp_id, total_communities, active_communities, total_activities, coverage_rate) " +
                "SELECT ?, " +
                "(SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE corp_id=?), " +
                "(SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0), " +
                "(SELECT COUNT(*) FROM ops_activity_review WHERE corp_id=?), " +
                "ROUND((SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0) * 100.0 / NULLIF((SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE corp_id=?),0), 1)",
                corpId, corpId, corpId, corpId, corpId, corpId);
        } catch (Exception e) { log.warn("ETL refresh failed: {}", e.getMessage()); }
        clearCache(corpId);
        return R.ok(Map.of("refreshed", true, "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), "corpId", corpId));
    }

    /** Community distribution — from ETL table (fast, pre-computed) */
    @GetMapping("/community-stats")
    public R<?> communityStats(@RequestParam(required = false) Long corpId,
                               @RequestParam(required = false) String category) {
        Long effectiveCorp = corpId != null ? corpId : 10L;
        String cacheK = ck(effectiveCorp, "communities" + (category != null ? ":" + category : ""));

        List<Map<String, Object>> cached = cacheGet(cacheK);
        if (cached != null) return R.ok(Map.of("records", cached, "cached", true));

        try {
            String sql = "SELECT * FROM ops_map_community_distribution WHERE corp_id = ?";
            if (category != null && !category.isBlank()) {
                sql += " AND community_id IN (SELECT DISTINCT community_id FROM ops_member_ledger WHERE corp_id=? AND category=? AND delete_flag=0)";
                // Re-run with params
            }
            List<Map<String, Object>> list;
            if (category != null && !category.isBlank()) {
                list = jdbcTemplate.queryForList("SELECT cd.* FROM ops_map_community_distribution cd WHERE cd.corp_id = ? AND cd.community_id IN (SELECT DISTINCT community_id FROM ops_member_ledger WHERE corp_id = ? AND category = ? AND delete_flag = 0) ORDER BY cd.total_sales DESC", effectiveCorp, effectiveCorp, category);
            } else {
                list = jdbcTemplate.queryForList("SELECT * FROM ops_map_community_distribution WHERE corp_id = ? ORDER BY total_sales DESC", effectiveCorp);
            }
            cacheSet(cacheK, list);
            return R.ok(Map.of("records", list, "cached", false));
        } catch (Exception e) { log.warn("community-stats failed: {}", e.getMessage()); return R.ok(Map.of("records", List.of())); }
    }

    /** Summary from ETL tables (fast) */
    @GetMapping("/summary")
    public R<?> summary(@RequestParam(required = false, defaultValue = "10") Long corpId) {
        String cacheK = ck(corpId, "summary");
        Map<String, Object> cached = cacheGet(cacheK);
        if (cached != null) { cached.put("cached", true); return R.ok(cached); }

        try {
            Map<String, Object> stats = new LinkedHashMap<>();
            // Org name
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT region_name FROM ai_platform.sys_corp WHERE id = ?", corpId);
                if (rows != null && !rows.isEmpty()) {
                    String rn = (String) rows.get(0).get("region_name"); stats.put("regionName", rn);
                    if (rn != null && rn.contains("/")) stats.put("cityName", rn.substring(rn.lastIndexOf('/') + 1));
                }
            } catch (Exception e) {}
            // From org_coverage
            try {
                Map<String, Object> cov = jdbcTemplate.queryForMap("SELECT * FROM ops_map_org_coverage WHERE corp_id = ?", corpId);
                stats.put("totalUsers", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_gas_user WHERE corp_id = ?", Long.class, corpId));
                stats.put("totalCommunities", cov.get("total_communities"));
                stats.put("reviewTotal", cov.get("total_activities"));
                stats.put("reviewApproved", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE corp_id = ? AND review_status = 1", Long.class, corpId));
                stats.put("reviewPending", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE corp_id = ? AND review_status = 0", Long.class, corpId));
                Long rt = ((Number) cov.get("total_activities")).longValue();
                Long ra = (Long) stats.get("reviewApproved");
                stats.put("reviewRate", rt > 0 ? Math.round(100.0 * (ra != null ? ra : 0) / rt) + "%" : "-");
                // Total sales from community_distribution
                stats.put("yearlySales", jdbcTemplate.queryForObject("SELECT COALESCE(SUM(total_sales),0) FROM ops_map_community_distribution WHERE corp_id = ?", java.math.BigDecimal.class, corpId));
            } catch (Exception e) { /* partial data ok */ }
            cacheSet(cacheK, stats); stats.put("cached", false);
            return R.ok(stats);
        } catch (Exception e) { return R.ok(Map.of("cached", false)); }
    }

    /** Community detail — from ETL tables + live join on demand */
    @GetMapping("/community/{id}/detail")
    public R<?> communityDetail(@PathVariable Long id) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("community", jdbcTemplate.queryForMap("SELECT id, community_name, address, longitude, latitude FROM ai_platform.sys_community WHERE id = ?", id));
            detail.put("gasUsers", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_gas_user WHERE community_id = ?", Long.class, id));
            detail.put("gridManagers", jdbcTemplate.queryForList("SELECT mg.real_name, mg.phone FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag = 0 AND (mg.end_time IS NULL OR mg.end_time >= CURDATE()) WHERE mgc.community_id = ?", id));
            detail.put("recentLedger", jdbcTemplate.queryForList("SELECT ml.id, ml.category, ml.brand, ml.model, ml.sales_price, ml.usage_date, ml.agent FROM ops_member_ledger ml WHERE ml.community_id = ? AND ml.delete_flag = 0 ORDER BY ml.create_time DESC LIMIT 10", id));
            detail.put("salesByCategory", jdbcTemplate.queryForList("SELECT ml.category, COUNT(*) as cnt, COALESCE(SUM(ml.sales_price),0) as total FROM ops_member_ledger ml WHERE ml.community_id = ? AND ml.delete_flag = 0 GROUP BY ml.category", id));
            detail.put("activityReviews", jdbcTemplate.queryForList("SELECT ar.id, a.title, ar.review_status, ar.create_time FROM ops_activity_review ar JOIN ops_activity a ON a.id = ar.activity_id WHERE ar.activity_id IN (SELECT DISTINCT activity_id FROM ops_member_ledger WHERE community_id = ? AND activity_id IS NOT NULL AND delete_flag = 0)", id));
            return R.ok(detail);
        } catch (Exception e) { return R.fail("获取详情失败"); }
    }
}
