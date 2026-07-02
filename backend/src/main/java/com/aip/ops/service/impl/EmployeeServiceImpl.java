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
        return employeeMapper.selectDetailRecords(phone, month, category);
    }

    @Override
    public Map<String, Object> getMonthlyStats(String month) {
        String phone = resolvePhone();

        Map<String, Object> current = employeeMapper.selectMonthlyStats(phone, month);
        BigDecimal totalSale = toBigDecimal(current.get("totalSale"));
        BigDecimal totalCommission = toBigDecimal(current.get("totalCommission"));
        Object orderCountObj = current.get("orderCount");
        long orderCount = orderCountObj instanceof Number ? ((Number) orderCountObj).longValue() : 0L;

        // Previous month for MoM comparison
        String prevMonth = previousMonth(month);
        Map<String, Object> prev = employeeMapper.selectMonthlyStats(phone, prevMonth);
        BigDecimal prevSale = toBigDecimal(prev.get("totalSale"));
        BigDecimal prevCommission = toBigDecimal(prev.get("totalCommission"));
        Object prevOrderObj = prev.get("orderCount");
        long prevOrderCount = prevOrderObj instanceof Number ? ((Number) prevOrderObj).longValue() : 0L;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSale", totalSale);
        result.put("totalCommission", totalCommission);
        result.put("orderCount", orderCount);

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

        return result;
    }

    // ---- private helpers ----

    /** Resolve the current member entity (with fallback for legacy accounts). */
    private OpsMember resolveMember() {
        Long platformUserId = SecurityUtils.getCurrentUserId();
        Long corpId = SecurityUtils.getCurrentCorpId();

        OpsMember member = memberMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPlatformMemberId, platformUserId)
                .eq(OpsMember::getCorpId, corpId));
        if (member == null) {
            // Fallback for legacy accounts without platformMemberId populated
            member = memberMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                    .eq(OpsMember::getId, platformUserId)
                    .eq(OpsMember::getCorpId, corpId));
        }
        return member;
    }

    /** Resolve the current member's phone number. */
    private String resolvePhone() {
        OpsMember member = resolveMember();
        if (member == null) {
            throw BusinessException.of("会员信息不存在");
        }
        return member.getPhone();
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
