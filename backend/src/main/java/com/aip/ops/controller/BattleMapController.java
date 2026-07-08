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

    // In-memory cache (30min TTL) — key format: ops_dashboard_data:{corpId}:{suffix}
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000;

    private static class CacheEntry { final Object data; final long ts; CacheEntry(Object d) { this.data = d; this.ts = System.currentTimeMillis(); } }

    private String cacheKey(Long corpId, String suffix) { return "ops_dashboard_data:" + (corpId != null ? corpId : 0) + ":" + suffix; }

    @SuppressWarnings("unchecked")
    private <T> T cacheGet(String key) { CacheEntry e = cache.get(key); if (e != null && (System.currentTimeMillis() - e.ts) < CACHE_TTL_MS) return (T) e.data; if (e != null) cache.remove(key); return null; }
    private void cacheSet(String key, Object value) { cache.put(key, new CacheEntry(value)); }
    private void clearCache(Long corpId) {
        String prefix = "ops_dashboard_data:" + (corpId != null ? corpId : 0) + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Manual cache refresh (clears + returns timestamp) */
    @PostMapping("/refresh-cache")
    public R<?> refreshCache(@RequestParam(required = false) Long corpId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (corpId == null && visible != null) corpId = visible.iterator().next();
        if (corpId == null) corpId = 4L;
        clearCache(corpId);
        return R.ok(Map.of("refreshed", true, "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
    }

    /** Aggregated community stats for the map (cached 30min) */
    @GetMapping("/community-stats")
    public R<?> communityStats(@RequestParam(required = false) Long corpId,
                               @RequestParam(required = false) String dateStart,
                               @RequestParam(required = false) String dateEnd,
                               @RequestParam(required = false) String category) {
        String cacheSuffix = "communities" + (category != null ? ":" + category : "");
        String cacheK = cacheKey(corpId, cacheSuffix);

        List<Map<String, Object>> cached = cacheGet(cacheK);
        if (cached != null) {
            return R.ok(Map.of("records", cached, "cached", true, "cacheTime", ""));
        }

        // Query DB
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        String corpFilter = visible != null
            ? " AND gu.corp_id IN (" + visible.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")"
            : (corpId != null ? " AND gu.corp_id = " + corpId : "");

        String dateFilter = (dateStart != null && dateEnd != null)
            ? " AND ml.usage_date BETWEEN '" + dateStart + "' AND '" + dateEnd + "'" : "";
        String catFilter = category != null && !category.isBlank() ? " AND ml.category = '" + category + "'" : "";

        String sql = new StringBuilder()
            .append("SELECT sc.id, sc.community_name, sc.longitude, sc.latitude, sc.address, ")
            .append("(SELECT COUNT(*) FROM ops_gas_user gu2 WHERE gu2.community_id = sc.id").append(corpFilter).append(") AS gas_users, ")
            .append("(SELECT COUNT(*) FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag = 0 AND (mg.end_time IS NULL OR mg.end_time >= CURDATE()) WHERE mgc.community_id = sc.id) AS grid_manager_count, ")
            .append("(SELECT GROUP_CONCAT(mg.real_name) FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag = 0 AND (mg.end_time IS NULL OR mg.end_time >= CURDATE()) WHERE mgc.community_id = sc.id) AS grid_manager_names, ")
            .append("COUNT(DISTINCT ml.id) AS ledger_cnt, COALESCE(SUM(ml.sales_price), 0) AS total_sales, ")
            .append("(SELECT COUNT(*) FROM ops_activity_review ar JOIN ops_activity a ON a.id = ar.activity_id JOIN ops_gas_user gu3 ON gu3.community_id = sc.id WHERE ar.corp_id = gu3.corp_id").append(corpFilter).append(") AS review_cnt, ")
            .append("(SELECT COUNT(*) FROM ops_activity_review ar WHERE ar.corp_id IN (SELECT DISTINCT gu4.corp_id FROM ops_gas_user gu4 WHERE gu4.community_id = sc.id").append(corpFilter.replace("gu.","gu4.")).append(") AND ar.review_status = 1) AS approved_cnt ")
            .append("FROM ai_platform.sys_community sc JOIN ops_gas_user gu ON gu.community_id = sc.id ")
            .append("LEFT JOIN ops_member_ledger ml ON ml.community_id = sc.id AND ml.delete_flag = 0").append(dateFilter).append(catFilter).append(" ")
            .append("WHERE sc.delete_flag = 0 AND sc.longitude IS NOT NULL").append(corpFilter).append(" ")
            .append("GROUP BY sc.id ORDER BY total_sales DESC").toString();

        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            cacheSet(cacheK, list);
            return R.ok(Map.of("records", list, "cached", false));
        } catch (Exception e) { log.warn("community-stats failed: {}", e.getMessage()); return R.ok(Map.of("records", List.of())); }
    }

    /** Summary stats (cached 30min) */
    @GetMapping("/summary")
    public R<?> summary(@RequestParam(required = false) Long corpId) {
        String cacheK = cacheKey(corpId, "summary");
        Map<String, Object> cached = cacheGet(cacheK);
        if (cached != null) { cached.put("cached", true); return R.ok(cached); }

        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        String corpFilter = visible != null
            ? " AND corp_id IN (" + visible.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")" : "";
        try {
            Map<String, Object> stats = new LinkedHashMap<>();
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT region_name as regionName FROM ai_platform.sys_corp WHERE id=" + (corpId != null ? corpId : 4));
                if (rows != null && !rows.isEmpty()) {
                    String rn = (String) rows.get(0).get("regionName"); stats.put("regionName", rn);
                    if (rn != null && rn.contains("/")) { String last = rn.split("/")[rn.split("/").length - 1]; stats.put("cityName", last); }
                }
            } catch (Exception e) {}
            stats.put("totalUsers", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_gas_user WHERE 1=1" + corpFilter, Long.class));
            stats.put("totalCommunities", jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE community_id IS NOT NULL" + corpFilter, Long.class));
            stats.put("yearlySales", jdbcTemplate.queryForObject("SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE YEAR(create_time)=YEAR(CURDATE()) AND delete_flag=0" + corpFilter, java.math.BigDecimal.class));
            Long rt = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE 1=1" + corpFilter, Long.class);
            Long ra = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE review_status=1" + corpFilter, Long.class);
            Long rp = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE review_status=0" + corpFilter, Long.class);
            stats.put("reviewTotal", rt != null ? rt : 0); stats.put("reviewApproved", ra != null ? ra : 0); stats.put("reviewPending", rp != null ? rp : 0);
            stats.put("reviewRate", rt != null && rt > 0 ? Math.round(100.0 * (ra != null ? ra : 0) / rt) + "%" : "-");
            cacheSet(cacheK, stats); stats.put("cached", false);
            return R.ok(stats);
        } catch (Exception e) { log.warn("summary failed: {}", e.getMessage()); return R.ok(Map.of("cached", false)); }
    }

    @GetMapping("/community/{id}/detail")
    public R<?> communityDetail(@PathVariable Long id) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("community", jdbcTemplate.queryForMap("SELECT id, community_name, address, longitude, latitude FROM ai_platform.sys_community WHERE id=" + id));
            detail.put("gasUsers", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_gas_user WHERE community_id=" + id, Long.class));
            detail.put("gridManagers", jdbcTemplate.queryForList("SELECT mg.real_name, mg.phone FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id=mgc.grid_manager_id AND mg.delete_flag=0 AND (mg.end_time IS NULL OR mg.end_time>=CURDATE()) WHERE mgc.community_id=" + id));
            detail.put("recentLedger", jdbcTemplate.queryForList("SELECT ml.id, ml.category, ml.brand, ml.model, ml.sales_price, ml.usage_date, ml.agent FROM ops_member_ledger ml WHERE ml.community_id=" + id + " AND ml.delete_flag=0 ORDER BY ml.create_time DESC LIMIT 10"));
            detail.put("salesByCategory", jdbcTemplate.queryForList("SELECT ml.category, COUNT(*) as cnt, COALESCE(SUM(ml.sales_price),0) as total FROM ops_member_ledger ml WHERE ml.community_id=" + id + " AND ml.delete_flag=0 GROUP BY ml.category"));
            detail.put("activityReviews", jdbcTemplate.queryForList("SELECT ar.id, a.title, ar.review_status, ar.create_time FROM ops_activity_review ar JOIN ops_activity a ON a.id=ar.activity_id WHERE ar.activity_id IN (SELECT DISTINCT activity_id FROM ops_member_ledger WHERE community_id=" + id + " AND activity_id IS NOT NULL AND delete_flag=0)"));
            return R.ok(detail);
        } catch (Exception e) { return R.fail("获取详情失败"); }
    }
}
