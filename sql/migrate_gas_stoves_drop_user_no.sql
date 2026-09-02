-- 灶具信息表关联方式重构：删除旧 user_no（2026-08-27，第二段）
-- ⚠️ 必须在新 JAR 部署完成后再执行（旧 JAR 仍查询 user_no 列，先删会导致迁移/列表报错）。
-- MySQL DROP COLUMN 会自动删除仅含该列的索引 idx_user_no。
-- 幂等：字段不存在时跳过。执行方式：
--   mysql -uroot -p --default-character-set=utf8mb4 mall_db < migrate_gas_stoves_drop_user_no.sql
USE mall_db;

DROP PROCEDURE IF EXISTS migrate_drop_user_no;
DELIMITER $$
CREATE PROCEDURE migrate_drop_user_no()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_gas_stoves_info' AND COLUMN_NAME = 'user_no'
    ) THEN
        ALTER TABLE ops_gas_stoves_info DROP COLUMN user_no;
    END IF;
END$$
DELIMITER ;
CALL migrate_drop_user_no();
DROP PROCEDURE migrate_drop_user_no;
