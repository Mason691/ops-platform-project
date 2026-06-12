-- 活动预约 + 提醒系统
CREATE TABLE IF NOT EXISTS `ops_activity_booking` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `activity_id` BIGINT   NOT NULL COMMENT '活动ID',
    `user_id`     BIGINT   NOT NULL COMMENT '会员ID',
    `phone`       VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `status`      TINYINT  NOT NULL DEFAULT 0 COMMENT '0=待参加 1=已参加 2=已取消',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_activity` (`user_id`, `activity_id`),
    INDEX `idx_activity` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ops_remind_log` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `booking_id`  BIGINT   NOT NULL,
    `remind_type` VARCHAR(20) NOT NULL COMMENT '24h / 1h / custom',
    `status`      TINYINT  NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=失败',
    `send_time`   DATETIME DEFAULT NULL,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_booking` (`booking_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
