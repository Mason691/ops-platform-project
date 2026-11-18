-- =====================================================================
-- 业务订单：退回草稿 + 作废退款
-- 执行库：mall_db
-- 部署顺序：本脚本先于新 ops 后端 JAR 执行。
-- 说明：
--   1. ops_business_order 增加 draft_flag：退回草稿后置 1，重新提交后置 0。
--   2. 新表 ops_business_refund：作废退款申请、金额、原因、附件、流程实例与状态。
--   3. 生产 MySQL 8.0.46 不支持 ADD COLUMN IF NOT EXISTS，使用
--      information_schema 条件化 PREPARE，可重复执行。
--   4. mysql 客户端必须带 --default-character-set=utf8mb4。
-- =====================================================================

USE mall_db;
SET NAMES utf8mb4;
SET @db := DATABASE();

-- ── 1. 订单头草稿标记 ────────────────────────────────────────────────
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
      AND COLUMN_NAME = 'draft_flag');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE ops_business_order ADD COLUMN draft_flag TINYINT NOT NULL DEFAULT 0 COMMENT ''草稿标记：1=退回草稿待编辑重提'' AFTER biz_status',
    'SELECT ''draft_flag already exists''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2. 作废退款申请表 ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ops_business_refund (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    corp_id               BIGINT        NOT NULL COMMENT '订单归属组织ID',
    order_id              BIGINT        NOT NULL COMMENT '业务订单ID',
    refund_no             VARCHAR(64)   NOT NULL COMMENT '退款申请单号',
    reason                VARCHAR(500)  NOT NULL COMMENT '退款原因（必填）',
    amount                DECIMAL(12,2) NOT NULL COMMENT '退款金额（≤订单应付合计）',
    attachment_urls       VARCHAR(3000) DEFAULT NULL COMMENT '补充附件URL，逗号分隔',
    attachment_ids        VARCHAR(1000) DEFAULT NULL COMMENT '补充附件ID，逗号分隔',
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                          COMMENT 'PENDING=审批中 / APPROVED=已作废退款 / REJECTED=已驳回 / TERMINATED=已终止',
    original_biz_status   VARCHAR(10)   DEFAULT NULL COMMENT '申请时订单业务状态，驳回时恢复',
    wf_instance_id        BIGINT        DEFAULT NULL COMMENT '作废工作流实例ID',
    wf_node_order         INT           DEFAULT NULL COMMENT '当前节点序号',
    wf_node_name          VARCHAR(100)  DEFAULT NULL COMMENT '当前节点名称',
    applicant_user_id     BIGINT        DEFAULT NULL COMMENT '申请人 sys_user.id',
    applicant_name        VARCHAR(100)  DEFAULT NULL COMMENT '申请人姓名',
    applicant_phone       VARCHAR(20)   DEFAULT NULL COMMENT '申请人手机号',
    reject_reason         VARCHAR(500)  DEFAULT NULL COMMENT '驳回/终止原因',
    approved_time         DATETIME      DEFAULT NULL COMMENT '作废通过时间',
    update_key            VARCHAR(64)   DEFAULT NULL,
    unique_id             VARCHAR(64)   DEFAULT NULL,
    create_time           DATETIME      DEFAULT NULL,
    modify_time           DATETIME      DEFAULT NULL,
    create_user           BIGINT        DEFAULT NULL,
    modify_user           BIGINT        DEFAULT NULL,
    create_user_name      VARCHAR(50)   DEFAULT NULL,
    modify_user_name      VARCHAR(50)   DEFAULT NULL,
    sort                  INT           DEFAULT 0,
    remark                VARCHAR(500)  DEFAULT NULL COMMENT '退款备注（选填）',
    delete_flag           INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    KEY idx_order_status (order_id, status),
    KEY idx_corp_status (corp_id, status),
    KEY idx_wf_instance (wf_instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='业务订单作废退款申请（部长→财务→总经办）';

-- ── 3. 验证 ─────────────────────────────────────────────────────────
SELECT 'ops_business_order.draft_flag' AS item, COUNT(*) AS cnt
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_order'
  AND COLUMN_NAME = 'draft_flag';

SELECT 'ops_business_refund table' AS item, COUNT(*) AS cnt
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ops_business_refund';
