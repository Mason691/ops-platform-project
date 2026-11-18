-- =====================================================================
-- 员工制单：支付信息 / 凭证信息拆分
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新 ops 后端 JAR 执行（新 JAR 会读写新列/新表）。
-- 说明：
--   1. 生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS，使用
--      information_schema 条件化 PREPARE，可重复执行。
--   2. 收据号沿用 ops_business_order.receipt_no；本脚本只新增
--      收据图片、支付凭证 URL 列表、合同附件 URL 列表。
--   3. ops_business_attachment 记录上传人/时间/原文件名/文件类型，
--      提交订单后回填 order_id，实现文件与订单的可追溯关联。
--   4. mysql 客户端必须带 --default-character-set=utf8mb4（中文注释防乱码）。
-- =====================================================================

USE mall_db;
-- docker exec mysql 默认 latin1（本项目已知陷阱）：显式声明连接字符集，
-- 避免中文列注释/表注释双重编码。
SET NAMES utf8mb4;
SET @db := DATABASE();

-- ── 1. 订单头新增凭证字段 ────────────────────────────────────────────
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'receipt_image_url');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN receipt_image_url VARCHAR(1000) DEFAULT NULL COMMENT ''收据图片URL（OCR 收据号识别用）'' AFTER receipt_no',
    'SELECT ''receipt_image_url already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'payment_voucher_urls');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN payment_voucher_urls VARCHAR(3000) DEFAULT NULL COMMENT ''支付凭证URL列表（逗号分隔，最多3张）'' AFTER receipt_image_url',
    'SELECT ''payment_voucher_urls already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'contract_attachment_urls');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN contract_attachment_urls VARCHAR(3000) DEFAULT NULL COMMENT ''合同附件URL列表（逗号分隔，最多5份）'' AFTER payment_voucher_urls',
    'SELECT ''contract_attachment_urls already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2. 业务附件上传留痕表 ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ops_business_attachment (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    corp_id               BIGINT        NOT NULL COMMENT '员工所属组织ID',
    order_id              BIGINT        DEFAULT NULL COMMENT '关联订单ID（提交后回填）',
    file_type             VARCHAR(20)   NOT NULL COMMENT 'RECEIPT=收据图片 / PAYMENT=支付凭证 / CONTRACT=合同附件',
    file_name             VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    file_url              VARCHAR(1000) NOT NULL COMMENT '相对访问地址（/uploads/ops/...）',
    file_size             BIGINT        DEFAULT NULL COMMENT '字节数（压缩后）',
    content_type          VARCHAR(100)  DEFAULT NULL COMMENT 'MIME',
    uploader_user_id      BIGINT        DEFAULT NULL COMMENT '上传人 sys_user.id',
    uploader_name         VARCHAR(100)  DEFAULT NULL COMMENT '上传人姓名/账号',
    upload_time           DATETIME      DEFAULT NULL COMMENT '上传时间',
    update_key            VARCHAR(64)   DEFAULT NULL,
    unique_id             VARCHAR(64)   DEFAULT NULL,
    create_time           DATETIME      DEFAULT NULL,
    modify_time           DATETIME      DEFAULT NULL,
    create_user           BIGINT        DEFAULT NULL,
    modify_user           BIGINT        DEFAULT NULL,
    create_user_name      VARCHAR(50)   DEFAULT NULL,
    modify_user_name      VARCHAR(50)   DEFAULT NULL,
    sort                  INT           DEFAULT 0,
    remark                VARCHAR(500)  DEFAULT NULL,
    delete_flag           INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_corp_type_time (corp_id, file_type, upload_time),
    KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='业务订单附件（收据图片/支付凭证/合同附件上传留痕）';

-- ── 3. 验证 ─────────────────────────────────────────────────────────
SELECT 'ops_business_order voucher columns' AS item, COUNT(*) AS cnt
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
  AND COLUMN_NAME IN ('receipt_image_url', 'payment_voucher_urls', 'contract_attachment_urls');

SELECT 'ops_business_attachment table' AS item, COUNT(*) AS cnt
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_attachment';
