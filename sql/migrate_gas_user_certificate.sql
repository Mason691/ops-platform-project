-- 燃气用户表新增供气证号字段（用于外部系统导入去重）
ALTER TABLE ops_gas_user
  ADD COLUMN IF NOT EXISTS gas_supply_certificate_number VARCHAR(100) DEFAULT NULL COMMENT '供气证号（外部系统来源，与phone联合去重）',
  ADD COLUMN IF NOT EXISTS gas_company_name VARCHAR(200) DEFAULT NULL COMMENT '燃气公司名称';

-- 联合索引（phone + 供气证号），用于去重查询
CREATE INDEX IF NOT EXISTS idx_phone_cert ON ops_gas_user (phone, gas_supply_certificate_number);
