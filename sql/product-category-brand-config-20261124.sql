-- =====================================================================
-- 品类品牌规则字典（PRODUCT_CATEGORY_BRAND）
-- 目标库：ai_platform
-- itemKey = 供应链品类名称；itemValue = 0/否 品牌可选，1/是 品牌必填
-- 未配置的品类按「是否存在带品牌商品」自动判定，本字典用于运营显式覆盖。
-- 幂等：按 type_code / item_key 判重，可重复执行。
-- =====================================================================

USE ai_platform;

INSERT INTO sys_dict_type
    (corp_id, type_code, type_name, status, update_key, unique_id,
     create_time, modify_time, delete_flag)
SELECT 0, 'PRODUCT_CATEGORY_BRAND', '品类品牌必填规则', 1, '', REPLACE(UUID(), '-', ''),
       NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_type
    WHERE corp_id = 0 AND type_code = 'PRODUCT_CATEGORY_BRAND' AND delete_flag = 0
);

INSERT INTO sys_dict_item
    (corp_id, type_id, item_key, item_value, item_order, status,
     update_key, unique_id, create_time, modify_time, delete_flag)
SELECT t.corp_id, t.id, v.item_key, v.item_value, v.item_order, 1,
       '', v.uid, NOW(), NOW(), 0
FROM sys_dict_type t
JOIN (
    SELECT '报警器' AS item_key, '0' AS item_value, 1 AS item_order, 'pcb-01' AS uid UNION ALL
    SELECT '辅材耗材', '0', 2, 'pcb-02'
) v
WHERE t.corp_id = 0 AND t.type_code = 'PRODUCT_CATEGORY_BRAND' AND t.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.type_id = t.id AND i.item_key = v.item_key AND i.delete_flag = 0
  );

SELECT t.type_code, i.item_key, i.item_value, i.item_order
FROM sys_dict_type t
JOIN sys_dict_item i ON i.type_id = t.id AND i.delete_flag = 0
WHERE t.corp_id = 0 AND t.type_code = 'PRODUCT_CATEGORY_BRAND' AND t.delete_flag = 0
ORDER BY i.item_order;
