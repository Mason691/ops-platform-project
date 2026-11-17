-- =====================================================================
-- 物资领用 / 物资核销工作流定义种子（目标库：ai_platform）
-- 执行：DBA 对底座库执行；ops 运行时不连接 ai_platform。
--
-- 流程一 物资领用：发起 → 部长审核 → 结束
-- 流程二 物资核销：发起 → 部长审核 → 结束
-- 审批人按企业角色「部长」+ OPERATOR_TREE 实时解析，业务代码不硬编码审批人。
-- 回调地址：http://localhost:8082/api/internal/workflow/material/event
-- 幂等：SELECT ... WHERE NOT EXISTS，可重复执行。
-- =====================================================================

USE ai_platform;

SET @operator_corp_id := 4;

-- ── 1. 部长企业角色（仅配置的运营商组织；通常已存在）────────────────
INSERT INTO sys_corp_user_role
    (corp_id, role_name, description, update_key, unique_id,
     create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT c.id, '部长', '运营商部长：物资领用/核销审批', '', REPLACE(UUID(), '-', ''),
       NOW(), NOW(), 0, 0, 'system', 'system', 0, '物资审批角色', 0
FROM sys_corp c
WHERE c.delete_flag = 0 AND c.status = 1 AND c.id = @operator_corp_id
  AND NOT EXISTS (
      SELECT 1 FROM sys_corp_user_role r
      WHERE r.corp_id = c.id AND r.role_name = '部长' AND r.delete_flag = 0
  );

-- ── 2. 流程定义 ──────────────────────────────────────────────────
INSERT INTO wf_definition
    (corp_id, definition_code, definition_name, business_type, version, status,
     description, callback_url, update_key, unique_id,
     create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT c.id, 'OPS_MATERIAL_REQUISITION_APPROVAL', '物资领用审批', 'OPS_MATERIAL_REQUISITION', 1,
       'PUBLISHED', '员工提交物资领用 → 部长审核 → 通过后可核销',
       'http://localhost:8082/api/internal/workflow/material/event',
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM sys_corp c
WHERE c.delete_flag = 0 AND c.status = 1 AND c.id = @operator_corp_id
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition d
      WHERE d.corp_id = c.id AND d.definition_code = 'OPS_MATERIAL_REQUISITION_APPROVAL'
        AND d.delete_flag = 0
  );

INSERT INTO wf_definition
    (corp_id, definition_code, definition_name, business_type, version, status,
     description, callback_url, update_key, unique_id,
     create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT c.id, 'OPS_MATERIAL_WRITE_OFF_APPROVAL', '物资核销审批', 'OPS_MATERIAL_WRITE_OFF', 1,
       'PUBLISHED', '员工提交物资核销 → 部长审核 → 通过后回写领用余量',
       'http://localhost:8082/api/internal/workflow/material/event',
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM sys_corp c
WHERE c.delete_flag = 0 AND c.status = 1 AND c.id = @operator_corp_id
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition d
      WHERE d.corp_id = c.id AND d.definition_code = 'OPS_MATERIAL_WRITE_OFF_APPROVAL'
        AND d.delete_flag = 0
  );

-- ── 3. 节点：发起 + 部长审核（末节点）────────────────────────────
INSERT INTO wf_definition_node
    (corp_id, definition_id, node_order, node_name, approve_mode, node_type,
     biz_status, return_mode, return_biz_status, action_type, form_schema, form_url,
     last_node, allow_skip, timeout_hours, remind_hours, escalate_hours,
     update_key, unique_id, create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT d.corp_id, d.id, 1, '发起', 'ANY', 'START',
       NULL, 'INITIATOR', NULL, NULL, NULL, NULL,
       0, 0, 24, 24, 48,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM wf_definition d
WHERE d.definition_code IN ('OPS_MATERIAL_REQUISITION_APPROVAL',
                            'OPS_MATERIAL_WRITE_OFF_APPROVAL')
  AND d.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition_node n
      WHERE n.definition_id = d.id AND n.node_type = 'START' AND n.delete_flag = 0
  );

INSERT INTO wf_definition_node
    (corp_id, definition_id, node_order, node_name, approve_mode, node_type,
     biz_status, return_mode, return_biz_status, action_type, form_schema, form_url,
     last_node, allow_skip, timeout_hours, remind_hours, escalate_hours,
     update_key, unique_id, create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT d.corp_id, d.id, 2, '部长审核', 'ANY', 'APPROVAL',
       NULL, 'INITIATOR', NULL, NULL, NULL, NULL,
       1, 0, 24, 24, 48,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM wf_definition d
WHERE d.definition_code IN ('OPS_MATERIAL_REQUISITION_APPROVAL',
                            'OPS_MATERIAL_WRITE_OFF_APPROVAL')
  AND d.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition_node n
      WHERE n.definition_id = d.id AND n.node_name = '部长审核' AND n.delete_flag = 0
  );

-- ── 4. 角色审批人 ────────────────────────────────────────────────
INSERT INTO wf_definition_node_approver
    (corp_id, definition_id, node_id, approver_user_id, approver_name, approver_corp_id,
     approver_role_name, approver_scope, update_key, unique_id,
     create_time, modify_time, create_user, modify_user,
     create_user_name, modify_user_name, sort, remark, delete_flag)
SELECT d.corp_id, d.id, n.id, NULL, NULL, NULL,
       '部长', 'OPERATOR_TREE', '', REPLACE(UUID(), '-', ''),
       NOW(), NOW(), 0, 0, 'system', 'system', 0, NULL, 0
FROM wf_definition d
JOIN wf_definition_node n ON n.definition_id = d.id
    AND n.node_name = '部长审核' AND n.delete_flag = 0
WHERE d.definition_code IN ('OPS_MATERIAL_REQUISITION_APPROVAL',
                            'OPS_MATERIAL_WRITE_OFF_APPROVAL')
  AND d.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM wf_definition_node_approver a
      WHERE a.node_id = n.id AND a.approver_role_name = '部长' AND a.delete_flag = 0
  );

-- ── 验证 ─────────────────────────────────────────────────────────
SELECT d.id, d.corp_id, d.definition_code, d.business_type, d.status, d.callback_url
FROM wf_definition d
WHERE d.definition_code IN ('OPS_MATERIAL_REQUISITION_APPROVAL',
                            'OPS_MATERIAL_WRITE_OFF_APPROVAL')
  AND d.delete_flag = 0;

SELECT d.definition_code, n.node_order, n.node_name, n.node_type, n.last_node,
       a.approver_role_name, a.approver_scope
FROM wf_definition_node n
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
LEFT JOIN wf_definition_node_approver a ON a.node_id = n.id AND a.delete_flag = 0
WHERE d.definition_code IN ('OPS_MATERIAL_REQUISITION_APPROVAL',
                            'OPS_MATERIAL_WRITE_OFF_APPROVAL')
  AND n.delete_flag = 0
ORDER BY d.definition_code, n.node_order;
