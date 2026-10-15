-- 增值产品目录导入（2026-10-15）：小松鼠（4 个品类 sheet）
-- 来源：/home/jason/data/小松鼠.xlsx  md5 d2b07d41ef99ef8f1d1aae0e59e727f6
-- 口径：源文件有底价的行直接取用；缺底价的行（壁挂炉整表 + 热水器 JSQ46-24L80）按既定口径推导
--       零售底价 = 销售价 × 90%，集团最低价 = 零售底价（与旧目录小松鼠壁挂炉 9240→8316 等一致）
--       备注/产品特征列 → description；image_url / remark 留空
-- 幂等：NOT EXISTS 守卫；执行：--default-character-set=utf8mb4
--
INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SD18-B12', 5200.00, 4680.00, 4680.00,
       '轻薄精致设计，缩小安装空间 经济采暖运行模式，一键实现，eco模式节能高达25%，节能环保绿色经济智能恒温控制，提供快速稳定的热水，拥有较宽卫浴功率调节范围，满足夏季低水流量的热水需求。采用无氧铜材料主换热器与高品质不锈钢燃烧器，优化燃烧工况和气流分配，提高了采暖热水炉的效率。保修期2年，110㎡以内', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SD18-B12' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SD24-B12', 5500.00, 4950.00, 4950.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SD24-B12' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SP20-B6', 6500.00, 5850.00, 5850.00,
       'EC0智能间歇式供暖方式，倡导绿色节能生活，智能恒温控制，提供快速稳定的生活                                                                多重静音护盾，静享生活 高清大屏显示，运行状况了然于心，保修期2年', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SP20-B6' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SP26-B6', 7000.00, 6300.00, 6300.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SP26-B6' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SP31-B6', 8000.00, 7200.00, 7200.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SP31-B6' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SL25-M6', 9800.00, 8820.00, 8820.00,
       'IML覆膜工艺面板，时尚简约 轻触式按键大屏操控，人性化设计 手机远程控制，更智能                                                     全屋水电气火，52重安全保护 三级防冻功能，随时掌控家的温度 舒适体验：5:1燃烧负荷比，四季沐浴更舒适 整机噪音低至42分贝，静音护盾抗 一级能效，节能较常规壁挂炉31%以上，保修期5年', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SL25-M6' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SL30-M6', 10580.00, 9522.00, 9522.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SL30-M6' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SL35-M6', 11580.00, 10422.00, 10422.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SL35-M6' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB20-B23D', 7000.00, 6300.00, 6300.00,
       '高品质玻璃面板，清洁方便，璀璨品质 LED触控大屏，定制温暖简单点 WIFI远程操控，人未到，家先暖，保修期2年', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB20-B23D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'SL60-T2', 17200.00, 15480.00, 15480.00,
       '功率大、体积小，安装空间占用少·  IMD腹膜触摸屏,方便简洁更时尚3D火焰燃烧,更稳定,维护更方便。全预混系统炉,满足较大别墅及小型商用采暖需求一级能效,高效回收烟气余热,最高效率可达108%。可与水罐配合提供生活热水;多台锅炉可进行多种形式并联安装，保修期5年', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='SL60-T2' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'LNIGBQ80-T2', 22000.00, 19800.00, 19800.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='LNIGBQ80-T2' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB20-C30D', 5500.00, 4950.00, 4950.00,
       '体积缩小30%，自然融入家装；超大IML覆膜屏，轻松获取运行状态；                                                                                      板式换热技术，恒温热水舒心洗浴；分段燃烧技术，舒适热水不随“季”动                                                                          降噪低音设计，静音运转低至45dB；全屋水电气火，36重安全防护，保修期2年', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB20-C30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB26-C30D', 6000.00, 5400.00, 5400.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB26-C30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB32-C30D', 6500.00, 5850.00, 5850.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB32-C30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB36-C30D', 7000.00, 6300.00, 6300.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB36-C30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB40-C30D', 7500.00, 6750.00, 6750.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB40-C30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'LL1GBQ20-M20', 9000.00, 8100.00, 8100.00,
       '5:1燃烧负荷比，冬季澎湃热水，夏季舒爽沐浴；   抗电磁干扰装置，运行更稳定；                                  水电气火48重安全守护；                                                热效率最高可达108%，节能省气31%；                            多重降噪设计，静享温暖；                                         远程控制、语音控制，控温随心所欲，已加入米家智能生态，保修期5年', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='LL1GBQ20-M20' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'LL1GBQ25-M20', 10500.00, 9450.00, 9450.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='LL1GBQ25-M20' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'LL1GBQ30-M20', 11600.00, 10440.00, 10440.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='LL1GBQ30-M20' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'LL1GBQ35-M20', 12800.00, 11520.00, 11520.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='LL1GBQ35-M20' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB20-C18D', 5700.00, 5130.00, 5130.00,
       '旋钮式操作，长者轻松掌握多点控温技术，恒温洗浴更舒适，245mm超薄机身，紧凑精致更美观。语音控制，开启智慧懒生活，交叉流板式换热，经久耐用不易结构专利E、C技术，节能省气，15%智能分段燃烧四季。洗浴均舒适，36重安全防护，爱享舒适生活。保修期2年', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB20-C18D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB26-C18D', 5900.00, 5310.00, 5310.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB26-C18D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB20-B30D', 9200.00, 8280.00, 8280.00,
       '12寸超大触摸屏，全新蜕变之作，时尚时尚设计美学，精巧不占地，体积减少30%，自然融入家装。舒适洗浴，分段燃烧技术，板式换热技术，四点控温技术，热水恒温，舒适省心，三级防冻功能，智慧自检排故障。大动力循环水泵，智慧变频扬程最高可达7.5米，耗电量最高节省65%，内置智能芯片实时监控水泵运行。舒适热水两档变升，按需调控，舒适节能。智慧物联。保修期2年', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB20-B30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '4', 'L1PB26-B30D', 9500.00, 8550.00, 8550.00,
       '', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='4' AND model='L1PB26-B30D' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '3', 'JSQ23-12H06', 1890.00, 1700.00, 1330.00,
       '1.无氧铜水箱，铜离子抑菌，为宝宝洗多份健康礼； 2.全程舒适直流变频风机， 3.采用直流式排风系统，抗风能力强，燃烧更节能高效； 4.±1 ℃ 精控恒温，舒适热水体验; 12升、交流、恒温二级能效 530mm*350mm*155mm (长*宽*高)', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='3' AND model='JSQ23-12H06' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '3', 'JSQ30-16HB31', 2980.00, 2680.00, 1650.00,
       '1.点阵灰平面直角玻璃，底面专模; 2.精钢不锈钢水箱; 3.短七排三分段; 4.直流变频风机; 5,带漏保电源线插头; 6.精密比例阀: 7.塑料水流传感器; 8.白光LED显示! 9.直流循环水泵; 10.不锈钢烟管 520mm*335mm*170mm', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='3' AND model='JSQ30-16HB31' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '3', 'JSQ25-13HB30      （带防冻装置）', 3500.00, 3150.00, 1960.00,
       '1·精准算法，智能恒温 2·记忆合金水气双调，精准恒温                                                    3· 无盘管式不锈钢热交换器 4· 时尚高颜值，健康洗浴 13升、直流、恒温、二级能效 575mm*360mm*175mm  (长*宽*高)', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='3' AND model='JSQ25-13HB30      （带防冻装置）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '3', 'JSQ30-16HB30       （带防冻装置）', 3880.00, 3490.00, 2350.00,
       '1·精准算法，智能恒温 2·记忆合金水气双调，精准恒温                                                 3· 无盘管式不锈钢热交换器 4· 时尚高颜值，健康洗浴 16升、直流、恒温、二级能效 575mm*360mm*175mm  (长*宽*高)', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='3' AND model='JSQ30-16HB30       （带防冻装置）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '3', 'JSQ30-16LB33       （带防冻装置）', 4080.00, 3670.00, 2850.00,
       '1.冷灰暗纹玻璃面板； 2.风冷无盘管精钢芯； 3.双频智控零冷水； 4.AI自适应智控； 5.四维降噪，多重智能安防； 16升、直流、零冷水、一级能效 532x340x195 (长*宽*高)', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='3' AND model='JSQ30-16LB33       （带防冻装置）' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '3', 'JSQ46-24L80', 8500.00, 7650.00, 7650.00,
       '24升（4月21日 大柴旦报价）', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='3' AND model='JSQ46-24L80' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '1', 'JZT-Q02', 928.00, 836.00, 770.31,
       '1·直旋双模，火候更均匀 2·三维供氧，燃烧更充分 3·保洁炉头，清洁无死角 4·铜合金燃烧器，经久耐用 5.×形锅支架，时尚且耐用 常规、玻璃面板、4.8kw 开孔尺寸：630×330 4-R20mm 720mm*420mm*162mm (长*宽*高)', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='1' AND model='JZT-Q02' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '1', 'JZT-Q08', 1290.00, 1160.00, 1060.00,
       '1·5.2kw 大火力，瞬时锁鲜 2·铝合金燃烧器，经久耐用 3· 飞机炉头，可拆卸好清洁 4· 一级能效，高效节能 常规、玻璃面板、5.2kw 开孔尺寸：650×350  4-R20mm 750mm*430mm*150mm  (长*宽*高）', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='1' AND model='JZT-Q08' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '1', 'JZT-Q21', 1390.00, 1250.00, 1160.00,
       '1.5.2kw 大火力爆炒，鲜香美味 2 .内环稳焰，文火也稳定 3.黑品玻璃面板，易清洁 4.热电偶熄火保护，安全无忧 开孔尺寸：630×3304-R20mm                     670×3704-R20mm 常规、玻璃面板、5.2kw 720mm*420mm*124mm  (长*宽*高）', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='1' AND model='JZT-Q21' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '1', 'JZT-Q27', 1630.00, 1470.00, 1370.00,
       '1· 内外双层火力叠加 2·一级能效，高效节能 3·全进风式结构，燃烧更充分 4·纯铜火盖，经久耐用 开孔尺寸：630×330 4-R20mm 常规、玻璃面板、5.2kw 730mm*410mm*140mm(长*宽*高)', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='1' AND model='JZT-Q27' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '1', 'JZT-Q2572', 1850.00, 1670.00, 1570.00,
       '1.70%热效率，5.2kw大火力，超一级能效 2.三环烈焰稳火，宽频调火精控火候 3.第二代纳米易清洁技术疏水疏油打理更轻松 4.多重防爆设计层层坚实够安心 5.加厚弧形聚能炉架，聚拢热量更节能 面板：780*440钢化玻璃面板 开孔尺寸：630*330/650*350/680*380专款可变', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='1' AND model='JZT-Q2572' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '2', 'CXW-340-C06', 1380.00, 1240.00, 1030.30,
       '1.采用经典时尚的侧吸造型，简约大气        2.黑晶钢化玻璃和银色金属撞色设计            3.挥手控制功能、延时关机功能、一键自清洁功能                                                                 4.高品质塑封电机                                                                   风量：16m³/min                                                  900×400×900(mm)含围板', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='2' AND model='CXW-340-C06' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '2', 'CXW-265-T10', 1800.00, 1620.00, 1620.00,
       '1.体感+触模开关，轻轻一挥，告别油烟 2.895mm巨幕拢烟范围，高效不跑烟 3·第三代一键自清洁 欧式顶吸、风量风压：21m3/min 380pa895mm*450mm*565mm (长"宽"高）', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='2' AND model='CXW-265-T10' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '2', 'CXW-300-C11', 2750.00, 2480.00, 2000.00,
       '1· 电动拢烟板，不跑烟 · 54分贝超静音                                    2·小体积，不碰头 3·挥手控制，智能方便  4.第三代自清洁、侧吸 风量风压：25m3/min  470pa 895mm*350mm*840mm  (长*宽*高）', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='2' AND model='CXW-300-C11' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '2', 'CXW-310-L12', 2480.00, 2230.00, 1800.00,
       '1·一键清洗功能，免拆洗省心方便 2·一键爆炒功能，烹饪更省心 3· 全封闭塑封电机 4· 防水防锈提高寿命 顶侧双吸   风量:21m3/min   风压:≥350Pa 895mm*350mm*855mm  (长*宽*高）', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='2' AND model='CXW-310-L12' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '2', 'CXW-310-L15', 2780.00, 2510.00, 2050.00,
       '1.风量：23m³/min，最大静压：480Pa， 低空劲吸，闪吸油烟不上脸； 2.双重负压吸附，快速排烟； 3.平嵌橱柜，操作大空间； 4.智能体感操控，油污0触碰； 热风化油自清洁，持久强劲吸力；', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='2' AND model='CXW-310-L15' AND delete_flag=0);

