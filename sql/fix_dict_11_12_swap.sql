-- ============================================================================
-- 修复：平台字典 PRODUCT_CATEGORY 中 item_key 11/12 的值与存量数据约定相反
-- 执行库：ai_platform（平台库，非 mall_db）
-- 执行日期：2026-09-02（已执行验证）
--
-- 背景：
--   1. sql/migrate_new_categories.sql 种子约定：11=美装管道整改，12=美装安全评估
--   2. sql/fix_category_chinese_to_key.sql 按该约定落库：category 列存 '11'/'12'
--   3. 生产库中 PRODUCT_CATEGORY 的 11/12 值曾被反向修改（11=美装安全评估，
--      12=美装管道整改），导致导出/列表页展示与真实数据对调（数据本身无损）
--   4. 存量数据（corp 12）：147 条 category='11' + 108 条 category='12'，
--      与本脚本修正后的映射一致，无需迁移业务数据
--
-- 修复后必须执行的操作（缺一不可）：
--   a. 驱逐平台 dict 缓存：POST /api/sys/cache/refresh/dict（直接改库不触发
--      @CacheEvictMulti；不驱逐则平台 API 仍返回旧值最长 30 分钟）
--   b. 重启 ops 后端容器：BusinessDataServiceImpl 的 KEY_TO_CN/categoryNameCache
--      为 static 且首次加载后永不刷新，必须重启才会重新拉取字典
--   c. 作战地图 ETL 直读 sys_dict_item，下次 30 分钟 cron 自动自愈，无需操作
-- ============================================================================

UPDATE ai_platform.sys_dict_item i
JOIN ai_platform.sys_dict_type d
  ON d.id = i.type_id AND d.type_code = 'PRODUCT_CATEGORY'
SET i.item_value = '美装管道整改'
WHERE i.item_key = '11';

UPDATE ai_platform.sys_dict_item i
JOIN ai_platform.sys_dict_type d
  ON d.id = i.type_id AND d.type_code = 'PRODUCT_CATEGORY'
SET i.item_value = '美装安全评估'
WHERE i.item_key = '12';

-- 验证（应输出 11=美装管道整改 / 12=美装安全评估）：
-- SELECT i.item_key, i.item_value
-- FROM ai_platform.sys_dict_type d
-- JOIN ai_platform.sys_dict_item i ON i.type_id = d.id
-- WHERE d.type_code = 'PRODUCT_CATEGORY' AND i.item_key IN ('11','12');
