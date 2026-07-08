package com.aip.ops.controller;

import com.aip.ops.service.DataScopeService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/battle-map")
@RequiredArgsConstructor
public class BattleMapController {

    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    /** Aggregated community stats for the map */
    @GetMapping("/community-stats")
    public R<?> communityStats(@RequestParam(required = false) Long corpId,
                               @RequestParam(required = false) String dateStart,
                               @RequestParam(required = false) String dateEnd,
                               @RequestParam(required = false) String category) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        String corpFilter = "";
        if (visible != null) {
            corpFilter = " AND gu.corp_id IN (" + visible.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
        } else if (corpId != null) {
            corpFilter = " AND gu.corp_id = " + corpId;
        }

        String dateFilter = "";
        if (dateStart != null && dateEnd != null) {
            dateFilter = " AND ml.usage_date BETWEEN '" + dateStart + "' AND '" + dateEnd + "'";
        }

        String catFilter = category != null && !category.isBlank() ? " AND ml.category = '" + category + "'" : "";

        String corpFilter4 = corpFilter.replace("gu.", "gu4.");
        String sql = new StringBuilder()
            .append("SELECT sc.id, sc.community_name, sc.longitude, sc.latitude, sc.address, ")
            .append("(SELECT COUNT(*) FROM ops_gas_user gu2 WHERE gu2.community_id = sc.id").append(corpFilter).append(") AS gas_users, ")
            .append("(SELECT COUNT(*) FROM ops_grid_manager_community mgc ")
            .append("JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag = 0 ")
            .append("AND (mg.end_time IS NULL OR mg.end_time >= CURDATE()) ")
            .append("WHERE mgc.community_id = sc.id) AS grid_manager_count, ")
            .append("(SELECT GROUP_CONCAT(mg.real_name) FROM ops_grid_manager_community mgc ")
            .append("JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag = 0 ")
            .append("AND (mg.end_time IS NULL OR mg.end_time >= CURDATE()) ")
            .append("WHERE mgc.community_id = sc.id) AS grid_manager_names, ")
            .append("COUNT(DISTINCT ml.id) AS ledger_cnt, ")
            .append("COALESCE(SUM(ml.sales_price), 0) AS total_sales, ")
            .append("(SELECT COUNT(*) FROM ops_activity_review ar ")
            .append("JOIN ops_activity a ON a.id = ar.activity_id ")
            .append("JOIN ops_gas_user gu3 ON gu3.community_id = sc.id ")
            .append("WHERE ar.corp_id = gu3.corp_id").append(corpFilter).append(") AS review_cnt, ")
            .append("(SELECT COUNT(*) FROM ops_activity_review ar ")
            .append("WHERE ar.corp_id IN (SELECT DISTINCT gu4.corp_id FROM ops_gas_user gu4 WHERE gu4.community_id = sc.id")
            .append(corpFilter4).append(") AND ar.review_status = 1) AS approved_cnt ")
            .append("FROM ai_platform.sys_community sc ")
            .append("JOIN ops_gas_user gu ON gu.community_id = sc.id ")
            .append("LEFT JOIN ops_member_ledger ml ON ml.community_id = sc.id AND ml.delete_flag = 0").append(dateFilter).append(catFilter).append(" ")
            .append("WHERE sc.delete_flag = 0 AND sc.longitude IS NOT NULL").append(corpFilter).append(" ")
            .append("GROUP BY sc.id ORDER BY total_sales DESC")
            .toString();

        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            return R.ok(list);
        } catch (Exception e) {
            log.warn("BattleMap community-stats failed: {}", e.getMessage());
            return R.ok(List.of());
        }
    }

    /** Summary stats for the selected org */
    @GetMapping("/summary")
    public R<?> summary(@RequestParam(required = false) Long corpId) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        String corpFilter = visible != null ? " AND corp_id IN (" + visible.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")" : "";

        try {
            Map<String, Object> stats = new LinkedHashMap<>();
            // Org region name for map centering
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT region_name as regionName FROM ai_platform.sys_corp WHERE id=" + (corpId != null ? corpId : 4));
                if (rows != null && !rows.isEmpty()) {
                    String rawName = (String) rows.get(0).get("regionName");
                    stats.put("regionName", rawName);
                    if (rawName != null && rawName.contains("/")) {
                        String[] parts = rawName.split("/");
                        String last = parts[parts.length - 1];
                        stats.put("cityName", last.isEmpty() ? rawName : last);
                    } else if (rawName != null) {
                        stats.put("cityName", rawName);
                    }
                }
            } catch (Exception e) { stats.put("regionName", null); }
            // Gas users
            stats.put("totalUsers", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ops_gas_user WHERE 1=1" + corpFilter, Long.class));
            // Communities
            stats.put("totalCommunities", jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT community_id) FROM ops_gas_user WHERE community_id IS NOT NULL" + corpFilter, Long.class));
            // Yearly sales
            stats.put("yearlySales", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(sales_price),0) FROM ops_member_ledger WHERE YEAR(create_time)=YEAR(CURDATE()) AND delete_flag=0" + corpFilter, java.math.BigDecimal.class));
            // Reviews
            Long reviewTotal = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE 1=1" + corpFilter, Long.class);
            Long reviewApproved = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE review_status=1" + corpFilter, Long.class);
            Long reviewPending = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ops_activity_review WHERE review_status=0" + corpFilter, Long.class);
            stats.put("reviewTotal", reviewTotal != null ? reviewTotal : 0);
            stats.put("reviewApproved", reviewApproved != null ? reviewApproved : 0);
            stats.put("reviewPending", reviewPending != null ? reviewPending : 0);
            stats.put("reviewRate", reviewTotal != null && reviewTotal > 0
                ? Math.round(100.0 * (reviewApproved != null ? reviewApproved : 0) / reviewTotal) + "%" : "-");
            return R.ok(stats);
        } catch (Exception e) {
            log.warn("BattleMap summary failed: {}", e.getMessage());
            return R.ok(Map.of("totalUsers", 0, "totalCommunities", 0, "yearlySales", 0, "reviewTotal", 0, "reviewApproved", 0, "reviewPending", 0, "reviewRate", "-"));
        }
    }

    /** Detail for a specific community */
    @GetMapping("/community/{id}/detail")
    public R<?> communityDetail(@PathVariable Long id) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            // Community info
            Map<String, Object> comm = jdbcTemplate.queryForMap(
                "SELECT id, community_name, address, longitude, latitude FROM ai_platform.sys_community WHERE id=" + id);
            detail.put("community", comm);
            // Gas users count for this community
            detail.put("gasUsers", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ops_gas_user WHERE community_id=" + id, Long.class));
            // Grid manager
            detail.put("gridManagers", jdbcTemplate.queryForList(
                "SELECT mg.real_name, mg.phone FROM ops_grid_manager_community mgc JOIN ops_grid_manager mg ON mg.id=mgc.grid_manager_id AND mg.delete_flag=0 AND (mg.end_time IS NULL OR mg.end_time>=CURDATE()) WHERE mgc.community_id=" + id));
            // Recent ledger records
            detail.put("recentLedger", jdbcTemplate.queryForList(
                "SELECT ml.id, ml.category, ml.brand, ml.model, ml.sales_price, ml.usage_date, ml.agent FROM ops_member_ledger ml WHERE ml.community_id=" + id + " AND ml.delete_flag=0 ORDER BY ml.create_time DESC LIMIT 10"));
            // Sales by category
            detail.put("salesByCategory", jdbcTemplate.queryForList(
                "SELECT ml.category, COUNT(*) as cnt, COALESCE(SUM(ml.sales_price),0) as total FROM ops_member_ledger ml WHERE ml.community_id=" + id + " AND ml.delete_flag=0 GROUP BY ml.category"));
            // Activity reviews
            detail.put("activityReviews", jdbcTemplate.queryForList(
                "SELECT ar.id, a.title, ar.review_status, ar.create_time FROM ops_activity_review ar JOIN ops_activity a ON a.id=ar.activity_id WHERE ar.activity_id IN (SELECT DISTINCT activity_id FROM ops_member_ledger WHERE community_id=" + id + " AND activity_id IS NOT NULL AND delete_flag=0)"));
            return R.ok(detail);
        } catch (Exception e) {
            log.warn("BattleMap community detail failed: {}", e.getMessage());
            return R.fail("获取详情失败");
        }
    }
}
