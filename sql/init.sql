-- ===========================================================
-- 金地燃气聚合展示引流平台 — 数据库初始化
-- 目标库: mall_db (共用)
-- ===========================================================
USE `mall_db`;

-- 1. 公众号平台会员表（关联底座 sys_member.platform_member_id）
CREATE TABLE IF NOT EXISTS `ops_member` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `corp_id`             BIGINT       NOT NULL DEFAULT 3,
    `phone`               VARCHAR(20)  NOT NULL COMMENT '手机号',
    `password`            VARCHAR(255) DEFAULT NULL COMMENT 'BCrypt加密密码',
    `openid`              VARCHAR(100) DEFAULT NULL COMMENT '微信openid',
    `unionid`             VARCHAR(100) DEFAULT NULL COMMENT '微信unionid',
    `nickname`            VARCHAR(64)  DEFAULT '燃气用户',
    `avatar`              VARCHAR(500) DEFAULT NULL,
    `points_balance`      INT          NOT NULL DEFAULT 0,
    `platform_member_id`  BIGINT       DEFAULT NULL COMMENT '底座平台会员ID(sys_member.id)',
    `status`              TINYINT      NOT NULL DEFAULT 1,
    `last_login_time`     DATETIME     DEFAULT NULL,
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `modify_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `delete_flag`         INT          NOT NULL DEFAULT 0,
    UNIQUE KEY `uk_corp_phone` (`corp_id`, `phone`),
    INDEX `idx_corp_openid` (`corp_id`, `openid`),
    INDEX `idx_platform_member` (`platform_member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公众号平台会员';

-- 2. 积分流水
CREATE TABLE IF NOT EXISTS `ops_points_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY, `corp_id` BIGINT NOT NULL DEFAULT 3,
    `user_id` BIGINT NOT NULL, `change_type` VARCHAR(20) NOT NULL COMMENT 'GAIN/CONSUME/REFUND',
    `amount` INT NOT NULL, `balance_after` INT NOT NULL,
    `order_no` VARCHAR(64) DEFAULT NULL, `remark` VARCHAR(200) DEFAULT NULL,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 商品关系映射表（ops_product：运营商引入 mall_product 商品的关联记录）
CREATE TABLE IF NOT EXISTS `ops_product` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '映射记录主键',
    `mall_product_id` BIGINT   NOT NULL                         COMMENT '关联 mall_product.id',
    `operator_id`     BIGINT   NOT NULL                         COMMENT '运营商ID（引入该商品的组织）',
    `shelf_status`    TINYINT  NOT NULL DEFAULT 1               COMMENT '上下架状态 1=上架 0=下架',
    `category_id`     BIGINT   DEFAULT NULL                     COMMENT '运营商自定义分类ID',
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP        COMMENT '引入时间',
    UNIQUE KEY `uk_operator_product` (`operator_id`, `mall_product_id`),
    INDEX `idx_operator_shelf` (`operator_id`, `shelf_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营商商品关系映射表';

