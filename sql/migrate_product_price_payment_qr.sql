-- =====================================================================
-- 产品售价 + 特批文件 + 支付方式 + 收款码防篡改
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新 ops 后端 JAR 执行（新 JAR 会读写这些列/表）。
-- 说明：
--   1. 生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS，
--      使用 information_schema 条件化 PREPARE，可重复执行。
--   2. 历史订单 is_received 保留不用；新单 payment_method 落库。
--   3. 产品售价按运营商组织（corp_id）维护；城市合伙人读取父运营商配置。
-- =====================================================================

USE mall_db;
SET @db := DATABASE();

-- ── 1. 产品售价 ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ops_product_price (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    corp_id               BIGINT        NOT NULL COMMENT '运营商组织ID（售价归属）',
    product_id            BIGINT        NOT NULL COMMENT 'SCM 商品信息ID（scm_bd_material.id）',
    product_name          VARCHAR(200)  DEFAULT NULL COMMENT 'SCM 商品名称（冗余展示）',
    brand                 VARCHAR(100)  NOT NULL COMMENT '品牌（冗余自 SCM 商品信息）',
    model                 VARCHAR(200)  NOT NULL COMMENT '规格型号（冗余自 SCM 商品信息）',
    category              VARCHAR(64)   NOT NULL COMMENT '品类（PRODUCT_CATEGORY 字典 itemKey）',
    sale_price            DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '销售价（标准售价）',
    retail_floor_price    DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '零售底价',
    group_lowest_price    DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '集团最低价',
    status                INT           NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
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
    UNIQUE KEY uk_corp_product (corp_id, product_id),
    KEY idx_corp_brand_category (corp_id, brand, category),
    KEY idx_corp_model (corp_id, model),
    KEY idx_corp_status (corp_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='增值业务产品售价（按 SCM 商品）';

-- ── 2. 特批文件（低价制单附件，可追溯） ─────────────────────────────
CREATE TABLE IF NOT EXISTS ops_business_special_file (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    corp_id               BIGINT        NOT NULL COMMENT '员工所属组织ID',
    file_name             VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    file_url              VARCHAR(1000) NOT NULL COMMENT '相对访问地址（/uploads/...）',
    file_size             BIGINT        DEFAULT NULL COMMENT '字节数',
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
    KEY idx_corp_upload_time (corp_id, upload_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='业务数据低价特批文件（上传留痕）';

-- ── 3. 收款码配置（按运营商组织；哈希用于防篡改） ───────────────────
CREATE TABLE IF NOT EXISTS ops_payment_qr_config (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    corp_id               BIGINT        NOT NULL COMMENT '运营商组织ID',
    file_name             VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    file_path             VARCHAR(500)  NOT NULL COMMENT '相对上传根目录的路径（不对外暴露）',
    file_hash             CHAR(64)      NOT NULL COMMENT 'SHA-256',
    file_size             BIGINT        DEFAULT NULL,
    content_type          VARCHAR(100)  DEFAULT NULL,
    uploader_user_id      BIGINT        DEFAULT NULL,
    uploader_name         VARCHAR(100)  DEFAULT NULL,
    upload_time           DATETIME      DEFAULT NULL,
    status                INT           NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
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
    UNIQUE KEY uk_corp (corp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='收款码配置（哈希防篡改）';

-- ── 4. ops_business_order 支付方式 ──────────────────────────────────
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD COLUMN payment_method VARCHAR(20) NULL COMMENT ''支付方式：QR=二维码/CASH=现金/TRANSFER=转账'' AFTER is_received',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND COLUMN_NAME = 'payment_method');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD KEY idx_corp_payment (corp_id, payment_method)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND INDEX_NAME = 'idx_corp_payment');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 5. ops_business_order_item 支付方式 / 标准价 / 特批文件 ─────────
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN standard_sale_price DECIMAL(14,2) NULL COMMENT ''制单时产品售价表标准销售价（审计留存）'' AFTER sale_price',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = 'standard_sale_price');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN payment_method VARCHAR(20) NULL COMMENT ''支付方式：QR/CASH/TRANSFER'' AFTER is_received',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = 'payment_method');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN special_file_id BIGINT NULL COMMENT ''特批文件ID（ops_business_special_file.id）'' AFTER commission_rate',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = 'special_file_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN special_file_url VARCHAR(1000) NULL COMMENT ''特批文件访问地址'' AFTER special_file_id',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = 'special_file_url');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN special_file_name VARCHAR(255) NULL COMMENT ''特批文件原始文件名'' AFTER special_file_url',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = 'special_file_name');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order_item ADD KEY idx_corp_payment (corp_id, payment_method)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order_item' AND INDEX_NAME = 'idx_corp_payment');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'product_price_payment_qr migration done' AS result;
