-- 2026-11-09 员工手工新增业务：订单编号 + 月度批次
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新后端 JAR 上线执行（新 JAR 的 INSERT/SELECT 引用新列）
-- 幂等：DDL 经 information_schema 条件化（生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS）
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4（中文注释防乱码）
--
-- order_no：一次提交的所有「产品×经办人」明细共享同一订单编号（dd + 全局流水号）。
-- ops_order_sequence：全局订单流水，单行原子递增，事务回滚不烧号。

-- ═══ DDL：订单编号 ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'order_no');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN order_no VARCHAR(20) DEFAULT NULL COMMENT ''订单编号（dd+流水号，同一次提交所有明细共享）'' AFTER batch_no',
    'SELECT ''order_no already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：订单编号索引（按订单聚合/查询） ═══
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND INDEX_NAME = 'idx_corp_order');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE ops_business_data ADD INDEX idx_corp_order (corp_id, order_no)',
    'SELECT ''idx_corp_order already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：全局订单流水表 ═══
CREATE TABLE IF NOT EXISTS ops_order_sequence (
    id          TINYINT  NOT NULL COMMENT '固定为1',
    last_seq    BIGINT   NOT NULL DEFAULT 0 COMMENT '已分配最大流水号',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务订单编号全局流水';

INSERT IGNORE INTO ops_order_sequence (id, last_seq) VALUES (1, 0);
