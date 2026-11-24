-- =====================================================================
-- 回滚：向上采购申请
-- 执行库：mall_db
-- 警告：DROP TABLE 会丢失全部向上采购申请记录；仅在确认无需该能力时执行。
-- =====================================================================

USE mall_db;
SET NAMES utf8mb4;

DROP TABLE IF EXISTS ops_upward_purchase;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order_item'
      AND COLUMN_NAME = 'is_upward_purchase');
SET @ddl := IF(@has_col = 1,
    'ALTER TABLE ops_business_order_item DROP COLUMN is_upward_purchase',
    'SELECT ''is_upward_purchase not exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT 'rollback' AS check_name, 'done' AS value;
