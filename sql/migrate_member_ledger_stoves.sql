-- ============================================================================
-- 安检设备创建会员台账 — ops_member_ledger 表结构扩展
-- 执行库：mall_db
--
-- 变更：
--   1. 新增 platform_member_id — 平台会员ID（ai_platform.sys_member.id），
--      由 ops_gas_user.platform_member_id 带入
--   2. 新增 gas_stoves_id — 来源设备ID（ops_gas_stoves_info.id），去重键，
--      防止同一设备重复创建台账
--   3. usage_date 改为可空 — 设备无安装/购买/启用日期时（生产约 8,240/10,765 台），
--      台账仍创建但使用日期为空，页面显示 '-'
--   4. 唯一索引 (gas_stoves_id, delete_flag) — 设备+逻辑删除双键，
--      逻辑删除后可再次创建，活跃记录不可重复
--
-- 影响面确认：BattleMapEtlService 对 ops_member_ledger 仅使用
-- corp_id/activity_id/community_id/agent/sales_price/delete_flag，
-- 不涉及 usage_date，且 SUM 均带 COALESCE —— 本变更不影响 ETL。
-- ============================================================================

ALTER TABLE ops_member_ledger
    ADD COLUMN platform_member_id BIGINT DEFAULT NULL COMMENT '平台会员ID（ai_platform.sys_member.id，安检设备台账自动填充）' AFTER member_id,
    ADD COLUMN gas_stoves_id VARCHAR(32) DEFAULT NULL COMMENT '来源安检设备ID（ops_gas_stoves_info.id，去重键）' AFTER platform_member_id,
    MODIFY COLUMN usage_date DATE NULL COMMENT '使用日期（设备无安装日期时为空）',
    ADD UNIQUE KEY uk_ledger_stoves (gas_stoves_id, delete_flag),
    ADD INDEX idx_platform_member (platform_member_id);

-- 验证：
-- SHOW COLUMNS FROM ops_member_ledger LIKE 'gas_stoves_id';
-- SHOW COLUMNS FROM ops_member_ledger LIKE 'usage_date';
-- SHOW INDEX FROM ops_member_ledger WHERE Key_name IN ('uk_ledger_stoves','idx_platform_member');
