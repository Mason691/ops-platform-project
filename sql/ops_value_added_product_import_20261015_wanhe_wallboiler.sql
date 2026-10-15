-- 增值产品目录导入：万和壁挂炉（2026-10-15）
-- 来源：/home/jason/data/2026年万和壁挂炉.xls
--       md5 38bc4bf247c6c6867de5da0d61f0fead
--       表头：型号(系列) | 备注 | 型号 | 销售价 | 零售底价 | 集团最低价 | 空壳机
--       标题行「2026年金地燃气集团公司---万和壁挂炉价格表（26年3月25日开始执行）」
-- 口径（2026-10-15 与业务方确认）：
--   * 型号剥离尾部中文括注（如「L1PB40-Y36B3 (双泵，厚款）」→ L1PB40-Y36B3），
--     括注内容并入 description，使型号与业务数据表写法精确对齐（导入精确匹配路径）
--   * 源文件零售底价/集团最低价两列全空，且本表两列为 NOT NULL（非严格模式下 NULL 静默转 0），
--     故显式写 0.00 表示「无底价数据」，不按 90% 派生
--   * description = 系列名：备注（；括注）；remark = 空壳机：有/无；image_url 留空
-- 幂等：NOT EXISTS 守卫，可重复执行
-- 执行：必须 --default-character-set=utf8mb4
--
-- 型号数：32（11 个系列，9 个型号带括注）

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB20-Y18B2', 5200.00, 0.00, 0.00,
       'YB2（二级能效）：旋钮和按键操作，厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB20-Y18B2' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB26-Y24B2', 5800.00, 0.00, 0.00,
       'YB2（二级能效）：旋钮和按键操作，厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB26-Y24B2' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB32-Y30B2', 6300.00, 0.00, 0.00,
       'YB2（二级能效）：旋钮和按键操作，厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB32-Y30B2' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB20-Y18BSPlus', 5700.00, 0.00, 0.00,
       'YBSPlus（二级能效）：触摸屏，标配WIFI，分段燃烧', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB20-Y18BSPlus' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB26-Y24BSPlus', 6000.00, 0.00, 0.00,
       'YBSPlus（二级能效）：触摸屏，标配WIFI，分段燃烧', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB26-Y24BSPlus' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB30-Y28BSPlus', 6800.00, 0.00, 0.00,
       'YBSPlus（二级能效）：触摸屏，标配WIFI，分段燃烧', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB30-Y28BSPlus' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB35-Y32BSPlus', 7500.00, 0.00, 0.00,
       'YBSPlus（二级能效）：触摸屏，标配WIFI，分段燃烧；厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB35-Y32BSPlus' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB40-Y36BSPlus', 8000.00, 0.00, 0.00,
       'YBSPlus（二级能效）：触摸屏，标配WIFI，分段燃烧；厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB40-Y36BSPlus' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB20-C18B1S', 5800.00, 0.00, 0.00,
       'CB1S（二级能效）：触摸屏，WIFI，分段燃烧', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB20-C18B1S' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB26-C24B1S', 6200.00, 0.00, 0.00,
       'CB1S（二级能效）：触摸屏，WIFI，分段燃烧', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB26-C24B1S' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB30-C28B1S', 6900.00, 0.00, 0.00,
       'CB1S（二级能效）：触摸屏，WIFI，分段燃烧', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB30-C28B1S' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB40-Y36B3', 10000.00, 0.00, 0.00,
       'YB3（二级能效）：触摸屏，WIFI，分段燃烧；双泵，厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB40-Y36B3' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB46-Y42B3', 10800.00, 0.00, 0.00,
       'YB3（二级能效）：触摸屏，WIFI，分段燃烧；双泵，厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB46-Y42B3' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB53-Y48B3', 11800.00, 0.00, 0.00,
       'YB3（二级能效）：触摸屏，WIFI，分段燃烧；双泵，厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB53-Y48B3' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD24-Y24BSPro', 8300.00, 0.00, 0.00,
       'YBSPro（一级能效）：冷凝机，触摸屏，WIFI', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD24-Y24BSPro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD28-Y28BSPro', 8800.00, 0.00, 0.00,
       'YBSPro（一级能效）：冷凝机，触摸屏，WIFI', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD28-Y28BSPro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD24-K24BPro', 9000.00, 0.00, 0.00,
       'KBPro（一级能效）：冷凝机，触摸屏，WIFI,玻璃面板', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD24-K24BPro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD30-K30BPro', 9800.00, 0.00, 0.00,
       'KBPro（一级能效）：冷凝机，触摸屏，WIFI,玻璃面板', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD30-K30BPro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD24-D24B2Pro', 9500.00, 0.00, 0.00,
       'DB2Pro（一级能效）：冷凝机，触摸屏，WIFI,玻璃面板', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD24-D24B2Pro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD28-D28B2Pro', 10000.00, 0.00, 0.00,
       'DB2Pro（一级能效）：冷凝机，触摸屏，WIFI,玻璃面板', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD28-D28B2Pro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD36-D36B2Pro', 11600.00, 0.00, 0.00,
       'DB2Pro（一级能效）：冷凝机，触摸屏，WIFI,玻璃面板；厚款', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD36-D36B2Pro' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1GBQ24-P24B5', 11600.00, 0.00, 0.00,
       'PB5（一级能效,全预混）：全预混冷凝机，触摸屏，WIFI', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1GBQ24-P24B5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1GBQ28-P28B5', 13000.00, 0.00, 0.00,
       'PB5（一级能效,全预混）：全预混冷凝机，触摸屏，WIFI', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1GBQ28-P28B5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1GBQ24-N24B5', 13800.00, 0.00, 0.00,
       'NB5（一级能效,全预混）：全预混冷凝机，触摸屏，WIFI', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1GBQ24-N24B5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1GBQ30-N30B5', 14800.00, 0.00, 0.00,
       'NB5（一级能效,全预混）：全预混冷凝机，触摸屏，WIFI', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1GBQ30-N30B5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1GBQ36-X36B5', 16000.00, 0.00, 0.00,
       'XB5（一级能效,全预混）：全预混冷凝机，触摸屏，WIFI；厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1GBQ36-X36B5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1GBQ42-X42B5', 18000.00, 0.00, 0.00,
       'XB5（一级能效,全预混）：全预混冷凝机，触摸屏，WIFI；厚款', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1GBQ42-X42B5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LN1GBQ60-X60N5', 22000.00, 0.00, 0.00,
       'XB5（一级能效,全预混）：全预混冷凝机，触摸屏，WIFI；厚款，单暖', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LN1GBQ60-X60N5' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD24-N24B2L', 9800.00, 0.00, 0.00,
       'NB2L（一级能效,零冷水）：冷凝机，触摸屏，WIFI,玻璃面板，零冷水', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD24-N24B2L' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'LL1PBD30-N30B2L', 10600.00, 0.00, 0.00,
       'NB2L（一级能效,零冷水）：冷凝机，触摸屏，WIFI,玻璃面板，零冷水', '', '空壳机：有', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='LL1PBD30-N30B2L' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB20-Y18B1S', 4800.00, 0.00, 0.00,
       'YB1S(二级能效/项目用）：按键控制', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB20-Y18B1S' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '万和', '4', 'L1PB26-Y24B1S', 5000.00, 0.00, 0.00,
       'YB1S(二级能效/项目用）：按键控制', '', '空壳机：无', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='万和' AND category='4' AND model='L1PB26-Y24B1S' AND delete_flag=0);

