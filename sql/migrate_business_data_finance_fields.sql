-- 业务数据表字段调整：市场收款日期注释 + 财务收款日期 + 资金动态表横排数
-- 执行环境：mall_db（生产/测试同结构）；含中文注释，客户端需 utf8mb4
-- mysql --default-character-set=utf8mb4 mall_db < migrate_business_data_finance_fields.sql

ALTER TABLE ops_business_data
  MODIFY COLUMN receipt_date varchar(50) NULL COMMENT '市场收款日期',
  ADD COLUMN finance_receipt_date date NULL COMMENT '财务收款日期' AFTER receipt_date,
  ADD COLUMN fund_dynamic_rows int NULL COMMENT '资金动态表横排数' AFTER finance_receipt_date;
