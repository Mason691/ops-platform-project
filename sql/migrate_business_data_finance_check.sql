-- 2026-11-03 增值业务-财务核对：ops_business_data 新增核对人/核对时间 + 收据号查询索引
-- 执行库：mall_db
-- 前置：sql/migrate_business_data_receipt_voucher.sql（is_received / voucher_url）
-- 部署顺序：本脚本必须先于新后端 JAR 上线执行（新 JAR 的实体 SELECT 引用新列）
-- 幂等：DDL 经 information_schema 条件化（生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS）
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4（中文注释防乱码）

-- ═══ DDL：财务核对人ID（sys_user.id） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'finance_check_user_id');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN finance_check_user_id BIGINT DEFAULT NULL COMMENT ''财务核对人ID（ai_platform.sys_user.id）'' AFTER fund_dynamic_rows',
    'SELECT ''finance_check_user_id already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：财务核对人姓名（冗余展示/追溯） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'finance_check_user_name');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN finance_check_user_name VARCHAR(50) DEFAULT NULL COMMENT ''财务核对人姓名'' AFTER finance_check_user_id',
    'SELECT ''finance_check_user_name already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：财务核对时间（非空即已核对；列表状态按此判断） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'finance_check_time');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN finance_check_time DATETIME DEFAULT NULL COMMENT ''财务核对时间（非空=已核对）'' AFTER finance_check_user_name',
    'SELECT ''finance_check_time already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：收据号查重索引（同 corp 内查重，与导入口径一致） ═══
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND INDEX_NAME = 'idx_corp_receipt');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE ops_business_data ADD INDEX idx_corp_receipt (corp_id, receipt_no)',
    'SELECT ''idx_corp_receipt already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
