-- ============================================================
-- 会员表结构清理：删除微信 openid / unionid 字段
-- 目标库：ai_platform
-- 执行前请确认已备份 sys_member 表
-- 注意：MySQL 8.0 不支持 IF EXISTS，需按实际索引名精确删除
-- ============================================================

-- 1. 删除索引（wechat_openid 上存在 UNIQUE 约束 uk_wechat_openid + 普通索引 idx_openid）
ALTER TABLE ai_platform.sys_member DROP INDEX uk_wechat_openid;
ALTER TABLE ai_platform.sys_member DROP INDEX idx_openid;
ALTER TABLE ai_platform.sys_member DROP INDEX idx_unionid;

-- 2. 删除字段
ALTER TABLE ai_platform.sys_member DROP COLUMN wechat_openid;
ALTER TABLE ai_platform.sys_member DROP COLUMN wechat_unionid;

-- 3. 验证（执行后检查）
-- DESCRIBE ai_platform.sys_member;
-- 应确认 gender, birthday, address, community_id 已存在，wechat_openid / wechat_unionid 已删除
