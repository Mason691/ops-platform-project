-- 新增品类：美装管道整改 (item_key=11)、美装安全评估 (item_key=12)
-- 测试环境执行；生产环境已有这两个条目，此脚本幂等安全
INSERT INTO ai_platform.sys_dict_item (type_id, item_key, item_value, status, sort, delete_flag)
VALUES (9, '11', '美装管道整改', 1, 11, 0)
ON DUPLICATE KEY UPDATE item_value = VALUES(item_value), status = 1;

INSERT INTO ai_platform.sys_dict_item (type_id, item_key, item_value, status, sort, delete_flag)
VALUES (9, '12', '美装安全评估', 1, 12, 0)
ON DUPLICATE KEY UPDATE item_value = VALUES(item_value), status = 1;
