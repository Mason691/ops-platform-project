-- ============================================================
-- 首页配置表
-- 用于管理公众号 H5 端首页的广告轮播、精彩视频、热门推荐内容
-- 目标库：mall_db
-- ============================================================
USE mall_db;

CREATE TABLE IF NOT EXISTS `ops_home_config` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `config_type` VARCHAR(20)  NOT NULL COMMENT '配置类型: banner=广告轮播, video=精彩视频, recommend=热门推荐',
    `title`       VARCHAR(100) DEFAULT NULL COMMENT '标题/描述',
    `image_url`   VARCHAR(255) DEFAULT NULL COMMENT '图片URL（banner使用）',
    `jump_path`   VARCHAR(255) DEFAULT NULL COMMENT '跳转小程序路径（banner使用）',
    `target_id`   BIGINT       DEFAULT NULL COMMENT '目标ID: activity_id(video) / ops_product.id(recommend)',
    `sort`        INT          DEFAULT 0 COMMENT '排序（数字越小越靠前）',
    `status`      TINYINT      DEFAULT 1 COMMENT '状态: 1=启用 0=禁用',
    `operator_id` BIGINT       NOT NULL COMMENT '运营商组织ID',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_operator_type` (`operator_id`, `config_type`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首页配置管理';
