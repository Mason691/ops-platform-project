-- 2026-11-12 增值业务退货模型重构：退货统一由 SCM 销售退货处理，业务数据订单只接收负数分录
-- 执行库：mall_db
-- 部署顺序（同一维护窗口）：
--   ① 停 ops 后端 → ② 本脚本 → ③ 启动新 ops JAR → ④ 部署新 SCM JAR（旧 SCM 仍查 original_sale_quantity，删列后会失败）
--   → ⑤ 发布平台前端 / ops H5
-- 幂等：列/索引经 information_schema 条件化；历史负数分录按来源行 NOT EXISTS 去重；主表合计全量重算
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4
--
-- 模型变化：
--   * 删除 is_return / return_quantity / return_amount / settle_quantity / return_status /
--     original_receipt_amount / original_sale_quantity；
--   * 新增 source_return_id / source_return_no，退货以「负数量分录」表达；
--   * 正数分录 sale_quantity 恢复为原始销售数量，receipt_amount 恢复为原始收款金额；
--   * 历史退货生成负数分录（source_return_id=0、source_return_no='历史退货'、提成 0、已计算），
--     正数提成保留迁移前已缩放值 → 净提成与迁移前一致，后续退货按剩余提成比例冲减，不会重复扣减；
--   * 删除旧的业务数据退货申请表 ops_business_return（审批职能已移至 SCM）。
--
-- ⚠ 执行前必须备份：
--   docker exec mysql mysqldump --default-character-set=utf8mb4 -uroot -p mall_db \
--     ops_business_order_item ops_business_order ops_business_return > /data2/backup/ops_before_return_refactor.sql

SET SESSION group_concat_max_len = 1000000;

