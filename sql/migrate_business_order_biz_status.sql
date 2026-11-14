-- =====================================================================
-- 业务数据订单：业务状态（工作流驱动）+ 流程实例 + 节点处理留痕
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新 ops 后端 JAR 执行。
-- 注意：
--   1. 生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS，使用 information_schema
--      条件化 PREPARE，可重复执行。
--   2. biz_status 取值由字典 business_order_status 维护（0~6）。
--   3. 本脚本包含从旧派工单回填业务状态；必须在删除派工单表的脚本之前执行。
-- =====================================================================

USE mall_db;

SET @db := DATABASE();

-- ── 1. ops_business_order 业务状态 / 流程实例字段 ─────────────────────
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD COLUMN biz_status VARCHAR(10) NOT NULL DEFAULT ''0'' COMMENT ''业务状态（字典 business_order_status）'' AFTER calc_status',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND COLUMN_NAME = 'biz_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD COLUMN wf_instance_id BIGINT NULL COMMENT ''当前工作流实例ID（wf_instance.id）'' AFTER biz_status',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND COLUMN_NAME = 'wf_instance_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD COLUMN wf_node_order INT NULL COMMENT ''工作流当前节点序号'' AFTER wf_instance_id',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND COLUMN_NAME = 'wf_node_order');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD COLUMN wf_node_name VARCHAR(100) NULL COMMENT ''工作流当前节点名称'' AFTER wf_node_order',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND COLUMN_NAME = 'wf_node_name');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD COLUMN finance_check_conclusion VARCHAR(20) NULL COMMENT ''财务核对结论：PASS/REJECT/HOLD'' AFTER finance_check_time',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND COLUMN_NAME = 'finance_check_conclusion');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD COLUMN finance_check_remark VARCHAR(500) NULL COMMENT ''财务核对备注'' AFTER finance_check_conclusion',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND COLUMN_NAME = 'finance_check_remark');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ops_business_order ADD KEY idx_corp_biz_status (corp_id, biz_status)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order' AND INDEX_NAME = 'idx_corp_biz_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 2. 流程节点处理留痕（业务系统侧） ─────────────────────────────────
CREATE TABLE IF NOT EXISTS ops_business_flow_record (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    corp_id             BIGINT       NOT NULL,
    order_id            BIGINT       NOT NULL COMMENT 'ops_business_order.id',
    instance_id         BIGINT       DEFAULT NULL COMMENT 'wf_instance.id',
    task_id             BIGINT       DEFAULT NULL COMMENT 'wf_task.id',
    node_order          INT          DEFAULT NULL,
    node_name           VARCHAR(100) DEFAULT NULL,
    action_type         VARCHAR(50)  DEFAULT NULL COMMENT 'OPS_FINANCE_CHECK/OPS_DISPATCH_OUTBOUND/...',
    biz_status          VARCHAR(10)  DEFAULT NULL COMMENT '处理完成时的业务状态',
    operator_user_id    BIGINT       DEFAULT NULL,
    operator_name       VARCHAR(100) DEFAULT NULL,
    opinion             VARCHAR(500) DEFAULT NULL,
    form_json           MEDIUMTEXT   DEFAULT NULL COMMENT '节点表单数据 JSON',
    result_json         TEXT         DEFAULT NULL COMMENT '动作执行结果 JSON（出库单号等）',
    update_key          VARCHAR(64)  DEFAULT NULL,
    unique_id           VARCHAR(64)  DEFAULT NULL,
    create_time         DATETIME     DEFAULT NULL,
    modify_time         DATETIME     DEFAULT NULL,
    create_user         BIGINT       DEFAULT NULL,
    modify_user         BIGINT       DEFAULT NULL,
    create_user_name    VARCHAR(50)  DEFAULT NULL,
    modify_user_name    VARCHAR(50)  DEFAULT NULL,
    sort                INT          DEFAULT 0,
    remark              VARCHAR(500) DEFAULT NULL,
    delete_flag         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_flow_task_action (task_id, action_type, delete_flag),
    KEY idx_order (order_id),
    KEY idx_instance (instance_id),
    KEY idx_corp_status (corp_id, biz_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='业务数据订单工作流节点处理留痕';

-- ── 3. 从旧派工单回填业务状态（存在派工单表时） ──────────────────────
SET @has_dispatch := (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_dispatch_order');

SET @sql := IF(@has_dispatch > 0,
    'UPDATE ops_business_order o
     JOIN (
       SELECT source_order_id,
              MAX(CASE
                    WHEN status = ''COMPLETED'' THEN 5
                    WHEN status IN (''ACCEPTED'',''PROCESSING'') THEN 4
                    WHEN status IN (''PENDING'',''ACCEPTING'') THEN 3
                    ELSE 0 END) AS derived
       FROM ops_dispatch_order
       WHERE delete_flag = 0
       GROUP BY source_order_id
     ) d ON d.source_order_id = o.id
     SET o.biz_status = CAST(GREATEST(CAST(COALESCE(o.biz_status,''0'') AS UNSIGNED), d.derived) AS CHAR)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 财务已核对 → 2（不覆盖已派工/施工的更高状态）
UPDATE ops_business_order
SET biz_status = '2'
WHERE finance_check_time IS NOT NULL
  AND CAST(COALESCE(biz_status, '0') AS UNSIGNED) < 2;

-- 订单内全部明细均为「经办人已核对」→ 1
UPDATE ops_business_order o
SET o.biz_status = '1'
WHERE CAST(COALESCE(o.biz_status, '0') AS UNSIGNED) = 0
  AND EXISTS (SELECT 1 FROM ops_business_order_item i
              WHERE i.order_id = o.id AND i.delete_flag = 0)
  AND NOT EXISTS (SELECT 1 FROM ops_business_order_item i
                  WHERE i.order_id = o.id AND i.delete_flag = 0
                    AND (i.calc_status IS NULL OR i.calc_status <> '经办人已核对'));

-- 其余保持默认 0；防御性回填 NULL
UPDATE ops_business_order SET biz_status = '0' WHERE biz_status IS NULL OR biz_status = '';

-- 验证：各状态分布
SELECT biz_status, COUNT(*) AS cnt FROM ops_business_order
WHERE delete_flag = 0 GROUP BY biz_status ORDER BY biz_status;
