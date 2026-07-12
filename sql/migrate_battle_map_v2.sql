-- ============================================================================
-- 作战地图 ETL 预计算表 V2 扩展
-- ----------------------------------------------------------------------------
-- 新增：
--   1. ops_map_grid_performance — 网格员业绩排名表
--   2. ops_map_category_sales    — 品类销售额排名表
--   3. ALTER ops_map_employee_performance — 增加 department 列
-- 说明：ETL 每次按 org_id DELETE + INSERT 重写，不含逻辑删除。
-- ============================================================================

-- ── 1. 网格员业绩排名表 ─────────────────────────────────────────────────────
DROP TABLE IF EXISTS ops_map_grid_performance;
CREATE TABLE ops_map_grid_performance (
  id                  BIGINT        PRIMARY KEY AUTO_INCREMENT,
  org_id              BIGINT        NOT NULL COMMENT '组织ID(=corp_id)',
  grid_manager_id     BIGINT        NOT NULL COMMENT '关联 ops_grid_manager.id',
  employee_name       VARCHAR(64)   COMMENT '网格员姓名(从sys_user解析)',
  department          VARCHAR(128)  COMMENT '所属部门/组织名称',
  total_sales         DECIMAL(14,2) DEFAULT 0 COMMENT '销售额',
  community_count     INT           DEFAULT 0 COMMENT '负责小区数',
  rank_order          INT           DEFAULT 0 COMMENT '排名',
  etl_time            DATETIME      DEFAULT CURRENT_TIMESTAMP,
  KEY idx_org (org_id),
  KEY idx_org_rank (org_id, rank_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作战地图-网格员业绩排名表';

-- ── 2. 品类销售额排名表 ─────────────────────────────────────────────────────
DROP TABLE IF EXISTS ops_map_category_sales;
CREATE TABLE ops_map_category_sales (
  id                  BIGINT        PRIMARY KEY AUTO_INCREMENT,
  org_id              BIGINT        NOT NULL COMMENT '组织ID(=corp_id)',
  category_key        VARCHAR(32)   COMMENT '品类代码(item_key)',
  category_name       VARCHAR(64)   COMMENT '品类名称(字典中文)',
  total_sales         DECIMAL(14,2) DEFAULT 0 COMMENT '销售额',
  sales_count         INT           DEFAULT 0 COMMENT '销售订单数',
  rank_order          INT           DEFAULT 0 COMMENT '排名',
  etl_time            DATETIME      DEFAULT CURRENT_TIMESTAMP,
  KEY idx_org (org_id),
  KEY idx_org_rank (org_id, rank_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作战地图-品类销售额排名表';

-- ── 3. 经办人业绩表：增加部门字段 ──────────────────────────────────────────
-- 使用 ALTER ... ADD COLUMN IF NOT EXISTS 的兼容写法
-- MySQL 8.0 不支持 IF NOT EXISTS for column，改用存储过程模拟

DELIMITER //
DROP PROCEDURE IF EXISTS add_department_if_missing//
CREATE PROCEDURE add_department_if_missing()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ops_map_employee_performance'
      AND COLUMN_NAME = 'department'
  ) THEN
    ALTER TABLE ops_map_employee_performance
      ADD COLUMN department VARCHAR(128) DEFAULT NULL COMMENT '所属部门/组织名称' AFTER employee_phone;
  END IF;
END//
DELIMITER ;

CALL add_department_if_missing();
DROP PROCEDURE IF EXISTS add_department_if_missing;
