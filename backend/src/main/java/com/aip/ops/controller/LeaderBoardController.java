package com.aip.ops.controller;

import com.aip.ops.service.DataScopeService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/ledger")
@RequiredArgsConstructor
public class LeaderBoardController {

    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    /** Paginated ledger with multi-table joins and filters. */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "20") int pageSize,
                     @RequestParam(required = false) String memberPhone,
                     @RequestParam(required = false) String communityName,
                     @RequestParam(required = false) String gridManagerName,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) String usageDateStart,
                     @RequestParam(required = false) String usageDateEnd) {

        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        String corpFilter = "";
        if (visibleCorpIds != null) {
            corpFilter = " AND bd.corp_id IN (" + visibleCorpIds.stream().map(String::valueOf).reduce((a,b)->a+","+b).orElse("") + ")";
        }

        StringBuilder base = new StringBuilder(
            "FROM ops_business_data bd " +
            "LEFT JOIN ops_gas_user gu ON gu.phone = bd.agent_phone AND gu.corp_id = bd.corp_id " +
            "LEFT JOIN ai_platform.sys_community sc ON sc.id = gu.community_id " +
            "LEFT JOIN ai_platform.sys_member sm ON sm.phone = bd.agent_phone " +
            "LEFT JOIN (SELECT mgc.community_id, mg.real_name AS gm_name FROM ops_grid_manager_community mgc " +
            "  JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag = 0 " +
            "  AND (mg.end_time IS NULL OR mg.end_time >= CURDATE())) gm ON gm.community_id = gu.community_id " +
            "WHERE bd.delete_flag = 0" + corpFilter);

        List<Object> params = new ArrayList<>();
        if (memberPhone != null && !memberPhone.isBlank()) {
            base.append(" AND (bd.agent_phone LIKE ? OR sm.phone LIKE ?)");
            String k = "%" + memberPhone + "%"; params.add(k); params.add(k);
        }
        if (communityName != null && !communityName.isBlank()) {
            base.append(" AND sc.community_name LIKE ?");
            params.add("%" + communityName + "%");
        }
        if (gridManagerName != null && !gridManagerName.isBlank()) {
            base.append(" AND gm.gm_name LIKE ?");
            params.add("%" + gridManagerName + "%");
        }
        if (category != null && !category.isBlank()) {
            base.append(" AND bd.category = ?");
            params.add(category);
        }
        if (usageDateStart != null && !usageDateStart.isBlank()) {
            base.append(" AND bd.usage_date >= ?");
            params.add(usageDateStart);
        }
        if (usageDateEnd != null && !usageDateEnd.isBlank()) {
            base.append(" AND bd.usage_date <= ?");
            params.add(usageDateEnd);
        }

        // Count
        String countSql = "SELECT COUNT(*)" + base.toString();
        Long total;
        try {
            total = jdbcTemplate.queryForObject(countSql, params.toArray(), Long.class);
            if (total == null) total = 0L;
        } catch (Exception e) {
            log.warn("Ledger count failed: {}", e.getMessage());
            return R.fail("统计失败");
        }

        // Data
        String dataSql = "SELECT bd.agent_phone, COALESCE(sm.nickname,'') AS member_name, " +
            "COALESCE(sc.community_name,'-') AS community_name, " +
            "COALESCE(gm.gm_name,'-') AS grid_manager_name, " +
            "bd.category, bd.brand, bd.model, bd.sale_price, bd.commission_amount, " +
            "bd.usage_date, bd.sale_date, bd.corp_id" + base.toString() +
            " ORDER BY bd.sale_date DESC LIMIT " + ((pageNum-1)*pageSize) + "," + pageSize;

        List<Map<String, Object>> records = new ArrayList<>();
        try {
            jdbcTemplate.query(dataSql, params.toArray(), rs -> {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("agentPhone", rs.getString("agent_phone"));
                    row.put("memberName", rs.getString("member_name"));
                    row.put("communityName", rs.getString("community_name"));
                    row.put("gridManagerName", rs.getString("grid_manager_name"));
                    row.put("category", rs.getString("category"));
                    row.put("brand", rs.getString("brand"));
                    row.put("model", rs.getString("model"));
                    row.put("salePrice", rs.getBigDecimal("sale_price"));
                    row.put("commissionAmount", rs.getBigDecimal("commission_amount"));
                    row.put("usageDate", rs.getDate("usage_date") != null ? rs.getDate("usage_date").toString() : null);
                    row.put("saleDate", rs.getString("sale_date"));
                    records.add(row);
                }
            });
        } catch (Exception e) {
            log.warn("Ledger query failed: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        return R.ok(result);
    }
}
