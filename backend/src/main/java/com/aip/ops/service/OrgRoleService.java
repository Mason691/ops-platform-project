package com.aip.ops.service;

import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
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
        return resolveCorpInfo(corpId).orgRoleId;
    }

    /** 指定组织是否为城市合伙人（orgRoleId=5）；平台不可达时按运营商处理（安全兜底）。 */
    public boolean isCityPartner(Long corpId) {
        if (corpId == null || corpId <= 0L) return false;
        Long orgRoleId = resolveOrgRoleId(corpId);
        return orgRoleId != null && orgRoleId == CITY_PARTNER_ROLE_ID;
    }

    /** 指定组织是否为运营商/顶级组织（非城市合伙人）。 */
    public boolean isOperatorCorp(Long corpId) {
        if (corpId == null || corpId <= 0L) return false;
        Long orgRoleId = resolveOrgRoleId(corpId);
        return orgRoleId == null || orgRoleId != CITY_PARTNER_ROLE_ID;
    }

    /**
     * 指定组织的运营商（共享模板归属）：
     * 城市合伙人 → parent_corp_id；运营商 → 自身；parent 缺失 → 自身。
     */
    public Long getOperatorCorpId(Long corpId) {
        if (corpId == null || corpId <= 0L) return corpId;
        if (!isCityPartner(corpId)) return corpId;
        Long parent = resolveCorpInfo(corpId).parentCorpId;
        return parent != null && parent > 0L ? parent : corpId;
    }

    /**
     * 订单编号计数域：严格解析运营商（城市合伙人 → parent_corp_id，运营商 → 自身）。
     *
     * <p>与 {@link #getOperatorCorpId(Long)} 不同，平台不可达或城市合伙人缺少上级时
     * 直接抛业务异常，不允许退回 org 自身计数——否则同一运营商体系会生成重复编号。</p>
     */
    public Long getOperatorCorpIdStrict(Long corpId) {
        if (corpId == null || corpId <= 0L) {
            throw BusinessException.of("订单归属组织无效，无法生成订单编号");
        }
        CacheEntry info = resolveCorpInfoStrict(corpId);
        if (info.orgRoleId == null) {
            throw BusinessException.of("组织角色未配置，无法生成订单编号");
        }
        if (info.orgRoleId == CITY_PARTNER_ROLE_ID) {
            if (info.parentCorpId == null || info.parentCorpId <= 0L) {
                throw BusinessException.of("城市合伙人未配置所属运营商，无法生成订单编号");
            }
            return info.parentCorpId;
        }
        return corpId;
    }

    /** 严格解析：查询失败不复用 (null,null) 兜底缓存，直接抛异常交上层回滚。 */
    @SuppressWarnings("unchecked")
    private CacheEntry resolveCorpInfoStrict(Long corpId) {
        CacheEntry cached = cache.get(corpId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.orgRoleId != null
                && (now - cached.ts) < CACHE_TTL_MS) {
            return cached;
        }
        try {
            Map<String, Object> resp = platformClient.get("/api/internal/corp/" + corpId, null);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) {
                throw new IllegalStateException("empty corp response");
            }
            Object orgRoleIdObj = data.get("orgRoleId");
            Long orgRoleId = orgRoleIdObj instanceof Number
                    ? ((Number) orgRoleIdObj).longValue() : null;
            Object parentIdObj = data.get("parentCorpId");
            Long parentCorpId = parentIdObj instanceof Number
                    ? ((Number) parentIdObj).longValue() : null;
            CacheEntry resolved = new CacheEntry(orgRoleId, parentCorpId);
            cache.put(corpId, resolved);
            return resolved;
        } catch (Exception e) {
            log.warn("Strict operator resolve failed corpId={}: {}", corpId, e.getMessage());
            throw BusinessException.of("组织信息查询失败，无法生成订单编号，请稍后重试");
        }
    }

    /** 指定组织的上级组织ID（可能为 null）。 */
    public Long getParentCorpId(Long corpId) {
        if (corpId == null || corpId <= 0L) return null;
        return resolveCorpInfo(corpId).parentCorpId;
    }

    private CacheEntry resolveCorpInfo(Long corpId) {
        CacheEntry entry = cache.get(corpId);
        if (entry != null && (System.currentTimeMillis() - entry.ts) < CACHE_TTL_MS) {
            return entry;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformClient.get(
                    "/api/internal/corp/" + corpId, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) {
                CacheEntry fallback = new CacheEntry(null, null);
                cache.put(corpId, fallback);
                return fallback;
            }
            Object orgRoleIdObj = data.get("orgRoleId");
            Long orgRoleId = orgRoleIdObj instanceof Number
                    ? ((Number) orgRoleIdObj).longValue() : null;
            Object parentIdObj = data.get("parentCorpId");
            Long parentCorpId = parentIdObj instanceof Number
                    ? ((Number) parentIdObj).longValue() : null;
            CacheEntry resolved = new CacheEntry(orgRoleId, parentCorpId);
            cache.put(corpId, resolved);
            return resolved;
        } catch (Exception e) {
            log.warn("Failed to resolve orgRoleId for corpId={}: {}", corpId, e.getMessage());
            // 安全兜底：查询失败视为运营商（不阻断写操作）
            CacheEntry fallback = new CacheEntry(null, null);
            cache.put(corpId, fallback);
            return fallback;
        }
    }

    private static class CacheEntry {
        final Long orgRoleId;
        final Long parentCorpId;
        final long ts;
        CacheEntry(Long orgRoleId, Long parentCorpId) {
            this.orgRoleId = orgRoleId;
            this.parentCorpId = parentCorpId;
            this.ts = System.currentTimeMillis();
        }
    }
}
