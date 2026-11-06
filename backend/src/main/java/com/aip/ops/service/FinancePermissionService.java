package com.aip.ops.service;

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
 * <p>运营商判定使用底座组织 API 的严格结果：平台不可达时返回 false 并回退到财务角色查询，
 * 避免沿用 OrgRoleService「查询失败视为运营商」的 fail-open 语义（财务核对是权限敏感写操作）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinancePermissionService {

    private static final String FINANCE_ROLE_KEYWORD = "财务";
    private static final long CITY_PARTNER_ROLE_ID = 5L;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformInternalClient platformClient;

    /** 当前用户是否可执行财务核对（失败时 fail-closed）。 */
    public boolean canFinanceCheck() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null) return false;
        if (corpId == 0L) return true; // super admin
        if (isOperatorStrict(corpId)) return true;

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return false;
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_platform.sys_corp_user_role cur "
                            + "JOIN ai_platform.sys_user_corp_role ucr ON ucr.corp_role_id = cur.id "
                            + "WHERE ucr.user_id = ? AND cur.corp_id = ? AND cur.delete_flag = 0 "
                            + "AND cur.role_name LIKE ?",
                    Long.class, userId, corpId, "%" + FINANCE_ROLE_KEYWORD + "%");
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("财务角色查询失败 userId={}, corpId={}: {}", userId, corpId, e.getMessage());
            return false;
        }
    }

    /** 严格运营商判定：平台 API 失败返回 false（不 fail-open）。 */
    @SuppressWarnings("unchecked")
    private boolean isOperatorStrict(Long corpId) {
        try {
            Map<String, Object> resp = platformClient.get("/api/internal/corp/" + corpId, null);
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) return false;
            Object role = data.get("orgRoleId");
            if (role == null) return true; // 顶级组织按运营商处理
            return ((Number) role).longValue() != CITY_PARTNER_ROLE_ID;
        } catch (Exception e) {
            log.warn("财务核对组织角色查询失败 corpId={}: {}", corpId, e.getMessage());
            return false;
        }
    }
}
