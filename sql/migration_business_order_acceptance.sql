-- =====================================================================
-- 业务订单：施工完成 → 完工验收（替代售后维护）
--   · 状态字典 6 改名为「完工验收」，新增 7「历史归档」
--   · 工作流 biz_order_flow：节点 6 施工完成（施工员确认）
--                         节点 7 完工验收（审批人 = 订单主经办人）
--   · 存量已完结「售后维护」订单归档为 7，不重新进入验收
--   · 完工验收附件新增 duration_seconds（视频时长）
-- 目标库：ai_platform + mall_db（同一 MySQL 实例）
-- 幂等：可重复执行。
-- 执行顺序（同一维护窗口）：
--   1) 平台 DDL：ai-native-platform/sql/migration-wf-approver-strategy.sql
--   2) 发布新平台 JAR（识别 ORDER_HANDLER，此时所有节点仍为 CONFIG，行为不变）
--   3) 执行本脚本 + 发布新 ops JAR（脚本改节点动作，必须与新校验同时生效）
--   4) 发布两套前端
--   5) 执行角色清理阶段一
-- 注意：新 ops JAR 依赖 duration_seconds 列，SQL 必须先于 JAR。
-- 回滚：migration_business_order_acceptance_rollback.sql
-- =====================================================================

-- ═══════════════════════════════════════════════════════════════════
-- 0. 迁移前核对（结果请留存）
-- ═══════════════════════════════════════════════════════════════════
SELECT 'orders_by_status' AS check_name, biz_status, COUNT(*) AS cnt
FROM mall_db.ops_business_order WHERE delete_flag = 0
GROUP BY biz_status ORDER BY biz_status;

SELECT 'running_instances' AS check_name, COUNT(*) AS cnt
FROM ai_platform.wf_instance i
JOIN ai_platform.wf_definition d ON d.id = i.definition_id
WHERE d.definition_code = 'biz_order_flow' AND d.delete_flag = 0
  AND i.status = 'RUNNING';

SELECT 'pending_at_legacy_nodes' AS check_name,
       n.node_name, COUNT(*) AS cnt
FROM ai_platform.wf_task t
JOIN ai_platform.wf_definition_node n ON n.id = t.node_id
JOIN ai_platform.wf_definition d ON d.id = n.definition_id
WHERE d.definition_code = 'biz_order_flow' AND d.delete_flag = 0
  AND n.node_name IN ('施工完成', '售后维护') AND t.status = 'PENDING'
GROUP BY n.node_name;

-- ═══════════════════════════════════════════════════════════════════
-- 1. 底座：审批人来源列（若平台 DDL 已执行则跳过）
-- ═══════════════════════════════════════════════════════════════════
USE ai_platform;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ai_platform' AND TABLE_NAME = 'wf_definition_node'
      AND COLUMN_NAME = 'approver_strategy'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE wf_definition_node ADD COLUMN approver_strategy VARCHAR(32) NOT NULL DEFAULT ''CONFIG'' COMMENT ''审批人来源：CONFIG=节点配置；ORDER_HANDLER=业务系统按单据经办人动态解析'' AFTER node_type',
    'SELECT ''approver_strategy already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 迁移前备份：biz_order_flow 全部节点审批人配置（供完整回滚）
CREATE TABLE IF NOT EXISTS bak_acceptance_migration_wf_node_approver
    LIKE wf_definition_node_approver;
INSERT IGNORE INTO bak_acceptance_migration_wf_node_approver
SELECT a.* FROM wf_definition_node_approver a
JOIN wf_definition_node n ON n.id = a.node_id
JOIN wf_definition d ON d.id = n.definition_id
WHERE d.definition_code = 'biz_order_flow';

-- ═══════════════════════════════════════════════════════════════════
-- 2. 状态字典：6=完工验收，7=历史归档
-- ═══════════════════════════════════════════════════════════════════
UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.id = i.type_id AND t.delete_flag = 0
SET i.item_value = '完工验收',
    i.item_order = 6,
    i.status = 1,
    i.modify_time = NOW()
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status'
  AND i.item_key = '6' AND i.delete_flag = 0;

INSERT INTO sys_dict_item
    (corp_id, type_id, item_key, item_value, item_order, status,
     update_key, unique_id, create_time, modify_time, delete_flag)
