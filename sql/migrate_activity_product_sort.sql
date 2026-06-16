-- Migration: Add sort column to ops_activity_product for product reordering
-- Date: 2026-06-16
-- Context: Activity-product associations need explicit sort ordering

USE mall_db;

-- Add sort column
ALTER TABLE `ops_activity_product`
    ADD COLUMN `sort` INT NOT NULL DEFAULT 0 COMMENT '排序（数字越小越靠前）' AFTER `product_id`;

-- Index for efficient ordering in JOIN queries
ALTER TABLE `ops_activity_product`
    ADD INDEX `idx_activity_sort` (`activity_id`, `sort`);
