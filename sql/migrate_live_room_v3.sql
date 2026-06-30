-- 扩展 ops_live_room 表，支持直播简介展示
-- 扩展 ops_remind_log 表，支持直播开播提醒
-- 执行时间：2026-06-30

ALTER TABLE `ops_live_room`
  ADD COLUMN `description` TEXT DEFAULT NULL COMMENT '直播间简介（展示在跳转前页面，支持换行）' AFTER `remark`;

-- 扩展提醒日志：支持直播间开播提醒（原先仅支持活动预约提醒）
ALTER TABLE `ops_remind_log`
  ADD COLUMN `live_room_id` BIGINT DEFAULT NULL COMMENT '关联直播间ID' AFTER `booking_id`,
  MODIFY COLUMN `remind_type` VARCHAR(50) NOT NULL DEFAULT 'ACTIVITY'
    COMMENT '提醒类型: ACTIVITY=活动, LIVE=直播';