SELECT t.corp_id, t.id, '6', '完工验收', 6, 1,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0
FROM sys_dict_type t
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND t.delete_flag = 0
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item i
                  WHERE i.type_id = t.id AND i.item_key = '6' AND i.delete_flag = 0);

UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.id = i.type_id AND t.delete_flag = 0
SET i.item_value = '历史归档', i.item_order = 7, i.status = 1, i.modify_time = NOW()
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status'
  AND i.item_key = '7' AND i.delete_flag = 0;

INSERT INTO sys_dict_item
    (corp_id, type_id, item_key, item_value, item_order, status,
     update_key, unique_id, create_time, modify_time, delete_flag)
SELECT t.corp_id, t.id, '7', '历史归档', 7, 1,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0
FROM sys_dict_type t
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND t.delete_flag = 0
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item i
                  WHERE i.type_id = t.id AND i.item_key = '7' AND i.delete_flag = 0);

-- ═══════════════════════════════════════════════════════════════════
-- 3. 流程定义：节点 6 施工完成；节点 7 完工验收（ORDER_HANDLER）
-- ═══════════════════════════════════════════════════════════════════
UPDATE wf_definition_node n
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
SET n.node_name = '施工完成',
    n.action_type = 'OPS_CONSTRUCTION_RECORD',
    n.approver_strategy = 'CONFIG',
    n.biz_status = '5',
    n.return_mode = 'PREVIOUS',
    n.return_biz_status = '4',
    n.last_node = 0,
    n.form_schema = '[{"key":"completionNote","label":"完工说明","type":"textarea","required":false,"maxLength":1000},{"key":"photos","label":"现场照片","type":"text","required":false}]',
    n.modify_time = NOW(),
    n.modify_user_name = 'system-acceptance-migration'
WHERE d.definition_code = 'biz_order_flow' AND n.delete_flag = 0
  AND n.node_order = 6;

UPDATE wf_definition_node n
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
SET n.node_name = '完工验收',
    n.action_type = 'OPS_ACCEPTANCE',
    n.approver_strategy = 'ORDER_HANDLER',
    n.biz_status = '6',
    n.return_mode = 'PREVIOUS',
    n.return_biz_status = '5',
    n.last_node = 1,
    n.form_schema = '[{"key":"acceptanceConclusion","label":"验收结论","type":"radio","required":true,"options":[{"label":"通过","value":"PASS"},{"label":"不通过","value":"REJECT"}]},{"key":"acceptanceNote","label":"验收意见","type":"textarea","required":true,"maxLength":500}]',
    n.modify_time = NOW(),
    n.modify_user_name = 'system-acceptance-migration'
WHERE d.definition_code = 'biz_order_flow' AND n.delete_flag = 0
  AND (n.node_order = 7 OR n.node_name IN ('售后维护', '完工验收'));

-- 施工完成节点审批人：验收员 → 施工员（施工员角色保留）
UPDATE wf_definition_node_approver a
JOIN wf_definition_node n ON n.id = a.node_id AND n.delete_flag = 0
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
SET a.approver_role_name = '施工员',
    a.approver_scope = 'INITIATOR_CORP',
    a.modify_time = NOW(),
    a.modify_user_name = 'system-acceptance-migration'
WHERE d.definition_code = 'biz_order_flow'
  AND n.node_name = '施工完成'
  AND a.approver_role_name IN ('验收员', '售后', '售后维护', '售后服务')
  AND a.delete_flag = 0;

-- 完工验收节点由订单经办人动态解析，删除旧角色审批人配置
UPDATE wf_definition_node_approver a
JOIN wf_definition_node n ON n.id = a.node_id AND n.delete_flag = 0
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
SET a.delete_flag = 1,
    a.modify_time = NOW(),
    a.modify_user_name = 'system-acceptance-migration'
WHERE d.definition_code = 'biz_order_flow'
  AND n.node_name IN ('完工验收', '售后维护')
  AND a.delete_flag = 0;

