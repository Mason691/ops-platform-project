-- 2026-11-11 业务数据主子表拆分 + 退货字段
-- 执行库：mall_db
-- 部署顺序：备份 → 停 ops 后端 → 本脚本 → 启动新 ops JAR（SCM 只读视图，可不停）
-- 幂等：DDL 经 information_schema 条件化；回填按唯一键 (corp_id, order_key) + INSERT IGNORE
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4
--
-- 结构：
--   ops_business_order        订单头（主表）
--   ops_business_order_item   商品分录（子表，由 ops_business_data 重命名）
--   ops_business_data         兼容视图（SELECT * FROM ops_business_order_item）
--   ops_business_return       退货申请表
--
-- ⚠ 执行前必须备份（示例）：
--   docker exec mysql mysqldump --default-character-set=utf8mb4 -uroot -p mall_db \
--     ops_business_data > /data2/backup/ops_business_data_before_order_split.sql

SET SESSION group_concat_max_len = 1000000;

-- 全局订单流水（幂等；新库/历史迁移未执行时兜底）
CREATE TABLE IF NOT EXISTS ops_order_sequence (
    id          TINYINT  NOT NULL COMMENT '固定为1',
    last_seq    BIGINT   NOT NULL DEFAULT 0 COMMENT '已分配最大流水号',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务订单编号全局流水';
INSERT IGNORE INTO ops_order_sequence (id, last_seq) VALUES (1, 0);

-- ═══ 1. 主表 ops_business_order ═══
CREATE TABLE IF NOT EXISTS ops_business_order (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    corp_id                 BIGINT       NOT NULL COMMENT '租户ID',
    order_key               VARCHAR(191) NOT NULL COMMENT '订单幂等键（迁移/业务唯一标识）',
    batch_no                VARCHAR(64)  DEFAULT NULL COMMENT '批次号',
    order_no                VARCHAR(20)  DEFAULT NULL COMMENT '订单编号（dd+流水号）',
    business_group_no       VARCHAR(40)  DEFAULT NULL COMMENT '历史业务组号',
    receipt_no              VARCHAR(50)  DEFAULT NULL COMMENT '收据号',
    company                 VARCHAR(32)  DEFAULT NULL COMMENT '公司',
    customer                VARCHAR(100) DEFAULT NULL COMMENT '客户名称',
    customer_phone          VARCHAR(20)  DEFAULT NULL COMMENT '客户手机号',
    platform_member_id      BIGINT       DEFAULT NULL COMMENT '平台会员ID',
    community_id            BIGINT       DEFAULT NULL COMMENT '小区ID',
    community_name          VARCHAR(200) DEFAULT NULL COMMENT '小区名称',
    detailed_address        VARCHAR(500) DEFAULT NULL COMMENT '详细地址',
    agent_names             VARCHAR(500) DEFAULT NULL COMMENT '经办人姓名列表（、分隔）',
    agent_phones            VARCHAR(500) DEFAULT NULL COMMENT '经办人手机号列表（、分隔）',
    receipt_date            VARCHAR(50)  DEFAULT NULL COMMENT '市场收款日期',
    finance_receipt_date    DATE         DEFAULT NULL COMMENT '财务收款日期',
    fund_dynamic_rows       INT          DEFAULT NULL COMMENT '资金动态表横排数',
    is_received             VARCHAR(2)   DEFAULT NULL COMMENT '是否收款',
    voucher_url             VARCHAR(1000) DEFAULT NULL COMMENT '收款凭证',
    total_amount            DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '订单合计金额（产品去重）',
    total_commission        DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '订单提成合计',
    calc_status             VARCHAR(20)  DEFAULT NULL COMMENT '计算状态',
    finance_check_user_id   BIGINT       DEFAULT NULL,
    finance_check_user_name VARCHAR(50)  DEFAULT NULL,
    finance_check_time      DATETIME     DEFAULT NULL,
    sort                    INT          DEFAULT NULL,
    remark                  VARCHAR(500) DEFAULT NULL,
    delete_flag             INT          NOT NULL DEFAULT 0,
    create_time             DATETIME     DEFAULT NULL,
    modify_time             DATETIME     DEFAULT NULL,
    create_user             BIGINT       DEFAULT NULL,
    modify_user             BIGINT       DEFAULT NULL,
    create_user_name        VARCHAR(50)  DEFAULT NULL,
    modify_user_name        VARCHAR(50)  DEFAULT NULL,
    unique_id               VARCHAR(64)  DEFAULT NULL,
    update_key              VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_corp_order_key (corp_id, order_key),
    KEY idx_corp_order_no (corp_id, order_no),
    KEY idx_corp_receipt (corp_id, receipt_no),
    KEY idx_corp_batch (corp_id, batch_no),
    KEY idx_corp_calc_status (corp_id, calc_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='增值业务订单头';

-- ═══ 2. 退货申请表 ops_business_return ═══
CREATE TABLE IF NOT EXISTS ops_business_return (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    corp_id           BIGINT       NOT NULL,
    order_id          BIGINT       NOT NULL COMMENT '主表ID',
    item_id           BIGINT       NOT NULL COMMENT '发起退货的子表行ID',
    product_key       VARCHAR(255) DEFAULT NULL COMMENT '订单内产品分组键（品类|品牌|型号|单价）',
    return_quantity   INT          NOT NULL COMMENT '本次退货数量',
    return_amount     DECIMAL(12,2) DEFAULT NULL COMMENT '本次退货金额',
    reason            VARCHAR(500) DEFAULT NULL COMMENT '退货原因',
    status            VARCHAR(20)  NOT NULL DEFAULT '审批中' COMMENT '审批中/已通过/已驳回',
    applicant_user_id BIGINT       DEFAULT NULL,
    applicant_name    VARCHAR(50)  DEFAULT NULL,
    applicant_phone   VARCHAR(20)  DEFAULT NULL,
    approver_user_id  BIGINT       DEFAULT NULL,
    approver_name     VARCHAR(50)  DEFAULT NULL,
    approve_time      DATETIME     DEFAULT NULL,
    approve_remark    VARCHAR(500) DEFAULT NULL,
    sort              INT          DEFAULT NULL,
    remark            VARCHAR(500) DEFAULT NULL,
    delete_flag       INT          NOT NULL DEFAULT 0,
    create_time       DATETIME     DEFAULT NULL,
    modify_time       DATETIME     DEFAULT NULL,
    create_user       BIGINT       DEFAULT NULL,
    modify_user       BIGINT       DEFAULT NULL,
    create_user_name  VARCHAR(50)  DEFAULT NULL,
    modify_user_name  VARCHAR(50)  DEFAULT NULL,
    unique_id         VARCHAR(64)  DEFAULT NULL,
    update_key        VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_corp_status (corp_id, status),
    KEY idx_order_item (order_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='增值业务退货申请';

-- ═══ 3. 旧表重命名为子表（仅当旧表仍是 BASE TABLE 且子表不存在） ═══
SET @old_type := (SELECT TABLE_TYPE FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_data');
SET @new_exists := (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item');
SET @ddl := IF(@old_type = 'BASE TABLE' AND @new_exists = 0,
    'RENAME TABLE ops_business_data TO ops_business_order_item',
    'SELECT ''rename skipped'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 4. 子表新增列（条件化） ═══
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND COLUMN_NAME='order_id');
SET @ddl := IF(@has=0,
    'ALTER TABLE ops_business_order_item ADD COLUMN order_id BIGINT DEFAULT NULL COMMENT ''订单头ID（ops_business_order.id）'' AFTER id',
    'SELECT ''order_id exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND COLUMN_NAME='is_return');
SET @ddl := IF(@has=0,
    "ALTER TABLE ops_business_order_item ADD COLUMN is_return VARCHAR(2) NOT NULL DEFAULT '否' COMMENT '是否退货' AFTER commission_amount",
    'SELECT ''is_return exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND COLUMN_NAME='return_quantity');
SET @ddl := IF(@has=0,
    'ALTER TABLE ops_business_order_item ADD COLUMN return_quantity INT NOT NULL DEFAULT 0 COMMENT ''累计退货数量'' AFTER is_return',
    'SELECT ''return_quantity exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND COLUMN_NAME='return_amount');
SET @ddl := IF(@has=0,
    'ALTER TABLE ops_business_order_item ADD COLUMN return_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT ''累计退货金额'' AFTER return_quantity',
    'SELECT ''return_amount exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND COLUMN_NAME='settle_quantity');
SET @ddl := IF(@has=0,
    'ALTER TABLE ops_business_order_item ADD COLUMN settle_quantity INT DEFAULT NULL COMMENT ''实际结算数量=销售数量-退货数量'' AFTER return_amount',
    'SELECT ''settle_quantity exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND COLUMN_NAME='return_status');
SET @ddl := IF(@has=0,
    "ALTER TABLE ops_business_order_item ADD COLUMN return_status VARCHAR(20) DEFAULT '未申请' COMMENT '退货审批状态' AFTER settle_quantity",
    'SELECT ''return_status exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND COLUMN_NAME='original_receipt_amount');
SET @ddl := IF(@has=0,
    'ALTER TABLE ops_business_order_item ADD COLUMN original_receipt_amount DECIMAL(12,2) DEFAULT NULL COMMENT ''退货前收款金额'' AFTER return_status',
    'SELECT ''original_receipt_amount exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 子表索引
SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND INDEX_NAME='idx_order_id');
SET @ddl := IF(@has=0,
    'ALTER TABLE ops_business_order_item ADD INDEX idx_order_id (order_id)',
    'SELECT ''idx_order_id exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_business_order_item' AND INDEX_NAME='idx_order_return');
SET @ddl := IF(@has=0,
    'ALTER TABLE ops_business_order_item ADD INDEX idx_order_return (order_id, is_return)',
    'SELECT ''idx_order_return exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 5. 兼容视图 ops_business_data（SCM / AI / 旧 JAR 只读） ═══
-- 直接执行（CREATE VIEW 不放进 PREPARE，避免部分 MySQL 版本不支持动态视图 DDL）；
-- 正常路径下旧表已在第 3 步重命名，此处创建/替换视图。
CREATE OR REPLACE VIEW ops_business_data AS SELECT * FROM ops_business_order_item;

-- ═══ 6. 回填：订单分组键临时表 ═══
DROP TEMPORARY TABLE IF EXISTS tmp_biz_item_key;
CREATE TEMPORARY TABLE tmp_biz_item_key AS
SELECT id,
       corp_id,
       CASE
         WHEN order_no IS NOT NULL AND order_no <> '' THEN CONCAT('O|', order_no)
         WHEN business_group_no IS NOT NULL AND business_group_no <> '' THEN CONCAT('G|', business_group_no)
         WHEN receipt_no IS NOT NULL AND receipt_no <> ''
              THEN CONCAT('R|', receipt_no, '|', COALESCE(batch_no, ''), '|', COALESCE(customer_phone, ''))
         ELSE CONCAT('I|', id)
       END AS order_key
FROM ops_business_order_item
WHERE delete_flag = 0;

DROP TEMPORARY TABLE IF EXISTS tmp_biz_order;
CREATE TEMPORARY TABLE tmp_biz_order AS
SELECT x.corp_id, x.order_key,
       MAX(x.batch_no) AS batch_no,
       MAX(x.order_no) AS order_no,
       MAX(x.business_group_no) AS business_group_no,
       MAX(x.receipt_no) AS receipt_no,
       MAX(x.company) AS company,
       MAX(x.customer) AS customer,
       MAX(x.customer_phone) AS customer_phone,
       MAX(x.platform_member_id) AS platform_member_id,
       MAX(x.community_id) AS community_id,
       MAX(x.community_name) AS community_name,
       MAX(x.detailed_address) AS detailed_address,
       GROUP_CONCAT(DISTINCT x.agent ORDER BY x.agent SEPARATOR '、') AS agent_names,
       GROUP_CONCAT(DISTINCT x.agent_phone ORDER BY x.agent_phone SEPARATOR '、') AS agent_phones,
       MAX(x.receipt_date) AS receipt_date,
       MAX(x.finance_receipt_date) AS finance_receipt_date,
       MAX(x.fund_dynamic_rows) AS fund_dynamic_rows,
       MAX(x.is_received) AS is_received,
       MAX(x.voucher_url) AS voucher_url,
       CASE
         WHEN SUM(x.calc_status = '已上报集团') > 0 THEN '已上报集团'
         WHEN SUM(x.calc_status = '未计算') > 0 THEN '未计算'
         WHEN SUM(x.calc_status = '已计算') > 0 THEN '已计算'
         WHEN SUM(x.calc_status = '经办人已核对') > 0 THEN '经办人已核对'
         ELSE MAX(x.calc_status)
       END AS calc_status,
       MAX(x.finance_check_user_id) AS finance_check_user_id,
       MAX(x.finance_check_user_name) AS finance_check_user_name,
       MAX(x.finance_check_time) AS finance_check_time
FROM (
    SELECT i.*, k.order_key FROM ops_business_order_item i
    JOIN tmp_biz_item_key k ON k.id = i.id
    WHERE i.delete_flag = 0
) x
GROUP BY x.corp_id, x.order_key;

DROP TEMPORARY TABLE IF EXISTS tmp_biz_order_total;
CREATE TEMPORARY TABLE tmp_biz_order_total AS
SELECT y.corp_id, y.order_key, SUM(y.amount) AS total_amount
FROM (
    SELECT x.corp_id, x.order_key, x.category, x.brand, x.model, x.sale_price,
           MAX(x.receipt_amount) AS amount
    FROM (
        SELECT i.*, k.order_key FROM ops_business_order_item i
        JOIN tmp_biz_item_key k ON k.id = i.id
        WHERE i.delete_flag = 0
    ) x
    GROUP BY x.corp_id, x.order_key, x.category, x.brand, x.model, x.sale_price
) y
GROUP BY y.corp_id, y.order_key;

-- ═══ 7. 回填主表（幂等：唯一键 (corp_id, order_key) + INSERT IGNORE） ═══
INSERT IGNORE INTO ops_business_order
    (corp_id, order_key, batch_no, order_no, business_group_no, receipt_no, company,
     customer, customer_phone, platform_member_id, community_id, community_name,
     detailed_address, agent_names, agent_phones, receipt_date, finance_receipt_date,
     fund_dynamic_rows, is_received, voucher_url, total_amount, total_commission,
     calc_status, finance_check_user_id, finance_check_user_name, finance_check_time,
     delete_flag, create_time, modify_time)
SELECT o.corp_id, o.order_key, o.batch_no, o.order_no, o.business_group_no, o.receipt_no,
       o.company, o.customer, o.customer_phone, o.platform_member_id, o.community_id,
       o.community_name, o.detailed_address, o.agent_names, o.agent_phones,
       o.receipt_date, o.finance_receipt_date, o.fund_dynamic_rows, o.is_received,
       o.voucher_url, COALESCE(t.total_amount, 0), 0,
       o.calc_status, o.finance_check_user_id, o.finance_check_user_name,
       o.finance_check_time, 0, NOW(), NOW()
FROM tmp_biz_order o
LEFT JOIN tmp_biz_order_total t ON t.corp_id = o.corp_id AND t.order_key = o.order_key;

-- 幂等重跑时补主表合计（含旧 JAR 在视图上新增的行）
UPDATE ops_business_order m
JOIN tmp_biz_order o ON o.corp_id = m.corp_id AND o.order_key = m.order_key
LEFT JOIN tmp_biz_order_total t ON t.corp_id = o.corp_id AND t.order_key = o.order_key
SET m.total_amount = COALESCE(t.total_amount, 0),
    m.modify_time = NOW();

-- ═══ 8. 回填子表 order_id ═══
UPDATE ops_business_order_item i
JOIN tmp_biz_item_key k ON k.id = i.id
JOIN ops_business_order m ON m.corp_id = k.corp_id AND m.order_key = k.order_key
SET i.order_id = m.id
WHERE i.order_id IS NULL;

-- ═══ 9. 退货字段初始化 ═══
UPDATE ops_business_order_item
SET is_return = '否'
WHERE is_return IS NULL OR is_return = '';

UPDATE ops_business_order_item
SET return_quantity = 0, return_amount = 0,
    settle_quantity = GREATEST(COALESCE(sale_quantity, 1), 0),
    return_status = '未申请',
    original_receipt_amount = COALESCE(original_receipt_amount, receipt_amount)
WHERE return_quantity IS NULL OR settle_quantity IS NULL OR return_status IS NULL
   OR return_amount IS NULL;

UPDATE ops_business_order_item
SET original_receipt_amount = receipt_amount
WHERE original_receipt_amount IS NULL AND is_return <> '是';

-- ═══ 10. 主表提成合计回填 ═══
UPDATE ops_business_order m
JOIN (
    SELECT order_id, SUM(commission_amount) AS total_commission
    FROM ops_business_order_item
    WHERE delete_flag = 0 AND order_id IS NOT NULL
    GROUP BY order_id
) x ON x.order_id = m.id
SET m.total_commission = COALESCE(x.total_commission, 0)
WHERE m.delete_flag = 0;

-- ═══ 11. 核对 ═══
-- 子表条数应等于迁移前 ops_business_data 条数
SELECT (SELECT COUNT(*) FROM ops_business_order_item WHERE delete_flag = 0) AS item_rows,
       (SELECT COUNT(*) FROM ops_business_order WHERE delete_flag = 0) AS order_rows,
       (SELECT COUNT(*) FROM ops_business_order_item WHERE order_id IS NULL AND delete_flag = 0) AS unlinked_rows;
