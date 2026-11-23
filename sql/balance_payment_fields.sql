-- =====================================================================
-- 业务数据订单：货款补录明细字段增强（mall_db）
-- 执行前必须先备份：mysqldump ... mall_db ops_business_order > backup.sql
-- 生产执行必须带 --default-character-set=utf8mb4
--
-- 背景：定金支付订单补录尾款时需要记录尾款收款方式、收款日期、收据号，
--       并为“最近提醒时间”提供落库字段（定时提醒任务写入）。
-- 幂等：列已存在则跳过；本脚本只新增可空列，旧代码/旧数据不受影响。
-- =====================================================================

USE mall_db;

SET @has_columns := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME IN ('balance_payment_method', 'balance_receipt_date',
                          'balance_receipt_no', 'balance_remind_time'));

SET @ddl := IF(@has_columns = 0,
    'ALTER TABLE ops_business_order '
    'ADD COLUMN balance_payment_method VARCHAR(20) NULL COMMENT ''尾款收款方式：QR/CASH/TRANSFER'' AFTER balance_remark, '
    'ADD COLUMN balance_receipt_date VARCHAR(50) NULL COMMENT ''尾款收款日期 yyyy-MM-dd'' AFTER balance_payment_method, '
    'ADD COLUMN balance_receipt_no VARCHAR(50) NULL COMMENT ''尾款收据号'' AFTER balance_receipt_date, '
    'ADD COLUMN balance_remind_time DATETIME NULL COMMENT ''最近一次尾款补录提醒时间'' AFTER balance_receipt_no',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 验证：四列均存在
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order'
  AND COLUMN_NAME IN ('balance_payment_method', 'balance_receipt_date',
                      'balance_receipt_no', 'balance_remind_time')
ORDER BY ORDINAL_POSITION;
