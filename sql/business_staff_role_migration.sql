-- =====================================================================
-- 城市合伙人组织「业务员」基础角色（目标库：ai_platform）
--
-- 需求：
--   1. 每个城市合伙人组织（sys_corp.org_role_id = 5）各建一个「业务员」角色；
--   2. 组织内全部在职员工默认关联该角色（存量 + 后续新增）；
--   3. 业务员拥有公众号 H5 员工工作台除「已核对业务」外的全部功能权限。
--      已核对业务 = 采购订单 H5 制单权限 scm:pur-order:h5:create，本角色不授予；
--      H5 核审 scm:pur-order:h5:review 亦不授予（业务员不自动获得审批权限）。
--
-- 执行：DBA / CI 对底座库执行；幂等，可重复执行（重复执行会把默认工作台
--       权限补回业务员角色，不会重复建角色/绑定员工）。
-- 注意：sys_corp_user_role 没有 role_code 列，角色编码写入 remark='salesperson'。
-- =====================================================================

USE ai_platform;

-- ── 1. 为每个城市合伙人组织创建业务员角色（幂等）────────────────────
INSERT INTO sys_corp_user_role
    (corp_id, role_name, description, update_key, unique_id,
     create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT c.id, '业务员',
       '组织基础角色：员工工作台全部功能（不含已核对业务）',
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0,
       'system', 'system', 0, 'salesperson', 0
FROM sys_corp c
WHERE c.delete_flag = 0
  AND c.org_role_id = 5
  AND NOT EXISTS (
      SELECT 1 FROM sys_corp_user_role r
      WHERE r.corp_id = c.id
        AND r.role_name = '业务员'
        AND r.delete_flag = 0
  );

-- ── 2. 授予业务员角色默认工作台菜单（幂等）──────────────────────────
-- 权限码即功能事实源，跨环境不依赖固定菜单 ID；故意不包含：
--   scm:pur-order:h5:create  已核对业务 / 采购 H5 制单
--   scm:pur-order:h5:review  H5 核审（审批权限）
INSERT IGNORE INTO sys_corp_user_role_menu (corp_role_id, menu_id)
SELECT r.id, m.id
FROM sys_corp_user_role r
JOIN sys_corp c ON c.id = r.corp_id
JOIN sys_menu m ON m.delete_flag = 0
WHERE r.delete_flag = 0
  AND r.role_name = '业务员'
  AND c.delete_flag = 0
  AND c.org_role_id = 5
  AND m.permission_code IN (
      'wf:task:todo',
      'wf:task:initiated',
      'scm:material-pick:list',
      'scm:material-pick:create',
      'scm:material-pick:print',
      'scm:material-pick:h5:create',
      'scm:material-write-off:list',
      'scm:material-write-off:create',
      'scm:material-write-off:h5:create',
      'h5:biz-order:balance-receive'
  );

-- ── 3. 存量员工全量绑定业务员角色（幂等）──────────────────────────
-- 只绑定在职任职（sys_user_corp.status = 1），已离职/停用关系不补；
-- 含已另有部长/财务/派工员等角色的员工（多角色权限取并集）。
INSERT IGNORE INTO sys_user_corp_role (user_id, corp_role_id)
SELECT uc.user_id, r.id
FROM sys_user_corp uc
JOIN sys_corp c ON c.id = uc.corp_id
JOIN sys_corp_user_role r ON r.corp_id = c.id
WHERE uc.delete_flag = 0
  AND uc.status = 1
  AND c.delete_flag = 0
  AND c.org_role_id = 5
  AND r.delete_flag = 0
  AND r.role_name = '业务员';

-- ── 4. 验证（执行结果自检）────────────────────────────────────────
SELECT '城市合伙人组织数' AS item, COUNT(*) AS cnt
FROM sys_corp c
WHERE c.delete_flag = 0 AND c.org_role_id = 5;

SELECT '业务员角色数' AS item, COUNT(*) AS cnt
FROM sys_corp_user_role r
JOIN sys_corp c ON c.id = r.corp_id
WHERE r.delete_flag = 0 AND r.role_name = '业务员'
  AND c.delete_flag = 0 AND c.org_role_id = 5;

SELECT '业务员角色菜单数' AS item,COUNT(*) AS cnt FROM (
  SELECT DISTINCT r.id, crm.menu_id
  FROM sys_corp_user_role r
  JOIN sys_corp c ON c.id = r.corp_id
  JOIN sys_corp_user_role_menu crm ON crm.corp_role_id = r.id
  WHERE r.delete_flag = 0 AND r.role_name = '业务员'
    AND c.delete_flag = 0 AND c.org_role_id = 5
) t;

SELECT '未绑定业务员角色的在职员工数' AS item, COUNT(*) AS cnt
FROM sys_user_corp uc
JOIN sys_corp c ON c.id = uc.corp_id
WHERE uc.delete_flag = 0 AND uc.status = 1
  AND c.delete_flag = 0 AND c.org_role_id = 5
  AND NOT EXISTS (
      SELECT 1
      FROM sys_user_corp_role ur
      JOIN sys_corp_user_role r ON r.id = ur.corp_role_id
      WHERE ur.user_id = uc.user_id
        AND r.corp_id = uc.corp_id
        AND r.role_name = '业务员'
        AND r.delete_flag = 0
  );

-- ── 5. 回滚（如需，仅清理本脚本创建的角色；先确认无业务依赖）────────
-- DELETE crm FROM sys_corp_user_role_menu crm
--   JOIN sys_corp_user_role r ON r.id = crm.corp_role_id
--   WHERE r.role_name = '业务员' AND r.remark = 'salesperson';
-- DELETE ur FROM sys_user_corp_role ur
--   JOIN sys_corp_user_role r ON r.id = ur.corp_role_id
--   WHERE r.role_name = '业务员' AND r.remark = 'salesperson';
-- DELETE FROM sys_corp_user_role WHERE role_name = '业务员' AND remark = 'salesperson';
