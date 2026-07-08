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
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) String usageStart,
                     @RequestParam(required = false) String usageEnd) {

        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        if (visibleCorpIds != null && visibleCorpIds.isEmpty()) {
            return R.ok(Map.of("records", List.of(), "total", 0));
        }

        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE delete_flag = 0");
        if (visibleCorpIds != null) {
            where.append(" AND corp_id IN (").append(
                    visibleCorpIds.stream().map(String::valueOf).collect(Collectors.joining(","))).append(")");
        }
        if (phone != null && !phone.isBlank()) {
            where.append(" AND agent_phone LIKE ?");
            params.add("%" + phone + "%");
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = ?");
            params.add(category);
        }
        if (usageStart != null && !usageStart.isBlank()) {
            where.append(" AND usage_date >= ?");
            params.add(usageStart);
        }
        if (usageEnd != null && !usageEnd.isBlank()) {
            where.append(" AND usage_date <= ?");
            params.add(usageEnd);
        }

        String baseSql = " FROM ops_business_data" + where;

        // Count
        int total;
        try {
            total = jdbcTemplate.query("SELECT COUNT(*)" + baseSql, ps -> {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            }, rs -> rs.next() ? rs.getInt(1) : 0);
        } catch (Exception e) {
            log.error("Ledger count query failed", e);
            return R.ok(Map.of("records", List.of(), "total", 0));
        }

        // Data
        String dataSql = "SELECT *" + baseSql
                + " ORDER BY id DESC LIMIT " + ((pageNum - 1) * pageSize) + ", " + pageSize;

        List<Map<String, Object>> records = new ArrayList<>();
        try {
            jdbcTemplate.query(dataSql, ps -> {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            }, rs -> {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("customer", rs.getString("customer"));
                    row.put("brand", rs.getString("brand"));
                    row.put("model", rs.getString("model"));
                    row.put("category", rs.getString("category"));
                    row.put("salePrice", rs.getBigDecimal("sale_price"));
                    row.put("commissionAmount", rs.getBigDecimal("commission_amount"));
                    row.put("agent", rs.getString("agent"));
                    row.put("agentPhone", rs.getString("agent_phone"));
                    row.put("saleDate", rs.getString("sale_date"));
                    java.sql.Date ud = rs.getDate("usage_date");
                    row.put("usageDate", ud != null ? ud.toString() : null);
                    records.add(row);
                }
            });
        } catch (Exception e) {
            log.error("Ledger data query failed", e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        return R.ok(result);
    }
}
