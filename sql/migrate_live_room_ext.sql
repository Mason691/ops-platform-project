-- 扩展 ops_live_room 表，支持直播状态管理
-- 执行时间：2026-06-30
-- 说明：增加回放URL、观看人数、实际开播/结束时间字段

ALTER TABLE `ops_live_room`
  ADD COLUMN `replay_url`        VARCHAR(500) DEFAULT NULL COMMENT '回放视频URL（直播结束后运营填写）' AFTER `status`,
  ADD COLUMN `viewer_count`      INT          NOT NULL DEFAULT 0 COMMENT '观看人数（运营手动填写或API获取）' AFTER `replay_url`,
  ADD COLUMN `actual_start_time` DATETIME     DEFAULT NULL COMMENT '实际上播时间' AFTER `end_time`,
  ADD COLUMN `actual_end_time`   DATETIME     DEFAULT NULL COMMENT '实际结束时间' AFTER `actual_start_time`;
