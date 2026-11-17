-- 2026-11-13 公众号消息模板组织共享
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新 ops 后端 JAR 上线执行（新 JAR 使用 owner_corp_id/share_scope 等新列）
-- 幂等：生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS，使用 information_schema 条件化 DDL
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4
--
-- 变更：
--   1. 新增 owner_corp_id（归属组织；0=平台全局）；保留 corp_id 作为旧 JAR 兼容镜像
--   2. 新增 share_scope：PRIVATE（仅本组织）/ OPERATOR_TREE（运营商及下属共享）/ PLATFORM（平台全局）
--   3. 新增 template_id_map：按公众号 appId 映射 template_id（子公司自有公众号场景）
--   4. 新增 use_count / last_used_time：模板使用审计
--   5. 存量数据处理：
--      - 原全局模板（owner=0）若 app_id 命中某组织的公众号配置，归属该组织并改为 OPERATOR_TREE
--        （生产现有「金地美家派工通知」app_id=wxda1842aa8df7d2da 属于 corp 4，迁移后由运营商维护）
--      - 其余 owner=0 → PLATFORM；owner>0 → PRIVATE
USE mall_db;

-- ═══ 1. 新增 owner_corp_id（保留 corp_id 作为旧 JAR 兼容镜像） ═══
-- 不在本迁移中重命名/删除 corp_id：迁移与旧 JAR 运行窗口内，旧代码仍读 corp_id；
-- 新 JAR 以 owner_corp_id 为准，并在新增/修改时同步 corp_id。待新 JAR 稳定后可另行清理 corp_id。
SET @has_owner := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_wx_msg_template'
      AND COLUMN_NAME = 'owner_corp_id');
SET @ddl := IF(@has_owner = 0,
    'ALTER TABLE ops_wx_msg_template ADD COLUMN owner_corp_id BIGINT NOT NULL DEFAULT 0 COMMENT ''归属组织ID；0=平台全局'' AFTER id',
    'SELECT ''owner_corp_id already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 若旧列 corp_id 存在，回填 owner_corp_id（不改 corp_id，保持旧 JAR 行为）
SET @has_corp := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_wx_msg_template'
      AND COLUMN_NAME = 'corp_id');
SET @ddl := IF(@has_corp = 1,
    'UPDATE ops_wx_msg_template SET owner_corp_id = corp_id WHERE owner_corp_id = 0 AND corp_id > 0',
    'SELECT ''corp_id missing (fresh schema)''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 2. share_scope ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_wx_msg_template'
      AND COLUMN_NAME = 'share_scope');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_wx_msg_template ADD COLUMN share_scope VARCHAR(20) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''PRIVATE=仅本组织 / OPERATOR_TREE=运营商及下属共享 / PLATFORM=平台全局'' AFTER owner_corp_id',
    'SELECT ''share_scope already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 3. template_id_map ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_wx_msg_template'
      AND COLUMN_NAME = 'template_id_map');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_wx_msg_template ADD COLUMN template_id_map VARCHAR(1000) DEFAULT NULL COMMENT ''按公众号 appId 映射 template_id JSON'' AFTER app_id',
    'SELECT ''template_id_map already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 4. use_count / last_used_time ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_wx_msg_template'
      AND COLUMN_NAME = 'use_count');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_wx_msg_template ADD COLUMN use_count INT NOT NULL DEFAULT 0 COMMENT ''累计使用次数'' AFTER status',
    'SELECT ''use_count already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_wx_msg_template'
      AND COLUMN_NAME = 'last_used_time');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_wx_msg_template ADD COLUMN last_used_time DATETIME DEFAULT NULL COMMENT ''最近使用时间'' AFTER use_count',
    'SELECT ''last_used_time already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 5. 存量归属与共享范围回填 ═══
-- 原全局模板的 app_id 若命中某组织的公众号配置，归属该组织并共享给其下属
-- ⚠ 生产 mall_db 表间 COLLATE 不一致（本表 unicode_ci / 公众号配置 0900_ai_ci），
--    比较 app_id 必须显式 COLLATE，否则报 Illegal mix of collations。
UPDATE ops_wx_msg_template t
SET t.owner_corp_id = (
        SELECT MIN(c.corp_id) FROM sys_public_account_config c
        WHERE c.app_id COLLATE utf8mb4_unicode_ci = t.app_id
          AND c.status = 1 AND c.delete_flag = 0 AND c.corp_id > 0
    ),
    t.share_scope = 'OPERATOR_TREE'
WHERE t.owner_corp_id = 0
  AND t.app_id IS NOT NULL AND t.app_id <> ''
  AND EXISTS (
      SELECT 1 FROM sys_public_account_config c
      WHERE c.app_id COLLATE utf8mb4_unicode_ci = t.app_id
        AND c.status = 1 AND c.delete_flag = 0 AND c.corp_id > 0
  );

UPDATE ops_wx_msg_template SET share_scope = 'PLATFORM' WHERE owner_corp_id = 0;
UPDATE ops_wx_msg_template SET share_scope = 'PRIVATE' WHERE owner_corp_id > 0 AND share_scope = 'PLATFORM';

-- ═══ 6. 索引 ═══
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_wx_msg_template'
      AND INDEX_NAME = 'idx_owner_scope');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE ops_wx_msg_template ADD INDEX idx_owner_scope (owner_corp_id, share_scope, status)',
    'SELECT ''idx_owner_scope already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 验证输出 ═══
SELECT id, owner_corp_id, corp_id, share_scope, scene_code, template_id, app_id,
       status, use_count, last_used_time
FROM ops_wx_msg_template
ORDER BY id;
