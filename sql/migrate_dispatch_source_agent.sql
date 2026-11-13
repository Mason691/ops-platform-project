-- 2026-11-13 派工单记录来源订单经办人（仅展示/追溯，不参与权限判断）
-- 执行库：mall_db
-- 部署顺序：先于新 ops JAR 执行（新代码创建派工单时写入该列）
-- 幂等：生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS，使用 information_schema 条件化 DDL
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4
USE mall_db;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_dispatch_order'
      AND COLUMN_NAME = 'source_agent_names');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_dispatch_order ADD COLUMN source_agent_names VARCHAR(500) DEFAULT NULL COMMENT ''来源订单经办人（展示/追溯）'' AFTER source_order_no',
    'SELECT ''source_agent_names already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
