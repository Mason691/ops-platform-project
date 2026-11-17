-- =====================================================================
-- 员工工作台「货款补录」权限（目标库：ai_platform，幂等）
-- 权限：h5:biz-order:balance-receive
-- 菜单 2108（父菜单 145 业务数据），仅按钮型权限；H5 卡片与后端接口共用。
-- 执行：DBA 对底座库执行；ops 运行时不连接 ai_platform。
-- =====================================================================

USE ai_platform;

INSERT INTO sys_menu (id, parent_id, name, type, permission_code, path, icon, sort_order, visible,
                      create_time, modify_time, sort, delete_flag)
SELECT 2108, 145, '货款补录', 'button', 'h5:biz-order:balance-receive', NULL, NULL, 1, 1,
       NOW(), NOW(), 0, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 2108);

-- 组织角色授权
INSERT INTO sys_org_role_menu (role_id, menu_id)
SELECT r.id, 2108 FROM sys_org_role r
WHERE NOT EXISTS (SELECT 1 FROM sys_org_role_menu x
                  WHERE x.role_id = r.id AND x.menu_id = 2108);

-- 企业角色授权（员工 H5 卡片）
INSERT INTO sys_corp_user_role_menu (corp_role_id, menu_id)
SELECT r.id, 2108 FROM sys_corp_user_role r
WHERE r.delete_flag = 0
  AND NOT EXISTS (SELECT 1 FROM sys_corp_user_role_menu x
                  WHERE x.corp_role_id = r.id AND x.menu_id = 2108);

SELECT id, parent_id, name, type, permission_code
FROM sys_menu WHERE id = 2108 AND delete_flag = 0;
