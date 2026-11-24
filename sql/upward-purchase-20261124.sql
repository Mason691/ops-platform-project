-- =====================================================================
-- 向上采购申请：部长审核勾选 + 财务核对生成待发起申请
-- 执行库：mall_db（ops 业务表）
-- 执行顺序：备份 → 本脚本 → 发布 ops 后端 → 平台工作流节点 action_type 脚本 → H5
-- 幂等：列经 information_schema 条件化；表 IF NOT EXISTS；可重复执行。
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4
-- =====================================================================

USE mall_db;
SET NAMES utf8mb4;

-- ── 1. 业务订单商品明细：是否向上采购（部长审核写入，财务只读）────────
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order_item'
      AND COLUMN_NAME = 'is_upward_purchase');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN is_upward_purchase TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否向上采购：1是 0否（部长审核写入）'' AFTER calc_status',
    'SELECT ''is_upward_purchase already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ── 2. 向上采购申请记录表 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ops_upward_purchase (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    corp_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '归属组织ID',
    order_id            BIGINT       NOT NULL COMMENT '业务订单ID（ops_business_order.id）',
    order_no            VARCHAR(50)  DEFAULT NULL COMMENT '订单编号',
    receipt_no          VARCHAR(50)  DEFAULT NULL COMMENT '收据号',
    item_id             BIGINT       NOT NULL COMMENT '商品明细主行ID（ops_business_order_item.id）',
    item_ids            VARCHAR(1000) DEFAULT NULL COMMENT '同商品组合的全部明细行ID（英文逗号分隔）',
    product_key         VARCHAR(500) DEFAULT NULL COMMENT '商品分组键（品类+品牌+型号+单价）',
    batch_no            VARCHAR(50)  DEFAULT NULL COMMENT '批次号',
    customer_name       VARCHAR(100) DEFAULT NULL COMMENT '客户',
    category            VARCHAR(50)  DEFAULT NULL COMMENT '品类',
    brand               VARCHAR(100) DEFAULT NULL COMMENT '品牌',
    model               VARCHAR(200) DEFAULT NULL COMMENT '型号',
    quantity            DECIMAL(12,2) DEFAULT 0 COMMENT '数量',
    sale_price          DECIMAL(10,2) DEFAULT NULL COMMENT '销售单价',
    unit                VARCHAR(20)  DEFAULT NULL COMMENT '库存单位',
    local_stock         DECIMAL(12,2) DEFAULT NULL COMMENT '财务核对时本组织库存快照',
    other_stock_json    TEXT         DEFAULT NULL COMMENT '财务核对时其他城市合伙人库存快照 JSON',
    selection_node      VARCHAR(30)  NOT NULL DEFAULT 'MINISTER_REVIEW' COMMENT '勾选来源节点',
    finance_check_time  DATETIME     DEFAULT NULL COMMENT '财务核对通过时间',
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待发起 REQUESTED已发起 HANDLED已处理 INVALID已失效',
    request_bill_no     VARCHAR(64)  DEFAULT NULL COMMENT 'SCM 采购订单草稿单号',
    request_time        DATETIME     DEFAULT NULL COMMENT '发起采购申请时间',
    handled_time        DATETIME     DEFAULT NULL COMMENT '已处理时间',
    invalid_reason      VARCHAR(500) DEFAULT NULL COMMENT '失效原因',
    update_key          VARCHAR(64)  NOT NULL DEFAULT '',
    unique_id           VARCHAR(32)  NOT NULL DEFAULT '',
    create_time         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    modify_time         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    create_user         BIGINT       DEFAULT NULL COMMENT '创建人ID',
    modify_user         BIGINT       DEFAULT NULL COMMENT '修改人ID',
    create_user_name    VARCHAR(64)  DEFAULT NULL COMMENT '创建人姓名',
    modify_user_name    VARCHAR(64)  DEFAULT NULL COMMENT '修改人姓名',
    sort                INT          NOT NULL DEFAULT 0 COMMENT '排序',
    remark              VARCHAR(500) DEFAULT NULL COMMENT '备注',
    delete_flag         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
    UNIQUE KEY uk_order_item (order_id, item_id),
    KEY idx_corp_status (corp_id, status),
    KEY idx_corp_order (corp_id, order_id),
    KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='向上采购申请记录';

-- ── 3. 验证 ───────────────────────────────────────────────────────────
SELECT 'column' AS check_name, COLUMN_TYPE AS value
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_business_order_item'
  AND COLUMN_NAME = 'is_upward_purchase';

SELECT 'table' AS check_name, COUNT(*) AS value
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_upward_purchase';
