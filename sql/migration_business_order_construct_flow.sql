-- =====================================================================
-- 业务订单「派工 → 施工中」状态链重构
-- 执行库：mall_db（第 1 节）+ ai_platform（第 2 节）
-- 部署顺序：备份 → 本脚本 → 重启 platform（字典缓存）→ 发布新 ops JAR
-- 幂等：DDL 经 information_schema 条件化；字典/节点按 key 判重；可重复执行。
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4
--
-- 变更摘要：
--   1. ops_business_order：biz_status 扩到 32；新增施工员、关键时间点、乐观锁 version。
--   2. 新表 ops_order_construct_log：每一步施工流转留痕（含定位/附件/备注）。
--   3. 新表 ops_customer_notice：客户手写签字的法律凭证（含内容快照 + 图片哈希）。
--   4. 字典 business_order_status：新增 WAIT_ACCEPT/ACCEPTED/ARRIVED/
--      CUSTOMER_CONFIRMED/CUSTOMER_REFUSED；施工中仍沿用 key=4（历史数据零迁移）。
--   5. 工作流节点「施工中」→「施工执行」并清空 biz_status：
--      节点进入不再直接写「施工中」，业务状态改由施工员操作推进。
--
-- 存量数据处理：**不回退**。存量 biz_status=4 的订单视为已开工，继续原流程。
--   分布核查见脚本末尾 SELECT。
-- =====================================================================

USE mall_db;
SET NAMES utf8mb4;
SET @db := DATABASE();

-- ═══════════════════════════════════════════════════════════════════
-- 1. 订单表 & 施工流转表
-- ═══════════════════════════════════════════════════════════════════

