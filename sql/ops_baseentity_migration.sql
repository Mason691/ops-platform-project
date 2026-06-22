-- ============================================================================
-- BaseEntity 合规迁移脚本 — ops-platform-project (金地燃气运营平台)
-- 日期: 2026-06-22
-- 说明: 为未继承 BaseEntity 的表添加标准审计字段 + corp_id
-- 注意: 执行前请备份数据库
-- ============================================================================

-- ═══ 1. ops_activity — 新增 BaseEntity 字段（已有 corp_id，无时间字段） ═══
ALTER TABLE ops_activity
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `status`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间' AFTER `unique_id`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `create_time`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 2. ops_address — 新增 BaseEntity 字段（已有 corp_id，无时间字段） ═══
ALTER TABLE ops_address
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `is_default`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间' AFTER `unique_id`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `create_time`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 3. ops_click_log — 新增 BaseEntity 字段（已有 corp_id + create_time） ═══
ALTER TABLE ops_click_log
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `create_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `unique_id`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 4. ops_coupon — 新增 BaseEntity 字段（已有 corp_id + create_time） ═══
ALTER TABLE ops_coupon
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `create_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `unique_id`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 5. ops_order — 新增 BaseEntity 字段（已有 corp_id + create_time/modify_time） ═══
ALTER TABLE ops_order
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `modify_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `unique_id`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 6. ops_points_log — 新增 BaseEntity 字段（已有 corp_id + create_time） ═══
ALTER TABLE ops_points_log
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `create_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `unique_id`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 7. ops_product — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_product
    ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID' AFTER `id`,
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `create_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `unique_id`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 8. ops_user_coupon — 新增 BaseEntity 字段（已有 corp_id + create_time） ═══
ALTER TABLE ops_user_coupon
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `create_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `unique_id`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 9. ops_home_config — 新增 BaseEntity 字段（已有 create_time/update_time） ═══
ALTER TABLE ops_home_config
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `update_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `unique_id`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 10. ops_remind_log — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_remind_log
    ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID' AFTER `id`,
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `create_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `unique_id`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 11. ops_live_room — 新增 corp_id + BaseEntity 字段（已有 create_time/update_time） ═══
ALTER TABLE ops_live_room
    ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID' AFTER `id`,
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `update_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `unique_id`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 12. ops_activity_booking — 新增 corp_id + BaseEntity 字段（已有 create_time/update_time） ═══
ALTER TABLE ops_activity_booking
    ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID' AFTER `id`,
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `update_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `unique_id`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 13. ops_activity_product — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_activity_product
    ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID' AFTER `id`,
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `create_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `unique_id`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 14. biz_operator_merchant — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE biz_operator_merchant
    ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID' AFTER `id`,
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `create_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `unique_id`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 15. biz_merchant_miniapp_config — 新增 corp_id + BaseEntity 字段（已有 create_time/update_time） ═══
ALTER TABLE biz_merchant_miniapp_config
    ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID' AFTER `id`,
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `update_time`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `unique_id`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;

-- ═══ 16. biz_product_category — 新增 corp_id + BaseEntity 字段 ═══
ALTER TABLE biz_product_category
    ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID' AFTER `id`,
    ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识' AFTER `status`,
    ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识' AFTER `update_key`,
    ADD COLUMN create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间' AFTER `unique_id`,
    ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间' AFTER `create_time`,
    ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID' AFTER `modify_time`,
    ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID' AFTER `create_user`,
    ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名' AFTER `modify_user`,
    ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名' AFTER `create_user_name`,
    ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序' AFTER `modify_user_name`,
    ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER `sort`,
    ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)' AFTER `remark`;
