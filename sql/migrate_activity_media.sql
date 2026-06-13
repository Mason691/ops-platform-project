-- ============================================================
-- 活动媒体升级：封面裁剪 + 视频 + 多图
-- 目标库：mall_db
-- ============================================================
USE mall_db;

-- ═══ 1. 封面图 URL（800×800 裁剪后上传） ═══
ALTER TABLE `ops_activity`
    ADD COLUMN IF NOT EXISTS `cover_url` VARCHAR(500) DEFAULT NULL COMMENT '封面图URL（800×800裁剪后）';

-- ═══ 2. 详情视频 URL ═══
ALTER TABLE `ops_activity`
    ADD COLUMN IF NOT EXISTS `detail_video_url` VARCHAR(500) DEFAULT NULL COMMENT '详情视频URL（MP4/MOV）';

-- ═══ 3. 详情图片 URL 列表（JSON 数组） ═══
ALTER TABLE `ops_activity`
    ADD COLUMN IF NOT EXISTS `detail_images` TEXT DEFAULT NULL COMMENT '详情图片URL列表（JSON数组）';
