-- 增值产品目录导入（2026-10-15）
-- 背景：2026-10-15 整表清空后重建（80 行物理 DELETE，自增保留，下一 id 从 144 起）
-- 来源：
--   084708535e1f0cc4ea91482abb31550f  /home/jason/data/万和.xlsx
--   eee464c1d02689ba8ca29a223cdb2fd3  /home/jason/data/万家乐.xls
--   c248ab90c434c92f37f9f8145d6bcd86  /home/jason/data/美的.xls
-- 口径：销售价/零售底价/集团最低价 三价均直接取自源文件（不再按「众数 + 90%」推导）
--       备注列 → description（沿用旧目录字段约定）；image_url / remark 留空
--       万和表的集团最低价是源文件里的真实值（旧库中该字段是「=零售底价」的回退值，本次为升级）
-- 已知处理：
--   * 美的 JZT-QG50 集团最低价为坏公式 (#REF!+#REF!)/0.62 → 沿用旧目录同型号同价的历史计算值 990.32
--   * 小松鼠品牌（旧目录 17 个有效型号）不在本批源文件中，按决定暂不导入
-- 幂等：NOT EXISTS 守卫，可重复执行
-- 执行：必须 --default-character-set=utf8mb4
--
-- 型号数：万和 15（4 燃气灶/3 抽油烟机/8 热水器）+ 万家乐 21（6/3/5/7）+ 美的 18（4/5/4/5）= 54

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '1', 'Q1L16', 990.00, 891.00, 885.48,
       '5KW.可变径底盘', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='1' AND model='Q1L16' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '1', 'Q2L06', 1599.00, 1439.10, 1290.16,
       '5.2KW,可变径底盘', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='1' AND model='Q2L06' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '1', 'Q3L16', 1999.00, 1799.10, 1757.90,
       '5.2KW,可变径底盘', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='1' AND model='Q3L16' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '1', 'Q3L18E', 2599.00, 2339.10, 2306.29,
       '5.2KW,可变径底盘,无印玻璃', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='1' AND model='Q3L18E' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '2', 'CXW-380-X06SE（S)', 1999.00, 1799.10, 1967.58,
       '平板顶吸，触控面板', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='2' AND model='CXW-380-X06SE（S)' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '2', 'CXW-358-L06S75', 2999.00, 2699.10, 2838.55,
       '750mm宽，适合小户型，双吸力', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='2' AND model='CXW-358-L06S75' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '2', 'CXW-310-L07S', 2999.00, 2699.10, 3064.35,
       '顶侧双吸，手势控制，一键清洗', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='2' AND model='CXW-310-L07S' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '3', 'JSQ25-13Q6（二级能效）', 1999.00, 1799.10, 2096.61,
       '上黑下白，触摸屏', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='3' AND model='JSQ25-13Q6（二级能效）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '3', 'JSQ30-16Q6（二级能效）', 2599.00, 2339.10, 2515.97,
       '上黑下白，触摸屏', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='3' AND model='JSQ30-16Q6（二级能效）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '3', 'JSQ30-16Q20（二级能效）', 3199.00, 2879.10, 3096.61,
       '全白玻璃，触摸屏', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='3' AND model='JSQ30-16Q20（二级能效）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '3', 'JSQ30-16YL2HX（二级能效,零冷水）', 3999.00, 3599.10, 4274.03,
       '全白玻璃屏，触摸屏', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='3' AND model='JSQ30-16YL2HX（二级能效,零冷水）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '3', 'JSLQ27-16QV22 MAX(一级能效）', 3599.00, 3239.10, 3935.32,
       '海雾灰玻璃触摸屏屏，超静音', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='3' AND model='JSLQ27-16QV22 MAX(一级能效）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '3', 'JSLQ27-16QLV22 MAX(一级能效，零冷水）', 4299.00, 3869.10, 4564.35,
       '海雾灰玻璃触摸屏，超静音', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='3' AND model='JSLQ27-16QLV22 MAX(一级能效，零冷水）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '3', 'JSLQ27-16RV3（一级能效）', 3599.00, 3239.10, 3564.35,
       '全白玻璃触摸屏，超静音', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='3' AND model='JSLQ27-16RV3（一级能效）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '3', 'JSQ30-16Y2HXT(二级能效，不锈钢换热器，武山专供）', 3699.00, 3329.10, 3774.03,
       '全白玻璃，触摸屏，钛金不锈钢水箱', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='3' AND model='JSQ30-16Y2HXT(二级能效，不锈钢换热器，武山专供）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '1', 'JZT-CA021', 690.00, 620.00, 620.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='1' AND model='JZT-CA021' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '1', 'JZT-CA021BS', 890.00, 810.00, 690.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='1' AND model='JZT-CA021BS' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '1', 'JZT-CA031BS', 1080.00, 970.00, 810.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='1' AND model='JZT-CA031BS' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '1', 'JZT-CV3', 1250.00, 1125.00, 970.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='1' AND model='JZT-CV3' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '1', 'JZT-CV6', 1450.00, 1305.00, 1130.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='1' AND model='JZT-CV6' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '1', 'JZT-V1', 499.00, 449.10, 449.00,
       '单灶', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='1' AND model='JZT-V1' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '2', 'CXW-280-SC1', 1790.00, 1610.00, 1480.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='2' AND model='CXW-280-SC1' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '2', 'CXW-320-ST1', 2090.00, 1880.00, 1700.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='2' AND model='CXW-320-ST1' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '2', 'CXW-320-SV5', 2600.00, 2340.00, 2260.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='2' AND model='CXW-320-SV5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '3', 'JSQ25-13G1S', 1880.00, 1690.00, 1520.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='3' AND model='JSQ25-13G1S' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '3', 'JSQ30-16G1S(F)', 2100.00, 1890.00, 1680.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='3' AND model='JSQ30-16G1S(F)' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '3', 'JSQ26-13RH6(F)', 2700.00, 2430.00, 2180.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='3' AND model='JSQ26-13RH6(F)' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '3', 'JSQ30-16RH6(F)', 2930.00, 2640.00, 2450.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='3' AND model='JSQ30-16RH6(F)' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '3', 'JSQ30-16G5L', 3600.00, 3240.00, 2970.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='3' AND model='JSQ30-16G5L' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '4', 'L1PB20-18M3', 5190.00, 4670.00, 4020.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='4' AND model='L1PB20-18M3' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '4', 'L1PB26-24M3', 5600.00, 5040.00, 4330.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='4' AND model='L1PB26-24M3' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '4', 'L1PB30-28M3', 6090.00, 5480.00, 4650.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='4' AND model='L1PB30-28M3' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '4', 'L1PB26-24GL0', 6190.00, 5570.00, 4730.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='4' AND model='L1PB26-24GL0' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '4', 'L1PB40-36G2', 8900.00, 8010.00, 6960.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='4' AND model='L1PB40-36G2' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '4', 'LL1GBQ24-24Q5', 9700.00, 8730.00, 7640.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='4' AND model='LL1GBQ24-24Q5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万家乐', '4', 'LL1GBQ28-28Q5', 9900.00, 8910.00, 7800.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万家乐' AND category='4' AND model='LL1GBQ28-28Q5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '1', 'Q63B-M', 1590.00, 1430.00, 1338.00,
       '玻璃面板', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='1' AND model='Q63B-M' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '1', 'JZT-Q60G', 990.00, 890.00, 820.00,
       '玻璃面板', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='1' AND model='JZT-Q60G' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '1', 'JZT-QG50', 1190.00, 1070.00, 990.32,
       '玻璃面板', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='1' AND model='JZT-QG50' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '1', 'JZT-QG51', 1290.00, 1160.00, 1070.00,
       '玻璃面板', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='1' AND model='JZT-QG51' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '2', 'CXW-220-B80P', 1790.00, 1610.00, 1610.00,
       '欧式油抽油烟机', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='2' AND model='CXW-220-B80P' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '2', 'CXW-260-TJC3', 2590.00, 2330.00, 2230.00,
       '欧式油抽油烟机', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='2' AND model='CXW-260-TJC3' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '2', 'CXW-280-JJC2', 2590.00, 2330.00, 2230.00,
       '侧吸油抽油烟机', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='2' AND model='CXW-280-JJC2' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '2', 'CXW-260-F8255', 3390.00, 3050.00, 2900.00,
       '欧式油抽油烟机', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='2' AND model='CXW-260-F8255' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '2', 'CXW-140-XL26Z', 3990.00, 3590.00, 3350.00,
       '顶侧双吸抽油烟机', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='2' AND model='CXW-140-XL26Z' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '3', 'JSQ25-13HPC', 1590.00, 1430.00, 1430.00,
       '恒温', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='3' AND model='JSQ25-13HPC' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '3', 'JSQ30-16HPC', 2090.00, 1880.00, 1860.00,
       '恒温', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='3' AND model='JSQ30-16HPC' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '3', 'JSLQ27-HN16G9', 3890.00, 3510.00, 3310.00,
       '水伺服', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='3' AND model='JSLQ27-HN16G9' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '3', 'JSQ30-16HT1', 3190.00, 2870.00, 2690.00,
       '零冷水', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='3' AND model='JSQ30-16HT1' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '4', 'L1PB20-MC2II', 4200.00, 3780.00, 3780.00,
       '板换机分段燃烧', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='4' AND model='L1PB20-MC2II' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '4', 'L1PB24-MC2II', 4500.00, 4050.00, 4050.00,
       '板换机分段燃烧', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='4' AND model='L1PB24-MC2II' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '4', 'L1PB28-MC2II', 5000.00, 4500.00, 4500.00,
       '板换机分段燃烧', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='4' AND model='L1PB28-MC2II' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '4', 'LL1PBD24-ML1II', 7200.00, 6480.00, 6480.00,
       '冷凝机', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='4' AND model='LL1PBD24-ML1II' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '美的', '4', 'LL1PBD28- ML1II', 7900.00, 7110.00, 7110.00,
       '冷凝机', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='美的' AND category='4' AND model='LL1PBD28- ML1II' AND delete_flag=0);

-- 合计 54 个型号
-- ⚠️ 美的 JZT-QG50: 集团最低价用覆盖值 990.32（原文件为坏公式）
