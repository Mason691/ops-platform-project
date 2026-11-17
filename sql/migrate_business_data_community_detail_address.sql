-- 2026-11-06 增值业务：收货地址拆分为「小区」+「详细地址」
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新 ops 后端 JAR / SCM 后端 JAR 上线执行；
--          列重命名后旧 JAR（仍读写 delivery_address）不可用，须与新 JAR 在同一发布窗口切换。
-- 幂等：DDL 经 information_schema 条件化（生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS）
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4（中文注释防乱码）
--
-- 字段顺序：customer_phone → community_id → community_name → detailed_address
-- 存量处理：原 delivery_address 整列重命名为 detailed_address，原值（小区+详细地址拼接串）
--          原样保留；community_id / community_name 保持 NULL，后续人工或匹配逻辑回填。

-- 1) 小区ID（sys_community.id；导入按名称匹配成功时写入，未匹配为空）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'community_id');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN community_id BIGINT DEFAULT NULL COMMENT ''小区ID（ai_platform.sys_community.id）'' AFTER customer_phone',
    'SELECT ''community_id already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) 小区名称（冗余展示；长度对齐 ai_platform.sys_community.community_name VARCHAR(200)）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'community_name');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN community_name VARCHAR(200) DEFAULT NULL COMMENT ''小区名称（冗余，导入/手工录入）'' AFTER community_id',
    'SELECT ''community_name already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) delivery_address → detailed_address（整列重命名，存量值原样保留）
SET @has_delivery := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'delivery_address');
SET @has_detailed := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'detailed_address');
SET @ddl := IF(@has_detailed = 0 AND @has_delivery = 1,
    'ALTER TABLE ops_business_data CHANGE COLUMN delivery_address detailed_address VARCHAR(500) DEFAULT NULL COMMENT ''详细地址（楼栋/单元/门牌号）''',
    IF(@has_detailed = 0,
       'ALTER TABLE ops_business_data ADD COLUMN detailed_address VARCHAR(500) DEFAULT NULL COMMENT ''详细地址（楼栋/单元/门牌号）'' AFTER community_name',
       'SELECT ''detailed_address already exists'''));
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 4) 极端历史形态（两列并存）：先搬值再删旧列，保证不丢数据
SET @has_delivery := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'delivery_address');
SET @has_detailed := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'detailed_address');
SET @both := @has_delivery + @has_detailed;
SET @ddl := IF(@both = 2,
    'UPDATE ops_business_data SET detailed_address = delivery_address WHERE (detailed_address IS NULL OR detailed_address = '''') AND delivery_address IS NOT NULL',
    'SELECT ''no legacy delivery_address to copy''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
SET @ddl := IF(@both = 2,
    'ALTER TABLE ops_business_data DROP COLUMN delivery_address',
    'SELECT ''no legacy delivery_address to drop''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
