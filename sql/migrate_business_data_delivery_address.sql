-- 2026-11-06 增值业务：ops_business_data 新增收货地址
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新 ops 后端 JAR / SCM 后端 JAR 上线执行
-- 幂等：DDL 经 information_schema 条件化（生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS）
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4（中文注释防乱码）

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'delivery_address');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN delivery_address VARCHAR(500) DEFAULT NULL COMMENT ''收货地址'' AFTER customer_phone',
    'SELECT ''delivery_address already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
