-- 2026-11-13 派工单 / 待处理 / 公众号消息提醒
-- 执行库：mall_db
-- 部署顺序：本脚本必须先于新 ops 后端 JAR 上线执行（新 JAR 直接读写这些表）
-- 注意：mysql 客户端必须带 --default-character-set=utf8mb4（中文注释防乱码）
--
-- 设计要点：
--   * 派工单号 {公司代码}-PG-{yyyyMMdd}-{3位流水}，由 ops_dispatch_sequence 按
--     (company_code, seq_date) 行锁串行分配；dispatch_no 唯一索引作为最后兜底。
--   * 派工单商品行保存来源分录 id（ops_business_order_item.id），接单时据此向 SCM
--     生成销售出库单；退货负数分录不纳入。
--   * 消息记录保存每次推送结果，派工单主表冗余最近一次状态，便于列表提示与重发。
--   * 模板为平台级配置（corp_id=0 全局，corp_id>0 组织覆盖），场景编码区分业务。

USE mall_db;

-- ═══ 1. 派工单主表 ═══
CREATE TABLE IF NOT EXISTS ops_dispatch_order (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    dispatch_no         VARCHAR(64)  NOT NULL COMMENT '派工单号：{公司代码}-PG-{yyyyMMdd}-{3位流水}',
    source_order_id     BIGINT       NOT NULL COMMENT '来源业务订单头 ops_business_order.id',
    source_order_no     VARCHAR(64)  DEFAULT NULL COMMENT '来源订单编号（冗余）',
    source_agent_names  VARCHAR(500) DEFAULT NULL COMMENT '来源订单经办人（展示/追溯）',
    receipt_no          VARCHAR(50)  DEFAULT NULL COMMENT '收据号（冗余）',
    corp_id             BIGINT       NOT NULL COMMENT '派工单归属组织',
    dispatch_type       VARCHAR(20)  NOT NULL DEFAULT 'CONSTRUCTION' COMMENT 'CONSTRUCTION=施工 / VISIT=入户随访',
    customer_name       VARCHAR(100) NOT NULL COMMENT '客户名称',
    customer_phone      VARCHAR(20)  NOT NULL COMMENT '客户手机',
    platform_member_id  BIGINT       DEFAULT NULL COMMENT '平台会员ID（来源订单，用于出库追溯）',
    community_id        BIGINT       DEFAULT NULL COMMENT '小区ID',
    community_name      VARCHAR(200) DEFAULT NULL COMMENT '小区名称',
    detailed_address    VARCHAR(500) DEFAULT NULL COMMENT '详细地址',
    expect_time         DATETIME     NOT NULL COMMENT '期望上门时间',
    assignee_user_id    BIGINT       NOT NULL COMMENT '被指派员工 sys_user.id',
    assignee_name       VARCHAR(50)  DEFAULT NULL COMMENT '被指派员工姓名',
    assignee_username   VARCHAR(50)  DEFAULT NULL COMMENT '被指派员工账号/手机号',
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTING(内部)/ACCEPTED/PROCESSING/COMPLETED/REJECTED/CANCELLED',
    dispatch_by         BIGINT       DEFAULT NULL COMMENT '派发人 sys_user.id',
    dispatch_by_name    VARCHAR(50)  DEFAULT NULL COMMENT '派发人姓名',
    dispatch_time       DATETIME     NOT NULL COMMENT '派发时间',
    accept_time         DATETIME     DEFAULT NULL COMMENT '接单时间',
    accept_lock_time    DATETIME     DEFAULT NULL COMMENT '接单处理中锁定时间（防取消/接单竞态，超时由定时任务回退）',
    start_time          DATETIME     DEFAULT NULL COMMENT '开始处理时间',
    complete_time       DATETIME     DEFAULT NULL COMMENT '完成时间',
    reject_time         DATETIME     DEFAULT NULL COMMENT '退回时间',
    reject_reason       VARCHAR(500) DEFAULT NULL COMMENT '退回原因',
    cancel_time         DATETIME     DEFAULT NULL COMMENT '取消时间',
    cancel_reason       VARCHAR(500) DEFAULT NULL COMMENT '取消原因',
    stock_out_id        BIGINT       DEFAULT NULL COMMENT '接单后生成的 SCM 销售出库单ID',
    stock_out_no        VARCHAR(64)  DEFAULT NULL COMMENT '销售出库单号（冗余）',
    message_status      VARCHAR(20)  DEFAULT NULL COMMENT '最近一次消息推送：SUCCESS/FAILED/SKIPPED',
    message_error       VARCHAR(500) DEFAULT NULL COMMENT '最近一次推送失败原因',
    source_snapshot     VARCHAR(1000) DEFAULT NULL COMMENT '派发人修改客户信息的原始值留痕（JSON）',
    update_key          VARCHAR(64)  DEFAULT NULL,
    unique_id           VARCHAR(64)  DEFAULT NULL,
    create_time         DATETIME     DEFAULT NULL,
    modify_time         DATETIME     DEFAULT NULL,
    create_user         BIGINT       DEFAULT NULL,
    modify_user         BIGINT       DEFAULT NULL,
    create_user_name    VARCHAR(50)  DEFAULT NULL,
    modify_user_name    VARCHAR(50)  DEFAULT NULL,
    sort                INT          DEFAULT 0,
    remark              VARCHAR(500) DEFAULT NULL COMMENT '备注；入户随访类型必填任务说明',
    delete_flag         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dispatch_no (dispatch_no),
    KEY idx_corp_status (corp_id, status),
    KEY idx_assignee_status (assignee_user_id, status),
    KEY idx_source_order (source_order_id),
    KEY idx_dispatch_time (dispatch_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派工单主表';

-- ═══ 2. 派工单商品明细（仅施工类型） ═══
CREATE TABLE IF NOT EXISTS ops_dispatch_order_item (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    dispatch_id         BIGINT        NOT NULL COMMENT '派工单ID',
    source_item_id      BIGINT        DEFAULT NULL COMMENT '来源业务数据分录 ops_business_order_item.id',
    product_name        VARCHAR(200)  DEFAULT NULL COMMENT '商品名称（品牌+型号）',
    brand               VARCHAR(100)  DEFAULT NULL,
    category            VARCHAR(50)   DEFAULT NULL COMMENT '品类字典 itemKey（1/2/3/4）',
    model               VARCHAR(200)  DEFAULT NULL,
    sale_quantity       INT           NOT NULL DEFAULT 1 COMMENT '施工数量',
    unit                VARCHAR(20)   DEFAULT NULL COMMENT '单位（台/套）',
    sale_price          DECIMAL(14,2) DEFAULT NULL COMMENT '销售单价',
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
    KEY idx_dispatch (dispatch_id),
    KEY idx_source_item (source_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派工单商品明细（施工类型）';

-- ═══ 3. 公众号消息推送记录 ═══
CREATE TABLE IF NOT EXISTS ops_dispatch_message (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    corp_id             BIGINT        NOT NULL COMMENT '派工单组织',
    dispatch_id         BIGINT        NOT NULL COMMENT '派工单ID',
    scene_code          VARCHAR(50)   DEFAULT NULL COMMENT '消息场景：DISPATCH_NOTICE / DISPATCH_CANCELLED',
    template_id         VARCHAR(128)  DEFAULT NULL COMMENT '微信模板ID',
    receiver_user_id    BIGINT        DEFAULT NULL COMMENT '接收员工 sys_user.id',
    openid              VARCHAR(128)  DEFAULT NULL COMMENT '接收人 openid',
    send_status         VARCHAR(16)   NOT NULL DEFAULT 'FAILED' COMMENT 'SUCCESS/FAILED/SKIPPED',
    error_msg           VARCHAR(500)  DEFAULT NULL COMMENT '失败原因',
    send_time           DATETIME      DEFAULT NULL,
    read_status         VARCHAR(16)   NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD/READ',
    read_time           DATETIME      DEFAULT NULL,
    retry_count         INT           NOT NULL DEFAULT 0,
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
    KEY idx_dispatch (dispatch_id),
    KEY idx_receiver (receiver_user_id),
    KEY idx_send_time (send_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派工单公众号消息推送记录';

-- ═══ 4. 公众号消息模板 ═══
CREATE TABLE IF NOT EXISTS ops_wx_msg_template (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    owner_corp_id       BIGINT        NOT NULL DEFAULT 0 COMMENT '归属组织ID；0=平台全局',
    corp_id             BIGINT        NOT NULL DEFAULT 0 COMMENT '兼容旧 JAR 的归属组织镜像；新代码以 owner_corp_id 为准',
    share_scope         VARCHAR(20)   NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE=仅本组织 / OPERATOR_TREE=运营商及下属共享 / PLATFORM=平台全局',
    scene_code          VARCHAR(50)   NOT NULL COMMENT '业务场景编码',
    template_id         VARCHAR(128)  NOT NULL COMMENT '微信公众号模板ID（默认/主公众号）',
    title               VARCHAR(100)  DEFAULT NULL COMMENT '模板标题',
    content             VARCHAR(2000) DEFAULT NULL COMMENT '模板内容（含变量占位符，供运营查看）',
    variable_desc       VARCHAR(1000) DEFAULT NULL COMMENT '变量字段说明',
    field_mapping       VARCHAR(1000) DEFAULT NULL COMMENT '逻辑变量 -> 微信模板字段 JSON，如 {"customerName":"thing1"}',
    app_id              VARCHAR(64)   DEFAULT NULL COMMENT '指定公众号 appId；空=按业务组织默认公众号',
    template_id_map     VARCHAR(1000) DEFAULT NULL COMMENT '按公众号 appId 映射 template_id JSON',
    status              TINYINT       NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    use_count           INT           NOT NULL DEFAULT 0 COMMENT '累计使用次数',
    last_used_time      DATETIME      DEFAULT NULL COMMENT '最近使用时间',
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
    KEY idx_scene_status (scene_code, status),
    KEY idx_owner_scope (owner_corp_id, share_scope, status),
    KEY idx_corp (corp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公众号消息模板';

-- ═══ 5. 派工单号流水（按公司代码 + 日期） ═══
CREATE TABLE IF NOT EXISTS ops_dispatch_sequence (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    corp_id             BIGINT       NOT NULL COMMENT '最近一次使用该代码的组织',
    company_code        VARCHAR(16)  NOT NULL COMMENT '公司代码（如 DB/KL/GH）',
    seq_date            DATE         NOT NULL COMMENT '流水日期',
    current_no          INT          NOT NULL DEFAULT 0 COMMENT '当日已分配最大流水号',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_date (company_code, seq_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派工单号每日流水';
