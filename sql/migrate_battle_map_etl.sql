-- ============================================================================
-- 作战地图 ETL 预计算表（重建）
-- ----------------------------------------------------------------------------
-- 数据来源：
--   ai_platform.sys_community   小区（名称/地址/坐标/business region）
--   ops_activity                活动（名称/类型）
--   ops_member_ledger           活动台账（销售额/经办人/小区/活动关联）
--   ops_business_data           增值业务数据（提成 commission_amount）
--   ops_gas_user                燃气用户（用户数）
--   ops_grid_manager(_community) 网格员
-- 说明：全部为 BattleMapEtlService 全量/增量刷新的派生表，可随时重建，
--       按 org_id(=corp_id) 隔离；不含逻辑删除（ETL 每次先 DELETE 本组织再重写）。
-- ============================================================================

-- ── 1. 组织汇总表 ───────────────────────────────────────────────────────────
DROP TABLE IF EXISTS ops_map_org_summary;
CREATE TABLE ops_map_org_summary (
  id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
  org_id              BIGINT       NOT NULL COMMENT '组织ID(=corp_id)',
  total_communities   INT          DEFAULT 0 COMMENT '小区总数(有燃气用户的小区)',
  covered_communities INT          DEFAULT 0 COMMENT '覆盖小区数(有台账的小区)',
  coverage_rate       DECIMAL(5,1) DEFAULT 0 COMMENT '覆盖率%',
  total_activities    INT          DEFAULT 0 COMMENT '活动次数',
  month_activities    INT          DEFAULT 0 COMMENT '本月活动次数',
  total_sales         DECIMAL(14,2) DEFAULT 0 COMMENT '总销售额(台账)',
  total_commission    DECIMAL(14,2) DEFAULT 0 COMMENT '总提成(增值业务)',
  total_users         INT          DEFAULT 0 COMMENT '总用户数',
  etl_time            DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'ETL时间',
  UNIQUE KEY uk_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作战地图-组织汇总表';

-- ── 2. 小区分布表（地图标注 + 详情弹窗数据源）─────────────────────────────────
DROP TABLE IF EXISTS ops_map_community;
CREATE TABLE ops_map_community (
  id                 BIGINT        PRIMARY KEY AUTO_INCREMENT,
  org_id             BIGINT        NOT NULL,
  community_id       BIGINT        NOT NULL COMMENT '小区ID(sys_community.id)',
  community_name     VARCHAR(128),
  address            VARCHAR(255)  COMMENT '小区地址',
  longitude          DECIMAL(10,6),
  latitude           DECIMAL(10,6),
  user_count         INT           DEFAULT 0 COMMENT '用户数',
  activity_count     INT           DEFAULT 0 COMMENT '活动次数',
  ledger_count       INT           DEFAULT 0 COMMENT '台账数',
  agent_count        INT           DEFAULT 0 COMMENT '经办人数',
  total_sales        DECIMAL(14,2) DEFAULT 0 COMMENT '销售额',
  grid_manager_names VARCHAR(255)  COMMENT '网格员',
  etl_time           DATETIME      DEFAULT CURRENT_TIMESTAMP,
  KEY idx_org (org_id),
  KEY idx_org_comm (org_id, community_id),
  KEY idx_org_sales (org_id, total_sales)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作战地图-小区分布表';

-- ── 3. 经办人业绩表（TOP10 + 详情反查）─────────────────────────────────────────
DROP TABLE IF EXISTS ops_map_employee_performance;
CREATE TABLE ops_map_employee_performance (
  id                  BIGINT        PRIMARY KEY AUTO_INCREMENT,
  org_id              BIGINT        NOT NULL,
  agent_key           VARCHAR(64)   COMMENT '台账经办人原始标识(agent,用于详情反查)',
  employee_name       VARCHAR(64)   COMMENT '经办人姓名(解析后)',
  employee_phone      VARCHAR(32),
  primary_community   VARCHAR(128)  COMMENT '所属/主战小区(销售额最高的小区)',
  activity_count      INT           DEFAULT 0,
  covered_communities INT           DEFAULT 0 COMMENT '覆盖小区数',
  total_sales         DECIMAL(14,2) DEFAULT 0 COMMENT '累计销售额',
  sales_count         INT           DEFAULT 0 COMMENT '销售订单数',
  rank_order          INT           DEFAULT 0 COMMENT '排名',
  etl_time            DATETIME      DEFAULT CURRENT_TIMESTAMP,
  KEY idx_org (org_id),
  KEY idx_org_rank (org_id, rank_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作战地图-经办人业绩表';

-- ── 4. 活动统计表 ───────────────────────────────────────────────────────────
DROP TABLE IF EXISTS ops_map_activity;
CREATE TABLE ops_map_activity (
  id                BIGINT        PRIMARY KEY AUTO_INCREMENT,
  org_id            BIGINT        NOT NULL,
  activity_id       BIGINT        NOT NULL,
  activity_name     VARCHAR(128),
  activity_type     VARCHAR(32)   COMMENT '活动类型',
  participant_count INT           DEFAULT 0 COMMENT '参与人数(预约)',
  total_cost        DECIMAL(14,2) DEFAULT 0 COMMENT '活动费用',
  community_count   INT           DEFAULT 0 COMMENT '关联小区数',
  community_names   VARCHAR(512)  COMMENT '关联小区',
  sales_amount      DECIMAL(14,2) DEFAULT 0 COMMENT '活动带来的销售额',
  etl_time          DATETIME      DEFAULT CURRENT_TIMESTAMP,
  KEY idx_org (org_id),
  KEY idx_org_act (org_id, activity_id),
  KEY idx_org_sales (org_id, sales_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作战地图-活动统计表';
