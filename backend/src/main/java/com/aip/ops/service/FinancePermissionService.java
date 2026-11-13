package com.aip.ops.service;

import com.aip.ops.security.JwtTokenInspector;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 财务核对权限：运营商管理员/超管，或当前组织内角色名含「财务」的财务角色。
 *
 * <p>生产 corp 级角色（ai_platform.sys_corp_user_role）命名为「财务仓库 / 财务库存」，
 * 因此按 role_name LIKE '%财务%' 判定；角色绑定经 sys_user_corp_role(user_id → corp_role_id)。</p>
 *
 * <p>运营商判定使用底座组织 API 的严格结果：平台不可达或 orgRoleId 缺失时返回 false，
 * 再回退到财务角色查询；且「运营商」只代表组织类型，用户本人还必须在当前组织拥有
 * 管理员角色，避免组织内任意普通员工获得财务写权限（财务核对是权限敏感写操作）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinancePermissionService {

    private static final String FINANCE_ROLE_KEYWORD = "财务";
    private static final String ADMIN_ROLE_KEYWORD = "管理员";
    private static final long CITY_PARTNER_ROLE_ID = 5L;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformInternalClient platformClient;
    private final JwtTokenInspector jwtTokenInspector;

    /**
     * 当前用户是否可发起/取消派工。
     *
     * <p>口径：<b>任何员工</b>均可（平台/子系统员工 JWT，无 token_type 或非 sso）。
     * 会员 SSO 令牌（token_type=sso，sub=sys_member.id）仍拒绝，避免会员令牌
     * 越权操作用户端功能。数据范围由 {@code DataScopeService} 控制：
     * 城市合伙人仅本组织，运营商含下属组织。</p>
     */
    public boolean canCreateDispatch() {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) return false;
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null) return false;
        if (corpId == 0L) return true; // super admin
        return SecurityUtils.getCurrentUserId() != null;
    }

    /** 当前用户是否可执行财务核对（失败时 fail-closed）。 */
    public boolean canFinanceCheck() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null) return false;
        if (corpId == 0L) return true; // super admin

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return false;
        // 财务角色：任意组织类型均可（生产 corp 级角色「财务仓库/财务库存」）
        if (hasCorpRole(userId, corpId, FINANCE_ROLE_KEYWORD)) return true;
        // 运营商管理员：组织必须是运营商，且用户本人是 corp admin（sys_user.is_admin）
        // 或在该组织拥有管理员角色。orgRoleId 缺失/平台不可达时 isOperatorStrict=false，
        // 不允许把一个组织的所有普通员工都放行。
        return isOperatorStrict(corpId)
                && (SecurityUtils.isCorpAdmin()
                    || hasCorpRole(userId, corpId, ADMIN_ROLE_KEYWORD));
    }

    /** 当前用户在指定组织是否拥有 role_name 含 keyword 的 corp 级角色；查询失败 fail-closed。 */
    private boolean hasCorpRole(Long userId, Long corpId, String roleKeyword) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_platform.sys_corp_user_role cur "
                            + "JOIN ai_platform.sys_user_corp_role ucr ON ucr.corp_role_id = cur.id "
                            + "WHERE ucr.user_id = ? AND cur.corp_id = ? AND cur.delete_flag = 0 "
                            + "AND cur.role_name LIKE ?",
                    Long.class, userId, corpId, "%" + roleKeyword + "%");
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("财务核对角色查询失败 userId={}, corpId={}, keyword={}: {}",
                    userId, corpId, roleKeyword, e.getMessage());
            return false;
        }
    }

    /** 严格运营商判定：平台 API 失败或 orgRoleId 缺失返回 false（不 fail-open）。 */
    @SuppressWarnings("unchecked")
    private boolean isOperatorStrict(Long corpId) {
        try {
            Map<String, Object> resp = platformClient.get("/api/internal/corp/" + corpId, null);
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) return false;
            Object role = data.get("orgRoleId");
            if (role == null) return false; // 组织角色缺失 → fail-closed
            return ((Number) role).longValue() != CITY_PARTNER_ROLE_ID;
        } catch (Exception e) {
            log.warn("财务核对组织角色查询失败 corpId={}: {}", corpId, e.getMessage());
            return false;
        }
    }
}
