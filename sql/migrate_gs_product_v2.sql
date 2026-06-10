-- ===========================================================
-- gs_product 表重构 — 从全量商品表改为关系映射表
--
-- 定位：gs_product 是一条关系映射表，而非全量同步。
-- 通过 mall_product_id 关联 mall_product 表中的商品，
-- 由运营商（operator_id）选择引入聚合平台展示。
--
-- 执行前请确认：
-- 1. gs_product 旧表中无重要数据（或已备份）
-- 2. 本脚本已在生产环境（104.238.165.57）执行通过
-- 3. 金厨美家运营商 corp_id=4，金粮源公司1 corp_id=3
-- ===========================================================
USE `mall_db`;

-- ═══ 1. 扩展 mall_product 表（如缺失平台所需字段） ═══
-- mall_product 是商城商品主表，可能已存在。本脚本仅追加缺失列。
ALTER TABLE mall_product ADD COLUMN points_price INT NOT NULL DEFAULT 0 COMMENT '积分价格' AFTER sale_price;
ALTER TABLE mall_product ADD COLUMN cash_price DECIMAL(10,2) DEFAULT 0.00 COMMENT '现金价格' AFTER points_price;
ALTER TABLE mall_product ADD COLUMN max_points_deduct INT DEFAULT 0 COMMENT '最大积分抵扣' AFTER cash_price;
ALTER TABLE mall_product ADD COLUMN click_count INT DEFAULT 0 COMMENT '点击量' AFTER max_points_deduct;

-- ═══ 2. 确保种子商品存在（id=1004, 1005, corp_id=3 金粮源公司1） ═══
INSERT INTO mall_product (id, corp_id, name, cover_image, description, sale_price, points_price, cash_price, status)
VALUES (1004, 3, '金粮源优选大米5kg', '/static/product/rice-5kg.png', '金粮源公司精选东北优质大米，5公斤装，颗粒饱满，口感香糯', 0, 300, 0, 1)
ON DUPLICATE KEY UPDATE name='金粮源优选大米5kg', cover_image='/static/product/rice-5kg.png', points_price=300, cash_price=0;

INSERT INTO mall_product (id, corp_id, name, cover_image, description, sale_price, points_price, cash_price, status)
VALUES (1005, 3, '金粮源食用油4L', '/static/product/oil-4l.png', '金粮源公司高品质食用植物油，4升装，非转基因，健康之选', 0, 500, 0, 1)
ON DUPLICATE KEY UPDATE name='金粮源食用油4L', cover_image='/static/product/oil-4l.png', points_price=500, cash_price=0;

-- ═══ 3. 重建 gs_product 为关系映射表 ═══
-- 备份（如旧表有数据）
-- CREATE TABLE gs_product_backup AS SELECT * FROM gs_product;

DROP TABLE IF EXISTS gs_product;

CREATE TABLE gs_product (
    id              BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '映射记录主键',
    mall_product_id BIGINT   NOT NULL                         COMMENT '关联 mall_product.id',
    operator_id     BIGINT   NOT NULL                         COMMENT '运营商ID（corp_id，引入该商品的组织）',
    shelf_status    TINYINT  NOT NULL DEFAULT 1               COMMENT '上下架状态 1=上架 0=下架',
    category_id     BIGINT   DEFAULT NULL                     COMMENT '运营商自定义分类ID（关联 biz_product_category.id）',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP        COMMENT '引入时间',
    UNIQUE KEY uk_operator_product (operator_id, mall_product_id),
    INDEX idx_operator_shelf (operator_id, shelf_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营商商品关系映射表';

-- ═══ 4. 初始化映射数据 ═══
-- 金厨美家运营商（corp_id=4）引入金粮源公司1 的商品 1004、1005
INSERT INTO gs_product (mall_product_id, operator_id, shelf_status) VALUES
(1004, 4, 1),
(1005, 4, 1);

-- ═══ 5. 确保运营商-销售商绑定关系存在 ═══
INSERT IGNORE INTO biz_operator_merchant (operator_id, merchant_id, status) VALUES (4, 3, 1);

-- ═══ 验证 ═══
SELECT '=== gs_product 映射表 ===' AS '';
SELECT * FROM gs_product;
SELECT '=== mall_product 数据 ===' AS '';
SELECT id, corp_id, name, points_price, cash_price FROM mall_product WHERE id IN (1004,1005);
SELECT '=== 绑定关系 ===' AS '';
SELECT * FROM biz_operator_merchant WHERE status=1;
