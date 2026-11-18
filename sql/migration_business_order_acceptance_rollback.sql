-- =====================================================================
-- 回滚：业务订单「完工验收」迁移
-- 目标库：ai_platform + mall_db
-- 执行顺序：先执行角色清理回滚（恢复验收员/售后角色与节点审批人配置），
--          再执行本脚本。
-- 说明：附件 duration_seconds 列保留（旧代码不读取，删除会丢失已录时长）。
--      在途任务若被迁移脚本重指派，本脚本不回滚处理人（迁移时已核对为 0 条）。
-- =====================================================================

USE ai_platform;

-- 1. 状态字典回滚
UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.id = i.type_id AND t.delete_flag = 0
SET i.item_value = '售后维护', i.item_order = 6, i.modify_time = NOW()
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status'
  AND i.item_key = '6' AND i.delete_flag = 0;

UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.id = i.type_id AND t.delete_flag = 0
SET i.delete_flag = 1, i.modify_time = NOW()
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status'
  AND i.item_key = '7' AND i.delete_flag = 0;

-- 2. 节点回滚：施工完成恢复为验收动作；售后维护恢复为末节点
UPDATE wf_definition_node n
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
SET n.node_name = '施工完成',
    n.action_type = 'OPS_ACCEPTANCE',
    n.approver_strategy = 'CONFIG',
    n.biz_status = '5',
    n.return_mode = 'PREVIOUS',
    n.return_biz_status = '4',
    n.last_node = 0,
    n.form_schema = '[{"key":"acceptanceConclusion","label":"验收结论","type":"radio","required":true,"options":[{"label":"通过","value":"PASS"},{"label":"不通过","value":"REJECT"}]},{"key":"acceptanceNote","label":"验收说明","type":"textarea","required":false,"maxLength":1000}]',
    n.modify_time = NOW(),
    n.modify_user_name = 'system-acceptance-rollback'
WHERE d.definition_code = 'biz_order_flow' AND n.delete_flag = 0
  AND n.node_order = 6;

UPDATE wf_definition_node n
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
SET n.node_name = '售后维护',
    n.action_type = 'OPS_AFTER_SALES',
    n.approver_strategy = 'CONFIG',
    n.biz_status = '6',
    n.return_mode = 'PREVIOUS',
    n.return_biz_status = '5',
    n.last_node = 1,
    n.form_schema = '[{"key":"serviceNote","label":"售后内容","type":"textarea","required":false,"maxLength":1000},{"key":"visitResult","label":"回访结果","type":"textarea","required":false,"maxLength":1000}]',
    n.modify_time = NOW(),
    n.modify_user_name = 'system-acceptance-rollback'
WHERE d.definition_code = 'biz_order_flow' AND n.delete_flag = 0
  AND n.node_order = 7;

-- 3. 恢复迁移前的节点审批人配置（完整的 biz_order_flow 备份）
REPLACE INTO wf_definition_node_approver
SELECT * FROM bak_acceptance_migration_wf_node_approver;

-- 4. 订单回滚 + 删除迁移留痕
USE mall_db;

UPDATE ops_business_order o
JOIN bak_acceptance_migration_orders b ON b.id = o.id
SET o.biz_status = b.biz_status,
    o.wf_node_name = b.wf_node_name,
    o.modify_time = NOW(),
    o.modify_user_name = 'system-acceptance-rollback'
WHERE o.delete_flag = 0;

DELETE FROM ops_business_flow_record
WHERE action_type = 'MIGRATE_STATUS' AND delete_flag = 0;

-- 5. 核对
SELECT 'orders_by_status' AS check_name, biz_status, COUNT(*) AS cnt
FROM ops_business_order WHERE delete_flag = 0
GROUP BY biz_status ORDER BY biz_status;

SELECT 'dict_items' AS check_name, i.item_key, i.item_value, i.delete_flag
FROM ai_platform.sys_dict_type t
JOIN ai_platform.sys_dict_item i ON i.type_id = t.id
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND t.delete_flag = 0
  AND i.item_key IN ('6', '7');
