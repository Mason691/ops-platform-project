-- 2026-11-17 业务订单以旧换新：订单/明细新增抵扣字段 + 旧品明细表
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新 ops / SCM JAR 上线执行（新实体 SELECT 引用新列）
-- 幂等：DDL 经 information_schema 条件化（生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS）
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4

-- ═══ 明细：是否以旧换新 ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
      AND COLUMN_NAME = 'is_trade_in');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN is_trade_in VARCHAR(2) NOT NULL DEFAULT ''否'' COMMENT ''是否以旧换新（是/否）'' AFTER receipt_amount',
    'SELECT ''is_trade_in already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 明细：抵扣金额（同一产品多经办人行写相同值，汇总按产品组 MAX 去重） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
      AND COLUMN_NAME = 'deduction_amount');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN deduction_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT ''以旧换新抵扣金额'' AFTER is_trade_in',
    'SELECT ''deduction_amount already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 明细：抵扣后金额 = 收款金额（销售价×数量） - 抵扣金额；提成仍按收款金额 ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
      AND COLUMN_NAME = 'trade_in_payable_amount');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN trade_in_payable_amount DECIMAL(12,2) DEFAULT NULL COMMENT ''抵扣后金额=收款金额-抵扣金额'' AFTER deduction_amount',
    'SELECT ''trade_in_payable_amount already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 订单头：是否含以旧换新 ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'has_trade_in');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN has_trade_in TINYINT NOT NULL DEFAULT 0 COMMENT ''是否含以旧换新（0否1是）'' AFTER total_commission',
    'SELECT ''has_trade_in already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 订单头：抵扣合计（按产品组 MAX 去重） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'trade_in_deduction_amount');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN trade_in_deduction_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT ''以旧换新抵扣合计'' AFTER has_trade_in',
    'SELECT ''trade_in_deduction_amount already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 订单头：应付合计 = total_amount - 抵扣合计 ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'payable_amount');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN payable_amount DECIMAL(12,2) DEFAULT NULL COMMENT ''应付合计=订单金额-抵扣'' AFTER trade_in_deduction_amount',
    'SELECT ''payable_amount already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 存量订单应付=订单金额
UPDATE ops_business_order SET payable_amount = total_amount
WHERE payable_amount IS NULL;

-- ═══ 订单头：以旧换新自动采购单状态/结果（跨服务一致性标记） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'trade_in_purchase_status');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN trade_in_purchase_status VARCHAR(16) DEFAULT NULL COMMENT ''PENDING/SUCCESS/FAILED'' AFTER payable_amount',
    'SELECT ''trade_in_purchase_status already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'trade_in_purchase_order_id');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN trade_in_purchase_order_id BIGINT DEFAULT NULL COMMENT ''关联 SCM 采购订单ID'' AFTER trade_in_purchase_status',
    'SELECT ''trade_in_purchase_order_id already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'trade_in_purchase_bill_no');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN trade_in_purchase_bill_no VARCHAR(64) DEFAULT NULL COMMENT ''关联 SCM 采购订单号'' AFTER trade_in_purchase_order_id',
    'SELECT ''trade_in_purchase_bill_no already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'trade_in_purchase_error');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN trade_in_purchase_error VARCHAR(500) DEFAULT NULL COMMENT ''采购单创建失败原因'' AFTER trade_in_purchase_bill_no',
    'SELECT ''trade_in_purchase_error already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 旧品明细表 ═══
CREATE TABLE IF NOT EXISTS ops_business_order_trade_in (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    corp_id             BIGINT       NOT NULL,
    order_id            BIGINT       NOT NULL COMMENT 'ops_business_order.id',
    product_key         VARCHAR(512) NOT NULL COMMENT '销售商品行分组键（category|brand|model|price）',
    source_item_id      BIGINT       DEFAULT NULL COMMENT '销售商品行代表明细ID',
    old_product_id      BIGINT       DEFAULT NULL COMMENT 'SCM 商品ID（手工旧品为空）',
    old_material_number VARCHAR(64)  DEFAULT NULL COMMENT 'SCM 商品编码（手工旧品为空）',
    old_product_name    VARCHAR(200) NOT NULL COMMENT '旧品名称',
    brand               VARCHAR(100) DEFAULT NULL,
    model               VARCHAR(200) DEFAULT NULL,
    category            VARCHAR(100) DEFAULT NULL,
    quantity            INT          NOT NULL DEFAULT 1,
    unit                VARCHAR(50)  DEFAULT NULL,
    remark              VARCHAR(500) DEFAULT NULL,
    is_manual           TINYINT      NOT NULL DEFAULT 0 COMMENT '1=其他旧品手工录入',
    update_key          VARCHAR(64)  DEFAULT NULL,
    unique_id           VARCHAR(64)  DEFAULT NULL,
    create_time         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    modify_time         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_user         BIGINT       DEFAULT NULL,
    modify_user         BIGINT       DEFAULT NULL,
    create_user_name    VARCHAR(64)  DEFAULT NULL,
    modify_user_name    VARCHAR(64)  DEFAULT NULL,
    sort                INT          NOT NULL DEFAULT 0,
    delete_flag         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_trade_in_order (corp_id, order_id),
    KEY idx_trade_in_product (order_id, product_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务订单以旧换新旧品明细';
