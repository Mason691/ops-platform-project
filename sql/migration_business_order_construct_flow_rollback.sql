-- =====================================================================
-- 回滚：业务订单「派工 → 施工中」状态链重构
-- 执行库：mall_db（第 1 节）+ ai_platform（第 2 节）
-- 执行顺序：先回滚 ops 后端 JAR / platform JAR → 再执行本脚本。
-- 说明：
--   1. 新状态字典项只停用（status=0），不删除，保留历史订单可读。
--   2. 工作流节点恢复「施工中」+ biz_status=4（仅影响后续进入节点）。
--   3. 存量订单**不改状态**；如业务要求把新链在途订单回退，需人工确认后执行
--      第 3 节的 SELECT/UPDATE（默认注释）。
--   4. 新表不删除（法律凭证需保留）；如需彻底清理，见第 4 节注释。
-- =====================================================================

USE ai_platform;
SET NAMES utf8mb4;

-- ── 1. 停用新状态字典项并恢复原顺序 ─────────────────────────────────
UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.id = i.type_id AND t.delete_flag = 0
SET i.status = 0, i.modify_time = NOW()
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status'
  AND i.item_key IN ('WAIT_ACCEPT','ACCEPTED','ARRIVED',
                     'CUSTOMER_CONFIRMED','CUSTOMER_REFUSED')
  AND i.delete_flag = 0;

UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.id = i.type_id AND t.delete_flag = 0
SET i.item_order = CASE i.item_key
        WHEN '0' THEN 0
        WHEN '1' THEN 1
        WHEN '2' THEN 2
        WHEN '3' THEN 3
        WHEN '4' THEN 4
        WHEN '5' THEN 5
        WHEN '6' THEN 6
        WHEN '7' THEN 7
        ELSE i.item_order
    END,
    i.modify_time = NOW()
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND i.delete_flag = 0;

-- ── 2. 工作流节点恢复 ───────────────────────────────────────────────
UPDATE wf_definition_node n
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
SET n.node_name = '施工中',
    n.biz_status = '4',
    n.modify_time = NOW(),
    n.modify_user_name = 'system-construct-flow-rollback'
WHERE d.definition_code = 'biz_order_flow' AND n.delete_flag = 0
  AND n.node_order = 5;

-- ── 3. 在途新链订单处理（默认注释，人工确认后执行）──────────────────
-- 3.1 先核查分布：
-- SELECT biz_status, COUNT(*) AS cnt
-- FROM mall_db.ops_business_order
-- WHERE delete_flag = 0
--   AND biz_status IN ('WAIT_ACCEPT','ACCEPTED','ARRIVED',
--                      'CUSTOMER_CONFIRMED','CUSTOMER_REFUSED')
-- GROUP BY biz_status;
--
-- 3.2 回滚到旧口径「派工即施工中」（仅当业务确认放弃施工员确认链）：
-- USE mall_db;
-- UPDATE ops_business_order
-- SET biz_status = '4', modify_time = NOW(), version = version + 1
-- WHERE delete_flag = 0
--   AND biz_status IN ('WAIT_ACCEPT','ACCEPTED','ARRIVED',
--                      'CUSTOMER_CONFIRMED','CUSTOMER_REFUSED');

-- ── 4. 表结构清理（默认保留；确认法律凭证可弃用后再执行）────────────
-- USE mall_db;
-- DROP TABLE IF EXISTS ops_customer_notice;
-- DROP TABLE IF EXISTS ops_order_construct_log;
-- ALTER TABLE ops_business_order DROP COLUMN version;
-- ALTER TABLE ops_business_order DROP COLUMN start_time;
-- ALTER TABLE ops_business_order DROP COLUMN customer_confirm_time;
-- ALTER TABLE ops_business_order DROP COLUMN arrive_time;
-- ALTER TABLE ops_business_order DROP COLUMN accept_time;
-- ALTER TABLE ops_business_order DROP COLUMN worker_user_type;
-- ALTER TABLE ops_business_order DROP COLUMN worker_name;
-- ALTER TABLE ops_business_order DROP COLUMN worker_id;

SELECT 'rollback done' AS check_name;
