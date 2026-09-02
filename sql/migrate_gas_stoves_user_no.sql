-- 燃具信息表关联燃气用户增量迁移（2026-08-27）
-- 在 corp_id 后新增 user_no（与 ops_gas_user.user_no 同型），建立 ops_gas_stoves_info ↔ ops_gas_user 关联。
-- 幂等脚本：已存在字段时跳过（MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，用存储过程兜底）。
-- 执行方式：
--   mysql -uroot -p --default-character-set=utf8mb4 mall_db < migrate_gas_stoves_user_no.sql
USE mall_db;

DROP PROCEDURE IF EXISTS migrate_add_user_no;
DELIMITER $$
CREATE PROCEDURE migrate_add_user_no()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_gas_stoves_info' AND COLUMN_NAME = 'user_no'
    ) THEN
        ALTER TABLE ops_gas_stoves_info
            ADD COLUMN user_no VARCHAR(50) DEFAULT NULL COMMENT '关联用户号（= sb_gas_user.id = ops_gas_user.user_no；关联条件：u.gas_company_id = s.gas_the_company_id AND u.gas_supply_certificate_number = s.user_number）' AFTER corp_id,
            ADD INDEX idx_user_no (user_no);
    END IF;
END$$
DELIMITER ;
CALL migrate_add_user_no();
DROP PROCEDURE migrate_add_user_no;
