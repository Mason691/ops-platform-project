-- =====================================================================
-- 业务订单作废退款：状态字典 -1 + 作废工作流定义种子
-- 目标库：ai_platform（DBA 执行；ops 运行时不连接 ai_platform）
-- 执行顺序：先执行底座 doc/add_business_order_status_dict.sql（0~6），再执行本脚本。
-- 幂等：按 type_code/item_key、definition_code、node_name 判重，可重复执行。
--
-- 流程：作废申请(START) → 部长审核 → 财务审核 → 总经办审核(末节点)
-- 业务类型：OPS_BUSINESS_ORDER_VOID
-- 回调：http://localhost:8082/api/internal/workflow/business-order/event
-- =====================================================================

USE ai_platform;
SET NAMES utf8mb4;

-- ── 1. 字典项 -1 / 作废退款 ────────────────────────────────────────
INSERT INTO sys_dict_type
    (corp_id, type_code, type_name, status, update_key, unique_id,
     create_time, modify_time, delete_flag)
SELECT 0, 'business_order_status', '业务数据订单状态', 1, '', REPLACE(UUID(), '-', ''),
       NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_type
    WHERE corp_id = 0 AND type_code = 'business_order_status' AND delete_flag = 0
);

INSERT INTO sys_dict_item
    (corp_id, type_id, item_key, item_value, item_order, status,
     update_key, unique_id, create_time, modify_time, delete_flag)
SELECT t.corp_id, t.id, '-1', '作废退款', -1, 1,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0
FROM sys_dict_type t
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND t.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.type_id = t.id AND i.item_key = '-1' AND i.delete_flag = 0
  );

-- ── 2. 流程定义（运营商组织；订单归属组织可为下级城市合伙人）────────
INSERT INTO wf_definition
    (corp_id, definition_code, definition_name, business_type, version, status,
     description, callback_url, update_key, unique_id,
     create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT c.id, 'biz_order_void_flow', '业务订单作废工作流', 'OPS_BUSINESS_ORDER_VOID', 1,
       'PUBLISHED', '员工发起作废退款 → 部长审核 → 财务审核 → 总经办审核；通过后订单置为作废退款',
       'http://localhost:8082/api/internal/workflow/business-order/event',
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM sys_corp c
WHERE c.delete_flag = 0 AND c.status = 1 AND c.org_role_id = 4
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition d
      WHERE d.corp_id = c.id AND d.definition_code = 'biz_order_void_flow'
        AND d.delete_flag = 0
  );

-- ── 3. 节点 ────────────────────────────────────────────────────────
INSERT INTO wf_definition_node
    (corp_id, definition_id, node_order, node_name, approve_mode, node_type,
     biz_status, return_mode, return_biz_status, action_type, form_schema, form_url,
     last_node, allow_skip, timeout_hours, remind_hours, escalate_hours,
     update_key, unique_id, create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT d.corp_id, d.id, 1, '作废申请', 'ANY', 'START',
       NULL, 'INITIATOR', NULL, NULL, NULL, NULL,
       0, 0, 24, 24, 48,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM wf_definition d
WHERE d.definition_code = 'biz_order_void_flow' AND d.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition_node n
      WHERE n.definition_id = d.id AND n.node_name = '作废申请' AND n.delete_flag = 0
  );

INSERT INTO wf_definition_node
    (corp_id, definition_id, node_order, node_name, approve_mode, node_type,
     biz_status, return_mode, return_biz_status, action_type, form_schema, form_url,
     last_node, allow_skip, timeout_hours, remind_hours, escalate_hours,
     update_key, unique_id, create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT d.corp_id, d.id, 2, '部长审核', 'ANY', 'APPROVAL',
       NULL, 'INITIATOR', NULL, 'OPS_VOID_MINISTER', NULL,
       '/ops/business-data/flow-task?taskId={taskId}',
       0, 0, 24, 24, 48,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM wf_definition d
WHERE d.definition_code = 'biz_order_void_flow' AND d.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition_node n
      WHERE n.definition_id = d.id AND n.node_name = '部长审核' AND n.delete_flag = 0
  );

