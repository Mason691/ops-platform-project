-- 灶具信息表关联方式重构：user_no → gas_user_id（2026-08-27，第一段）
-- 在 corp_id 后新增 gas_user_id BIGINT（关联 ops_gas_user.id）+ 索引，
-- 并按旧 user_no（= sb_gas_user.id = ops_gas_user.user_no）回填存量 gas_user_id。
-- 匹配规则（用户 2026-08-27 确认；需求字面 gas_company_id 实测匹配 0 条不可用）：
--   ops_gas_user.gas_company_id = sb_gas_stoves_info.gas_the_company_id
--   ops_gas_user.gas_supply_certificate_number = sb_gas_stoves_info.user_number
-- 本段对运行中的旧 JAR 无影响（只加列不删列），可先行执行。
-- 幂等：字段已存在时跳过。执行方式：
--   mysql -uroot -p --default-character-set=utf8mb4 mall_db < migrate_gas_stoves_gas_user_id.sql
USE mall_db;

DROP PROCEDURE IF EXISTS migrate_add_gas_user_id;
DELIMITER $$
CREATE PROCEDURE migrate_add_gas_user_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_gas_stoves_info' AND COLUMN_NAME = 'gas_user_id'
    ) THEN
        ALTER TABLE ops_gas_stoves_info
            ADD COLUMN gas_user_id BIGINT DEFAULT NULL
            COMMENT '关联燃气用户ID（ops_gas_user.id；匹配条件：u.gas_company_id=s.gas_the_company_id AND u.gas_supply_certificate_number=s.user_number）'
            AFTER corp_id,
            ADD INDEX idx_gas_user_id (gas_user_id);
    END IF;

    -- 存量回填：user_no → gas_user_id（user_no 已实测全局唯一，两侧显式 COLLATE 防 1267 排序冲突）
    -- 仅当 user_no 列仍存在时执行（第二段脚本删除 user_no 后本脚本重跑不应报错）
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_gas_stoves_info' AND COLUMN_NAME = 'user_no'
    ) THEN
        UPDATE mall_db.ops_gas_stoves_info s
        JOIN mall_db.ops_gas_user u
          ON u.user_no COLLATE utf8mb4_0900_ai_ci = s.user_no COLLATE utf8mb4_0900_ai_ci
         AND u.delete_flag = 0
        SET s.gas_user_id = u.id
        WHERE s.delete_flag = 0;
    END IF;
END$$
DELIMITER ;
CALL migrate_add_gas_user_id();
DROP PROCEDURE migrate_add_gas_user_id;
