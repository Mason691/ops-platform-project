-- =====================================================================
-- 回滚：从归档表恢复旧派工单 / 公众号消息模板
-- 执行库：mall_db
-- 前提：先执行 sql/migrate_dispatch_order.sql 与 sql/migrate_wx_template_sharing.sql
--       重建空表结构（归档表无索引与自增定义）。
-- 说明：仅用于紧急回滚；恢复后需重新部署旧 ops JAR。
-- =====================================================================

USE mall_db;

SET @db := DATABASE();

SET @sql := (SELECT IF(COUNT(*) > 0,
    'INSERT INTO ops_dispatch_order SELECT * FROM ops_archive_dispatch_order',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_archive_dispatch_order');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) > 0,
    'INSERT INTO ops_dispatch_order_item SELECT * FROM ops_archive_dispatch_order_item',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_archive_dispatch_order_item');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) > 0,
    'INSERT INTO ops_dispatch_message SELECT * FROM ops_archive_dispatch_message',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_archive_dispatch_message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) > 0,
    'INSERT INTO ops_dispatch_sequence SELECT * FROM ops_archive_dispatch_sequence',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_archive_dispatch_sequence');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) > 0,
    'INSERT INTO ops_wx_msg_template SELECT * FROM ops_archive_wx_msg_template',
    'SELECT 1')
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_archive_wx_msg_template');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
