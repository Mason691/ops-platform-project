-- =====================================================================
-- 业务订单编号：运营商 + 下属城市合伙人共享的每日流水
-- 执行库：mall_db
-- 执行时机：停旧 ops 容器后、启动新 JAR 前执行（避免切换窗口旧 JAR 继续占号）
--
-- 说明：
--   1. 新表 ops_order_daily_sequence：主键 (operator_corp_id, seq_date)，
--      last_seq 为当日已分配最大流水；跨日自动从新行 001 开始。
--   2. 回填当天各运营商体系已用的最大流水（按订单编号第 3 位起解析），
--      避免新规则上线当天与旧全局流水已生成的编号重复。
--   3. 旧表 ops_order_sequence 不再被代码读取，保留用于回滚。
--   4. 幂等：建表 IF NOT EXISTS；回填使用 GREATEST 保留较大值。
-- =====================================================================

USE mall_db;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS ops_order_daily_sequence (
    operator_corp_id BIGINT  NOT NULL COMMENT '运营商组织ID（计数域：运营商+下属城市合伙人）',
    seq_date         DATE    NOT NULL COMMENT '流水日期（Asia/Shanghai）',
    last_seq         INT     NOT NULL DEFAULT 0 COMMENT '当日已分配最大流水号',
    modify_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                     ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (operator_corp_id, seq_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='业务订单每日流水（运营商体系共享，按日归零）';

-- 回填当天已有订单的运营商体系最大流水（仅解析 dd+流水 格式）
INSERT INTO ops_order_daily_sequence (operator_corp_id, seq_date, last_seq)
SELECT t.operator_id, CURDATE(), t.max_seq
FROM (
    SELECT CASE WHEN c.org_role_id = 5 THEN c.parent_corp_id ELSE c.id END AS operator_id,
           MAX(CAST(SUBSTRING(o.order_no, 3) AS UNSIGNED)) AS max_seq
    FROM ops_business_order o
    JOIN ai_platform.sys_corp c ON c.id = o.corp_id AND c.delete_flag = 0
    WHERE o.delete_flag = 0
      AND DATE(o.create_time) = CURDATE()
      AND o.order_no REGEXP '^[0-9]{5,}$'
      AND LEFT(o.order_no, 2) = DATE_FORMAT(CURDATE(), '%d')
    GROUP BY operator_id
) t
WHERE t.operator_id IS NOT NULL AND t.operator_id > 0
ON DUPLICATE KEY UPDATE last_seq = GREATEST(last_seq, VALUES(last_seq));

-- 验证：当天各运营商已回填的序号
SELECT s.operator_corp_id, s.seq_date, s.last_seq
FROM ops_order_daily_sequence s
WHERE s.seq_date = CURDATE()
ORDER BY s.operator_corp_id;

-- 验证：表结构
SELECT 'ops_order_daily_sequence' AS item, COUNT(*) AS cnt
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ops_order_daily_sequence';
