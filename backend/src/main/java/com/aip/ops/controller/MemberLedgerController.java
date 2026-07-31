package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
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
                     @RequestParam(required = false) String memberKeyword,
                     @RequestParam(required = false) String communityName,
                     @RequestParam(required = false) String gridManagerName,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) Long activityId,
                     @RequestParam(required = false) String usageDateStart,
                     @RequestParam(required = false) String usageDateEnd,
                     @RequestParam(required = false) String receiptDateStart,
                     @RequestParam(required = false) String receiptDateEnd) {

        Set<Long> visible = dataScopeService.getVisibleCorpIds();

        // Pre-filter by member keyword: resolve matching gas_user IDs
        Set<Long> matchedMemberIds = null;
        if (memberKeyword != null && !memberKeyword.isBlank()) {
            matchedMemberIds = resolveGasUserIdsByKeyword(memberKeyword);
            if (matchedMemberIds.isEmpty()) {
                return R.ok(Map.of("records", List.of(), "total", 0));
            }
        }

        LambdaQueryWrapper<OpsMemberLedger> qw = new LambdaQueryWrapper<>();
        if (visible != null) qw.in(OpsMemberLedger::getCorpId, visible);
        if (category != null && !category.isBlank()) qw.eq(OpsMemberLedger::getCategory, category);
        if (activityId != null) qw.eq(OpsMemberLedger::getActivityId, activityId);
        if (matchedMemberIds != null) qw.in(OpsMemberLedger::getMemberId, matchedMemberIds);
        if (usageDateStart != null && !usageDateStart.isBlank()) qw.ge(OpsMemberLedger::getUsageDate, LocalDate.parse(usageDateStart));
        if (usageDateEnd != null && !usageDateEnd.isBlank()) qw.le(OpsMemberLedger::getUsageDate, LocalDate.parse(usageDateEnd));
        if (receiptDateStart != null && !receiptDateStart.isBlank()) qw.ge(OpsMemberLedger::getReceiptDate, LocalDate.parse(receiptDateStart));
        if (receiptDateEnd != null && !receiptDateEnd.isBlank()) qw.le(OpsMemberLedger::getReceiptDate, LocalDate.parse(receiptDateEnd));
        qw.orderByDesc(OpsMemberLedger::getCreateTime);

        Page<OpsMemberLedger> result = mapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // Resolve display names
        Set<Long> memberIds = new HashSet<>(), communityIds = new HashSet<>(), activityIds = new HashSet<>();
        for (OpsMemberLedger r : result.getRecords()) {
            memberIds.add(r.getMemberId());
            if (r.getCommunityId() != null) communityIds.add(r.getCommunityId());
            if (r.getActivityId() != null) activityIds.add(r.getActivityId());
        }
        Map<Long, String> memberNames = resolveMemberNames(memberIds);
        Map<Long, String> commNames = resolveCommunityNames(communityIds);
        Map<Long, String> gridNames = resolveGridManagerNames(communityIds);
        Map<Long, String> activityNames = resolveActivityNames(activityIds);

        // Apply text filters on resolved names
        List<OpsMemberLedger> filtered = new ArrayList<>();
        for (OpsMemberLedger r : result.getRecords()) {
            String mn = memberNames.getOrDefault(r.getMemberId(), "#" + r.getMemberId());
            String cn = r.getCommunityId() != null ? commNames.getOrDefault(r.getCommunityId(), "-") : "-";
            String gn = r.getCommunityId() != null ? gridNames.getOrDefault(r.getCommunityId(), "-") : "-";

            if (communityName != null && !communityName.isBlank() && !cn.contains(communityName)) continue;
            if (gridManagerName != null && !gridManagerName.isBlank() && !gn.contains(gridManagerName)) continue;

            r.setMemberName(mn);
            r.setCommunityName(cn);
            r.setGridManagerName(gn);
            r.setActivityTitle(r.getActivityId() != null ? activityNames.getOrDefault(r.getActivityId(), "-") : null);
            filtered.add(r);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", filtered);
        data.put("total", result.getTotal()); // use DB total when no post-filter applies
        return R.ok(data);
    }

    @OperationLog(module = "增值业务", action = "新增", targetTable = "ops_member_ledger")
    @PostMapping
    @Transactional
    public R<?> create(@RequestBody OpsMemberLedger rec) {
        rec.setId(null);
        rec.setCorpId(resolveCorpId());
        mapper.insert(rec);
        return R.ok(Map.of("id", rec.getId()));
    }

    @OperationLog(module = "增值业务", action = "修改", targetTable = "ops_member_ledger")
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

    @OperationLog(module = "增值业务", action = "删除", targetTable = "ops_member_ledger")
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

    /** Get product categories from platform dictionary. */
    @GetMapping("/categories")
    public R<?> categories() {
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT item_key AS 'key', item_value AS label FROM ai_platform.sys_dict_item " +
                "WHERE type_id = 9 AND delete_flag = 0 AND item_key != 'category_template' " +
                "ORDER BY CAST(item_key AS UNSIGNED)");
            return R.ok(list);
        } catch (Exception e) { log.warn("categories failed: {}", e.getMessage()); }
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

    private Map<Long, String> resolveMemberNames(Set<Long> gasUserIds) {
        if (gasUserIds.isEmpty()) return Map.of();
        Map<Long, String> map = new LinkedHashMap<>();
        try {
            String in = gasUserIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            // member_id = ops_gas_user.id; resolve via gas_user → sys_member chain
            jdbcTemplate.queryForList(
                "SELECT gu.id, COALESCE(NULLIF(sm.nickname,''), NULLIF(gu.full_name,''), gu.phone) AS name " +
                "FROM ops_gas_user gu LEFT JOIN ai_platform.sys_member sm ON sm.phone = gu.phone AND sm.delete_flag=0 " +
                "WHERE gu.id IN (" + in + ")")
                .forEach(row -> map.put(((Number)row.get("id")).longValue(),
                    row.get("name") != null ? (String)row.get("name") : "#" + row.get("id")));
        } catch (Exception e) { log.warn("memberNames failed: {}", e.getMessage()); }
        return map;
    }

    /** Resolve gas_user IDs by searching sys_member first, then gas_user as fallback. */
    private Set<Long> resolveGasUserIdsByKeyword(String keyword) {
        Set<Long> ids = new LinkedHashSet<>();
        try {
            String k = keyword.replace("'", "''");
            boolean isPhoneLike = k.matches("\\d+");

            // Step 1: Search sys_member (phone/nickname) → match gas_user by phone
            StringBuilder smSql = new StringBuilder(
                "SELECT DISTINCT gu.id FROM ops_gas_user gu " +
                "JOIN ai_platform.sys_member sm ON sm.phone = gu.phone AND sm.delete_flag=0 " +
                "WHERE gu.delete_flag=0 AND (");
            if (isPhoneLike) {
                smSql.append("sm.phone = '").append(k).append("'");
            } else {
                smSql.append("sm.nickname LIKE '%").append(k).append("%'")
                     .append(" OR sm.phone LIKE '%").append(k).append("%'");
            }
            smSql.append(") LIMIT 1000");
            jdbcTemplate.query(smSql.toString(),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> ids.add(rs.getLong("id")));

            // Step 2: Fallback — direct gas_user search
            jdbcTemplate.query(
                "SELECT id FROM ops_gas_user WHERE delete_flag=0 AND " +
                "(user_name LIKE '%" + k + "%' OR phone LIKE '%" + k + "%') LIMIT 500",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> ids.add(rs.getLong("id")));
        } catch (Exception e) { log.warn("gasUser search failed: {}", e.getMessage()); }
        return ids;
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

    private Map<Long, String> resolveActivityNames(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> map = new LinkedHashMap<>();
        try {
            String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.queryForList("SELECT id, title FROM ops_activity WHERE id IN (" + in + ")")
                .forEach(row -> map.put(((Number)row.get("id")).longValue(), (String)row.get("title")));
        } catch (Exception e) { log.warn("activityNames failed: {}", e.getMessage()); }
        return map;
    }
}