-- ═══════════════════════════════════════════════════════════════════
-- 4. 在途流程重指派
--    4.1 完工验收：待办改派订单主经办人（agent_phones 第一个手机号）
--    4.2 施工完成：待办改派订单归属组织第一位在职施工员
--    实发环境核对结果应为 0 条；脚本仍保留以覆盖未来重跑场景。
-- ═══════════════════════════════════════════════════════════════════
UPDATE ai_platform.wf_task t
JOIN ai_platform.wf_instance i ON i.id = t.instance_id
JOIN ai_platform.wf_definition_node n ON n.id = t.node_id
JOIN ai_platform.wf_definition d ON d.id = n.definition_id
JOIN (
    SELECT o.id AS order_id,
           MIN(CASE WHEN u.status = 1 AND u.delete_flag = 0
                     AND (u.corp_id = o.corp_id OR uc.corp_id = o.corp_id)
                    THEN u.id END) AS user_id
    FROM mall_db.ops_business_order o
    JOIN ai_platform.sys_user u
      ON (u.username = SUBSTRING_INDEX(o.agent_phones, '、', 1)
          OR u.phone = SUBSTRING_INDEX(o.agent_phones, '、', 1))
    LEFT JOIN ai_platform.sys_user_corp uc
      ON uc.user_id = u.id AND uc.status = 1 AND uc.delete_flag = 0
    WHERE o.delete_flag = 0 AND o.agent_phones IS NOT NULL
      AND TRIM(o.agent_phones) <> ''
    GROUP BY o.id
) h ON h.order_id = CAST(i.business_id AS UNSIGNED)
JOIN ai_platform.sys_user hu ON hu.id = h.user_id
SET t.approver_user_id = hu.id,
    t.approver_name = COALESCE(hu.real_name, hu.username),
    t.approver_corp_id = hu.corp_id,
    t.node_name = '完工验收',
    t.modify_time = NOW()
WHERE d.definition_code = 'biz_order_flow' AND d.delete_flag = 0
  AND n.node_name = '完工验收' AND t.status = 'PENDING' AND t.cc_flag = 0
  AND t.delete_flag = 0;

UPDATE ai_platform.wf_task t
JOIN ai_platform.wf_instance i ON i.id = t.instance_id
JOIN ai_platform.wf_definition_node n ON n.id = t.node_id
JOIN ai_platform.wf_definition d ON d.id = n.definition_id
JOIN (
    SELECT o.id AS order_id, MIN(u.id) AS user_id
    FROM mall_db.ops_business_order o
    JOIN ai_platform.sys_user u
      ON (u.corp_id = o.corp_id OR EXISTS (
            SELECT 1 FROM ai_platform.sys_user_corp uc
            WHERE uc.user_id = u.id AND uc.corp_id = o.corp_id
              AND uc.status = 1 AND uc.delete_flag = 0))
     AND u.status = 1 AND u.delete_flag = 0
    JOIN ai_platform.sys_user_corp_role ur ON ur.user_id = u.id
    JOIN ai_platform.sys_corp_user_role cr
      ON cr.id = ur.corp_role_id AND cr.corp_id = o.corp_id
     AND cr.role_name = '施工员' AND cr.delete_flag = 0
    WHERE o.delete_flag = 0
    GROUP BY o.id
) worker ON worker.order_id = CAST(i.business_id AS UNSIGNED)
JOIN ai_platform.sys_user wu ON wu.id = worker.user_id
SET t.approver_user_id = wu.id,
    t.approver_name = COALESCE(wu.real_name, wu.username),
    t.approver_corp_id = wu.corp_id,
    t.node_name = '施工完成',
    t.modify_time = NOW()
WHERE d.definition_code = 'biz_order_flow' AND d.delete_flag = 0
  AND n.node_name = '施工完成' AND t.status = 'PENDING' AND t.cc_flag = 0
  AND t.delete_flag = 0;

-- ═══════════════════════════════════════════════════════════════════
-- 5. 订单数据：附件时长列 + 历史归档 + 节点名同步
-- ═══════════════════════════════════════════════════════════════════
USE mall_db;

