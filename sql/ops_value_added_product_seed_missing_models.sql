-- 增值产品缺失型号补录（2026-09-20，920.xlsx 导入验证发现）
-- 价格口径：成交价众数 + 90% 底价；集团最低价无真实数据，保守取=零售底价
-- 幂等：NOT EXISTS 守卫，可重复执行
-- 注意：执行时必须 --default-character-set=utf8mb4，避免中文双重编码
-- 小松鼠 L1PB20-C30D 源文件销售价为 924000（疑似录入错误多两个 0），按 9240 补录

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '1', 'B1B08XG', 630.00, 567.00, 567.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='1' AND model='B1B08XG' AND delete_flag=0);

-- 万和 Q1L16111：武山特批行（ID=134749）导出底价兜底匹配需要，成交价唯一观测值 800 → 底价 720
INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '1', 'Q1L16111', 800.00, 720.00, 720.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='1' AND model='Q1L16111' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '小松鼠', '3', '13H20', 2488.00, 2239.20, 2239.20, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='3' AND model='13H20' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '小松鼠', '3', '16H20', 2888.00, 2599.20, 2599.20, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='3' AND model='16H20' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'L1PB26-C24B1S', 6300.00, 5670.00, 5670.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB26-C24B1S' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'LL1GBQ24-P24B5', 11200.00, 10080.00, 10080.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1GBQ24-P24B5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'LL1PBD24-U24B4', 9800.00, 8820.00, 8820.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD24-U24B4' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '小松鼠', '4', 'L1PB26-C30D', 6000.00, 5400.00, 5400.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB26-C30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '小松鼠', '4', 'LL1GBQ25-M6', 10000.00, 9000.00, 9000.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='LL1GBQ25-M6' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'LL1PBD24-Y24BSpro', 7800.00, 7020.00, 7020.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD24-Y24BSpro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '小松鼠', '4', 'L1PB20-C30D', 9240.00, 8316.00, 8316.00, 4, 1, 4, 0, '920.xlsx 导入验证补录（源价 924000 疑录错，按 9240）'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB20-C30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'L1PB26-Y24B1S', 5000.00, 4500.00, 4500.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB26-Y24B1S' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'LL1PBD24-N24B4LS', 12000.00, 10800.00, 10800.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD24-N24B4LS' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'LL1GBQ24-N24B5', 13500.00, 12150.00, 12150.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1GBQ24-N24B5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'LL1PBD24-K24Bpro', 9000.00, 8100.00, 8100.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD24-K24Bpro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     operator_id, status, corp_id, delete_flag, remark)
SELECT '万和', '4', 'L1PB20-Y18B1S', 19600.00, 17640.00, 17640.00, 4, 1, 4, 0, '920.xlsx 导入验证补录'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB20-Y18B1S' AND delete_flag=0);
