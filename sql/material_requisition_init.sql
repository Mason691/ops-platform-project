-- =====================================================================
-- 员工物资领用 / 物资核销（mall_db）
-- 幂等：CREATE TABLE IF NOT EXISTS；生产执行必须带 --default-character-set=utf8mb4
-- 说明：
--   - 领用为「申请制」：不绑仓库、不扣库存；审批通过后可核销
--   - 核销允许部分核销：核销数量 + 退回数量 ≤ 可核销余量
--   - 单号：LY-yyyyMMdd-NNN（领用）/ WX-yyyyMMdd-NNN（核销）
-- =====================================================================

USE mall_db;

CREATE TABLE IF NOT EXISTS ops_material_requisition (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  corp_id BIGINT NOT NULL DEFAULT 0,
  requisition_no VARCHAR(64) NOT NULL COMMENT '领用单号 LY-yyyyMMdd-NNN',
  applicant_user_id BIGINT NOT NULL COMMENT '主领用人（提交人）',
  applicant_name VARCHAR(64),
  applicant_user_ids VARCHAR(500) NOT NULL COMMENT '全部领用人ID，逗号分隔',
  applicant_names VARCHAR(500) COMMENT '全部领用人姓名，逗号分隔',
  dept_id BIGINT DEFAULT NULL,
  dept_name VARCHAR(128),
  purpose VARCHAR(500) NOT NULL COMMENT '领用用途',
  expected_date DATE NOT NULL COMMENT '期望领用日期',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/ISSUED',
  process_instance_id VARCHAR(64) DEFAULT NULL,
  wf_node_order INT DEFAULT NULL,
  wf_node_name VARCHAR(128) DEFAULT NULL,
  approval_remark VARCHAR(500) DEFAULT NULL,
  approve_time DATETIME DEFAULT NULL,
  write_off_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PARTIAL/COMPLETED',
  update_key VARCHAR(64), unique_id VARCHAR(32),
  create_time DATETIME, modify_time DATETIME,
  create_user BIGINT, modify_user BIGINT,
  create_user_name VARCHAR(64), modify_user_name VARCHAR(64),
  sort INT DEFAULT 0,
  remark VARCHAR(500),
  delete_flag TINYINT DEFAULT 0,
  UNIQUE KEY uk_requisition_no (requisition_no),
  KEY idx_corp_applicant (corp_id, applicant_user_id),
  KEY idx_corp_status (corp_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工物资领用单';

CREATE TABLE IF NOT EXISTS ops_material_requisition_entry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  corp_id BIGINT NOT NULL DEFAULT 0,
  requisition_id BIGINT NOT NULL,
  material_id BIGINT NOT NULL COMMENT '供应链商品ID（scm_bd_material.id）',
  material_number VARCHAR(64),
  material_name VARCHAR(128) NOT NULL,
  category VARCHAR(128) NOT NULL,
  brand VARCHAR(128),
  model VARCHAR(200),
  unit VARCHAR(64),
  quantity INT NOT NULL COMMENT '领用数量（正整数）',
  written_off_qty INT NOT NULL DEFAULT 0 COMMENT '累计已核销',
  returned_qty INT NOT NULL DEFAULT 0 COMMENT '累计已退回',
  update_key VARCHAR(64), unique_id VARCHAR(32),
  create_time DATETIME, modify_time DATETIME,
  create_user BIGINT, modify_user BIGINT,
  create_user_name VARCHAR(64), modify_user_name VARCHAR(64),
  sort INT DEFAULT 0,
  remark VARCHAR(500),
  delete_flag TINYINT DEFAULT 0,
  KEY idx_requisition (requisition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工物资领用明细';

CREATE TABLE IF NOT EXISTS ops_material_write_off (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  corp_id BIGINT NOT NULL DEFAULT 0,
  write_off_no VARCHAR(64) NOT NULL COMMENT '核销单号 WX-yyyyMMdd-NNN',
  requisition_id BIGINT NOT NULL,
  requisition_no VARCHAR(64),
  applicant_user_id BIGINT NOT NULL COMMENT '领用单主领用人',
  applicant_name VARCHAR(64),
  dept_id BIGINT DEFAULT NULL,
  dept_name VARCHAR(128),
  write_off_user_id BIGINT NOT NULL COMMENT '主核销人（提交人）',
  write_off_user_name VARCHAR(64),
  write_off_user_ids VARCHAR(500) NOT NULL,
  write_off_user_names VARCHAR(500),
  write_off_date DATE NOT NULL,
  usage_desc VARCHAR(1000) NOT NULL COMMENT '使用说明',
  voucher_urls TEXT COMMENT '凭证 JSON 数组',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  process_instance_id VARCHAR(64) DEFAULT NULL,
  wf_node_order INT DEFAULT NULL,
  wf_node_name VARCHAR(128) DEFAULT NULL,
  approval_remark VARCHAR(500) DEFAULT NULL,
  approve_time DATETIME DEFAULT NULL,
  update_key VARCHAR(64), unique_id VARCHAR(32),
  create_time DATETIME, modify_time DATETIME,
  create_user BIGINT, modify_user BIGINT,
  create_user_name VARCHAR(64), modify_user_name VARCHAR(64),
  sort INT DEFAULT 0,
  remark VARCHAR(500),
  delete_flag TINYINT DEFAULT 0,
  UNIQUE KEY uk_write_off_no (write_off_no),
  KEY idx_corp_user (corp_id, write_off_user_id),
  KEY idx_requisition (requisition_id),
  KEY idx_corp_status (corp_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工物资核销单';

CREATE TABLE IF NOT EXISTS ops_material_write_off_entry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  corp_id BIGINT NOT NULL DEFAULT 0,
  write_off_id BIGINT NOT NULL,
  requisition_entry_id BIGINT NOT NULL,
  material_id BIGINT NOT NULL,
  material_number VARCHAR(64),
  material_name VARCHAR(128) NOT NULL,
  category VARCHAR(128) NOT NULL,
  brand VARCHAR(128),
  model VARCHAR(200),
  unit VARCHAR(64),
  requisition_qty INT NOT NULL COMMENT '领用数量（原值）',
  remaining_qty INT NOT NULL COMMENT '可核销余量快照',
  write_off_qty INT NOT NULL DEFAULT 0,
  return_qty INT NOT NULL DEFAULT 0,
  update_key VARCHAR(64), unique_id VARCHAR(32),
  create_time DATETIME, modify_time DATETIME,
  create_user BIGINT, modify_user BIGINT,
  create_user_name VARCHAR(64), modify_user_name VARCHAR(64),
  sort INT DEFAULT 0,
  remark VARCHAR(500),
  delete_flag TINYINT DEFAULT 0,
  KEY idx_write_off (write_off_id),
  KEY idx_requisition_entry (requisition_entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工物资核销明细';

CREATE TABLE IF NOT EXISTS ops_material_seq (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  bill_type VARCHAR(8) NOT NULL COMMENT 'LY/WX',
  bill_date VARCHAR(8) NOT NULL COMMENT 'yyyyMMdd',
  current_no INT NOT NULL DEFAULT 0,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  modify_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_type_date (bill_type, bill_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物资领用/核销单号序列';
