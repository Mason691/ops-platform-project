-- ============================================================
-- 活动分类 + 直播关联 + 商品关联
-- 目标库：mall_db
-- ============================================================
USE mall_db;

-- ═══ 1. ops_activity 新增 type 字段 ═══
ALTER TABLE `ops_activity`
    ADD COLUMN `type` VARCHAR(20) NOT NULL DEFAULT 'activity' COMMENT '活动类型: live_reserve=直播预约, activity=活动展示';

-- ═══ 2. ops_activity 新增 live_room_id ═══
ALTER TABLE `ops_activity`
    ADD COLUMN `live_room_id` BIGINT DEFAULT NULL COMMENT '关联直播房间ID（type=live_reserve时有效）';

-- ═══ 3. 活动关联商品中间表 ═══
CREATE TABLE IF NOT EXISTS `ops_activity_product` (
    `activity_id` BIGINT NOT NULL COMMENT '活动ID',
    `product_id`  BIGINT NOT NULL COMMENT 'ops_product映射ID',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`activity_id`, `product_id`),
    INDEX `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动关联商品';

-- ═══ 4. 修正已有活动数据 ═══
UPDATE `ops_activity` SET `type` = 'activity' WHERE `type` IS NULL OR `type` = '';
