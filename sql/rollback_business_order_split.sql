-- 2026-11-11 业务数据主子表拆分 — 回滚脚本
-- 执行库：mall_db
-- ⚠ 破坏性：会删除主表/退货表及子表新增列（退货数据不可恢复）。仅在迁移失败时使用。
-- 执行顺序：停新 ops 后端 → 本脚本 → 回滚到旧 JAR。
--
-- 前置：确认已备份 ops_business_data（迁移前）与 ops_business_order_item（迁移后）。

SET SESSION group_concat_max_len = 1000000;

-- 1. 删除兼容视图
DROP VIEW IF EXISTS ops_business_data;

-- 2. 子表改回原名（若当前仍是子表）
SET @t := (SELECT TABLE_TYPE FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item');
SET @ddl := IF(@t = 'BASE TABLE',
    'RENAME TABLE ops_business_order_item TO ops_business_data',
    'SELECT ''ops_business_order_item missing'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3. 删除子表新增列（条件化）
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_data' AND COLUMN_NAME='order_id');
SET @ddl := IF(@has>0, 'ALTER TABLE ops_business_data DROP COLUMN order_id', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_data' AND COLUMN_NAME='is_return');
SET @ddl := IF(@has>0,
    'ALTER TABLE ops_business_data DROP COLUMN is_return, DROP COLUMN return_quantity, '
    'DROP COLUMN return_amount, DROP COLUMN settle_quantity, DROP COLUMN return_status, '
    'DROP COLUMN original_receipt_amount', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 4. 删除新表
DROP TABLE IF EXISTS ops_business_return;
DROP TABLE IF EXISTS ops_business_order;

SELECT 'rollback done' AS msg;
