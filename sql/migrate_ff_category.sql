-- 财务分类字典表（财务管理 → 分类管理）
-- 平台级基础数据，无 corp_id；分类编码取 Excel「分类」工作表文本前五位（如 11.01），
-- 其余文本为分类名称。逻辑删除字段 delete_flag 遵循 BaseEntity 约定。
-- 注意：category_code 仅建普通索引 —— 逻辑删除下唯一键会与已删除行冲突，
-- 编码唯一性由应用层（FfCategoryServiceImpl）保证。
-- 执行顺序：必须先于新 ops JAR 执行。

CREATE TABLE IF NOT EXISTS `ff_category` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT                  COMMENT '主键',
    `category_code`     VARCHAR(20)  NOT NULL                                 COMMENT '分类编码（前五位，如 11.01）',
    `category_name`     VARCHAR(100) NOT NULL                                 COMMENT '分类名称（编码之后的文本）',
    `parent_code`       VARCHAR(20)  DEFAULT NULL                             COMMENT '父级编码（按点号推导，如 11.01 → 11）',
    `category_level`    INT          NOT NULL DEFAULT 1                       COMMENT '层级（点号分隔段数，11.01 → 2）',
    `status`            TINYINT      NOT NULL DEFAULT 1                       COMMENT '状态：1启用 0禁用',
    `sort`              INT          DEFAULT 0                                COMMENT '排序（导入按行序 ×10）',
    `remark`            VARCHAR(500) DEFAULT NULL                             COMMENT '备注',
    `update_key`        VARCHAR(50)  NOT NULL DEFAULT ''                      COMMENT '更改键',
    `unique_id`         VARCHAR(50)  NOT NULL DEFAULT ''                      COMMENT '唯一码',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP                COMMENT '创建时间',
    `modify_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `create_user`       BIGINT       DEFAULT NULL                             COMMENT '创建人ID',
    `modify_user`       BIGINT       DEFAULT NULL                             COMMENT '修改人ID',
    `create_user_name`  VARCHAR(100) DEFAULT NULL                             COMMENT '创建人名称',
    `modify_user_name`  VARCHAR(100) DEFAULT NULL                             COMMENT '修改人名称',
    `delete_flag`       INT          NOT NULL DEFAULT 0                       COMMENT '逻辑删除：0正常 1删除',
    PRIMARY KEY (`id`),
    KEY `idx_category_code` (`category_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='财务分类字典';
