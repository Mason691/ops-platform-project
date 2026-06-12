-- 头像统一迁移：ops_member → sys_member, 删除本地 avatar 列

-- ═══ 1. 同步现有 ops_member.avatar 到底座 sys_member ═══
-- ops_member.platform_member_id = sys_member.id
UPDATE ai_platform.sys_member sm
INNER JOIN mall_db.ops_member om ON sm.id = om.platform_member_id
SET sm.avatar = om.avatar
WHERE om.avatar IS NOT NULL AND om.avatar != ''
  AND (sm.avatar IS NULL OR sm.avatar = '');

-- ═══ 2. 验证同步结果 ═══
SELECT 'after_migration' AS step,
       COUNT(*) AS ops_with_avatar,
       COUNT(CASE WHEN sm.avatar IS NOT NULL AND sm.avatar != '' THEN 1 END) AS sys_with_avatar
FROM mall_db.ops_member om
LEFT JOIN ai_platform.sys_member sm ON sm.id = om.platform_member_id
WHERE om.avatar IS NOT NULL AND om.avatar != '';
