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
@RequestMapping("/api/ops/admin/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "20") int pageSize,
                     @RequestParam(required = false) String phone,
                     @RequestParam(required = false) String communityName,
                     @RequestParam(required = false) String gridManagerName,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) String usageStart,
                     @RequestParam(required = false) String usageEnd) {

        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        if (visibleCorpIds != null && visibleCorpIds.isEmpty()) {
            return R.ok(Map.of("records", List.of(), "total", 0));
        }

        // Build filters
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE bd.delete_flag = 0");
        if (visibleCorpIds != null) {
            where.append(" AND bd.corp_id IN (").append(
                    visibleCorpIds.stream().map(String::valueOf).collect(Collectors.joining(","))).append(")");
        }
        if (phone != null && !phone.isBlank()) {
            where.append(" AND bd.agent_phone LIKE ?");
            params.add("%" + phone + "%");
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND bd.category = ?");
            params.add(category);
        }
        if (usageStart != null && !usageStart.isBlank()) {
            where.append(" AND bd.usage_date >= ?");
            params.add(usageStart);
        }
        if (usageEnd != null && !usageEnd.isBlank()) {
            where.append(" AND bd.usage_date <= ?");
            params.add(usageEnd);
        }

        // Community name filter
        String commJoin = "";
        String commCol = "";
        if (communityName != null && !communityName.isBlank()) {
            commJoin = " JOIN ops_gas_user gu ON gu.phone = bd.agent_phone AND gu.delete_flag = 0"
                    + " JOIN ai_platform.sys_community sc ON sc.id = gu.community_id AND sc.delete_flag = 0";
            where.append(" AND sc.community_name LIKE ?");
            params.add("%" + communityName + "%");
        } else {
            commJoin = " LEFT JOIN ops_gas_user gu ON gu.phone = bd.agent_phone AND gu.delete_flag = 0"
                    + " LEFT JOIN ai_platform.sys_community sc ON sc.id = gu.community_id AND sc.delete_flag = 0";
        }
        // Always select community columns
        commCol = ", sc.community_name AS _community_name, gu.community_id AS _community_id";

        // Grid manager filter
        String gmJoin = "";
        if (gridManagerName != null && !gridManagerName.isBlank()) {
            gmJoin = " LEFT JOIN ops_grid_manager_community mgc ON mgc.community_id = gu.community_id"
                    + " JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag = 0"
                    + " AND (mg.end_time IS NULL OR mg.end_time >= CURDATE())";
            where.append(" AND mg.real_name LIKE ?");
            params.add("%" + gridManagerName + "%");
        } else {
            gmJoin = " LEFT JOIN ops_grid_manager_community mgc ON mgc.community_id = gu.community_id"
                    + " LEFT JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag = 0"
                    + " AND (mg.end_time IS NULL OR mg.end_time >= CURDATE())";
        }

        String baseSql = " FROM ops_business_data bd" + commJoin + gmJoin;

        // Count
        String countSql = "SELECT COUNT(*)" + baseSql + where;
        int total;
        try {
            total = jdbcTemplate.query(countSql, ps -> {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            }, rs -> rs.next() ? rs.getInt(1) : 0);
        } catch (Exception e) {
            log.error("Ledger count query failed", e);
            return R.ok(Map.of("records", List.of(), "total", 0));
        }

        // Data
        String dataSql = "SELECT bd.*" + commCol
                + ", mg.real_name AS _grid_manager_name"
                + baseSql + where
                + " ORDER BY bd.id DESC LIMIT " + ((pageNum - 1) * pageSize) + ", " + pageSize;

        List<Map<String, Object>> records = new ArrayList<>();
        try {
            jdbcTemplate.query(dataSql, ps -> {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            }, rs -> {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("agentPhone", rs.getString("agent_phone"));
                    row.put("brand", rs.getString("brand"));
                    row.put("model", rs.getString("model"));
                    row.put("category", rs.getString("category"));
                    row.put("salePrice", rs.getBigDecimal("sale_price"));
                    row.put("commissionAmount", rs.getBigDecimal("commission_amount"));
                    row.put("customer", rs.getString("customer"));
                    row.put("saleDate", rs.getString("sale_date"));

                    // usage_date
                    java.sql.Date ud = rs.getDate("usage_date");
                    row.put("usageDate", ud != null ? ud.toString() : null);

                    // Community name
                    String cn = rs.getString("_community_name");
                    row.put("communityName", cn != null ? cn : "-");

                    // Grid manager name
                    String gmName = rs.getString("_grid_manager_name");
                    row.put("gridManagerName", gmName != null ? gmName : "-");

                    // Member name: cross-db lookup by agent_phone
                    String agentPhone = rs.getString("agent_phone");
                    row.put("memberName", resolveMemberName(agentPhone));

                    records.add(row);
                }
            });
        } catch (Exception e) {
            log.error("Ledger data query failed", e);
        }

        // Batch resolve member names
        Map<String, String> phoneToName = new HashMap<>();
        for (Map<String, Object> row : records) {
            String ap = (String) row.get("agentPhone");
            if (ap != null && !phoneToName.containsKey(ap)) {
                phoneToName.put(ap, resolveMemberName(ap));
            }
            row.put("memberName", phoneToName.getOrDefault(row.get("agentPhone"), (String) row.get("agentPhone")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        return R.ok(result);
    }

    private String resolveMemberName(String phone) {
        if (phone == null || phone.isBlank()) return "-";
        try {
            return jdbcTemplate.query(
                    "SELECT nickname FROM ai_platform.sys_member WHERE phone = ? AND delete_flag = 0 ORDER BY create_time ASC LIMIT 1",
                    ps -> ps.setString(1, phone),
                    rs -> rs.next() ? rs.getString("nickname") : phone);
        } catch (Exception e) {
            return phone;
        }
    }
}
