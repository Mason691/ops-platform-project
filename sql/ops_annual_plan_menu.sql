-- ===========================================================
-- 菜单迁移 — 在"增值业务"下新增"年度任务"子菜单
-- 目标库: ai_platform
-- 父菜单: 增值业务 (id=100, parent_id=0, path=/value-added-service)
-- ===========================================================
USE `ai_platform`;

-- sys_menu 表实际列: parent_id, name, type, path, icon, sort_order, visible, delete_flag, permission_code
INSERT INTO `sys_menu` (
    `parent_id`, `name`, `type`, `path`, `icon`,
    `sort_order`, `visible`, `delete_flag`, `permission_code`,
    `create_time`, `modify_time`
)
SELECT
    100,
    '年度任务',
    'menu',
    '/ops/annual-plan',
    'Calendar',
    40,
    1,
    0,
    'ops:annual-plan:view',
    NOW(),
    NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/ops/annual-plan' AND delete_flag = 0
);
