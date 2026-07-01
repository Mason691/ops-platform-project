-- ============================================================
-- 增值业务-业务数据：新增经办人手机号字段
-- 执行前确认：ops_business_data 表存在
-- ============================================================

-- 添加经办人手机号字段（NOT NULL DEFAULT '' 兼容已有数据）
ALTER TABLE ops_business_data
ADD COLUMN IF NOT EXISTS agent_phone VARCHAR(20) NOT NULL DEFAULT '' COMMENT '经办人手机号';

-- 添加索引
CREATE INDEX IF NOT EXISTS idx_agent_phone ON ops_business_data(agent_phone);
