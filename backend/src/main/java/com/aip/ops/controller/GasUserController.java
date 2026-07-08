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
            jdbcTemplate.query(
                    "SELECT id FROM ai_platform.sys_community WHERE community_name LIKE ?",
                    ps -> ps.setString(1, "%" + keyword + "%"),
                    rs -> { while (rs.next()) ids.add(rs.getLong("id")); });
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
}
