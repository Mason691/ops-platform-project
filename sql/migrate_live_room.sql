-- ============================================================
-- 视频号直播配置表
-- 运营商配置自己的视频号直播间，供公众号H5端展示和跳转
-- ============================================================
CREATE TABLE IF NOT EXISTS `ops_live_room` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `merchant_id`    BIGINT       NOT NULL COMMENT '运营商组织ID（关联 ai_platform.sys_corp.id）',
    `finder_username` VARCHAR(128) NOT NULL COMMENT '视频号 finderUserName（如 sphxxxx@finder）',
    `live_title`     VARCHAR(256) NOT NULL COMMENT '直播标题',
    `cover_url`      VARCHAR(500) DEFAULT NULL COMMENT '直播封面图URL',
    `start_time`     DATETIME     DEFAULT NULL COMMENT '预计开播时间',
    `end_time`       DATETIME     DEFAULT NULL COMMENT '预计结束时间',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0=预告 1=直播中 2=已结束',
    `sort_order`     INT          NOT NULL DEFAULT 0 COMMENT '排序（数字越小越靠前）',
    `remark`         VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_merchant_status` (`merchant_id`, `status`),
    INDEX `idx_status_time` (`status`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频号直播配置';
