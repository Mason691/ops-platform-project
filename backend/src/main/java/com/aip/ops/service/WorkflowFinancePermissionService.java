package com.aip.ops.service;

import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 工作流财务暂缓操作的权限校验。
 *
 * <p>正常财务核对由工作流任务归属保证；「暂缓」是 ops 侧独立接口（不推进流程），
 * 必须自行校验：运营商管理员/超管，或当前组织内角色名含「财务」的员工。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowFinancePermissionService {

    private static final String FINANCE_ROLE_KEYWORD = "财务";

    private final JdbcTemplate jdbcTemplate;
    private final OrgRoleService orgRoleService;

    public boolean canHoldFinance() {
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId == 0L) {
            return true; // 超管
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return false;
        }
        // 财务角色：任意组织类型均可
        if (hasFinanceRole(userId, corpId)) {
            return true;
        }
        // 运营商组织的管理员也可代暂缓；普通运营商员工不可（避免任意员工改财务字段）
        return !"partner".equals(orgRoleService.getOrgRoleType())
                && SecurityUtils.isCorpAdmin();
    }

    private boolean hasFinanceRole(Long userId, Long corpId) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_platform.sys_corp_user_role cur "
                            + "JOIN ai_platform.sys_user_corp_role ucr ON ucr.corp_role_id = cur.id "
                            + "WHERE ucr.user_id = ? AND cur.corp_id = ? AND cur.delete_flag = 0 "
                            + "AND cur.role_name LIKE ?",
                    Long.class, userId, corpId, "%" + FINANCE_ROLE_KEYWORD + "%");
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("财务暂缓权限查询失败 userId={}, corpId={}: {}", userId, corpId, e.getMessage());
            return false;
        }
    }
}
