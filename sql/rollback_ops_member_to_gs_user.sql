-- ===========================================================
-- 回滚：ops_member → gs_user
-- 目标库：mall_db
-- 仅在需要回滚时执行
-- ===========================================================
USE `mall_db`;

-- ═══ 1. 回滚索引（如有新增 platform_member_id 索引） ═══
-- ALTER TABLE `ops_member` DROP INDEX `idx_platform_member`;

-- ═══ 2. 回滚表名 ═══
RENAME TABLE `ops_member` TO `gs_user`;

-- ═══ 3. 恢复旧表注释 ═══
ALTER TABLE `gs_user` COMMENT='用户表';

-- ═══ 验证 ═══
-- SHOW CREATE TABLE gs_user;
