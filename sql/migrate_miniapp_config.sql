-- 销售商小程序跳转配置表
-- 存储各销售商（merchant）的小程序 AppID 和路径模板，
-- 供 H5 端商品点击时跳转到对应微信小程序。
-- 一个销售商仅一条配置（merchant_id UNIQUE）。

CREATE TABLE IF NOT EXISTS `biz_merchant_miniapp_config` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `merchant_id`    BIGINT       NOT NULL COMMENT '销售商组织ID（关联 ai_platform.sys_corp.id）',
    `app_id`         VARCHAR(64)  NOT NULL COMMENT '微信小程序 AppID',
    `path_template`  VARCHAR(256) NOT NULL DEFAULT 'pages/index/index' COMMENT '跳转路径模板，支持占位符 {productId}',
    `env_version`    VARCHAR(16)  NOT NULL DEFAULT 'release' COMMENT '小程序版本: release / trial / develop',
    `remark`         VARCHAR(256) DEFAULT NULL COMMENT '备注',
    `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_merchant_id` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售商小程序跳转配置';

-- 种子数据：为金粮源公司1（merchant_id=3）配置小程序跳转
-- 使用金厨美家运营商的微信测试小程序 AppID
INSERT INTO `biz_merchant_miniapp_config` (`merchant_id`, `app_id`, `path_template`, `env_version`, `remark`)
VALUES (3, 'wxe0eb0deb7d251505', 'pages/product/detail?id={productId}', 'trial', '金粮源公司1 — 测试小程序配置')
ON DUPLICATE KEY UPDATE `app_id` = VALUES(`app_id`), `path_template` = VALUES(`path_template`);
