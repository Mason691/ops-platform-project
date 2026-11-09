-- 2026-11-09 员工手工新增业务：业务组号（产品×经办人拆行共享）
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新后端 JAR 上线执行（新 JAR 的 INSERT/SELECT 引用新列）
-- 幂等：DDL 经 information_schema 条件化（生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS）
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4（中文注释防乱码）
--
-- 用途：员工手工新增一笔业务时，为该笔业务的所有「产品×经办人」行写入同一组号。
-- 提成均分与统计去重优先按组号分组，避免财务补录收据号之前计算提成/统计导致：
--   ① 多经办人行各自按全额计提；② 销售额重复计入。
-- 历史导入行组号为 NULL，继续按收据号分组，行为不变。

-- ═══ DDL：业务组号 ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'business_group_no');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN business_group_no VARCHAR(40) DEFAULT NULL COMMENT ''员工手工业务组号（产品×经办人拆行共享）'' AFTER batch_no',
    'SELECT ''business_group_no already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：组号查询索引（提成/统计按 corp+组号 分组） ═══
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND INDEX_NAME = 'idx_corp_group');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE ops_business_data ADD INDEX idx_corp_group (corp_id, business_group_no)',
    'SELECT ''idx_corp_group already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
