-- ============================================================
-- 迁移：gs_* 表重命名为 ops_*（统一平台命名规范）
-- ops_member 已在 migrate_gs_user_to_ops_member.sql 中重命名，此处不再处理
-- 执行前建议备份：mysqldump mall_db > mall_db_backup_$(date +%Y%m%d).sql
-- 回滚脚本见：rollback_ops_to_gs.sql
-- ============================================================

USE `mall_db`;

-- 仅当源表存在且目标表不存在时执行重命名（幂等）
RENAME TABLE IF EXISTS `gs_points_log`  TO `ops_points_log`;
RENAME TABLE IF EXISTS `gs_product`     TO `ops_product`;
RENAME TABLE IF EXISTS `gs_activity`    TO `ops_activity`;
RENAME TABLE IF EXISTS `gs_order`       TO `ops_order`;
RENAME TABLE IF EXISTS `gs_coupon`      TO `ops_coupon`;
RENAME TABLE IF EXISTS `gs_user_coupon` TO `ops_user_coupon`;
RENAME TABLE IF EXISTS `gs_address`     TO `ops_address`;
RENAME TABLE IF EXISTS `gs_click_log`   TO `ops_click_log`;

-- 验证
SELECT TABLE_NAME AS 'renamed_to'
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME LIKE 'ops_%'
ORDER BY TABLE_NAME;
