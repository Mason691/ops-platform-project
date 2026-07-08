package com.aip.ops.controller;

import com.aip.ops.entity.OpsMemberLedger;
import com.aip.ops.mapper.OpsMemberLedgerMapper;
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
@RequestMapping("/api/ops/admin/member-ledger")
@RequiredArgsConstructor
public class MemberLedgerController {

    private final OpsMemberLedgerMapper mapper;
    private final DataScopeService dataScopeService;
    private final JdbcTemplate jdbcTemplate;

    /** Paginated list with filters and resolved names. */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "20") int pageSize,
                     @RequestParam(required = false) String memberPhone,
                     @RequestParam(required = false) String communityName,
                     @RequestParam(required = false) String gridManagerName,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) String usageDateStart,
                     @RequestParam(required = false) String usageDateEnd) {

        Set<Long> visible = dataScopeService.getVisibleCorpIds();

        LambdaQueryWrapper<OpsMemberLedger> qw = new LambdaQueryWrapper<>();
        if (visible != null) qw.in(OpsMemberLedger::getCorpId, visible);
        if (category != null && !category.isBlank()) qw.eq(OpsMemberLedger::getCategory, category);
        if (usageDateStart != null && !usageDateStart.isBlank()) qw.ge(OpsMemberLedger::getUsageDate, LocalDate.parse(usageDateStart));
        if (usageDateEnd != null && !usageDateEnd.isBlank()) qw.le(OpsMemberLedger::getUsageDate, LocalDate.parse(usageDateEnd));
        qw.orderByDesc(OpsMemberLedger::getCreateTime);

        Page<OpsMemberLedger> result = mapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // Resolve display names
        Set<Long> memberIds = new HashSet<>(), communityIds = new HashSet<>();
        for (OpsMemberLedger r : result.getRecords()) {
            memberIds.add(r.getMemberId());
            if (r.getCommunityId() != null) communityIds.add(r.getCommunityId());
        }
        Map<Long, String> memberNames = resolveMemberNames(memberIds, memberPhone);
        Map<Long, String> commNames = resolveCommunityNames(communityIds);
        Map<Long, String> gridNames = resolveGridManagerNames(communityIds);

        // Filter by resolved names and apply name filters
        List<OpsMemberLedger> filtered = new ArrayList<>();
        for (OpsMemberLedger r : result.getRecords()) {
            String mn = memberNames.getOrDefault(r.getMemberId(), "#" + r.getMemberId());
            String cn = r.getCommunityId() != null ? commNames.getOrDefault(r.getCommunityId(), "-") : "-";
            String gn = r.getCommunityId() != null ? gridNames.getOrDefault(r.getCommunityId(), "-") : "-";

            // Apply text filters
            if (memberPhone != null && !memberPhone.isBlank() && !mn.contains(memberPhone)) continue;
            if (communityName != null && !communityName.isBlank() && !cn.contains(communityName)) continue;
            if (gridManagerName != null && !gridManagerName.isBlank() && !gn.contains(gridManagerName)) continue;

            r.setMemberName(mn);
            r.setCommunityName(cn);
            r.setGridManagerName(gn);
            filtered.add(r);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", filtered);
        data.put("total", filtered.size());
        return R.ok(data);
    }

    @PostMapping
    @Transactional
    public R<?> create(@RequestBody OpsMemberLedger rec) {
        rec.setId(null);
        rec.setCorpId(resolveCorpId());
        mapper.insert(rec);
        return R.ok(Map.of("id", rec.getId()));
    }

    @PutMapping("/{id}")
    @Transactional
    public R<?> update(@PathVariable Long id, @RequestBody OpsMemberLedger rec) {
        OpsMemberLedger existing = mapper.selectById(id);
        if (existing == null) return R.fail("记录不存在");
        rec.setId(id);
        rec.setCorpId(existing.getCorpId());
        mapper.updateById(rec);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Transactional
    public R<?> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return R.ok();
    }

    /** Search members from ai_platform.sys_member for dropdown. */
    @GetMapping("/members")
    public R<?> members(@RequestParam(required = false) String keyword) {
        try {
            String sql = "SELECT id, phone, nickname FROM ai_platform.sys_member WHERE status=1 AND delete_flag=0";
            if (keyword != null && !keyword.isBlank()) {
                String k = keyword.replace("'", "''");
                sql += " AND (phone LIKE '%" + k + "%' OR nickname LIKE '%" + k + "%')";
            }
            sql += " ORDER BY nickname, phone LIMIT 50";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            return R.ok(list);
        } catch (Exception e) { log.warn("members search failed: {}", e.getMessage()); }
        return R.ok(List.of());
    }

    @GetMapping("/communities")
    public R<?> communities() {
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT id, community_name AS name FROM ai_platform.sys_community WHERE delete_flag=0 ORDER BY community_name");
            return R.ok(list);
        } catch (Exception e) { log.warn("communities failed: {}", e.getMessage()); }
        return R.ok(List.of());
    }

    // ── Private helpers ──

    private Long resolveCorpId() {
        Set<Long> ids = dataScopeService.getVisibleCorpIds();
        if (ids == null || ids.isEmpty()) return 4L;
        return ids.iterator().next();
    }

    private Map<Long, String> resolveMemberNames(Set<Long> ids, String phoneFilter) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> map = new LinkedHashMap<>();
        try {
            String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.queryForList(
                "SELECT id, COALESCE(NULLIF(nickname,''), phone) AS name FROM ai_platform.sys_member WHERE id IN (" + in + ")")
                .forEach(row -> map.put(((Number)row.get("id")).longValue(), (String)row.get("name")));
        } catch (Exception e) { log.warn("memberNames failed: {}", e.getMessage()); }
        return map;
    }

    private Map<Long, String> resolveCommunityNames(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> map = new LinkedHashMap<>();
        try {
            String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.queryForList(
                "SELECT id, community_name FROM ai_platform.sys_community WHERE id IN (" + in + ")")
                .forEach(row -> map.put(((Number)row.get("id")).longValue(), (String)row.get("community_name")));
        } catch (Exception e) { log.warn("communityNames failed: {}", e.getMessage()); }
        return map;
    }

    private Map<Long, String> resolveGridManagerNames(Set<Long> communityIds) {
        if (communityIds.isEmpty()) return Map.of();
        Map<Long, String> map = new LinkedHashMap<>();
        try {
            String in = communityIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.queryForList(
                "SELECT mgc.community_id, mg.real_name FROM ops_grid_manager_community mgc " +
                "JOIN ops_grid_manager mg ON mg.id = mgc.grid_manager_id AND mg.delete_flag=0 " +
                "AND (mg.end_time IS NULL OR mg.end_time >= CURDATE()) " +
                "WHERE mgc.community_id IN (" + in + ")")
                .forEach(row -> map.put(((Number)row.get("community_id")).longValue(), (String)row.get("real_name")));
        } catch (Exception e) { log.warn("gridManagerNames failed: {}", e.getMessage()); }
        return map;
    }
}
