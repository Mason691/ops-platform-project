-- 品类使用年限规则表
-- 用于配置各品类（燃气灶/热水器等）的使用年限、安检间隔和提前提醒天数
CREATE TABLE IF NOT EXISTS `ops_category_lifecycle_rule` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `corp_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
    `category` VARCHAR(64) NOT NULL COMMENT '品类（dict item_key，对应 PRODUCT_CATEGORY 字典）',
    `max_years` INT NOT NULL DEFAULT 8 COMMENT '使用年限（年）',
    `inspection_interval` INT NOT NULL DEFAULT 12 COMMENT '间隔安检期（月）',
    `remind_days` INT NOT NULL DEFAULT 30 COMMENT '提前提醒天数',
    `remark` VARCHAR(255) DEFAULT NULL COMMENT '备注',
    -- BaseEntity audit fields
    `create_by` VARCHAR(64) DEFAULT NULL,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `modify_by` VARCHAR(64) DEFAULT NULL,
    `modify_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `delete_flag` INT DEFAULT 0 COMMENT '逻辑删除（0=正常, 1=删除）',
    `version` INT DEFAULT 0,
    UNIQUE KEY `uk_corp_category` (`corp_id`, `category`, `delete_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品类使用年限规则';
