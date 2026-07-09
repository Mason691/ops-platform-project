package com.aip.ops.service;

import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 数据权限范围服务 — 根据当前用户的组织角色计算可见的 corpId 列表。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>超管（corpId=0）→ null（查全部数据）</li>
 *   <li>城市合伙人（orgRoleId=5）→ 只查自己 corpId</li>
 *   <li>运营商 → 查自己 + 所有下属城市合伙人</li>
 *   <li>其他/异常 → 回退到只查自己（安全第一）</li>
 * </ul>
 */
@Slf4j
@Service
public class DataScopeService {

    private static final long CITY_PARTNER_ROLE_ID = 5L;
    private static final long CACHE_TTL_MS = 60_000; // 60s

    private final PlatformInternalClient platformClient;

    /** 可见 corpId 集合缓存，带时间戳 */
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public DataScopeService(PlatformInternalClient platformClient) {
        this.platformClient = platformClient;
    }

    /**
     * 获取当前登录用户可见的 corpId 集合。
     *
     * @return null 表示超管（查全部），否则返回可见 corpId 列表
     */
    public Set<Long> getVisibleCorpIds() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null) {
            return Collections.emptySet();
        }
        return getVisibleCorpIdsForCorp(corpId);
    }

    /**
     * 指定组织「自身 + 所有下级」的 corp_id 逗号分隔串，供 SQL {@code corp_id IN (...)} 内联。
     * 值均为平台返回的 Long（非用户输入），可安全内联。超管/解析为空时回退为传入 corpId 自身，
     * 保证至少按单组织隔离、不越权。
     */
    public String getVisibleCorpIdCsv(Long corpId) {
        Set<Long> ids = getVisibleCorpIdsForCorp(corpId);
        if (ids == null || ids.isEmpty()) return String.valueOf(corpId == null ? 0 : corpId);
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * 获取指定 corpId 可见的组织集合（供 Kafka 等无安全上下文的场景使用）。
     */
    public Set<Long> getVisibleCorpIdsForCorp(Long corpId) {
        if (corpId == null) return Collections.emptySet();
        if (corpId == 0L) return null; // super admin

        CacheEntry entry = cache.get(corpId);
        if (entry != null && (System.currentTimeMillis() - entry.ts) < CACHE_TTL_MS) {
            return entry.ids;
        }

        try {
            // 1. Get org info to check orgRoleId
            Map<String, Object> resp = platformClient.get(
                    "/api/internal/corp/" + corpId, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) {
                log.warn("getCorpInfo returned null for corpId={}, falling back to self-only", corpId);
                Set<Long> fb = Set.of(corpId);
                cache.put(corpId, new CacheEntry(fb));
                return fb;
            }

            Object orgRoleIdObj = data.get("orgRoleId");
            Long orgRoleId = orgRoleIdObj instanceof Number ? ((Number) orgRoleIdObj).longValue() : null;

            Set<Long> result;
            if (orgRoleId != null && orgRoleId == CITY_PARTNER_ROLE_ID) {
                // City partner: only see own data
                result = Set.of(corpId);
            } else {
                // Operator (or other): see self + all city partners under this operator
                result = resolveOperatorScope(corpId);
            }
            cache.put(corpId, new CacheEntry(result));
            return result;
        } catch (Exception e) {
            log.warn("Failed to resolve org scope for corpId={}, falling back to self-only: {}",
                    corpId, e.getMessage());
            Set<Long> fallback = Set.of(corpId);
            cache.put(corpId, new CacheEntry(fallback));
            return fallback;
        }
    }

    /**
     * 运营商：获取自己 + 所有下属城市合伙人的 corpId。
     */
    @SuppressWarnings("unchecked")
    private Set<Long> resolveOperatorScope(Long operatorCorpId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(operatorCorpId);

        try {
            Map<String, Object> resp = platformClient.get(
                    "/api/internal/corp/list-by-role?roleName=城市合伙人&pageNum=1&pageSize=999", null);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null && data.get("records") instanceof List) {
                List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
                for (Map<String, Object> r : records) {
                    Object parentIdObj = r.get("parentCorpId");
                    Long parentId = parentIdObj instanceof Number ? ((Number) parentIdObj).longValue() : null;
                    if (operatorCorpId.equals(parentId)) {
                        Object idObj = r.get("id");
                        if (idObj instanceof Number) {
                            ids.add(((Number) idObj).longValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list city partners for operator corpId={}: {}", operatorCorpId, e.getMessage());
        }
        return ids;
    }

    private static class CacheEntry {
        final Set<Long> ids;
        final long ts;
        CacheEntry(Set<Long> ids) { this.ids = ids; this.ts = System.currentTimeMillis(); }
    }
}
