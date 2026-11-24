-- =====================================================================
-- 施工员接单页优化：施工记录落库 + 流转记录施工员留痕 + 文案统一
-- 执行库：mall_db（ops 业务表）+ ai_platform（状态字典）
-- 执行顺序：备份 → 本脚本 → 重启 platform（字典缓存）→ 发布 ops/SCM 新版本
-- 幂等：列经 information_schema 条件化；字典按 item_key 更新；可重复执行。
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4
-- =====================================================================

USE mall_db;
SET NAMES utf8mb4;

-- ═══════════════════════════════════════════════════════════════════
-- 1. 订单表：施工记录字段（提交验收申请时一次性保存，避免记录丢失）
-- ═══════════════════════════════════════════════════════════════════
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'construction_note');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN construction_note VARCHAR(1000) NULL COMMENT ''施工说明'' AFTER start_time',
    'SELECT ''construction_note already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'completion_note');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN completion_note VARCHAR(1000) NULL COMMENT ''完工说明'' AFTER construction_note',
    'SELECT ''completion_note already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'construction_attachment_ids');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN construction_attachment_ids VARCHAR(200) NULL COMMENT ''施工记录现场照片附件ID，逗号分隔'' AFTER completion_note',
    'SELECT ''construction_attachment_ids already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'acceptance_submitted_time');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN acceptance_submitted_time DATETIME NULL COMMENT ''提交验收申请时间'' AFTER construction_attachment_ids',
    'SELECT ''acceptance_submitted_time already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══════════════════════════════════════════════════════════════════
-- 2. 施工流转表：派工指派的目标施工员（操作人仍为派工人，用于审计）
-- ═══════════════════════════════════════════════════════════════════
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_order_construct_log'
      AND COLUMN_NAME = 'worker_id');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_order_construct_log ADD COLUMN worker_id BIGINT NULL COMMENT ''目标施工员 sys_user.id（ASSIGN）'' AFTER notice_id',
    'SELECT ''worker_id already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_order_construct_log'
      AND COLUMN_NAME = 'worker_name');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_order_construct_log ADD COLUMN worker_name VARCHAR(50) NULL COMMENT ''目标施工员姓名'' AFTER worker_id',
    'SELECT ''worker_name already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_order_construct_log'
      AND COLUMN_NAME = 'worker_phone');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_order_construct_log ADD COLUMN worker_phone VARCHAR(32) NULL COMMENT ''目标施工员手机号'' AFTER worker_name',
    'SELECT ''worker_phone already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 历史 ASSIGN 日志回填：仅订单只有一次派工历史时回填，多次指派不猜测
UPDATE ops_order_construct_log l
JOIN ops_business_order o ON o.id = l.order_id
LEFT JOIN ai_platform.sys_user u ON u.id = o.worker_id
SET l.worker_id = o.worker_id,
    l.worker_name = o.worker_name,
    l.worker_phone = COALESCE(u.phone, u.username)
WHERE l.action = 'ASSIGN' AND l.delete_flag = 0 AND l.worker_id IS NULL
  AND o.worker_id IS NOT NULL
  AND (SELECT COUNT(*) FROM ops_order_construct_log l2
       WHERE l2.order_id = l.order_id AND l2.action = 'ASSIGN' AND l2.delete_flag = 0) = 1;

-- ═══════════════════════════════════════════════════════════════════
-- 3. 存量 CUSTOMER_CONFIRMED 订单自动进入施工中（签字即开工）
-- ═══════════════════════════════════════════════════════════════════
INSERT INTO ops_order_construct_log
    (corp_id, order_id, order_no, from_status, to_status, action,
     operator_id, operator_name, operator_type, operate_time,
     location_status, notice_id,
     update_key, unique_id, create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT o.corp_id, o.id, COALESCE(o.order_no, ''), 'CUSTOMER_CONFIRMED', '4', 'START',
       COALESCE(o.worker_id, 0), o.worker_name, o.worker_user_type, NOW(),
       NULL,
       (SELECT n.id FROM ops_customer_notice n
        WHERE n.order_id = o.id AND n.status = 1 AND n.delete_flag = 0
        ORDER BY n.version DESC LIMIT 1),
       REPLACE(UUID(), '-', ''), REPLACE(UUID(), '-', ''), NOW(), NOW(),
       o.worker_id, o.worker_id, o.worker_name, o.worker_name, 0,
       '上线迁移：客户已签字自动进入施工中', 0
FROM ops_business_order o
WHERE o.delete_flag = 0 AND o.biz_status = 'CUSTOMER_CONFIRMED'
  AND NOT EXISTS (
      SELECT 1 FROM ops_order_construct_log l
      WHERE l.order_id = o.id AND l.action = 'START' AND l.delete_flag = 0);

UPDATE ops_business_order
SET biz_status = '4',
    start_time = COALESCE(start_time, NOW()),
    version = COALESCE(version, 0) + 1,
    modify_time = NOW()
WHERE delete_flag = 0 AND biz_status = 'CUSTOMER_CONFIRMED';

-- ═══════════════════════════════════════════════════════════════════
-- 4. 状态字典：接收 → 接受（页面状态标签与待办保持一致）
-- ═══════════════════════════════════════════════════════════════════
UPDATE ai_platform.sys_dict_item i
JOIN ai_platform.sys_dict_type t ON t.id = i.type_id AND t.delete_flag = 0
SET i.item_value = CASE i.item_key
        WHEN 'WAIT_ACCEPT' THEN '待接受'
        WHEN 'ACCEPTED' THEN '已接受'
        ELSE i.item_value END,
    i.modify_time = NOW()
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status'
  AND i.delete_flag = 0 AND i.item_key IN ('WAIT_ACCEPT', 'ACCEPTED');

-- ═══════════════════════════════════════════════════════════════════
-- 5. 验证
-- ═══════════════════════════════════════════════════════════════════
SELECT 'order construct columns' AS check_name, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order'
  AND COLUMN_NAME IN ('construction_note', 'completion_note',
                      'construction_attachment_ids', 'acceptance_submitted_time');

SELECT 'log worker columns' AS check_name, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_order_construct_log'
  AND COLUMN_NAME IN ('worker_id', 'worker_name', 'worker_phone');

SELECT 'status dict' AS check_name, i.item_key, i.item_value
FROM ai_platform.sys_dict_type t
JOIN ai_platform.sys_dict_item i ON i.type_id = t.id AND i.delete_flag = 0
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status'
  AND i.item_key IN ('WAIT_ACCEPT', 'ACCEPTED', '4', '5', '6');

SELECT 'remaining CUSTOMER_CONFIRMED' AS check_name, COUNT(*) AS cnt
FROM ops_business_order
WHERE delete_flag = 0 AND biz_status = 'CUSTOMER_CONFIRMED';