-- mall_product 表（商城商品主表，所有销售商共用）
CREATE TABLE IF NOT EXISTS `mall_product` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `corp_id`           BIGINT       NOT NULL DEFAULT 3       COMMENT '归属组织ID',
    `name`              VARCHAR(128) NOT NULL                 COMMENT '商品名称',
    `cover_image`       VARCHAR(500) DEFAULT NULL             COMMENT '封面图',
    `images`            TEXT         DEFAULT NULL             COMMENT '商品图片集（JSON数组）',
    `video_url`         VARCHAR(500) DEFAULT NULL             COMMENT '视频链接',
    `description`       TEXT         DEFAULT NULL             COMMENT '商品描述',
    `points_price`      INT          NOT NULL DEFAULT 0       COMMENT '积分价格',
    `cash_price`        DECIMAL(10,2) DEFAULT 0.00           COMMENT '现金价格',
    `max_points_deduct` INT          DEFAULT 0               COMMENT '最大积分抵扣',
    `click_count`       INT          DEFAULT 0               COMMENT '点击量',
    `status`            TINYINT      NOT NULL DEFAULT 1       COMMENT '状态 1=正常 0=禁用',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `delete_flag`       INT          NOT NULL DEFAULT 0,
    INDEX `idx_corp_status` (`corp_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城商品主表（所有销售商共用）';

-- 4. 活动
CREATE TABLE IF NOT EXISTS `ops_activity` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY, `corp_id` BIGINT NOT NULL DEFAULT 3,
    `title` VARCHAR(128) NOT NULL, `cover_image` VARCHAR(500) DEFAULT NULL,
    `images` TEXT DEFAULT NULL, `video_url` VARCHAR(500) DEFAULT NULL,
    `detail` TEXT DEFAULT NULL, `category` VARCHAR(50) DEFAULT NULL,
    `start_time` DATETIME DEFAULT NULL, `end_time` DATETIME DEFAULT NULL,
    `location` VARCHAR(200) DEFAULT NULL, `points_reward` INT DEFAULT 0,
    `max_participants` INT DEFAULT NULL, `status` TINYINT NOT NULL DEFAULT 1,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP, `delete_flag` INT NOT NULL DEFAULT 0,
    INDEX `idx_corp_status` (`corp_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 订单
CREATE TABLE IF NOT EXISTS `ops_order` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY, `corp_id` BIGINT NOT NULL DEFAULT 3,
    `user_id` BIGINT NOT NULL, `order_no` VARCHAR(64) NOT NULL,
    `product_id` BIGINT NOT NULL, `product_name` VARCHAR(128) DEFAULT NULL,
    `product_image` VARCHAR(500) DEFAULT NULL,
    `points_spent` INT NOT NULL DEFAULT 0, `cash_amount` DECIMAL(10,2) DEFAULT 0,
    `address_id` BIGINT DEFAULT NULL, `address_snapshot` VARCHAR(500) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `third_party_order_no` VARCHAR(64) DEFAULT NULL,
    `expire_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_order_no` (`order_no`), INDEX `idx_corp_user` (`corp_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 优惠券
CREATE TABLE IF NOT EXISTS `ops_coupon` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY, `corp_id` BIGINT NOT NULL DEFAULT 3,
    `name` VARCHAR(64) NOT NULL, `type` VARCHAR(20) NOT NULL DEFAULT 'POINTS',
    `value` INT NOT NULL, `min_points` INT DEFAULT 0,
    `total_qty` INT NOT NULL DEFAULT 0, `remain_qty` INT NOT NULL DEFAULT 0,
    `expire_days` INT DEFAULT 30, `status` TINYINT NOT NULL DEFAULT 1,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. 用户优惠券
CREATE TABLE IF NOT EXISTS `ops_user_coupon` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY, `corp_id` BIGINT NOT NULL DEFAULT 3,
    `user_id` BIGINT NOT NULL, `coupon_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'UNUSED',
    `expire_time` DATETIME NOT NULL, `used_time` DATETIME DEFAULT NULL,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8. 收货地址
CREATE TABLE IF NOT EXISTS `ops_address` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY, `corp_id` BIGINT NOT NULL DEFAULT 3,
    `user_id` BIGINT NOT NULL, `name` VARCHAR(20) NOT NULL,
    `phone` VARCHAR(20) NOT NULL,
    `province` VARCHAR(30) DEFAULT NULL, `city` VARCHAR(30) DEFAULT NULL,
    `district` VARCHAR(30) DEFAULT NULL, `detail` VARCHAR(200) NOT NULL,
    `is_default` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_default` (`user_id`, `is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9. 点击统计
CREATE TABLE IF NOT EXISTS `ops_click_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY, `corp_id` BIGINT NOT NULL DEFAULT 3,
    `user_id` BIGINT DEFAULT NULL, `target_type` VARCHAR(20) NOT NULL,
    `target_id` BIGINT NOT NULL, `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 10. 多公众号配置
CREATE TABLE IF NOT EXISTS `sys_public_account_config` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY, `corp_id` BIGINT NOT NULL,
    `app_id` VARCHAR(64) NOT NULL, `app_secret` VARCHAR(256) DEFAULT NULL,
    `token` VARCHAR(64) DEFAULT NULL, `aes_key` VARCHAR(128) DEFAULT NULL,
    `js_domain` VARCHAR(200) DEFAULT NULL, `name` VARCHAR(64) DEFAULT NULL,
    `is_default` TINYINT DEFAULT 0, `status` TINYINT DEFAULT 1,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `delete_flag` INT NOT NULL DEFAULT 0,
    UNIQUE KEY `uk_corp_appid` (`corp_id`, `app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多公众号运营配置';

-- 种子数据
-- mall_product 种子数据（销售商商品）
INSERT IGNORE INTO mall_product (id, corp_id, name, cover_image, points_price, cash_price, description, status) VALUES
(1001, 3, '燃气安全检测器', '/static/gas-product1.png', 2000, 0, '家用燃气泄漏智能检测器', 1),
(1002, 3, '品牌食用油5L装', '/static/gas-product2.png', 500, 0, '高品质食用油', 1),
(1003, 3, '大米10斤装', '/static/gas-product3.png', 300, 0, '东北优质大米', 1),
(1004, 3, '金粮源优选大米5kg', '/static/product/rice-5kg.png', 300, 0, '金粮源公司精选东北优质大米，5公斤装，颗粒饱满，口感香糯', 1),
(1005, 3, '金粮源食用油4L', '/static/product/oil-4l.png', 500, 0, '金粮源公司高品质食用植物油，4升装，非转基因，健康之选', 1);

-- ops_product 映射种子数据（运营商引入商品关联）
-- operator_id=4 = 金厨美家运营商，引入销售商 金粮源公司1 (corp_id=3) 的商品
INSERT IGNORE INTO ops_product (mall_product_id, operator_id, shelf_status, category_id) VALUES
(1004, 4, 1, NULL),
(1005, 4, 1, NULL);

INSERT IGNORE INTO ops_activity (corp_id, title, cover_image, category, start_time, end_time, location, points_reward, detail, status) VALUES
(3, '社区安全用气讲座', '/static/activity1.png', 'OFFLINE', '2026-06-15', '2026-06-15 18:00:00', '各社区服务中心', 100, '免费燃气安全知识讲座', 1),
(3, '亲子农场体验日', '/static/activity2.png', 'FAMILY', '2026-06-20', '2026-06-20 17:00:00', '金地生态农场', 200, '亲子采摘+DIY厨艺体验', 1);

INSERT IGNORE INTO ops_coupon (corp_id, name, type, value, min_points, total_qty, remain_qty, expire_days) VALUES
(3, '新人50积分券', 'POINTS', 50, 0, 1000, 1000, 30),
(3, '满500减30积分券', 'POINTS', 30, 500, 500, 500, 30);

INSERT IGNORE INTO sys_public_account_config (corp_id, app_id, app_secret, name, js_domain, is_default, status)
VALUES (3, 'wx095b91409f8ecbfe', '53668cb543dd33b165061e706894f937', '金地燃气', 'a.15891589.xyz', 1, 1);
