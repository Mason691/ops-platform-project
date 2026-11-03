-- 2026-11-03 员工手工新增业务：收款凭证字段 + 批次流水号表
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新后端 JAR 上线执行（新 JAR 的 INSERT/SELECT 引用新列）
-- 幂等：DDL 经 information_schema 条件化（生产 MySQL 8.0.46 不支持
--       ADD COLUMN IF NOT EXISTS，已实测报 1064）
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4（中文注释防乱码）

-- ═══ DDL：是否收款（是/否） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'is_received');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN is_received VARCHAR(2) DEFAULT NULL COMMENT ''是否收款（是/否）'' AFTER receipt_no',
    'SELECT ''is_received already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：收款凭证 URL（多张英文逗号分隔，最多 3 张） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'voucher_url');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN voucher_url VARCHAR(1000) DEFAULT NULL COMMENT ''收款凭证URL（最多3张，英文逗号分隔）'' AFTER is_received',
    'SELECT ''voucher_url already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：批次查询索引（条件化） ═══
-- 员工手工录入的批次列表/批次归属校验按 (corp_id, batch_no) 过滤
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND INDEX_NAME = 'idx_corp_batch');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE ops_business_data ADD INDEX idx_corp_batch (corp_id, batch_no)',
    'SELECT ''idx_corp_batch already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：批次流水号表 ═══
-- 员工手工新建批次：{公司简称}-{yyyyMMdd}-{3位流水号}
-- 取号使用 INSERT ... ON DUPLICATE KEY UPDATE last_seq = last_seq + 1，行锁串行化，
-- 禁止 SELECT MAX+1（并发重号）。唯一键 (corp_id, seq_date) 限定组织+日期。
CREATE TABLE IF NOT EXISTS ops_batch_sequence (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    corp_id     BIGINT       NOT NULL COMMENT '组织ID',
    seq_date    CHAR(8)      NOT NULL COMMENT '日期 yyyyMMdd',
    last_seq    INT          NOT NULL DEFAULT 0 COMMENT '当日已分配最大流水号',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    modify_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    delete_flag TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_corp_date (corp_id, seq_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务数据批次流水号';
