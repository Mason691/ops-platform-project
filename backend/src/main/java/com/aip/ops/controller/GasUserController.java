package com.aip.ops.controller;

import com.aip.ops.entity.OpsGasUser;
import com.aip.ops.mapper.OpsGasUserMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.sdk.result.R;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/gas-user")
@RequiredArgsConstructor
public class GasUserController {

    private final OpsGasUserMapper mapper;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;

    /** Paginated list with optional community filter and keyword search. */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) Long communityId,
                     @RequestParam(required = false) String communityKeyword,
                     @RequestParam(required = false) String keyword) {
        // Data scope isolation
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();

        LambdaQueryWrapper<OpsGasUser> qw = new LambdaQueryWrapper<>();
        if (visibleCorpIds != null) {
            qw.in(OpsGasUser::getCorpId, visibleCorpIds);
        }
        qw.eq(communityId != null, OpsGasUser::getCommunityId, communityId);
        // Fuzzy match community name: resolve matching IDs from sys_community
        if (communityKeyword != null && !communityKeyword.isBlank()) {
            Set<Long> matchedIds = resolveCommunityIdsByKeyword(communityKeyword);
            if (matchedIds.isEmpty()) {
                qw.eq(OpsGasUser::getCommunityId, -1L); // force empty result
            } else {
                qw.in(OpsGasUser::getCommunityId, matchedIds);
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w
                .like(OpsGasUser::getUserNo, keyword)
                .or().like(OpsGasUser::getUserName, keyword)
                .or().like(OpsGasUser::getPhone, keyword)
                .or().like(OpsGasUser::getAddress, keyword)
                .or().like(OpsGasUser::getArea, keyword));
        }
        qw.orderByAsc(OpsGasUser::getUserNo);
        Page<OpsGasUser> result = mapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // Resolve community names via ai_platform.sys_community (same MySQL, cross-db)
        Map<Long, String> nameMap = resolveCommunityNames(result.getRecords());
        for (OpsGasUser u : result.getRecords()) {
            if (u.getCommunityId() != null) u.setCommunityName(nameMap.get(u.getCommunityId()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        return R.ok(data);
    }

    private Map<Long, String> resolveCommunityNames(List<OpsGasUser> users) {
        Set<Long> ids = new LinkedHashSet<>();
        for (OpsGasUser u : users) if (u.getCommunityId() != null) ids.add(u.getCommunityId());
        if (ids.isEmpty()) return Map.of();

        Map<Long, String> map = new LinkedHashMap<>();
        try {
            String inClause = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            jdbcTemplate.query(
                    "SELECT id, community_name FROM ai_platform.sys_community WHERE id IN (" + inClause + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> map.put(rs.getLong("id"), rs.getString("community_name")));
        } catch (Exception e) {
            log.warn("Failed to resolve community names: {}", e.getMessage());
        }
        return map;
    }

    /** Resolve community IDs whose name matches the keyword (LIKE). */
    private Set<Long> resolveCommunityIdsByKeyword(String keyword) {
        Set<Long> ids = new LinkedHashSet<>();
        try {
            String likeKw = keyword.replace("'", "''");
            jdbcTemplate.query(
                    "SELECT id FROM ai_platform.sys_community WHERE community_name LIKE '%" + likeKw + "%'",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        while (rs.next()) ids.add(rs.getLong("id"));
                    });
        } catch (Exception e) {
            log.warn("Failed to resolve community IDs by keyword: {}", e.getMessage());
        }
        return ids;
    }

    /** Get all community id->name pairs for the community selector dropdown. */
    @GetMapping("/communities")
    public R<?> communities() {
        Map<Long, String> map = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT id, community_name FROM ai_platform.sys_community WHERE delete_flag=0 ORDER BY community_name",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> { while (rs.next()) map.put(rs.getLong("id"), rs.getString("community_name")); });
        } catch (Exception e) {
            log.warn("Failed to load communities: {}", e.getMessage());
        }
        return R.ok(map.entrySet().stream()
                .map(e -> Map.of("id", e.getKey(), "name", e.getValue()))
                .toList());
    }

    /** Bulk convert: match gas users to platform members by phone, auto-create missing members. */
    @PostMapping("/convert-all")
    public R<?> convertAll() {
        int updated = 0, created = 0, failed = 0;
        try {
            // Step 1: match existing sys_member by phone
            updated = jdbcTemplate.update(
                "UPDATE ops_gas_user gu " +
                "JOIN (SELECT phone, MIN(id) AS mid FROM ai_platform.sys_member WHERE phone IS NOT NULL GROUP BY phone) sm " +
                "ON gu.phone = sm.phone " +
                "SET gu.platform_member_id = sm.mid " +
                "WHERE gu.platform_member_id IS NULL");

            // Step 2: for unmatched users, create sys_member records
            List<Map<String, Object>> unmatched = new ArrayList<>();
            jdbcTemplate.query(
                "SELECT gu.id, gu.phone, gu.user_name FROM ops_gas_user gu " +
                "WHERE gu.platform_member_id IS NULL AND gu.phone IS NOT NULL AND gu.phone REGEXP '^[0-9]{11}$' LIMIT 5000",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    while (rs.next()) {
                        Map<String, Object> r = new java.util.LinkedHashMap<>();
                        r.put("id", rs.getLong("id")); r.put("phone", rs.getString("phone"));
                        r.put("name", rs.getString("user_name")); unmatched.add(r);
                    }
                });

            int seq = 0;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss");
            for (Map<String, Object> row : unmatched) {
                String ts2 = sdf.format(new java.util.Date()) + String.format("%02d", (seq % 100));
                seq++;
                try {
                    String memberId = "M" + ts2;
                    String phone = (String) row.get("phone");
                    String name = (String) row.getOrDefault("name", "");
                    if (name == null || name.isBlank()) name = phone.substring(0, 3) + "****" + phone.substring(7);

                    // Check if a member with this phone already exists (from a previous run)
                    Long existingId = null;
                    try {
                        existingId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE phone = ? LIMIT 1",
                            Long.class, phone);
                    } catch (Exception ignored) {}

                    if (existingId != null) {
                        jdbcTemplate.update(
                            "UPDATE ops_gas_user SET platform_member_id = ? WHERE id = ?",
                            existingId, row.get("id"));
                        created++;
                    } else {
                        // Create new sys_member
                        jdbcTemplate.update(
                            "INSERT INTO ai_platform.sys_member (member_id, phone, nickname, status) VALUES (?,?,?,1)",
                            memberId, phone, name);
                        Long memberDbId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE member_id = ?", Long.class, memberId);
                        if (memberDbId != null) {
                            jdbcTemplate.update(
                                "UPDATE ops_gas_user SET platform_member_id = ? WHERE id = ?",
                                memberDbId, row.get("id"));
                            created++;
                        }
                    }
                } catch (Exception e) {
                    // Phone unique collision — try one more time to link by phone
                    try {
                        Long existingId = jdbcTemplate.queryForObject(
                            "SELECT id FROM ai_platform.sys_member WHERE phone = ? LIMIT 1",
                            Long.class, (String) row.get("phone"));
                        if (existingId != null) {
                            jdbcTemplate.update(
                                "UPDATE ops_gas_user SET platform_member_id = ? WHERE id = ?",
                                existingId, row.get("id"));
                            created++;
                            continue;
                        }
                    } catch (Exception ignored2) {}
                    log.warn("Failed to create member for gas_user id={}: {}", row.get("id"), e.getMessage());
                    failed++;
                }
            }
        } catch (Exception e) {
            return R.fail("批量转化失败: " + e.getMessage());
        }
        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ops_gas_user WHERE platform_member_id IS NULL", Integer.class);
        return R.ok(Map.of("updated", updated, "created", created, "failed", failed,
            "unmatched", remaining != null ? remaining : 0));
    }

    /** Single manual conversion: force associate a gas user with a specific platform member. */
    @PostMapping("/{id}/convert")
    public R<?> convertOne(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        Long memberId = body.get("platformMemberId");
        if (memberId == null) return R.fail("platformMemberId 不能为空");
        OpsGasUser gu = mapper.selectById(id);
        if (gu == null) return R.fail("燃气用户不存在");
        gu.setPlatformMemberId(memberId);
        mapper.updateById(gu);
        return R.ok();
    }
}
