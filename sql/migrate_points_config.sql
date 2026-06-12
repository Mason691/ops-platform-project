-- 积分兑换配置迁移
-- 1. ops_product 增加运营配置字段
-- 2. mall_product 增加 sale_price（如不存在）

-- ============================================================
-- 运营商商品映射表：增加积分兑换配置
-- ============================================================
ALTER TABLE `ops_product`
    ADD COLUMN `points`          INT          NOT NULL DEFAULT 0       COMMENT '积分数量（运营配置）' AFTER `category_id`,
    ADD COLUMN `exchange_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '兑换金额（运营配置）' AFTER `points`;

-- ============================================================
-- 商城商品主表：增加销售价（如实际表已存在则可忽略此步的重复列错误）
-- ============================================================
-- 通过存储过程安全添加，避免重复列报错
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS add_sale_price_if_missing()
BEGIN
    IF NOT EXISTS (SELECT * FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'mall_product' AND COLUMN_NAME = 'sale_price') THEN
        ALTER TABLE `mall_product`
            ADD COLUMN `sale_price` DECIMAL(10,2) DEFAULT NULL COMMENT '销售价（销售商提供）' AFTER `max_points_deduct`;
    END IF;
END //
DELIMITER ;
CALL add_sale_price_if_missing();
DROP PROCEDURE IF EXISTS add_sale_price_if_missing;
