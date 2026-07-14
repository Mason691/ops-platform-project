-- ===========================================================
-- 年度任务计划表
-- 存储各子公司各品类的年度计划金额明细
-- 汇总数据实时计算，不在表中存储
-- ===========================================================
USE `mall_db`;

CREATE TABLE IF NOT EXISTS `ops_annual_plan` (
    `id`              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    `year`            INT             NOT NULL                         COMMENT '年度',
    `org_id`          BIGINT          NOT NULL                         COMMENT '组织ID（子公司）',
    `category`        VARCHAR(20)     NOT NULL                         COMMENT '品类代码（对应数据字典item_key）',
    `plan_amount`     DECIMAL(12,2)   NOT NULL DEFAULT 0.00            COMMENT '计划金额',
    `status`          TINYINT         NOT NULL DEFAULT 0               COMMENT '审核状态 0=待审核 1=已审核',
    `operator_id`     BIGINT          DEFAULT NULL                     COMMENT '运营商ID（操作组织）',
    `remark`          VARCHAR(255)    DEFAULT NULL                     COMMENT '备注',
    `create_time`     DATETIME        DEFAULT CURRENT_TIMESTAMP        COMMENT '创建时间',
    `create_user`     BIGINT          DEFAULT NULL                     COMMENT '创建人ID',
    `create_user_name` VARCHAR(64)    DEFAULT NULL                     COMMENT '创建人姓名',
    `modify_time`     DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `modify_user`     BIGINT          DEFAULT NULL                     COMMENT '修改人ID',
    `modify_user_name` VARCHAR(64)    DEFAULT NULL                     COMMENT '修改人姓名',
    `unique_id`       VARCHAR(64)     DEFAULT NULL                     COMMENT 'UUID',
    `update_key`      VARCHAR(64)     DEFAULT ''                       COMMENT '更新键',
    `sort`            INT             DEFAULT 0                        COMMENT '排序',
    `delete_flag`     INT             NOT NULL DEFAULT 0               COMMENT '逻辑删除 0=正常 1=删除',
    INDEX `idx_year_org` (`year`, `org_id`),
    INDEX `idx_year_category` (`year`, `category`),
    INDEX `idx_org_id` (`org_id`),
    UNIQUE KEY `uk_year_org_category_del` (`year`, `org_id`, `category`, `delete_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年度任务计划表';
