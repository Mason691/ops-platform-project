package com.aip.ops.controller;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.aip.ops.service.DataScopeService;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/activity-review")
@RequiredArgsConstructor
public class ActivityReviewController {

    private final OpsActivityReviewMapper revMapper;
    private final OpsActivityReviewCostMapper costMapper;
    private final OpsMemberLedgerMapper ledgerMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;

    /** Paginated list of reviews. */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String activityTitle,
                     @RequestParam(required = false) Integer reviewStatus,
                     @RequestParam(required = false) String dateStart,
                     @RequestParam(required = false) String dateEnd) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        LambdaQueryWrapper<OpsActivityReview> qw = new LambdaQueryWrapper<>();
        if (visible != null) qw.in(OpsActivityReview::getCorpId, visible);
        if (reviewStatus != null) qw.eq(OpsActivityReview::getReviewStatus, reviewStatus);
        if (dateStart != null && !dateStart.isBlank()) qw.ge(OpsActivityReview::getCreateTime, dateStart + " 00:00:00");
        if (dateEnd != null && !dateEnd.isBlank()) qw.le(OpsActivityReview::getCreateTime, dateEnd + " 23:59:59");
        qw.orderByDesc(OpsActivityReview::getCreateTime);

        Page<OpsActivityReview> result = revMapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // Resolve activity titles + costs
        for (OpsActivityReview r : result.getRecords()) {
            try {
                String title = jdbcTemplate.queryForObject(
                    "SELECT title FROM ops_activity WHERE id = " + r.getActivityId(), String.class);
                r.setActivityTitle(title);
            } catch (Exception e) { r.setActivityTitle("#" + r.getActivityId()); }
            r.setCosts(costMapper.selectList(new LambdaQueryWrapper<OpsActivityReviewCost>()
                    .eq(OpsActivityReviewCost::getReviewId, r.getId())));
        }

        // Title filter (post-query)
        List<OpsActivityReview> filtered = new ArrayList<>(result.getRecords());
        if (activityTitle != null && !activityTitle.isBlank()) {
            filtered = filtered.stream().filter(r -> r.getActivityTitle() != null && r.getActivityTitle().contains(activityTitle)).toList();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", filtered);
        data.put("total", filtered.size());
        return R.ok(data);
    }

    /** Get review detail with costs and sales ledger. */
    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        OpsActivityReview r = revMapper.selectById(id);
        if (r == null) return R.fail("复盘不存在");
        // Load costs
        r.setCosts(costMapper.selectList(new LambdaQueryWrapper<OpsActivityReviewCost>()
                .eq(OpsActivityReviewCost::getReviewId, id)));
        // Load activity title
        try {
            r.setActivityTitle(jdbcTemplate.queryForObject("SELECT title FROM ops_activity WHERE id=" + r.getActivityId(), String.class));
        } catch (Exception e) { r.setActivityTitle("#" + r.getActivityId()); }
        // Load sales ledger for this activity
        List<OpsMemberLedger> sales = ledgerMapper.selectList(new LambdaQueryWrapper<OpsMemberLedger>()
                .eq(OpsMemberLedger::getActivityId, r.getActivityId()).eq(OpsMemberLedger::getDeleteFlag, 0));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review", r);
        result.put("sales", sales);
        return R.ok(result);
    }

    /** Create review (partners only). */
    @PostMapping
    @Transactional
    public R<?> create(@RequestBody OpsActivityReview rev) {
        rev.setId(null);
        rev.setCorpId(currentCorpId());
        rev.setReviewStatus(0);
        revMapper.insert(rev);
        saveCosts(rev.getId(), rev.getCosts());
        return R.ok(Map.of("id", rev.getId()));
    }

    /** Update review (partners, only pending/rejected). */
    @PutMapping("/{id}")
    @Transactional
    public R<?> update(@PathVariable Long id, @RequestBody OpsActivityReview rev) {
        OpsActivityReview existing = revMapper.selectById(id);
        if (existing == null) return R.fail("复盘不存在");
        if (existing.getReviewStatus() != null && existing.getReviewStatus() == 1) return R.fail("已通过审核，不可编辑");
        rev.setId(id); rev.setCorpId(existing.getCorpId()); rev.setReviewStatus(existing.getReviewStatus());
        revMapper.updateById(rev);
        // Replace costs
        costMapper.delete(new LambdaQueryWrapper<OpsActivityReviewCost>().eq(OpsActivityReviewCost::getReviewId, id));
        saveCosts(id, rev.getCosts());
        return R.ok();
    }

    /** Delete review (partners, only pending/rejected). */
    @DeleteMapping("/{id}")
    @Transactional
    public R<?> delete(@PathVariable Long id) {
        OpsActivityReview existing = revMapper.selectById(id);
        if (existing == null) return R.fail("复盘不存在");
        if (existing.getReviewStatus() != null && existing.getReviewStatus() == 1) return R.fail("已通过审核，不可删除");
        costMapper.delete(new LambdaQueryWrapper<OpsActivityReviewCost>().eq(OpsActivityReviewCost::getReviewId, id));
        revMapper.deleteById(id);
        return R.ok();
    }

    /** Operator approves or rejects. */
    @PutMapping("/{id}/approve")
    @Transactional
    public R<?> approve(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        OpsActivityReview r = revMapper.selectById(id);
        if (r == null) return R.fail("复盘不存在");
        int status = Integer.parseInt(body.get("reviewStatus").toString());
        String comment = (String) body.getOrDefault("reviewComment", "");
        r.setReviewStatus(status);
        r.setReviewComment(comment);
        r.setReviewBy(SecurityUtils.getCurrentUserId());
        r.setReviewTime(LocalDateTime.now());
        revMapper.updateById(r);
        return R.ok();
    }

    /** Get available activities for the current user. */
    @GetMapping("/available-activities")
    public R<?> availableActivities() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String filter = corpId != null ? " AND (a.corp_id=" + corpId +
                " OR (a.is_operator_initiated=1 AND FIND_IN_SET(" + corpId + ", a.partner_corp_ids)>0))" : "";
            jdbcTemplate.query("SELECT id, title FROM ops_activity a WHERE a.delete_flag=0" + filter + " ORDER BY a.id DESC",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    while (rs.next()) list.add(Map.of("id", rs.getLong("id"), "title", rs.getString("title")));
                });
        } catch (Exception e) { log.warn("availableActivities failed: {}", e.getMessage()); }
        return R.ok(list);
    }

    /** Get employees (sys_user) of the current org for participant/agent selection. */
    @GetMapping("/employees")
    public R<?> employees() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null) return R.ok(List.of());
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT id, real_name, phone FROM ai_platform.sys_user WHERE status=1 AND delete_flag=0 AND corp_id=" + corpId + " ORDER BY real_name");
            return R.ok(list);
        } catch (Exception e) { log.warn("employees failed: {}", e.getMessage()); }
        return R.ok(List.of());
    }

    // helpers
    private Long currentCorpId() {
        Set<Long> ids = dataScopeService.getVisibleCorpIds();
        if (ids == null || ids.isEmpty()) return 4L;
        return ids.iterator().next();
    }

    private void saveCosts(Long reviewId, List<OpsActivityReviewCost> costs) {
        if (costs == null) return;
        for (OpsActivityReviewCost c : costs) {
            c.setId(null); c.setReviewId(reviewId); costMapper.insert(c);
        }
    }
}
