-- Add missing BaseEntity columns to value-added business tables
-- Part of: 增值业务模块 BaseEntity 架构规范化重构
-- See Specs/增值业务-规格说明.md

-- ═══ ops_value_added_product ═══
-- This table previously did NOT extend BaseEntity in Java.
-- Adding all standard audit columns + renaming update_time → modify_time.

ALTER TABLE ops_value_added_product
  ADD COLUMN IF NOT EXISTS corp_id BIGINT DEFAULT 0 COMMENT '租户ID',
  ADD COLUMN IF NOT EXISTS update_key VARCHAR(50) DEFAULT '' COMMENT '更新键',
  ADD COLUMN IF NOT EXISTS unique_id VARCHAR(50) DEFAULT '' COMMENT '唯一ID',
  ADD COLUMN IF NOT EXISTS delete_flag INT DEFAULT 0 COMMENT '逻辑删除',
  ADD COLUMN IF NOT EXISTS sort INT DEFAULT NULL COMMENT '排序',
  ADD COLUMN IF NOT EXISTS create_user BIGINT DEFAULT NULL COMMENT '创建人ID',
  ADD COLUMN IF NOT EXISTS modify_user BIGINT DEFAULT NULL COMMENT '修改人ID',
  ADD COLUMN IF NOT EXISTS create_user_name VARCHAR(100) DEFAULT NULL COMMENT '创建人名称',
  ADD COLUMN IF NOT EXISTS modify_user_name VARCHAR(100) DEFAULT NULL COMMENT '修改人名称',
  CHANGE COLUMN update_time modify_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';

-- ═══ ops_business_data ═══
-- This table's Java entity extends BaseEntity but no migration SQL existed.
-- Adding missing standard audit columns.

ALTER TABLE ops_business_data
  ADD COLUMN IF NOT EXISTS corp_id BIGINT DEFAULT 0 COMMENT '租户ID',
  ADD COLUMN IF NOT EXISTS update_key VARCHAR(50) DEFAULT '' COMMENT '更新键',
  ADD COLUMN IF NOT EXISTS unique_id VARCHAR(50) DEFAULT '' COMMENT '唯一ID',
  ADD COLUMN IF NOT EXISTS delete_flag INT DEFAULT 0 COMMENT '逻辑删除',
  ADD COLUMN IF NOT EXISTS sort INT DEFAULT NULL COMMENT '排序',
  ADD COLUMN IF NOT EXISTS create_user BIGINT DEFAULT NULL COMMENT '创建人ID',
  ADD COLUMN IF NOT EXISTS modify_user BIGINT DEFAULT NULL COMMENT '修改人ID',
  ADD COLUMN IF NOT EXISTS create_user_name VARCHAR(100) DEFAULT NULL COMMENT '创建人名称',
  ADD COLUMN IF NOT EXISTS modify_user_name VARCHAR(100) DEFAULT NULL COMMENT '修改人名称';

-- Note: ops_business_data may already have create_time / modify_time columns.
-- If modify_time is missing, uncomment:
-- ALTER TABLE ops_business_data ADD COLUMN IF NOT EXISTS modify_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';

-- ═══ ENABLE/DISABLE 系统字典 ═══
-- Used by ops_value_added_product.status, ops_personal_commission_rule.status,
-- ops_company_commission_rule.status, and any future entities needing enable/disable.

INSERT IGNORE INTO ai_platform.sys_dict_type (type_code, type_name, status, delete_flag)
VALUES ('ENABLE_DISABLE', '启用/禁用状态', 1, 0);

INSERT IGNORE INTO ai_platform.sys_dict_item (type_id, item_key, item_value, item_order, status, delete_flag)
SELECT t.id, '1', '启用', 1, 1, 0 FROM ai_platform.sys_dict_type t WHERE t.type_code = 'ENABLE_DISABLE';

INSERT IGNORE INTO ai_platform.sys_dict_item (type_id, item_key, item_value, item_order, status, delete_flag)
SELECT t.id, '0', '禁用', 2, 1, 0 FROM ai_platform.sys_dict_type t WHERE t.type_code = 'ENABLE_DISABLE';