INSERT INTO ops_value_added_product
    (brand, category, model, sale_price, retail_floor_price, group_lowest_price,
     description, image_url, remark, operator_id, status, corp_id, delete_flag)
SELECT '小松鼠', '2', 'CXW-260-L18', 3380.00, 3050.00, 2260.00,
       '1.雅灰AG肤感玻璃，抗爆无指纹; 2.风量：25m³/min，最大静压：1100Pa， 低空劲吸，闪吸油烟不上脸； 3.智能巡航系统，智能增压排放； 4.纤薄机身,顶部隐藏式平嵌橱柜； 5.智能体感操控，油污0触碰； 6.热风化油自清洁，持久强劲吸力；', '', '', 4, 1, 4, 0
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM ops_value_added_product
    WHERE brand='小松鼠' AND category='2' AND model='CXW-260-L18' AND delete_flag=0);

-- 合计 41 个型号
-- 推导价 [壁挂炉] SD18-B12: 源文件缺底价 → 销售价 5200.00 × 0.9 = 4680.00
-- 推导价 [壁挂炉] SD24-B12: 源文件缺底价 → 销售价 5500.00 × 0.9 = 4950.00
-- 推导价 [壁挂炉] SP20-B6: 源文件缺底价 → 销售价 6500.00 × 0.9 = 5850.00
-- 推导价 [壁挂炉] SP26-B6: 源文件缺底价 → 销售价 7000.00 × 0.9 = 6300.00
-- 推导价 [壁挂炉] SP31-B6: 源文件缺底价 → 销售价 8000.00 × 0.9 = 7200.00
-- 推导价 [壁挂炉] SL25-M6: 源文件缺底价 → 销售价 9800.00 × 0.9 = 8820.00
-- 推导价 [壁挂炉] SL30-M6: 源文件缺底价 → 销售价 10580.00 × 0.9 = 9522.00
-- 推导价 [壁挂炉] SL35-M6: 源文件缺底价 → 销售价 11580.00 × 0.9 = 10422.00
-- 推导价 [壁挂炉] L1PB20-B23D: 源文件缺底价 → 销售价 7000.00 × 0.9 = 6300.00
-- 推导价 [壁挂炉] SL60-T2: 源文件缺底价 → 销售价 17200.00 × 0.9 = 15480.00
-- 推导价 [壁挂炉] LNIGBQ80-T2: 源文件缺底价 → 销售价 22000.00 × 0.9 = 19800.00
-- 推导价 [壁挂炉] L1PB20-C30D: 源文件缺底价 → 销售价 5500.00 × 0.9 = 4950.00
-- 推导价 [壁挂炉] L1PB26-C30D: 源文件缺底价 → 销售价 6000.00 × 0.9 = 5400.00
-- 推导价 [壁挂炉] L1PB32-C30D: 源文件缺底价 → 销售价 6500.00 × 0.9 = 5850.00
-- 推导价 [壁挂炉] L1PB36-C30D: 源文件缺底价 → 销售价 7000.00 × 0.9 = 6300.00
-- 推导价 [壁挂炉] L1PB40-C30D: 源文件缺底价 → 销售价 7500.00 × 0.9 = 6750.00
-- 推导价 [壁挂炉] LL1GBQ20-M20: 源文件缺底价 → 销售价 9000.00 × 0.9 = 8100.00
-- 推导价 [壁挂炉] LL1GBQ25-M20: 源文件缺底价 → 销售价 10500.00 × 0.9 = 9450.00
-- 推导价 [壁挂炉] LL1GBQ30-M20: 源文件缺底价 → 销售价 11600.00 × 0.9 = 10440.00
-- 推导价 [壁挂炉] LL1GBQ35-M20: 源文件缺底价 → 销售价 12800.00 × 0.9 = 11520.00
-- 推导价 [壁挂炉] L1PB20-C18D: 源文件缺底价 → 销售价 5700.00 × 0.9 = 5130.00
-- 推导价 [壁挂炉] L1PB26-C18D: 源文件缺底价 → 销售价 5900.00 × 0.9 = 5310.00
-- 推导价 [壁挂炉] L1PB20-B30D: 源文件缺底价 → 销售价 9200.00 × 0.9 = 8280.00
-- 推导价 [壁挂炉] L1PB26-B30D: 源文件缺底价 → 销售价 9500.00 × 0.9 = 8550.00
-- 推导价 [热水器] JSQ46-24L80: 源文件缺底价 → 销售价 8500.00 × 0.9 = 7650.00
