package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsMember;
import com.aip.ops.mapper.OpsBusinessDataEmployeeMapper;
import com.aip.ops.mapper.OpsMemberMapper;
import com.aip.ops.service.EmployeeService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Employee identity verification and personal business data queries.
 * All data queries scope by agent_phone = current member's phone number.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final OpsMemberMapper memberMapper;
    private final OpsBusinessDataEmployeeMapper employeeMapper;
    private final PlatformInternalClient platformInternalClient;

    private final ConcurrentHashMap<Long, CacheEntry> identityCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000;

    // ---- identity check ----

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkIdentity() {
        Long platformUserId = SecurityUtils.getCurrentUserId();

        // Check cache
        CacheEntry cached = identityCache.get(platformUserId);
        if (cached != null && System.currentTimeMillis() - cached.ts < CACHE_TTL_MS) {
            return cached.data;
        }

        // Look up local member to get platformMemberId
        OpsMember member = resolveMember();
        if (member == null || member.getPlatformMemberId() == null) {
            Map<String, Object> result = Map.of("isEmployee", false, "companies", List.of());
            identityCache.put(platformUserId, new CacheEntry(result));
            return result;
        }

        try {
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/internal/user/employee-info?platformMemberId=" + member.getPlatformMemberId(), null);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            boolean isEmployee = data != null && Boolean.TRUE.equals(data.get("isEmployee"));
            Object companiesRaw = data != null ? data.get("companies") : null;
            List<?> companies = (companiesRaw instanceof List) ? (List<?>) companiesRaw : List.of();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("isEmployee", isEmployee);
            result.put("companies", companies);
            identityCache.put(platformUserId, new CacheEntry(result));
            return result;
        } catch (Exception e) {
            log.warn("Employee identity check failed for platformMemberId={}: {}",
                    member.getPlatformMemberId(), e.getMessage());
            Map<String, Object> fallback = Map.of("isEmployee", false, "companies", List.of());
            identityCache.put(platformUserId, new CacheEntry(fallback));
            return fallback;
        }
    }

    // ---- business data ----

    @Override
    public List<String> getAvailableMonths() {
        String phone = resolvePhone();
        return employeeMapper.selectAvailableMonths(phone);
    }

    @Override
    public List<String> getBatchNos() {
        String phone = resolvePhone();
        return employeeMapper.selectBatchNos(phone);
    }

    @Override
    public List<Map<String, Object>> getCategorySummary(String month) {
        String phone = resolvePhone();
        List<Map<String, Object>> rows = employeeMapper.selectCategorySummary(phone, month);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            String catKey = (String) row.get("category");
            item.put("categoryName", BusinessDataServiceImpl.KEY_TO_CN.getOrDefault(catKey, catKey));
            result.add(item);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getDetailRecords(String month, String category) {
        String phone = resolvePhone();
        List<Map<String, Object>> rows = employeeMapper.selectDetailRecords(phone, month, category);
        for (Map<String, Object> row : rows) {
            row.put("brand", normalizeSentinel(row.get("brand")));
            row.put("model", normalizeSentinel(row.get("model")));
        }
        return rows;
    }

    @Override
    public Map<String, Object> getMonthlyStats(String month) {
        String phone = resolvePhone();

        Map<String, Object> current = employeeMapper.selectMonthlyStats(phone, month);
        BigDecimal totalSale = toBigDecimal(current.get("totalSale"));
        BigDecimal totalCommission = toBigDecimal(current.get("totalCommission"));
        Object orderCountObj = current.get("orderCount");
        long orderCount = orderCountObj instanceof Number ? ((Number) orderCountObj).longValue() : 0L;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSale", totalSale);
        result.put("totalCommission", totalCommission);
        result.put("orderCount", orderCount);

        // Previous month for MoM comparison — only when month is ISO format (YYYY-MM)
        try {
            String prevMonth = previousMonth(month);
            Map<String, Object> prev = employeeMapper.selectMonthlyStats(phone, prevMonth);
            BigDecimal prevSale = toBigDecimal(prev.get("totalSale"));
            BigDecimal prevCommission = toBigDecimal(prev.get("totalCommission"));
            Object prevOrderObj = prev.get("orderCount");
            long prevOrderCount = prevOrderObj instanceof Number ? ((Number) prevOrderObj).longValue() : 0L;

            if (prevSale.compareTo(BigDecimal.ZERO) > 0) {
                result.put("momSaleChange", totalSale.subtract(prevSale)
                        .divide(prevSale, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP));
            }
            if (prevCommission.compareTo(BigDecimal.ZERO) > 0) {
                result.put("momCommissionChange", totalCommission.subtract(prevCommission)
                        .divide(prevCommission, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP));
            }
            if (prevOrderCount > 0) {
                result.put("momOrderChange", BigDecimal.valueOf(orderCount - prevOrderCount)
                        .divide(BigDecimal.valueOf(prevOrderCount), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP));
            }
        } catch (Exception e) {
            log.debug("MoM comparison skipped for month={}: {}", month, e.getMessage());
        }

        return result;
    }

    // ---- private helpers ----

    /** Resolve the current member entity (with fallback for legacy accounts).
     * Uses JWT member_id claim when available (employee JWTs carry it from sys_user.platformMemberId). */
    private OpsMember resolveMember() {
        Long platformUserId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();
        // Prefer JWT member_id claim for correct platformMemberId matching (employee JWTs)
        Long memberId;
        try { memberId = SecurityUtils.getCurrentUser().getMemberId(); } catch (Exception e) { memberId = null; }
        if (memberId == null) memberId = platformUserId;

        OpsMember member = memberMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPlatformMemberId, memberId)
                .eq(OpsMember::getCorpId, corpId));
        if (member == null) {
            // Fallback for legacy accounts without platformMemberId populated
            member = memberMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                    .eq(OpsMember::getId, memberId)
                    .eq(OpsMember::getCorpId, corpId));
        }
        return member;
    }

    /** Resolve the employee's phone for agent_phone matching.
     * Uses JWT username (sys_user.username) which is the employee login account name (phone number).
     * Falls back to ops_member.phone for non-employee accounts. */
    private String resolvePhone() {
        // Primary: JWT username = sys_user.username (phone number for employee accounts)
        String username = SecurityUtils.getCurrentUser().getUsername();
        if (username != null && username.matches("^1[3-9]\\d{9}$")) {
            return username;
        }
        // Fallback: ops_member.phone for non-employee/member accounts
        OpsMember member = resolveMember();
        if (member != null && member.getPhone() != null) {
            return member.getPhone();
        }
        throw BusinessException.of("会员信息不存在");
    }

    /** Normalize sentinel values (e.g. "-1") to null so the frontend displays "-". */
    private static String normalizeSentinel(Object val) {
        if (val == null) return null;
        String s = val.toString().trim();
        if (s.isEmpty() || "-1".equals(s)) return null;
        return s;
    }

    private static BigDecimal toBigDecimal(Object val) {
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    /** Compute previous month in YYYY-MM format. */
    static String previousMonth(String month) {
        int y = Integer.parseInt(month.substring(0, 4));
        int m = Integer.parseInt(month.substring(5, 7));
        if (m == 1) return (y - 1) + "-12";
        return y + "-" + (m - 1 < 10 ? "0" : "") + (m - 1);
    }

    // ---- overview (all-time totals + monthly breakdown) ----

    @Override
    public Map<String, Object> getOverview(String batchNo) {
        String phone = resolvePhone();

        List<Map<String, Object>> all = employeeMapper.selectAllRecords(phone);
        // Filter by batchNo if specified
        if (batchNo != null && !batchNo.isBlank()) {
            all = all.stream().filter(r -> batchNo.equals(r.get("batchNo"))).collect(java.util.stream.Collectors.toList());
        }
        BigDecimal allTimeSale = BigDecimal.ZERO;
        BigDecimal allTimeCommission = BigDecimal.ZERO;

        // Group by month — monthKey → {monthLabel, totals, details[]}
        Map<String, MonthGroup> groups = new LinkedHashMap<>();

        for (Map<String, Object> row : all) {
            BigDecimal sale = toBigDecimal(row.get("salePrice"));
            BigDecimal commission = toBigDecimal(row.get("commissionAmount"));
            allTimeSale = allTimeSale.add(sale);
            allTimeCommission = allTimeCommission.add(commission);

            String saleDate = row.get("saleDate") instanceof String
                    ? (String) row.get("saleDate") : "";
            MonthKey mk = extractMonth(saleDate);

            MonthGroup g = groups.computeIfAbsent(mk.sortKey, k -> {
                MonthGroup mg = new MonthGroup();
                mg.monthKey = mk.key;
                mg.monthLabel = mk.label;
                return mg;
            });
            g.totalSale = g.totalSale.add(sale);
            g.totalCommission = g.totalCommission.add(commission);
            g.orderCount++;

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("brand", normalizeSentinel(row.get("brand")));
            detail.put("model", normalizeSentinel(row.get("model")));
            detail.put("salePrice", sale);
            detail.put("commissionAmount", commission);
            detail.put("commissionRate", toBigDecimal(row.get("commissionRate")));
            detail.put("saleDate", saleDate);
            detail.put("customer", row.get("customer"));
            detail.put("category", row.get("category"));
            if (row.get("calcStatus") != null) detail.put("calcStatus", row.get("calcStatus"));
            if (row.get("batchNo") != null) detail.put("batchNo", row.get("batchNo"));
            String catKey = (String) row.get("category");
            detail.put("categoryName", BusinessDataServiceImpl.KEY_TO_CN.getOrDefault(catKey, catKey));
            g.details.add(detail);
        }

        // Build months list (sorted by sortKey descending)
        List<Map<String, Object>> months = new ArrayList<>();
        List<String> sortedKeys = new ArrayList<>(groups.keySet());
        sortedKeys.sort(java.util.Comparator.reverseOrder());
        for (String sk : sortedKeys) {
            MonthGroup g = groups.get(sk);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", g.monthKey);
            m.put("monthLabel", g.monthLabel);
            m.put("totalSale", g.totalSale.setScale(2, RoundingMode.HALF_UP));
            m.put("totalCommission", g.totalCommission.setScale(2, RoundingMode.HALF_UP));
            m.put("orderCount", g.orderCount);
            m.put("details", g.details);
            months.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allTime", Map.of(
                "totalSale", allTimeSale.setScale(2, RoundingMode.HALF_UP),
                "totalCommission", allTimeCommission.setScale(2, RoundingMode.HALF_UP),
                "orderCount", all.size()));
        result.put("months", months);
        return result;
    }

    /** Holds month aggregation data during grouping. */
    private static class MonthGroup {
        String monthKey, monthLabel;
        BigDecimal totalSale = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        int orderCount;
        List<Map<String, Object>> details = new ArrayList<>();
    }

    /** Extract month key + label from a sale_date string in any supported format. */
    private static MonthKey extractMonth(String raw) {
        if (raw == null || raw.isBlank()) return new MonthKey("未知", "未知", "00-未知");

        raw = raw.trim();

        // ISO: 2026-06-23
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            String k = raw.substring(0, 7); // "2026-06"
            int y = Integer.parseInt(raw.substring(0, 4));
            int m = Integer.parseInt(raw.substring(5, 7));
            return new MonthKey(k, y + "年" + m + "月", k);
        }

        // Chinese with year: 2026年6月23日
        java.util.regex.Matcher ym = java.util.regex.Pattern.compile(
                "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月").matcher(raw);
        if (ym.find()) {
            String k = String.format("%s-%02d", ym.group(1), Integer.parseInt(ym.group(2)));
            return new MonthKey(k, ym.group(1) + "年" + Integer.parseInt(ym.group(2)) + "月", k);
        }

        // Chinese without year: 6月23日 / 06月23日
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d{1,2})\\s*月").matcher(raw);
        if (m.find()) {
            int mon = Integer.parseInt(m.group(1));
            String k = String.format("01-%02d", mon); // sort key: prefix with "01-" for legacy records
            return new MonthKey(k, mon + "月", k);
        }

        return new MonthKey(raw, raw, "00-" + raw);
    }

    private static class MonthKey {
        final String key;    // "2026-06" or "01-06" for sorting
        final String label;  // "2026年6月" or "6月"
        final String sortKey; // same as key, for Map grouping

        MonthKey(String key, String label, String sortKey) {
            this.key = key; this.label = label; this.sortKey = sortKey;
        }
    }

    // ---- cache entry ----

    private static class CacheEntry {
        final Map<String, Object> data;
        final long ts;

        CacheEntry(Map<String, Object> data) {
            this.data = data;
            this.ts = System.currentTimeMillis();
        }
    }
}
