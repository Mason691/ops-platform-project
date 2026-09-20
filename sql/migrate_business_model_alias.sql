-- =============================================================================
-- 业务数据导入型号匹配关系表
-- =============================================================================
-- 用途：增值业务"业务数据"导入时，操作人员对模糊型号（如 Excel 填 Q08 匹配到
--       JZT-Q08）确认的匹配关系落库记忆。后续导入相同 品牌+品类+型号关键信息
--       时自动套用该匹配，避免重复确认。
-- 执行时机：必须在新后端版本部署前执行（导入确认阶段会写入该表）。
-- 幂等性：DROP TABLE IF EXISTS + CREATE TABLE，可重复执行（会清空已确认的匹配
--         关系，仅在首次部署前执行一次即可）。

DROP TABLE IF EXISTS `ops_business_model_alias`;
CREATE TABLE `ops_business_model_alias` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY   COMMENT '主键',
    `corp_id`             BIGINT       NOT NULL DEFAULT 0                    COMMENT '租户ID（多租户隔离）',
    `brand`               VARCHAR(100) NOT NULL                              COMMENT '品牌（与增值产品信息表一致）',
    `category`            VARCHAR(32)  NOT NULL                              COMMENT '品类（PRODUCT_CATEGORY 字典 itemKey）',
    `model_keyword`       VARCHAR(100) NOT NULL                              COMMENT 'Excel 填写的型号关键信息（归一化：去空格转小写）',
    `product_id`          BIGINT       NOT NULL                              COMMENT '匹配到的增值产品 ops_value_added_product.id',
    `remark`              VARCHAR(255) DEFAULT NULL                          COMMENT '备注',
    -- BaseEntity audit fields (matching ops_category_lifecycle_rule / deployed SDK)
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
    UNIQUE KEY `uk_alias` (`corp_id`, `brand`, `category`, `model_keyword`, `delete_flag`),
    KEY `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务数据导入型号匹配关系表（确认后记忆，同型号免重复确认）';
