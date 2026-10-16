-- ============================================================================
-- 增值业务-会员台账：ops_business_data 新增 customer_phone + platform_member_id
--   + 存量一次性回填
-- 执行库：mall_db（与 ai_platform 同实例，跨库 JOIN）
-- 部署顺序：本脚本必须先于新后端 JAR 上线执行（幂等，可重复运行）
--
-- 会员归属两条路径（2026-10-16 起）：
--   路径一（新导入，主）：customer_phone ↔ ai_platform.sys_member.phone
--     （uk_phone 全局唯一，无歧义）。由导入代码在 check 阶段完成匹配，
--     未匹配整文件拒绝。存量行无手机号来源，customer_phone 恒为 NULL。
--   路径二（存量回填，一次性）：customer 姓名匹配，只填 NULL 行。
--     策略一：customer 姓名 + 同 corp 内唯一匹配燃气用户
--       gu.full_name = bd.customer AND gu.corp_id = bd.corp_id，
--       组内 COUNT(DISTINCT gu.id) = 1 且 gu.platform_member_id 非空。
--       实测覆盖率 1330/3249 行（40.9%）、1016 个不同会员
--       （corp 分布：9=538、14=340、12=255、13=97、11=62、10=38）。
--     策略二（兜底，只填策略一后仍为 NULL 的行）：customer ↔ sys_member.real_name
--       全库唯一匹配（sys_member 无 corp 列，无法做组织范围约束，故仅全库唯一才匹配）。
--       实测独立覆盖 1335 行；real_name 填充率 100%（93036/93036）。
--
-- 字符集：ops_business_data=utf8mb4_unicode_ci vs ops_gas_user=utf8mb4_0900_ai_ci，
--   跨表 JOIN 必须双侧显式 COLLATE（一次性离线 SQL，索引失效可接受）。
--   手机号纯数字，路径一的 = 比较不受 collation 差异影响，无需 COLLATE。
-- 幂等：DDL 经 information_schema 条件化（生产 MySQL 8.0.46 不支持
--   ADD COLUMN/INDEX IF NOT EXISTS，已实测验证）；回填只更新 NULL 行，
--   可安全重跑（覆盖发布窗口期旧 JAR 导入的新行）。
--
-- 执行前确认：SHOW COLUMNS FROM ops_gas_user LIKE 'full_name';
-- ============================================================================

-- ═══ DDL：新增 customer_phone 列（条件化，不存在才加） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'customer_phone');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN customer_phone VARCHAR(20) DEFAULT NULL COMMENT ''客户手机号（新模板必填；存量行无来源为 NULL）'' AFTER customer',
    'SELECT ''customer_phone already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：新增 platform_member_id 列（条件化，不存在才加） ═══
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND COLUMN_NAME = 'platform_member_id');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_data ADD COLUMN platform_member_id BIGINT DEFAULT NULL COMMENT ''平台会员ID（= ai_platform.sys_member.id；新导入按客户手机号匹配，存量行姓名回填）'' AFTER customer_phone',
    'SELECT ''platform_member_id already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ DDL：新增索引（条件化，不存在才加） ═══
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data'
      AND INDEX_NAME = 'idx_platform_member');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE ops_business_data ADD INDEX idx_platform_member (platform_member_id)',
    'SELECT ''idx_platform_member already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 回填策略一：客户姓名 → 同组织唯一燃气用户 → 平台会员 ═══
UPDATE ops_business_data bd
JOIN (
    SELECT gu.corp_id, gu.full_name, MAX(gu.platform_member_id) AS platform_member_id
    FROM ops_gas_user gu
    WHERE gu.delete_flag = 0
      AND gu.platform_member_id IS NOT NULL
      AND gu.full_name IS NOT NULL AND gu.full_name <> ''
    GROUP BY gu.corp_id, gu.full_name
    HAVING COUNT(DISTINCT gu.id) = 1
) m ON m.corp_id = bd.corp_id
   AND m.full_name COLLATE utf8mb4_unicode_ci = bd.customer COLLATE utf8mb4_unicode_ci
SET bd.platform_member_id = m.platform_member_id
WHERE bd.platform_member_id IS NULL AND bd.delete_flag = 0;

-- ═══ 回填策略二（兜底）：客户姓名 → 全库唯一 sys_member.real_name ═══
UPDATE ops_business_data bd
JOIN (
    SELECT sm.real_name, MAX(sm.id) AS member_id
    FROM ai_platform.sys_member sm
    WHERE sm.delete_flag = 0
      AND sm.real_name IS NOT NULL AND sm.real_name <> ''
    GROUP BY sm.real_name
    HAVING COUNT(*) = 1
) sm ON sm.real_name COLLATE utf8mb4_unicode_ci = bd.customer COLLATE utf8mb4_unicode_ci
SET bd.platform_member_id = sm.member_id
WHERE bd.platform_member_id IS NULL AND bd.delete_flag = 0;

-- ═══ 验证 ═══
SELECT COUNT(*) AS total,
       SUM(customer_phone IS NOT NULL) AS with_phone,     -- 上线前预期 0
       SUM(platform_member_id IS NOT NULL) AS matched,
       SUM(platform_member_id IS NULL) AS unmatched
FROM ops_business_data WHERE delete_flag = 0;

-- 覆盖分布（预期 matched ≈ 2600~2800）：
SELECT corp_id, COUNT(*) AS matched_rows
FROM ops_business_data
WHERE delete_flag = 0 AND platform_member_id IS NOT NULL
GROUP BY corp_id;

-- 孤儿检查（预期 0）：所有 platform_member_id 必须指向 sys_member.id
SELECT COUNT(*) AS orphan_count
FROM ops_business_data bd
WHERE bd.delete_flag = 0 AND bd.platform_member_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM ai_platform.sys_member sm
                  WHERE sm.id = bd.platform_member_id);
