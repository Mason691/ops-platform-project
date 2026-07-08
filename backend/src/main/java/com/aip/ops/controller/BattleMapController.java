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

@Slf4j
@RestController @RequestMapping("/api/ops/admin/battle-map") @RequiredArgsConstructor
public class BattleMapController {
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000;
    private static class CacheEntry { final Object data; final long ts; CacheEntry(Object d) { this.data = d; this.ts = System.currentTimeMillis(); } }
    private String ck(Long id, String s) { return "ops_dashboard:" + (id != null ? id : 0) + ":" + s; }
    @SuppressWarnings("unchecked")
    private <T> T cacheGet(String k) { CacheEntry e = cache.get(k); if (e != null && System.currentTimeMillis() - e.ts < CACHE_TTL_MS) return (T) e.data; if (e != null) cache.remove(k); return null; }
    private void cacheSet(String k, Object v) { cache.put(k, new CacheEntry(v)); }
    private void clearCache(Long id) { String p = "ops_dashboard:" + (id != null ? id : 0) + ":"; cache.keySet().removeIf(k -> k.startsWith(p)); }

    @PostMapping("/refresh-cache")
    public R<?> refreshCache(@RequestParam(defaultValue = "10") Long corpId) {
        try {
            // community
            jdbcTemplate.update("DELETE FROM ops_map_community WHERE org_id=?", corpId);
            jdbcTemplate.update("INSERT INTO ops_map_community (org_id,community_id,community_name,longitude,latitude,user_count,activity_count,ledger_count,total_sales,grid_manager_names) SELECT ?,sc.id,sc.community_name,sc.longitude,sc.latitude,(SELECT COUNT(*) FROM ops_gas_user WHERE community_id=sc.id AND corp_id=?),(SELECT COUNT(DISTINCT activity_id) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND activity_id IS NOT NULL AND delete_flag=0),(SELECT COUNT(*) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND delete_flag=0),(SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE community_id=sc.id AND corp_id=? AND delete_flag=0),(SELECT GROUP_CONCAT(mg.real_name) FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id=mgc.grid_manager_id AND mg.delete_flag=0 AND (mg.end_time IS NULL OR mg.end_time>=CURDATE()) WHERE mgc.community_id=sc.id) FROM ai_platform.sys_community sc JOIN (SELECT DISTINCT community_id FROM ops_gas_user WHERE corp_id=?) gu ON gu.community_id=sc.id WHERE sc.delete_flag=0 AND sc.longitude IS NOT NULL", corpId,corpId,corpId,corpId,corpId,corpId);
            // employee performance
            jdbcTemplate.update("DELETE FROM ops_map_employee_performance WHERE org_id=?", corpId);
            jdbcTemplate.update("INSERT INTO ops_map_employee_performance (org_id,employee_name,employee_phone,activity_count,covered_communities,total_sales,sales_count,rank_order) SELECT ?,COALESCE((SELECT real_name FROM ai_platform.sys_user WHERE CONCAT(',',phone,',') LIKE CONCAT('%,',ml.agent,',%') OR phone=ml.agent LIMIT 1),SUBSTRING_INDEX(ml.agent,',',1)) AS en,COALESCE((SELECT phone FROM ai_platform.sys_user WHERE CONCAT(',',phone,',') LIKE CONCAT('%,',ml.agent,',%') OR phone=ml.agent LIMIT 1),'') AS ep,COUNT(DISTINCT ml.activity_id),COUNT(DISTINCT ml.community_id),COALESCE(SUM(ml.sales_price),0),COUNT(*),0 FROM ops_member_ledger ml WHERE ml.corp_id=? AND ml.delete_flag=0 AND ml.agent IS NOT NULL AND ml.agent!='' GROUP BY ml.agent ORDER BY total_sales DESC LIMIT 10", corpId,corpId);
            jdbcTemplate.update("SET @r=0; UPDATE ops_map_employee_performance SET rank_order=(@r:=@r+1) WHERE org_id=? ORDER BY total_sales DESC", corpId);
            // org summary
            jdbcTemplate.update("DELETE FROM ops_map_org_summary WHERE org_id=?", corpId);
            jdbcTemplate.update("INSERT INTO ops_map_org_summary (org_id,total_communities,covered_communities,coverage_rate,total_activities,month_activities,total_sales,total_users) SELECT ?,(SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE corp_id=?),(SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0),ROUND((SELECT COUNT(DISTINCT community_id) FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0)*100.0/NULLIF((SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE corp_id=?),0),1),(SELECT COUNT(*) FROM ops_activity_review WHERE corp_id=?),(SELECT COUNT(*) FROM ops_activity_review WHERE corp_id=? AND create_time>=DATE_FORMAT(CURDATE(),'%Y-%m-01')),(SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0),(SELECT COUNT(*) FROM ops_gas_user WHERE corp_id=?)", corpId,corpId,corpId,corpId,corpId,corpId,corpId,corpId,corpId);
        } catch (Exception e) { log.warn("ETL failed: {}", e.getMessage()); }
        clearCache(corpId);
        return R.ok(Map.of("refreshed",true,"timestamp",LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
    }

    @GetMapping("/employee-performance")
    public R<?> employeePerformance(@RequestParam(defaultValue="10") Long corpId) {
        try { return R.ok(Map.of("records", jdbcTemplate.queryForList("SELECT * FROM ops_map_employee_performance WHERE org_id=? ORDER BY rank_order", corpId))); }
        catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    @GetMapping("/category-sales")
    public R<?> categorySales(@RequestParam(defaultValue="10") Long corpId) {
        String k = ck(corpId,"cat"); List<Map<String,Object>> c = cacheGet(k); if (c != null) return R.ok(Map.of("records",c,"cached",true));
        try { c = jdbcTemplate.queryForList("SELECT category,COUNT(*) cnt,COALESCE(SUM(sales_price),0) total FROM ops_member_ledger WHERE corp_id=? AND delete_flag=0 AND category IS NOT NULL GROUP BY category ORDER BY total DESC", corpId); cacheSet(k,c); return R.ok(Map.of("records",c,"cached",false)); }
        catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    @GetMapping("/community-stats")
    public R<?> communityStats(@RequestParam(required=false) Long corpId, @RequestParam(required=false) String category) {
        Long oid = corpId != null ? corpId : 10L; String k = ck(oid,"comm"+(category!=null?":"+category:""));
        List<Map<String,Object>> c = cacheGet(k); if (c != null) return R.ok(Map.of("records",c,"cached",true));
        try { c = category != null ? jdbcTemplate.queryForList("SELECT mc.* FROM ops_map_community mc WHERE mc.org_id=? AND mc.community_id IN (SELECT DISTINCT community_id FROM ops_member_ledger WHERE corp_id=? AND category=? AND delete_flag=0) ORDER BY mc.total_sales DESC", oid,oid,category) : jdbcTemplate.queryForList("SELECT * FROM ops_map_community WHERE org_id=? ORDER BY total_sales DESC", oid); cacheSet(k,c); return R.ok(Map.of("records",c,"cached",false)); }
        catch (Exception e) { return R.ok(Map.of("records", List.of())); }
    }

    @GetMapping("/summary")
    public R<?> summary(@RequestParam(defaultValue="10") Long corpId) {
        String k = ck(corpId,"sum"); Map<String,Object> c = cacheGet(k); if (c != null) { c.put("cached",true); return R.ok(c); }
        try {
            Map<String,Object> s = new LinkedHashMap<>();
            try { List<Map<String,Object>> r = jdbcTemplate.queryForList("SELECT region_name rn FROM ai_platform.sys_corp WHERE id=?", corpId); if (!r.isEmpty()) { String rn = (String)r.get(0).get("rn"); s.put("regionName",rn); if (rn!=null&&rn.contains("/")) s.put("cityName", rn.substring(rn.lastIndexOf('/')+1)); } } catch (Exception e) {}
            try { Map<String,Object> os = jdbcTemplate.queryForMap("SELECT * FROM ops_map_org_summary WHERE org_id=?", corpId); s.put("totalUsers", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_gas_user WHERE corp_id=?", Long.class, corpId)); s.putAll(os); } catch (Exception e) {}
            Long rt = ((Number)s.getOrDefault("total_activities",0)).longValue();
            Long ra = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE corp_id=? AND review_status=1", Long.class, corpId);
            s.put("reviewApproved", ra != null ? ra : 0);
            s.put("reviewPending", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE corp_id=? AND review_status=0", Long.class, corpId));
            s.put("reviewRate", rt>0 ? Math.round(100.0*(ra!=null?ra:0)/rt)+"%" : "-");
            cacheSet(k,s); s.put("cached",false); return R.ok(s);
        } catch (Exception e) { return R.ok(Map.of("cached", false)); }
    }

    @GetMapping("/community/{id}/detail")
    public R<?> communityDetail(@PathVariable Long id) {
        try {
            Map<String,Object> d = new LinkedHashMap<>();
            d.put("community", jdbcTemplate.queryForMap("SELECT id,community_name,address,longitude,latitude FROM ai_platform.sys_community WHERE id=?", id));
            d.put("gasUsers", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_gas_user WHERE community_id=?", Long.class, id));
            d.put("gridManagers", jdbcTemplate.queryForList("SELECT mg.real_name,mg.phone FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id=mgc.grid_manager_id AND mg.delete_flag=0 AND (mg.end_time IS NULL OR mg.end_time>=CURDATE()) WHERE mgc.community_id=?", id));
            d.put("recentLedger", jdbcTemplate.queryForList("SELECT id,category,brand,model,sales_price,usage_date,agent FROM ops_member_ledger WHERE community_id=? AND delete_flag=0 ORDER BY create_time DESC LIMIT 10", id));
            d.put("salesByCategory", jdbcTemplate.queryForList("SELECT category,COUNT(*) cnt,COALESCE(SUM(sales_price),0) total FROM ops_member_ledger WHERE community_id=? AND delete_flag=0 GROUP BY category", id));
            d.put("activityReviews", jdbcTemplate.queryForList("SELECT ar.id,a.title,ar.review_status,ar.create_time FROM ops_activity_review ar JOIN ops_activity a ON a.id=ar.activity_id WHERE ar.activity_id IN (SELECT DISTINCT activity_id FROM ops_member_ledger WHERE community_id=? AND activity_id IS NOT NULL AND delete_flag=0)", id));
            return R.ok(d);
        } catch (Exception e) { return R.fail("获取详情失败"); }
    }
}