SET @dur_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_attachment'
      AND COLUMN_NAME = 'duration_seconds'
);
SET @ddl := IF(@dur_exists = 0,
    'ALTER TABLE ops_business_attachment ADD COLUMN duration_seconds INT NULL COMMENT ''视频时长（秒），图片为空'' AFTER content_type',
    'SELECT ''duration_seconds already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 迁移前备份：所有状态 6 订单的原状态/节点名（供精确回滚）
CREATE TABLE IF NOT EXISTS bak_acceptance_migration_orders (
    id BIGINT NOT NULL PRIMARY KEY,
    biz_status VARCHAR(10) NULL,
    wf_node_name VARCHAR(100) NULL
);
INSERT IGNORE INTO bak_acceptance_migration_orders (id, biz_status, wf_node_name)
SELECT id, biz_status, wf_node_name FROM ops_business_order
WHERE delete_flag = 0 AND (biz_status = '6' OR wf_node_name = '售后维护');

-- 已完结的原「售后维护」订单 → 历史归档，不重新进入完工验收
UPDATE ops_business_order o
JOIN ai_platform.wf_instance i ON i.id = o.wf_instance_id
SET o.biz_status = '7',
    o.wf_node_name = '历史归档',
    o.modify_time = NOW(),
    o.modify_user_name = 'system-acceptance-migration'
WHERE o.delete_flag = 0 AND o.biz_status = '6' AND i.status = 'COMPLETED';

-- 无流程实例的历史「售后维护」订单无法重新进入验收，同样归档
UPDATE ops_business_order o
SET o.biz_status = '7',
    o.wf_node_name = '历史归档',
    o.modify_time = NOW(),
    o.modify_user_name = 'system-acceptance-migration'
WHERE o.delete_flag = 0 AND o.biz_status = '6'
  AND (o.wf_instance_id IS NULL OR o.wf_instance_id = 0);

-- 未完结/仍在运行的旧售后节点 → 完工验收
UPDATE ops_business_order o
SET o.wf_node_name = '完工验收',
    o.modify_time = NOW(),
    o.modify_user_name = 'system-acceptance-migration'
WHERE o.delete_flag = 0 AND o.wf_node_name = '售后维护';

-- 迁移审计：写入业务订单流程留痕（订单详情可见）
INSERT INTO ops_business_flow_record
    (corp_id, order_id, instance_id, task_id, node_order, node_name, action_type,
     biz_status, operator_user_id, operator_name, opinion, form_json, result_json,
     create_time, modify_time, create_user_name, modify_user_name, delete_flag)
SELECT o.corp_id, o.id, o.wf_instance_id, NULL, o.wf_node_order, '历史归档', 'MIGRATE_STATUS',
       '7', NULL, 'system', '原「售后维护」已完结订单归档为「历史归档」，不重新进入完工验收',
       NULL, JSON_OBJECT('from', '6', 'to', '7', 'migration', 'acceptance-20261118'),
       NOW(), NOW(), 'system', 'system', 0
FROM ops_business_order o
WHERE o.delete_flag = 0 AND o.biz_status = '7'
  AND NOT EXISTS (
      SELECT 1 FROM ops_business_flow_record r
      WHERE r.order_id = o.id AND r.action_type = 'MIGRATE_STATUS' AND r.delete_flag = 0
  );

-- ═══════════════════════════════════════════════════════════════════
-- 6. 迁移后核对
-- ═══════════════════════════════════════════════════════════════════
SELECT 'dict_items' AS check_name, i.item_key, i.item_value, i.item_order
FROM ai_platform.sys_dict_type t
JOIN ai_platform.sys_dict_item i ON i.type_id = t.id AND i.delete_flag = 0
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND t.delete_flag = 0
ORDER BY i.item_order;

SELECT 'wf_nodes' AS check_name, d.definition_code, n.node_order, n.node_name,
       n.action_type, n.approver_strategy, n.biz_status, n.last_node
FROM ai_platform.wf_definition d
JOIN ai_platform.wf_definition_node n ON n.definition_id = d.id AND n.delete_flag = 0
WHERE d.definition_code = 'biz_order_flow' AND d.delete_flag = 0
ORDER BY n.node_order;

SELECT 'wf_approvers' AS check_name, n.node_name, a.approver_role_name, a.approver_scope
FROM ai_platform.wf_definition_node_approver a
JOIN ai_platform.wf_definition_node n ON n.id = a.node_id
JOIN ai_platform.wf_definition d ON d.id = n.definition_id
WHERE d.definition_code = 'biz_order_flow' AND a.delete_flag = 0
ORDER BY n.node_order;

SELECT 'orders_after' AS check_name, biz_status, COUNT(*) AS cnt
FROM ops_business_order WHERE delete_flag = 0
GROUP BY biz_status ORDER BY biz_status;

SELECT 'pending_acceptance' AS check_name, COUNT(*) AS cnt
FROM ai_platform.wf_task t
JOIN ai_platform.wf_definition_node n ON n.id = t.node_id
JOIN ai_platform.wf_definition d ON d.id = n.definition_id
WHERE d.definition_code = 'biz_order_flow' AND n.node_name = '完工验收'
  AND t.status = 'PENDING' AND t.delete_flag = 0;