-- ═══ 1. SCM 退货回写台账（增量幂等/并发防护） ═══
CREATE TABLE IF NOT EXISTS ops_scm_return_sync (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    corp_id       BIGINT       NOT NULL COMMENT '组织ID',
    scm_return_id BIGINT       NOT NULL COMMENT 'SCM 销售退货单ID',
    group_key     VARCHAR(255) NOT NULL COMMENT '订单ID|品类|品牌|型号|单价',
    applied_qty   INT          NOT NULL DEFAULT 0 COMMENT '本单在该产品组已回写的退货数量',
    delete_flag   INT          NOT NULL DEFAULT 0 COMMENT '0=正常 1=删除（反审核置1）',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    modify_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scm_group (scm_return_id, group_key),
    KEY idx_corp_return (corp_id, scm_return_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='SCM 销售退货回写台账（增量幂等/并发防护）';

-- ═══ 2. 子表新增退货来源标记列 ═══
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
      AND COLUMN_NAME = 'source_return_id');
SET @ddl := IF(@has = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN source_return_id BIGINT DEFAULT NULL COMMENT ''SCM销售退货单ID（空=正常销售，非空=退货负数分录）'' AFTER commission_amount',
    'SELECT ''source_return_id exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
      AND COLUMN_NAME = 'source_return_no');
SET @ddl := IF(@has = 0,
    'ALTER TABLE ops_business_order_item ADD COLUMN source_return_no VARCHAR(64) DEFAULT NULL COMMENT ''SCM退货单号（展示/跳转）；历史冲减为“历史退货”'' AFTER source_return_id',
    'SELECT ''source_return_no exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
      AND INDEX_NAME = 'idx_return_source');
SET @ddl := IF(@has = 0,
    'ALTER TABLE ops_business_order_item ADD INDEX idx_return_source (source_return_id)',
    'SELECT ''idx_return_source exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ═══ 3. 历史退货 → 负数分录（仅 return_quantity 列存在时执行） ═══
-- 临时表结构固定、不含旧列，旧列数据由后续条件化 UPDATE 填充，保证脚本可重复执行。
DROP TEMPORARY TABLE IF EXISTS tmp_hist_return;
CREATE TEMPORARY TABLE tmp_hist_return AS
SELECT i.id AS source_id, i.order_id, i.batch_no, i.order_no, i.business_group_no,
       i.company, i.agent, i.customer, i.customer_phone, i.community_id, i.community_name,
       i.detailed_address, i.platform_member_id, i.brand, i.category, i.model, i.sale_price,
       CAST(0 AS SIGNED) AS neg_quantity, CAST(0 AS DECIMAL(12,2)) AS neg_amount,
       i.sale_date, i.receipt_date, i.finance_receipt_date, i.fund_dynamic_rows,
       i.receipt_no, i.is_received, i.voucher_url, i.department, i.agent_phone,
       i.retail_floor_price, i.group_lowest_price, i.is_special, i.commission_rate,
       CAST(0 AS SIGNED) AS return_quantity, i.operator_id, i.create_user, i.modify_user,
       i.create_user_name, i.modify_user_name, i.update_key, i.unique_id, i.sort,
       i.usage_date, i.corp_id
FROM ops_business_order_item i
WHERE i.delete_flag = 0 AND COALESCE(i.sale_quantity, 0) >= 0;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
      AND COLUMN_NAME = 'return_quantity');
SET @ddl := IF(@has > 0,
    'UPDATE tmp_hist_return t JOIN ops_business_order_item i ON i.id = t.source_id
     SET t.neg_quantity = -i.return_quantity,
         t.neg_amount = -COALESCE(NULLIF(i.return_amount, 0),
                                  ROUND(COALESCE(i.sale_price, 0) * i.return_quantity, 2)),
         t.return_quantity = i.return_quantity
     WHERE COALESCE(i.return_quantity, 0) > 0',
    'SELECT ''skip legacy return scan''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

DELETE FROM tmp_hist_return WHERE COALESCE(return_quantity, 0) <= 0;

-- 历史遗留的负数量行（宽松导入/异常数据）统一标记为历史冲减，便于统计与对账识别；
-- 用 -id 作唯一来源键，避免同一订单同产品多条负数量行在去重时被合并
UPDATE ops_business_order_item
SET source_return_id = -id, source_return_no = '历史负数分录', modify_time = NOW()
WHERE delete_flag = 0 AND sale_quantity < 0
  AND source_return_id IS NULL AND source_return_no IS NULL;

INSERT INTO ops_business_order_item (
    order_id, batch_no, order_no, business_group_no, company, agent, customer, customer_phone,
    community_id, community_name, detailed_address, platform_member_id, brand, category, model,
    sale_price, sale_quantity, receipt_amount, sale_date, receipt_date, finance_receipt_date,
    fund_dynamic_rows, finance_check_user_id, finance_check_user_name, finance_check_time,
    receipt_no, is_received, voucher_url, department, agent_phone, retail_floor_price,
    group_lowest_price, is_special, commission_rate, commission_amount, remark, operator_id,
    create_time, corp_id, calc_status, update_key, unique_id, delete_flag, sort, create_user,
    modify_user, create_user_name, modify_user_name, modify_time, usage_date,
    source_return_id, source_return_no
)
SELECT t.order_id, t.batch_no, t.order_no, t.business_group_no, t.company, t.agent, t.customer,
       t.customer_phone, t.community_id, t.community_name, t.detailed_address,
       t.platform_member_id, t.brand, t.category, t.model, t.sale_price, t.neg_quantity,
       t.neg_amount, t.sale_date, t.receipt_date, t.finance_receipt_date, t.fund_dynamic_rows,
       NULL, NULL, NULL, t.receipt_no, t.is_received, t.voucher_url, t.department, t.agent_phone,
       t.retail_floor_price, t.group_lowest_price, t.is_special, t.commission_rate, 0,
       CONCAT('历史退货：累计退货 ', t.return_quantity), t.operator_id, NOW(), t.corp_id,
       '已计算', t.update_key, t.unique_id, 0, t.sort, t.create_user, t.modify_user,
       t.create_user_name, t.modify_user_name, NOW(), t.usage_date, 0, '历史退货'
FROM tmp_hist_return t
WHERE t.order_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM ops_business_order_item r
    WHERE r.delete_flag = 0 AND r.source_return_id = 0 AND r.sale_quantity < 0
      AND r.order_id <=> t.order_id
      AND r.category <=> t.category AND r.brand <=> t.brand AND r.model <=> t.model
      AND r.sale_price <=> t.sale_price
      AND r.agent_phone <=> t.agent_phone AND r.agent <=> t.agent
  );

-- ═══ 4. 正数分录恢复原始销售数量 / 原始收款金额（历史提成保留缩放值） ═══
-- 兼容两种旧语义：
--   ① HEAD 旧退货审批：sale_quantity 仍为原始数量，settle_quantity = sale - return（二者不等）；
--   ② 未发布的 SCM 同步改造：sale_quantity 已改为剩余数量，settle_quantity = sale（二者相等）。
-- 原始数量 Q = ① sale_quantity；② sale_quantity + return_quantity。
SET @ddl := IF(@has > 0,
    'UPDATE ops_business_order_item
     SET receipt_amount = COALESCE(NULLIF(original_receipt_amount, 0),
                                   ROUND(COALESCE(sale_price, 0) *
                                         (CASE WHEN COALESCE(settle_quantity, -1) <> COALESCE(sale_quantity, 0)
                                               THEN COALESCE(sale_quantity, 1)
                                               ELSE COALESCE(sale_quantity, 1) + COALESCE(return_quantity, 0) END), 2)),
         sale_quantity = CASE WHEN COALESCE(settle_quantity, -1) <> COALESCE(sale_quantity, 0)
                              THEN COALESCE(sale_quantity, 1)
                              ELSE COALESCE(sale_quantity, 1) + COALESCE(return_quantity, 0) END,
         modify_time = NOW()
     WHERE delete_flag = 0 AND COALESCE(return_quantity, 0) > 0
       AND COALESCE(sale_quantity, 0) >= 0',
    'SELECT ''skip restore''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
DROP TEMPORARY TABLE IF EXISTS tmp_hist_return;

-- ═══ 5. 删除旧退货字段（逐列条件化，兼容重复执行） ═══
SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
      AND INDEX_NAME = 'idx_order_return');
SET @ddl := IF(@has > 0,
    'ALTER TABLE ops_business_order_item DROP INDEX idx_order_return',
    'SELECT ''idx_order_return absent''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 列名 → 删除（MySQL 8.0 无 DROP COLUMN IF EXISTS，用 information_schema 条件化）
SET @col := 'is_return';
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = @col);
SET @ddl := IF(@has > 0, CONCAT('ALTER TABLE ops_business_order_item DROP COLUMN ', @col), 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := 'return_quantity';
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = @col);
SET @ddl := IF(@has > 0, CONCAT('ALTER TABLE ops_business_order_item DROP COLUMN ', @col), 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := 'return_amount';
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = @col);
SET @ddl := IF(@has > 0, CONCAT('ALTER TABLE ops_business_order_item DROP COLUMN ', @col), 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := 'settle_quantity';
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = @col);
SET @ddl := IF(@has > 0, CONCAT('ALTER TABLE ops_business_order_item DROP COLUMN ', @col), 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := 'return_status';
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = @col);
SET @ddl := IF(@has > 0, CONCAT('ALTER TABLE ops_business_order_item DROP COLUMN ', @col), 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := 'original_receipt_amount';
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = @col);
SET @ddl := IF(@has > 0, CONCAT('ALTER TABLE ops_business_order_item DROP COLUMN ', @col), 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := 'original_sale_quantity';
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item' AND COLUMN_NAME = @col);
SET @ddl := IF(@has > 0, CONCAT('ALTER TABLE ops_business_order_item DROP COLUMN ', @col), 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 旧退货申请审批表（职能已迁至 SCM 销售退货）
DROP TABLE IF EXISTS ops_business_return;

-- ═══ 6. 兼容视图 rebuild（SCM / AI 只读） ═══
CREATE OR REPLACE VIEW ops_business_data AS SELECT * FROM ops_business_order_item;

-- AI 实体配置字段 schema 同步（移除已删列，补充退货来源）
UPDATE ai_platform.sys_entity_config
SET fields_schema = JSON_SET(
        JSON_REMOVE(fields_schema, '$.is_return', '$.return_quantity', '$.settle_quantity'),
        '$.source_return_id', JSON_OBJECT('type', 'number', 'label', 'SCM退货单ID'),
        '$.source_return_no', JSON_OBJECT('type', 'string', 'label', '退货单号')),
    modify_time = NOW()
WHERE entity_class = 'com.aip.ops.entity.OpsBusinessData';

-- ═══ 7. 主表合计/提成/状态全量重算（正数产品组 MAX + 退货单产品组 MAX） ═══
DROP TEMPORARY TABLE IF EXISTS tmp_order_total2;
CREATE TEMPORARY TABLE tmp_order_total2 AS
SELECT x.order_id, SUM(x.amount) AS amount, SUM(x.commission) AS commission
FROM (
    SELECT i.order_id, MAX(i.receipt_amount) AS amount,
           SUM(i.commission_amount) AS commission
    FROM ops_business_order_item i
    WHERE i.delete_flag = 0 AND i.order_id IS NOT NULL
      AND COALESCE(i.sale_quantity, 1) >= 0
    GROUP BY i.order_id, i.category, i.brand, i.model, i.sale_price
    UNION ALL
    SELECT i.order_id, MAX(i.receipt_amount) AS amount,
           SUM(i.commission_amount) AS commission
    FROM ops_business_order_item i
    WHERE i.delete_flag = 0 AND i.order_id IS NOT NULL AND i.sale_quantity < 0
    GROUP BY i.order_id, COALESCE(i.source_return_id, 0),
             i.category, i.brand, i.model, i.sale_price
) x
GROUP BY x.order_id;

DROP TEMPORARY TABLE IF EXISTS tmp_order_status2;
CREATE TEMPORARY TABLE tmp_order_status2 AS
SELECT i.order_id,
       CASE
         WHEN SUM(i.calc_status = '已上报集团') > 0 THEN '已上报集团'
         WHEN SUM(i.calc_status = '未计算') > 0 THEN '未计算'
         WHEN SUM(i.calc_status = '已计算') > 0 THEN '已计算'
         WHEN SUM(i.calc_status = '经办人已核对') > 0 THEN '经办人已核对'
         ELSE MAX(i.calc_status)
       END AS calc_status
FROM ops_business_order_item i
WHERE i.delete_flag = 0 AND i.order_id IS NOT NULL
GROUP BY i.order_id;

UPDATE ops_business_order m
LEFT JOIN tmp_order_total2 t ON t.order_id = m.id
LEFT JOIN tmp_order_status2 s ON s.order_id = m.id
SET m.total_amount = COALESCE(t.amount, 0),
    m.total_commission = COALESCE(t.commission, 0),
    m.calc_status = COALESCE(s.calc_status, m.calc_status),
    m.modify_time = NOW()
WHERE m.delete_flag = 0;

-- ═══ 8. 验证（逐条 SELECT：MySQL 临时表同一查询不能重复打开） ═══
SELECT COUNT(*) AS negative_rows
FROM ops_business_order_item WHERE sale_quantity < 0;

SELECT COUNT(*) AS negative_without_marker
FROM ops_business_order_item
WHERE sale_quantity < 0 AND source_return_id IS NULL AND source_return_no IS NULL;

SELECT COUNT(*) AS legacy_columns_remaining
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_business_order_item'
  AND COLUMN_NAME IN ('is_return','return_quantity','return_amount','settle_quantity',
                      'return_status','original_receipt_amount','original_sale_quantity');

SELECT COUNT(*) AS total_mismatch
FROM ops_business_order m
LEFT JOIN tmp_order_total2 t ON t.order_id = m.id
WHERE m.delete_flag = 0
  AND ABS(COALESCE(m.total_amount, 0) - COALESCE(t.amount, 0)) > 0.01;

SELECT COUNT(*) AS commission_mismatch
FROM ops_business_order m
LEFT JOIN tmp_order_total2 t ON t.order_id = m.id
WHERE m.delete_flag = 0
  AND ABS(COALESCE(m.total_commission, 0) - COALESCE(t.commission, 0)) > 0.01;

DROP TEMPORARY TABLE IF EXISTS tmp_order_total2;
DROP TEMPORARY TABLE IF EXISTS tmp_order_status2;
