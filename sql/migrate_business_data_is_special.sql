-- 增值业务数据：特批字段改造（2026-09-20 二次调整）
-- 1. 是否特批比例 → 是否特比（is_special），移到提成比例 commission_rate 之前
-- 2. 特批比例 special_ratio 字段删除，特批行的比例值统一存入 commission_rate
-- 前置：migrate_business_data_special_ratio.sql 已在生产执行（is_special_ratio/special_ratio 两列存在，存量全为默认值）。
ALTER TABLE ops_business_data
    DROP COLUMN special_ratio,
    CHANGE COLUMN is_special_ratio is_special VARCHAR(2) NOT NULL DEFAULT '否'
        COMMENT '是否特比（是/否）：是=启用提成比例字段直接计算提成' AFTER group_lowest_price;