-- 1.1 biz_status 扩容：新状态 CUSTOMER_CONFIRMED 长 18 字符
SET @col_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'biz_status');
SET @ddl := IF(@col_len IS NOT NULL AND @col_len < 32,
    'ALTER TABLE ops_business_order MODIFY COLUMN biz_status VARCHAR(32) NOT NULL DEFAULT ''0'' COMMENT ''业务状态（字典 business_order_status）''',
    'SELECT ''biz_status already >= 32''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 1.2 施工员与关键时间点（冗余字段便于列表/统计，无需回查日志）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'worker_id');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN worker_id BIGINT NULL COMMENT ''指派施工员 sys_user.id'' AFTER draft_flag',
    'SELECT ''worker_id already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'worker_name');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN worker_name VARCHAR(50) NULL COMMENT ''指派施工员姓名'' AFTER worker_id',
    'SELECT ''worker_name already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'worker_user_type');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN worker_user_type TINYINT NULL COMMENT ''施工员类型 0内部 1外部'' AFTER worker_name',
    'SELECT ''worker_user_type already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'accept_time');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN accept_time DATETIME NULL COMMENT ''施工员确认接收时间'' AFTER worker_user_type',
    'SELECT ''accept_time already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'arrive_time');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN arrive_time DATETIME NULL COMMENT ''上门打卡时间'' AFTER accept_time',
    'SELECT ''arrive_time already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'customer_confirm_time');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN customer_confirm_time DATETIME NULL COMMENT ''客户签署告知书时间'' AFTER arrive_time',
    'SELECT ''customer_confirm_time already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'start_time');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN start_time DATETIME NULL COMMENT ''确认开工时间'' AFTER customer_confirm_time',
    'SELECT ''start_time already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 1.2.1 最近一次派工任务ID：同任务重试复用 SCM 出库单（SCM 按 dispatchId 幂等）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'dispatch_task_id');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN dispatch_task_id BIGINT NULL COMMENT ''最近一次派工任务ID（重试幂等）'' AFTER worker_user_type',
    'SELECT ''dispatch_task_id already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 1.3 乐观锁 version（应用层 CAS 使用，不启用 @Version 拦截器）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'version');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本（施工流转 CAS）'' AFTER start_time',
    'SELECT ''version already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 1.4 施工流转日志
CREATE TABLE IF NOT EXISTS ops_order_construct_log (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    corp_id           BIGINT        NOT NULL COMMENT '订单归属组织ID',
    order_id          BIGINT        NOT NULL COMMENT '业务订单ID',
    order_no          VARCHAR(50)   NOT NULL COMMENT '订单编号（冗余）',
    from_status       VARCHAR(32)   DEFAULT NULL COMMENT '变更前业务状态',
    to_status         VARCHAR(32)   NOT NULL COMMENT '变更后业务状态',
    action            VARCHAR(32)   NOT NULL COMMENT 'ASSIGN/ACCEPT/REJECT/ARRIVE/CUSTOMER_CONFIRM/CUSTOMER_REFUSE/START',
    operator_id       BIGINT        NOT NULL COMMENT '操作人 sys_user.id',
    operator_name     VARCHAR(50)   DEFAULT NULL COMMENT '操作人姓名',
    operator_type     TINYINT       DEFAULT NULL COMMENT '操作人类型 0内部 1外部',
    operate_time      DATETIME      NOT NULL COMMENT '操作时间',
    location_lng      DECIMAL(11,7) DEFAULT NULL COMMENT '经度',
    location_lat      DECIMAL(10,7) DEFAULT NULL COMMENT '纬度',
    location_address  VARCHAR(255)  DEFAULT NULL COMMENT '逆地理地址/手动填写地址',
    location_status   VARCHAR(20)   DEFAULT NULL COMMENT 'SUCCESS/FAILED/MANUAL',
    attachment_ids    VARCHAR(500)  DEFAULT NULL COMMENT '现场照片附件ID，逗号分隔',
    notice_id         BIGINT        DEFAULT NULL COMMENT '客户告知书ID（签字动作）',
    update_key        VARCHAR(64)   DEFAULT NULL,
    unique_id         VARCHAR(64)   DEFAULT NULL,
    create_time       DATETIME      DEFAULT NULL,
    modify_time       DATETIME      DEFAULT NULL,
    create_user       BIGINT        DEFAULT NULL,
    modify_user       BIGINT        DEFAULT NULL,
    create_user_name  VARCHAR(50)   DEFAULT NULL,
    modify_user_name  VARCHAR(50)   DEFAULT NULL,
    sort              INT           DEFAULT 0,
    remark            VARCHAR(500)  DEFAULT NULL COMMENT '备注/拒绝原因',
    delete_flag       INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_time (order_id, operate_time),
    KEY idx_corp_action (corp_id, action),
    KEY idx_operate_time (operate_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='订单施工流转日志（派工→待接收→已接收→已上门→客户确认→施工中）';

-- 1.5 客户用户告知书（法律凭证）
CREATE TABLE IF NOT EXISTS ops_customer_notice (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    corp_id             BIGINT        NOT NULL COMMENT '订单归属组织ID',
    order_id            BIGINT        NOT NULL COMMENT '业务订单ID',
    order_no            VARCHAR(50)   NOT NULL COMMENT '订单编号（冗余）',
    worker_id           BIGINT        NOT NULL COMMENT '施工员 sys_user.id',
    worker_name         VARCHAR(50)   DEFAULT NULL COMMENT '施工员姓名',
    customer_name       VARCHAR(50)   DEFAULT NULL COMMENT '客户姓名',
    notice_content      TEXT          NOT NULL COMMENT '告知书内容快照（含施工员/时间/订单信息）',
    notice_time         DATETIME      NOT NULL COMMENT '上门/告知时间',
    sign_image_url      VARCHAR(500)  NOT NULL COMMENT '客户手写签字图片URL',
    sign_image_hash     VARCHAR(64)   DEFAULT NULL COMMENT '签字图片 SHA-256（防篡改）',
    sign_time           DATETIME      NOT NULL COMMENT '签字时间',
    sign_location_lng   DECIMAL(11,7) DEFAULT NULL,
    sign_location_lat   DECIMAL(10,7) DEFAULT NULL,
    version             INT           NOT NULL DEFAULT 1 COMMENT '签字版本（重签递增）',
    status              TINYINT       NOT NULL DEFAULT 1 COMMENT '1有效 0作废（重签后旧版作废）',
    pdf_url             VARCHAR(500)  DEFAULT NULL COMMENT '归档PDF地址（预留，当前为空）',
    update_key          VARCHAR(64)   DEFAULT NULL,
    unique_id           VARCHAR(64)   DEFAULT NULL,
    create_time         DATETIME      DEFAULT NULL,
    modify_time         DATETIME      DEFAULT NULL,
    create_user         BIGINT        DEFAULT NULL,
    modify_user         BIGINT        DEFAULT NULL,
    create_user_name    VARCHAR(50)   DEFAULT NULL,
    modify_user_name    VARCHAR(50)   DEFAULT NULL,
    sort                INT           DEFAULT 0,
    remark              VARCHAR(500)  DEFAULT NULL,
    delete_flag         INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_version (order_id, version),
    KEY idx_order_status (order_id, status),
    KEY idx_corp_sign_time (corp_id, sign_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='客户施工用户告知书（手写签字凭证）';

-- 1.6 工作流留痕表 biz_status 扩容（两个新状态写入时不能截断）
SET @db := 'mall_db';
SET @col_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_flow_record'
      AND COLUMN_NAME = 'biz_status');
SET @ddl := IF(@col_len IS NOT NULL AND @col_len < 32,
    'ALTER TABLE ops_business_flow_record MODIFY COLUMN biz_status VARCHAR(32) NULL COMMENT ''处理完成时的业务状态''',
    'SELECT ''flow_record.biz_status already >= 32''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 1.7 作废退款表保存的原始状态同样扩容（回滚作废时恢复新状态）
SET @col_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_refund'
      AND COLUMN_NAME = 'original_biz_status');
SET @ddl := IF(@col_len IS NOT NULL AND @col_len < 32,
    'ALTER TABLE ops_business_refund MODIFY COLUMN original_biz_status VARCHAR(32) NULL COMMENT ''申请时订单业务状态，驳回时恢复''',
    'SELECT ''refund.original_biz_status already >= 32''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══════════════════════════════════════════════════════════════════
-- 2. 平台库：状态字典 + 工作流节点
-- ═══════════════════════════════════════════════════════════════════
USE ai_platform;
SET NAMES utf8mb4;

-- 2.1 新增中间状态字典项（幂等）
INSERT INTO sys_dict_item
    (corp_id, type_id, item_key, item_value, item_order, status,
     update_key, unique_id, create_time, modify_time, delete_flag)
SELECT t.corp_id, t.id, v.item_key, v.item_value, v.item_order, 1,
       '', REPLACE(UUID(), '-', ''), NOW(), NOW(), 0
FROM sys_dict_type t
JOIN (
    SELECT 'WAIT_ACCEPT' AS item_key, '待接收' AS item_value, 4 AS item_order UNION ALL
    SELECT 'ACCEPTED', '已接收', 5 UNION ALL
    SELECT 'ARRIVED', '已上门', 6 UNION ALL
    SELECT 'CUSTOMER_CONFIRMED', '客户已确认', 7 UNION ALL
    SELECT 'CUSTOMER_REFUSED', '客户拒绝确认', 8
) v
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND t.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.type_id = t.id AND i.item_key = v.item_key AND i.delete_flag = 0
  );

-- 2.2 重排显示顺序（不改变 key，历史数据零迁移）
UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.id = i.type_id AND t.delete_flag = 0
SET i.item_order = CASE i.item_key
        WHEN '0' THEN 0
        WHEN '1' THEN 1
        WHEN '2' THEN 2
        WHEN '3' THEN 3
        WHEN 'WAIT_ACCEPT' THEN 4
        WHEN 'ACCEPTED' THEN 5
        WHEN 'ARRIVED' THEN 6
        WHEN 'CUSTOMER_CONFIRMED' THEN 7
        WHEN 'CUSTOMER_REFUSED' THEN 8
        WHEN '4' THEN 9
        WHEN '5' THEN 10
        WHEN '6' THEN 11
        WHEN '7' THEN 12
        ELSE i.item_order
    END,
    i.modify_time = NOW()
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status' AND i.delete_flag = 0;

-- 2.3 工作流节点解耦：进入「施工执行」不再写业务状态（施工中改由确认开工写入）
UPDATE wf_definition_node n
JOIN wf_definition d ON d.id = n.definition_id AND d.delete_flag = 0
SET n.node_name = '施工执行',
    n.biz_status = NULL,
    -- 施工员拒绝接单必须退回派工节点重建派工待办，而不是终止整个实例
    n.return_mode = 'PREVIOUS',
    n.return_biz_status = '3',
    n.modify_time = NOW(),
    n.modify_user_name = 'system-construct-flow'
WHERE d.definition_code = 'biz_order_flow' AND n.delete_flag = 0
  AND n.node_order = 5;

-- ═══════════════════════════════════════════════════════════════════
-- 3. 验证
-- ═══════════════════════════════════════════════════════════════════
SELECT 'biz_status len' AS check_name,
       CHARACTER_MAXIMUM_LENGTH AS value
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order'
  AND COLUMN_NAME = 'biz_status';

SELECT 'new tables' AS check_name,
       (SELECT COUNT(*) FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = 'mall_db'
          AND TABLE_NAME IN ('ops_order_construct_log', 'ops_customer_notice')) AS value;

SELECT 'new dict items' AS check_name, i.item_key, i.item_value, i.item_order
FROM sys_dict_type t
JOIN sys_dict_item i ON i.type_id = t.id AND i.delete_flag = 0
WHERE t.corp_id = 0 AND t.type_code = 'business_order_status'
  AND i.item_key IN ('WAIT_ACCEPT','ACCEPTED','ARRIVED','CUSTOMER_CONFIRMED',
                     'CUSTOMER_REFUSED','4','5','6','7')
ORDER BY i.item_order;

SELECT 'construct node' AS check_name, d.definition_code, n.node_order,
       n.node_name, n.biz_status, n.action_type, n.return_mode, n.return_biz_status
FROM wf_definition d
JOIN wf_definition_node n ON n.definition_id = d.id AND n.delete_flag = 0
WHERE d.definition_code = 'biz_order_flow' AND n.node_order = 5;

-- 存量分布（不回退，仅核查）：
-- SELECT biz_status, COUNT(*) AS cnt
-- FROM mall_db.ops_business_order
-- WHERE delete_flag = 0
-- GROUP BY biz_status ORDER BY biz_status;
