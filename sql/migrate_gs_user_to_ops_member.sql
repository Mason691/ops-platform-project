-- ===========================================================
-- 迁移：gs_user → ops_member 重命名 + 规范化
-- 目标库：mall_db（共用库）
-- 日期：2026-06-12
-- ===========================================================
USE `mall_db`;

-- ═══ 1. 重命名表 ═══
RENAME TABLE `gs_user` TO `ops_member`;

-- ═══ 2. 补充缺失的 platform_member_id 列（若尚未添加） ═══
-- 注意：若列已存在，MySQL 8.x 不支持 IF NOT EXISTS for columns
-- 请先确认：SHOW COLUMNS FROM ops_member LIKE 'platform_member_id';
-- 若返回空，则执行：
-- ALTER TABLE `ops_member`
--     ADD COLUMN `platform_member_id` BIGINT DEFAULT NULL COMMENT '底座平台会员ID(sys_member.id)';

-- ═══ 3. 补充缺失的 password 列（若尚未添加） ═══
-- 请先确认：SHOW COLUMNS FROM ops_member LIKE 'password';
-- ALTER TABLE `ops_member`
--     ADD COLUMN `password` VARCHAR(255) DEFAULT NULL COMMENT 'BCrypt加密密码';

-- ═══ 4. 添加 platform_member_id 索引 ═══
-- 先检查索引是否存在：SHOW INDEX FROM ops_member WHERE Key_name = 'idx_platform_member';
-- ALTER TABLE `ops_member` ADD INDEX `idx_platform_member` (`platform_member_id`);

-- ═══ 5. 补充 BaseEntity 标准字段（若缺失） ═══
-- 注意：create_time, modify_time, delete_flag 在旧 DDL 中已存在
-- 以下字段为新 DDL 未包含但 MyBatis-Plus BaseEntity 需要的：
-- ALTER TABLE `ops_member` ADD COLUMN `update_key` VARCHAR(64) DEFAULT NULL AFTER `last_login_time`;
-- ALTER TABLE `ops_member` ADD COLUMN `unique_id` VARCHAR(32) DEFAULT NULL AFTER `update_key`;
-- ALTER TABLE `ops_member` ADD COLUMN `create_user` BIGINT DEFAULT NULL AFTER `last_login_time`;
-- ALTER TABLE `ops_member` ADD COLUMN `modify_user` BIGINT DEFAULT NULL AFTER `create_user`;
-- ALTER TABLE `ops_member` ADD COLUMN `create_user_name` VARCHAR(64) DEFAULT NULL AFTER `modify_user`;
-- ALTER TABLE `ops_member` ADD COLUMN `modify_user_name` VARCHAR(64) DEFAULT NULL AFTER `create_user_name`;
-- ALTER TABLE `ops_member` ADD COLUMN `sort` INT DEFAULT 0 AFTER `modify_user_name`;
-- ALTER TABLE `ops_member` ADD COLUMN `remark` VARCHAR(500) DEFAULT NULL AFTER `sort`;

-- ═══ 6. 更新表注释 ═══
ALTER TABLE `ops_member` COMMENT='公众号平台会员';

-- ═══ 验证 ═══
-- SHOW CREATE TABLE ops_member;
-- SELECT COUNT(*) FROM ops_member;
