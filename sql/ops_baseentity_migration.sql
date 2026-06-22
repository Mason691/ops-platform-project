-- ============================================================================
-- BaseEntity 合规迁移脚本 — ops-platform-project (金地燃气运营平台)
-- 日期: 2026-06-22
-- 说明: 为所有业务表添加 BaseEntity 标准审计字段 + corp_id
-- 注意:
--   1. 执行前请备份数据库
--   2. 每条 ALTER 独立执行，已存在的列会报 Duplicate column 错（无害，可忽略）
--   3. 不使用 AFTER 子句 —— 列位置不影响功能，且 AFTER 引用的列可能不存在
-- ============================================================================

-- ═══ 1. ops_activity — 新增 BaseEntity 字段（已有 corp_id） ═══
ALTER TABLE ops_activity ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_activity ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_activity ADD COLUMN create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE ops_activity ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_activity ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_activity ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_activity ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_activity ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_activity ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_activity ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_activity ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 2. ops_address — 新增 BaseEntity 字段（已有 corp_id） ═══
ALTER TABLE ops_address ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_address ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_address ADD COLUMN create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE ops_address ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_address ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_address ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_address ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_address ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_address ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_address ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_address ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 3. ops_click_log — 新增 BaseEntity 字段（已有 corp_id + create_time） ═══
ALTER TABLE ops_click_log ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_click_log ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_click_log ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_click_log ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_click_log ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_click_log ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_click_log ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_click_log ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_click_log ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_click_log ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 4. ops_coupon — 新增 BaseEntity 字段（已有 corp_id + create_time） ═══
ALTER TABLE ops_coupon ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_coupon ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_coupon ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_coupon ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_coupon ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_coupon ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_coupon ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_coupon ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_coupon ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_coupon ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 5. ops_order — 新增 BaseEntity 字段（已有 corp_id + create_time/modify_time） ═══
ALTER TABLE ops_order ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_order ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_order ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_order ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_order ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_order ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_order ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_order ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_order ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 6. ops_points_log — 新增 BaseEntity 字段（已有 corp_id + create_time） ═══
ALTER TABLE ops_points_log ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_points_log ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_points_log ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_points_log ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_points_log ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_points_log ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_points_log ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_points_log ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_points_log ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_points_log ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 7. ops_product — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_product ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE ops_product ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_product ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_product ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_product ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_product ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_product ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_product ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_product ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_product ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_product ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 8. ops_user_coupon — 新增 BaseEntity 字段（已有 corp_id + create_time） ═══
ALTER TABLE ops_user_coupon ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_user_coupon ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_user_coupon ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_user_coupon ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_user_coupon ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_user_coupon ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_user_coupon ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_user_coupon ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_user_coupon ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_user_coupon ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 9. ops_home_config — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_home_config ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE ops_home_config ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_home_config ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_home_config ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_home_config ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_home_config ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_home_config ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_home_config ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_home_config ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_home_config ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_home_config ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 10. ops_remind_log — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_remind_log ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE ops_remind_log ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_remind_log ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_remind_log ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_remind_log ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_remind_log ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_remind_log ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_remind_log ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_remind_log ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_remind_log ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_remind_log ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 11. ops_live_room — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_live_room ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE ops_live_room ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_live_room ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_live_room ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_live_room ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_live_room ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_live_room ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_live_room ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_live_room ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_live_room ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_live_room ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 12. ops_activity_booking — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_activity_booking ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE ops_activity_booking ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_activity_booking ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_activity_booking ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_activity_booking ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_activity_booking ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_activity_booking ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_activity_booking ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_activity_booking ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_activity_booking ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_activity_booking ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 13. ops_activity_product — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE ops_activity_product ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE ops_activity_product ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE ops_activity_product ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE ops_activity_product ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE ops_activity_product ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE ops_activity_product ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE ops_activity_product ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE ops_activity_product ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE ops_activity_product ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE ops_activity_product ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE ops_activity_product ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 14. biz_operator_merchant — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE biz_operator_merchant ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE biz_operator_merchant ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE biz_operator_merchant ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE biz_operator_merchant ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE biz_operator_merchant ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE biz_operator_merchant ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE biz_operator_merchant ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE biz_operator_merchant ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE biz_operator_merchant ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE biz_operator_merchant ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE biz_operator_merchant ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 15. biz_merchant_miniapp_config — 新增 corp_id + BaseEntity 字段（已有 create_time） ═══
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE biz_merchant_miniapp_config ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';

-- ═══ 16. biz_product_category — 新增 corp_id + BaseEntity 字段 ═══
ALTER TABLE biz_product_category ADD COLUMN corp_id          BIGINT       DEFAULT NULL COMMENT '租户ID';
ALTER TABLE biz_product_category ADD COLUMN update_key       VARCHAR(64)  DEFAULT NULL COMMENT '更新标识';
ALTER TABLE biz_product_category ADD COLUMN unique_id        VARCHAR(64)  DEFAULT NULL COMMENT '唯一标识';
ALTER TABLE biz_product_category ADD COLUMN create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE biz_product_category ADD COLUMN modify_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
ALTER TABLE biz_product_category ADD COLUMN create_user      BIGINT       DEFAULT NULL COMMENT '创建人ID';
ALTER TABLE biz_product_category ADD COLUMN modify_user      BIGINT       DEFAULT NULL COMMENT '修改人ID';
ALTER TABLE biz_product_category ADD COLUMN create_user_name VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名';
ALTER TABLE biz_product_category ADD COLUMN modify_user_name VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名';
ALTER TABLE biz_product_category ADD COLUMN sort             INT          DEFAULT 0   COMMENT '排序';
ALTER TABLE biz_product_category ADD COLUMN remark           VARCHAR(500) DEFAULT NULL COMMENT '备注';
ALTER TABLE biz_product_category ADD COLUMN delete_flag      INT          DEFAULT 0   COMMENT '逻辑删除(0-正常,1-删除)';