INSERT INTO wf_definition_node
    (corp_id, definition_id, node_order, node_name, approve_mode, node_type,
     biz_status, return_mode, return_biz_status, action_type, form_schema, form_url,
     last_node, allow_skip, timeout_hours, remind_hours, escalate_hours,
     update_key, unique_id, create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT d.corp_id, d.id, 3, '财务审核', 'ANY', 'APPROVAL',
       NULL, 'INITIATOR', NULL, 'OPS_VOID_FINANCE', NULL,
       '/ops/business-data/flow-task?taskId={taskId}',
       0, 0, 24, 24, 48,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM wf_definition d
WHERE d.definition_code = 'biz_order_void_flow' AND d.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition_node n
      WHERE n.definition_id = d.id AND n.node_name = '财务审核' AND n.delete_flag = 0
  );

INSERT INTO wf_definition_node
    (corp_id, definition_id, node_order, node_name, approve_mode, node_type,
     biz_status, return_mode, return_biz_status, action_type, form_schema, form_url,
     last_node, allow_skip, timeout_hours, remind_hours, escalate_hours,
     update_key, unique_id, create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT d.corp_id, d.id, 4, '总经办审核', 'ANY', 'APPROVAL',
       NULL, 'PREVIOUS', NULL, 'OPS_VOID_COMPLETE', NULL,
       '/ops/business-data/flow-task?taskId={taskId}',
       1, 0, 24, 24, 48,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM wf_definition d
WHERE d.definition_code = 'biz_order_void_flow' AND d.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition_node n
      WHERE n.definition_id = d.id AND n.node_name = '总经办审核' AND n.delete_flag = 0
  );

-- ── 4. 节点审批人（部长/财务按订单组织，总经办按运营商树）──────────
INSERT INTO wf_definition_node_approver
    (corp_id, definition_id, node_id, approver_user_id, approver_name, approver_corp_id,
     approver_role_name, approver_scope, update_key, unique_id,
     create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT d.corp_id, d.id, n.id, NULL, NULL, NULL,
       v.role_name, v.scope, '', REPLACE(UUID(), '-', ''),
       NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM wf_definition d
JOIN wf_definition_node n ON n.definition_id = d.id AND n.delete_flag = 0
JOIN (
    SELECT '部长审核' AS node_name, '部长' AS role_name, 'INITIATOR_CORP' AS scope UNION ALL
    SELECT '财务审核', '财务', 'INITIATOR_CORP' UNION ALL
    SELECT '总经办审核', '总经办', 'OPERATOR_TREE'
) v ON v.node_name = n.node_name
WHERE d.definition_code = 'biz_order_void_flow' AND d.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition_node_approver a
      WHERE a.node_id = n.id AND a.approver_role_name = v.role_name AND a.delete_flag = 0
  );

-- ── 5. 验证 ────────────────────────────────────────────────────────
SELECT d.id, d.corp_id, d.definition_code, d.business_type, d.status, d.callback_url
FROM wf_definition d
WHERE d.definition_code = 'biz_order_void_flow' AND d.delete_flag = 0;

SELECT d.definition_code, n.node_order, n.node_name, n.node_type, n.biz_status,
       n.return_mode, n.action_type, n.last_node,
       a.approver_role_name, a.approver_scope
FROM wf_definition d
JOIN wf_definition_node n ON n.definition_id = d.id AND n.delete_flag = 0
LEFT JOIN wf_definition_node_approver a ON a.node_id = n.id AND a.delete_flag = 0
WHERE d.definition_code = 'biz_order_void_flow' AND d.delete_flag = 0
ORDER BY d.corp_id, n.node_order;

SELECT t.type_code, i.item_key, i.item_value, i.item_order
FROM sys_dict_type t
JOIN sys_dict_item i ON i.type_id = t.id AND i.delete_flag = 0
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND t.delete_flag = 0
ORDER BY i.item_order;
