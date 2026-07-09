-- 修复：将 ops_business_data 中已存为中文名的品类修正为 dict key
-- 根因：旧版 BusinessDataImportHandler.mapCategoryKey() 未包含 11/12 映射，
--       导致导入时 category 列存为中文名（"美装管道整改"）而非 key（"11"）
-- 执行后需重新运行提成计算
UPDATE ops_business_data SET category = '11' WHERE category = '美装管道整改';
UPDATE ops_business_data SET category = '12' WHERE category = '美装安全评估';
