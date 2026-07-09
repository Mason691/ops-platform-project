-- 个人提成规则：新增「固定金额」提成类型支持
-- commission_type 取值扩展为：阶梯 / 固定 / 固定金额
-- 当 commission_type='固定金额' 时，提成直接取 commission_amount，不按销售额计算
ALTER TABLE ops_personal_commission_rule
    ADD COLUMN commission_amount DECIMAL(10,2) NULL COMMENT '提成金额，当提成类型为固定金额时使用'
    AFTER commission_rate;
