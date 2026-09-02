-- 燃气用户表增加燃气公司ID（2026-08-27）
-- ops_gas_user 新增 gas_company_id（varchar(32)，镜像外部 sb_gas_user.gas_company_id 类型），
-- 供灶具信息按公司+供气证号关联燃气用户（ops_gas_user.gas_company_id = sb_gas_stoves_info.gas_company_id
-- AND ops_gas_user.gas_supply_certificate_number = sb_gas_stoves_info.user_number）。
-- 幂等脚本：已存在字段时跳过（MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，用存储过程兜底）。
-- 执行方式：
--   mysql -uroot -p --default-character-set=utf8mb4 mall_db < migrate_gas_user_company_id.sql
USE mall_db;

DROP PROCEDURE IF EXISTS migrate_add_gas_company_id;
DELIMITER $$
CREATE PROCEDURE migrate_add_gas_company_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_gas_user' AND COLUMN_NAME = 'gas_company_id'
    ) THEN
        ALTER TABLE ops_gas_user
            ADD COLUMN gas_company_id VARCHAR(32) DEFAULT NULL
            COMMENT '燃气公司ID（外部系统 sb_gas_user.gas_company_id，供灶具关联匹配）'
            AFTER gas_company_name;
    END IF;
END$$
DELIMITER ;
CALL migrate_add_gas_company_id();
DROP PROCEDURE migrate_add_gas_company_id;
