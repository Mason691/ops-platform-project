package com.aip.ops.service;

import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组织角色查询服务 — 判断当前用户所属组织的类型（运营商/城市合伙人/超管）。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>超管（corpId=0）→ superadmin，拥有全部权限</li>
 *   <li>orgRoleId=5 → 城市合伙人，只读</li>
 *   <li>其他 orgRoleId（含 null）→ 运营商，完全 CRUD</li>
 * </ul>
 *
 * <h3>注</h3>
 * 与 {@link DataScopeService} 不同：本服务只判定 org 类型，不计算可见 org 范围。
 * 两个服务独立缓存，互不耦合。
 */
@Slf4j
@Service
public class OrgRoleService {

    /** 平台 org role 表中「城市合伙人」的 id */
    private static final long CITY_PARTNER_ROLE_ID = 5L;
    private static final long CACHE_TTL_MS = 60_000;

    private final PlatformInternalClient platformClient;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public OrgRoleService(PlatformInternalClient platformClient) {
        this.platformClient = platformClient;
    }

    /**
     * 当前用户是否拥有写权限（超管 或 运营商管理员）。
     * 城市合伙人返回 false。
     */
    public boolean isOperatorOrSuperAdmin() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId == 0L) return true;
        Long orgRoleId = resolveOrgRoleId(corpId);
        return orgRoleId == null || orgRoleId != CITY_PARTNER_ROLE_ID;
    }

    /**
     * 返回当前用户的 org 角色类型字符串，供前端渲染使用。
     *
     * @return "superadmin" / "operator" / "partner"
     */
    public String getOrgRoleType() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId == 0L) return "superadmin";
        Long orgRoleId = resolveOrgRoleId(corpId);
        return (orgRoleId != null && orgRoleId == CITY_PARTNER_ROLE_ID)
                ? "partner" : "operator";
    }

    /**
     * 查询指定 corpId 的 orgRoleId。结果缓存 60s。
     */
    private Long resolveOrgRoleId(Long corpId) {
        CacheEntry entry = cache.get(corpId);
        if (entry != null && (System.currentTimeMillis() - entry.ts) < CACHE_TTL_MS) {
            return entry.orgRoleId;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformClient.get(
                    "/api/internal/corp/" + corpId, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) {
                cache.put(corpId, new CacheEntry(null));
                return null;
            }
            Object orgRoleIdObj = data.get("orgRoleId");
            Long orgRoleId = orgRoleIdObj instanceof Number
                    ? ((Number) orgRoleIdObj).longValue() : null;
            cache.put(corpId, new CacheEntry(orgRoleId));
            return orgRoleId;
        } catch (Exception e) {
            log.warn("Failed to resolve orgRoleId for corpId={}: {}", corpId, e.getMessage());
            // 安全兜底：查询失败视为运营商（不阻断写操作）
            cache.put(corpId, new CacheEntry(null));
            return null;
        }
    }

    private static class CacheEntry {
        final Long orgRoleId;
        final long ts;
        CacheEntry(Long orgRoleId) { this.orgRoleId = orgRoleId; this.ts = System.currentTimeMillis(); }
    }
}
