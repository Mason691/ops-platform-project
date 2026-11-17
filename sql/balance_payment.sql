-- =====================================================================
-- 业务数据订单：收款方式增强（mall_db）
-- 执行前必须先备份：mysqldump ... mall_db ops_business_order > backup.sql
-- 生产执行必须带 --default-character-set=utf8mb4
--
-- 语义（不可混用）：
--   payment_type      制单时选择的付款方式：FULL 全款支付 / DEPOSIT 定金支付（制单后不变）
--   is_full_payment   当前是否已收齐全款：全款=1；定金初始=0，货款补录后=1
--   派工出库准入看 is_full_payment，不看 payment_type。
-- 幂等：列/索引存在则跳过；历史数据回填 FULL / 1 / 0 / 0。
-- =====================================================================

USE mall_db;

-- ── 1. 新增收款字段（整组添加；MySQL 8 DDL 原子性保证不会半套）──
SET @has_payment := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='mall_db' AND TABLE_NAME='ops_business_order'
      AND COLUMN_NAME='payment_type');
SET @ddl := IF(@has_payment=0,
    'ALTER TABLE ops_business_order '
    'ADD COLUMN payment_type VARCHAR(16) NOT NULL DEFAULT ''FULL'' COMMENT ''FULL全款支付/DEPOSIT定金支付'' AFTER payment_method, '
    'ADD COLUMN is_full_payment TINYINT NOT NULL DEFAULT 1 COMMENT ''是否全款：0否/1是'' AFTER payment_type, '
    'ADD COLUMN deposit_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''定金金额'' AFTER is_full_payment, '
    'ADD COLUMN balance_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''应补余款=销售价-定金金额'' AFTER deposit_amount, '
    'ADD COLUMN balance_received_amount DECIMAL(18,2) NULL COMMENT ''实收余款'' AFTER balance_amount, '
    'ADD COLUMN balance_paid_at DATETIME NULL COMMENT ''余款补齐时间'' AFTER balance_received_amount, '
    'ADD COLUMN balance_voucher_url VARCHAR(500) NULL COMMENT ''余款收款凭证URL'' AFTER balance_paid_at, '
    'ADD COLUMN balance_remark VARCHAR(500) NULL COMMENT ''收款备注'' AFTER balance_voucher_url',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 2. 货款补录查询索引（组织 + 定金待补）──
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA='mall_db' AND TABLE_NAME='ops_business_order'
      AND INDEX_NAME='idx_payment_pending');
SET @ddl := IF(@has_idx=0,
    'ALTER TABLE ops_business_order ADD INDEX idx_payment_pending (corp_id, payment_type, is_full_payment)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 3. 历史订单回填：全部视为已全款，避免改造后派工被误伤 ──
UPDATE ops_business_order
SET payment_type = 'FULL',
    is_full_payment = 1,
    deposit_amount = 0,
    balance_amount = 0
WHERE delete_flag = 0
  AND (payment_type IS NULL OR payment_type = '' OR is_full_payment IS NULL);

-- ── 4. 验证 ─────────────────────────────────────────────────────────
SELECT payment_type, is_full_payment, COUNT(*) AS cnt
FROM ops_business_order
WHERE delete_flag = 0
GROUP BY payment_type, is_full_payment;
