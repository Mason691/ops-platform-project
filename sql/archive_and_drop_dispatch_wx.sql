-- =====================================================================
-- 下线旧派工单 / 公众号消息模板
-- 执行库：mall_db
-- 部署顺序：
--   1. migrate_business_order_biz_status.sql（含旧派工状态回填）
--   2. 新 ops 后端 JAR + 新平台 JAR + 前端
--   3. 本脚本（归档后删除）
-- 说明：改为工作流驱动后，派工、公众号模板、接单自动出库由工作流节点动作承担。
--       先 CTAS 归档，再 DROP；回滚见 rollback_archive_dispatch_wx.sql。
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4。
-- =====================================================================

USE mall_db;

SET @db := DATABASE();

-- ── 1. 归档（存在才归档） ─────────────────────────────────────────────
SET @sql := (SELECT IF(COUNT(*) > 0,
    'CREATE TABLE IF NOT EXISTS ops_archive_dispatch_order AS SELECT * FROM ops_dispatch_order',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_dispatch_order');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) > 0,
    'CREATE TABLE IF NOT EXISTS ops_archive_dispatch_order_item AS SELECT * FROM ops_dispatch_order_item',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_dispatch_order_item');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) > 0,
    'CREATE TABLE IF NOT EXISTS ops_archive_dispatch_message AS SELECT * FROM ops_dispatch_message',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_dispatch_message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) > 0,
    'CREATE TABLE IF NOT EXISTS ops_archive_dispatch_sequence AS SELECT * FROM ops_dispatch_sequence',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_dispatch_sequence');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) > 0,
    'CREATE TABLE IF NOT EXISTS ops_archive_wx_msg_template AS SELECT * FROM ops_wx_msg_template',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_wx_msg_template');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 2. 删除原表 ───────────────────────────────────────────────────────
DROP TABLE IF EXISTS ops_dispatch_message;
DROP TABLE IF EXISTS ops_dispatch_order_item;
DROP TABLE IF EXISTS ops_dispatch_order;
DROP TABLE IF EXISTS ops_dispatch_sequence;
DROP TABLE IF EXISTS ops_wx_msg_template;

-- ── 3. 验证：归档行数 vs 业务订单状态分布 ─────────────────────────────
SELECT 'archive_dispatch_order' AS tbl, COUNT(*) AS rows_archived FROM ops_archive_dispatch_order
UNION ALL SELECT 'archive_dispatch_order_item', COUNT(*) FROM ops_archive_dispatch_order_item
UNION ALL SELECT 'archive_dispatch_message', COUNT(*) FROM ops_archive_dispatch_message
UNION ALL SELECT 'archive_dispatch_sequence', COUNT(*) FROM ops_archive_dispatch_sequence
UNION ALL SELECT 'archive_wx_msg_template', COUNT(*) FROM ops_archive_wx_msg_template;

SELECT biz_status, COUNT(*) AS cnt FROM ops_business_order
WHERE delete_flag = 0 GROUP BY biz_status ORDER BY biz_status;
