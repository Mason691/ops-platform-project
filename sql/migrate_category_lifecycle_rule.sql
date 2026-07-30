-- 品类使用年限规则表
-- 用于配置各品类（燃气灶/热水器等）的使用年限、安检间隔和提前提醒天数
-- 运营商维护，子公司可见（权限由 OrgRoleService.isOperatorOrSuperAdmin 控制写操作）
DROP TABLE IF EXISTS `ops_category_lifecycle_rule`;
CREATE TABLE `ops_category_lifecycle_rule` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY   COMMENT '主键',
    `corp_id`             BIGINT       NOT NULL DEFAULT 0                    COMMENT '租户ID（多租户隔离）',
    `category`            VARCHAR(64)  NOT NULL                              COMMENT '品类（PRODUCT_CATEGORY 字典 itemKey）',
    `max_years`           INT          NOT NULL DEFAULT 8                    COMMENT '使用年限（年）',
    `inspection_interval` INT          NOT NULL DEFAULT 12                   COMMENT '间隔安检期（月）',
    `remind_days`         INT          NOT NULL DEFAULT 30                   COMMENT '提前提醒天数',
    `remark`              VARCHAR(255) DEFAULT NULL                          COMMENT '备注',
    -- BaseEntity audit fields (matching ops_annual_plan / deployed SDK)
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP             COMMENT '创建时间',
    `create_user`         BIGINT       DEFAULT NULL                          COMMENT '创建人ID',
    `create_user_name`    VARCHAR(64)  DEFAULT NULL                          COMMENT '创建人姓名',
    `modify_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `modify_user`         BIGINT       DEFAULT NULL                          COMMENT '修改人ID',
    `modify_user_name`    VARCHAR(64)  DEFAULT NULL                          COMMENT '修改人姓名',
    `unique_id`           VARCHAR(64)  DEFAULT NULL                          COMMENT 'UUID',
    `update_key`          VARCHAR(64)  DEFAULT ''                            COMMENT '更新键',
    `sort`                INT          DEFAULT 0                             COMMENT '排序',
    `delete_flag`         INT          NOT NULL DEFAULT 0                    COMMENT '逻辑删除 0=正常 1=删除',
    UNIQUE KEY `uk_corp_category` (`corp_id`, `category`, `delete_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品类使用年限规则';
