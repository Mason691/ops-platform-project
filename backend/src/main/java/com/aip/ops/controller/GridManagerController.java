package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.entity.OpsGridManager;
import com.aip.ops.entity.OpsGridManagerCommunity;
import com.aip.ops.mapper.OpsGridManagerCommunityMapper;
import com.aip.ops.mapper.OpsGridManagerMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.sdk.result.R;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/grid-manager")
@RequiredArgsConstructor
public class GridManagerController {

    private final OpsGridManagerMapper mapper;
    private final OpsGridManagerCommunityMapper relMapper;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;

    /** Paginated list with keyword search. */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String keyword) {
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();

        LambdaQueryWrapper<OpsGridManager> qw = new LambdaQueryWrapper<>();
        if (visibleCorpIds != null) qw.in(OpsGridManager::getCorpId, visibleCorpIds);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(OpsGridManager::getRealName, keyword)
                    .or().like(OpsGridManager::getPhone, keyword));
        }
        qw.orderByDesc(OpsGridManager::getCreateTime);
        Page<OpsGridManager> result = mapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // Fill community count & names
        for (OpsGridManager gm : result.getRecords()) {
            List<OpsGridManagerCommunity> rels = relMapper.selectList(
                    new LambdaQueryWrapper<OpsGridManagerCommunity>()
                            .eq(OpsGridManagerCommunity::getGridManagerId, gm.getId()));
            gm.setCommunityCount(rels.size());
            if (!rels.isEmpty()) {
                Set<Long> cids = rels.stream().map(OpsGridManagerCommunity::getCommunityId).collect(Collectors.toSet());
                gm.setCommunityNames(resolveCommunityNames(cids));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        return R.ok(data);
    }

    /** Get detail with community IDs. */
    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        OpsGridManager gm = mapper.selectById(id);
        if (gm == null) return R.fail("网格员不存在");
        List<OpsGridManagerCommunity> rels = relMapper.selectList(
                new LambdaQueryWrapper<OpsGridManagerCommunity>()
                        .eq(OpsGridManagerCommunity::getGridManagerId, id));
        gm.setCommunityIds(rels.stream().map(OpsGridManagerCommunity::getCommunityId).toList());
        return R.ok(gm);
    }

    /** Create grid manager with community assignments. */
    @OperationLog(module = "增值业务", action = "新增", targetTable = "ops_grid_manager")
    @PostMapping
    @Transactional
    public R<?> create(@RequestBody OpsGridManager gm) {
        gm.setCorpId(validateAndResolveCorpId());
        gm.setId(null);
        mapper.insert(gm);

        if (gm.getCommunityIds() != null && !gm.getCommunityIds().isEmpty()) {
            validateNoCommunityConflict(gm.getCommunityIds(), gm.getStartTime(), gm.getEndTime(), null, gm.getCorpId());
            saveCommunities(gm.getId(), gm.getCommunityIds());
        }
        return R.ok(Map.of("id", gm.getId()));
    }

    /** Update grid manager with community assignments. */
    @OperationLog(module = "增值业务", action = "修改", targetTable = "ops_grid_manager")
    @PutMapping("/{id}")
    @Transactional
    public R<?> update(@PathVariable Long id, @RequestBody OpsGridManager gm) {
        OpsGridManager existing = mapper.selectById(id);
        if (existing == null) return R.fail("网格员不存在");

        gm.setId(id);
        gm.setCorpId(existing.getCorpId());
        mapper.updateById(gm);

        // Replace community associations
        relMapper.delete(new LambdaQueryWrapper<OpsGridManagerCommunity>()
                .eq(OpsGridManagerCommunity::getGridManagerId, id));

        if (gm.getCommunityIds() != null && !gm.getCommunityIds().isEmpty()) {
            validateNoCommunityConflict(gm.getCommunityIds(), gm.getStartTime(), gm.getEndTime(), id, existing.getCorpId());
            saveCommunities(id, gm.getCommunityIds());
        }
        return R.ok();
    }

    /** Delete grid manager and its community associations. */
    @OperationLog(module = "增值业务", action = "删除", targetTable = "ops_grid_manager")
    @DeleteMapping("/{id}")
    @Transactional
    public R<?> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        relMapper.delete(new LambdaQueryWrapper<OpsGridManagerCommunity>()
                .eq(OpsGridManagerCommunity::getGridManagerId, id));
        return R.ok();
    }

    /** Search employees from ai_platform.sys_user — scoped to visible organizations only. */
    @GetMapping("/employees")
    public R<?> employees(@RequestParam(required = false) String keyword) {
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, real_name, phone, corp_id FROM ai_platform.sys_user WHERE status=1 AND delete_flag=0");
            // Scope to visible orgs (null = super admin = all)
            if (visibleCorpIds != null) {
                sql.append(" AND corp_id IN (");
                sql.append(visibleCorpIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
                sql.append(")");
            }
            if (keyword != null && !keyword.isBlank()) {
                sql.append(" AND (real_name LIKE '%").append(keyword.replace("'", "''"))
                   .append("%' OR phone LIKE '%").append(keyword.replace("'", "''")).append("%')");
            }
            sql.append(" ORDER BY real_name LIMIT 50");
            String finalSql = sql.toString();
            jdbcTemplate.query(finalSql,
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("realName", rs.getString("real_name"));
                        row.put("phone", rs.getString("phone"));
                        row.put("corpId", rs.getLong("corp_id"));
                        list.add(row);
                    });
        } catch (Exception e) {
            log.warn("Failed to search employees: {}", e.getMessage());
        }
        return R.ok(list);
    }

    /**
     * Get communities with occupation status.
     * A community is "occupied" only if already assigned to a grid manager
     * within the current user's visible organizations.
     */
    @GetMapping("/available-communities")
    public R<?> availableCommunities(@RequestParam(required = false) Long excludeManagerId) {
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            // Find community IDs currently occupied by active grid managers in visible orgs
            StringBuilder occupiedSql = new StringBuilder(
                    "SELECT DISTINCT mgc.community_id FROM ops_grid_manager_community mgc " +
                    "JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id " +
                    "WHERE mg.delete_flag = 0 AND (mg.end_time IS NULL OR mg.end_time >= CURDATE())");
            if (excludeManagerId != null) {
                occupiedSql.append(" AND mg.id != ").append(excludeManagerId);
            }
            if (visibleCorpIds != null && !visibleCorpIds.isEmpty()) {
                occupiedSql.append(" AND mg.corp_id IN (")
                    .append(visibleCorpIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .append(")");
            }
            Set<Long> occupied = new HashSet<>();
            jdbcTemplate.query(occupiedSql.toString(),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        while (rs.next()) occupied.add(rs.getLong("community_id"));
                    });

            // Return only communities in visible organizations
            StringBuilder commSql = new StringBuilder(
                "SELECT id, community_name FROM ai_platform.sys_community WHERE delete_flag=0");
            if (visibleCorpIds != null && !visibleCorpIds.isEmpty()) {
                commSql.append(" AND corp_id IN (")
                    .append(visibleCorpIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .append(")");
            }
            commSql.append(" ORDER BY community_name");
            String finalCommSql = commSql.toString();
            jdbcTemplate.query(finalCommSql,
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        while (rs.next()) {
                            long cid = rs.getLong("id");
                            list.add(Map.of("id", cid, "name", rs.getString("community_name"),
                                    "occupied", occupied.contains(cid)));
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to load available communities: {}", e.getMessage());
        }
        return R.ok(list);
    }

    /** Get communities for a specific grid manager. */
    @GetMapping("/{id}/communities")
    public R<?> managerCommunities(@PathVariable Long id) {
        Set<Long> cids = new HashSet<>();
        List<OpsGridManagerCommunity> rels = relMapper.selectList(
                new LambdaQueryWrapper<OpsGridManagerCommunity>()
                        .eq(OpsGridManagerCommunity::getGridManagerId, id));
        cids = rels.stream().map(OpsGridManagerCommunity::getCommunityId).collect(Collectors.toSet());

        List<Map<String, Object>> list = new ArrayList<>();
        if (!cids.isEmpty()) {
            try {
                String inClause = cids.stream().map(String::valueOf).collect(Collectors.joining(","));
                jdbcTemplate.query(
                        "SELECT id, community_name, longitude, latitude, address FROM ai_platform.sys_community WHERE id IN (" + inClause + ") ORDER BY community_name",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                            while (rs.next()) {
                                list.add(new LinkedHashMap<>() {{
                                    put("id", rs.getLong("id"));
                                    put("name", rs.getString("community_name"));
                                    double lon = rs.getDouble("longitude");
                                    if (rs.wasNull()) put("longitude", null);
                                    else put("longitude", lon);
                                    double lat = rs.getDouble("latitude");
                                    if (rs.wasNull()) put("latitude", null);
                                    else put("latitude", lat);
                                    put("address", rs.getString("address"));
                                }});
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to resolve community names: {}", e.getMessage());
            }
        }
        return R.ok(list);
    }

    // ── Private helpers ──

    private Long validateAndResolveCorpId() {
        Set<Long> ids = dataScopeService.getVisibleCorpIds();
        if (ids == null || ids.isEmpty()) return 4L; // super admin default
        return ids.iterator().next();
    }

    private void validateNoCommunityConflict(List<Long> communityIds, LocalDate start, LocalDate end,
                                              Long excludeManagerId, Long corpId) {
        if (start == null) return;
        String endCondition = end != null ? "'" + end + "'" : "'9999-12-31'";
        String excludeCondition = excludeManagerId != null ? " AND mg.id != " + excludeManagerId : "";
        String corpCondition = corpId != null ? " AND mg.corp_id = " + corpId : "";

        for (Long cid : communityIds) {
            try {
                String sql = "SELECT COUNT(*) FROM ops_grid_manager_community mgc " +
                        "JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id " +
                        "WHERE mgc.community_id = " + cid + " AND mg.delete_flag = 0 " +
                        "AND mg.start_time < " + endCondition +
                        " AND (mg.end_time IS NULL OR mg.end_time > '" + start + "')" +
                        excludeCondition + corpCondition;
                Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
                if (count != null && count > 0) {
                    // Resolve community name for error message
                    String name = String.valueOf(cid);
                    try {
                        name = jdbcTemplate.queryForObject(
                                "SELECT community_name FROM ai_platform.sys_community WHERE id = " + cid, String.class);
                    } catch (Exception ignored) {}
                    throw new IllegalArgumentException("小区「" + name + "」在当前时间段已被其他网格员负责");
                }
            } catch (IllegalArgumentException e) { throw e;
            } catch (Exception e) {
                log.warn("Community conflict check failed for communityId={}: {}", cid, e.getMessage());
            }
        }
    }

    private void saveCommunities(Long managerId, List<Long> communityIds) {
        for (Long cid : communityIds) {
            OpsGridManagerCommunity rel = new OpsGridManagerCommunity();
            rel.setGridManagerId(managerId);
            rel.setCommunityId(cid);
            relMapper.insert(rel);
        }
    }

    private String resolveCommunityNames(Set<Long> ids) {
        if (ids.isEmpty()) return "";
        try {
            String inClause = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            List<String> names = new ArrayList<>();
            jdbcTemplate.query(
                    "SELECT community_name FROM ai_platform.sys_community WHERE id IN (" + inClause + ") ORDER BY community_name",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> names.add(rs.getString("community_name")));
            return String.join("、", names);
        } catch (Exception e) {
            return "";
        }
    }
}
