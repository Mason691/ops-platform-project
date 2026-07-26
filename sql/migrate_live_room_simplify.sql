-- 简化直播配置表：删除不再使用的字段，保留 live_link 为唯一跳转方式
-- 执行前请确认数据已备份

ALTER TABLE ops_live_room DROP COLUMN IF EXISTS finder_username;
ALTER TABLE ops_live_room DROP COLUMN IF EXISTS jump_url;
ALTER TABLE ops_live_room DROP COLUMN IF EXISTS scheme_ticket;

-- 直播链接字段（若尚未添加）
-- ALTER TABLE ops_live_room ADD COLUMN IF NOT EXISTS live_link VARCHAR(500) NULL COMMENT '第三方直播链接';
