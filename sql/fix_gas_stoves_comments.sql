-- 修复 ops_gas_stoves_info 字段注释乱码（2026-08-27）
-- 根因：建表 SQL 通过 latin1 字符集的客户端连接执行，UTF-8 中文注释被双重编码为乱码。
-- 本脚本仅重写 COMMENT 元数据，列类型/默认值与 migrate_gas_stoves.sql 完全一致，不触碰任何数据。
-- 执行方式（必须指定 utf8mb4，否则会再次乱码）：
--   mysql -uroot -p --default-character-set=utf8mb4 mall_db < fix_gas_stoves_comments.sql
USE mall_db;

ALTER TABLE ops_gas_stoves_info
  MODIFY COLUMN id VARCHAR(32) NOT NULL COMMENT '主键（= 外部 sb_gas_stoves_info.id）',
  MODIFY COLUMN corp_id BIGINT NOT NULL DEFAULT 0 COMMENT '组织ID（同步触发组织；重复同步不覆盖）',
  MODIFY COLUMN batch_no VARCHAR(100) DEFAULT NULL COMMENT '同步批次号',
  MODIFY COLUMN sb_gas_user_id VARCHAR(64) DEFAULT NULL COMMENT '燃气用户表ID（sb_gas_user.id = ops_gas_user.user_no，远程当前全为空）',
  MODIFY COLUMN t_gas_stoves_info_id VARCHAR(64) DEFAULT NULL COMMENT '燃气系统-燃具信息表ID',
  MODIFY COLUMN gas_stoves_class VARCHAR(20) DEFAULT NULL COMMENT '燃具种类（字典 GAS_APPLIANCE_TYPE itemKey：1灶具 2热水器 3挂壁炉）',
  MODIFY COLUMN gas_stoves_brand VARCHAR(64) DEFAULT NULL COMMENT '燃具品牌',
  MODIFY COLUMN gas_stoves_type VARCHAR(64) DEFAULT NULL COMMENT '燃具型号',
  MODIFY COLUMN `power` INT DEFAULT NULL COMMENT '功率',
  MODIFY COLUMN focal_eye VARCHAR(64) DEFAULT NULL COMMENT '灶眼',
  MODIFY COLUMN installation_time DATETIME DEFAULT NULL COMMENT '安装时间',
  MODIFY COLUMN startup_time DATETIME DEFAULT NULL COMMENT '启用时间',
  MODIFY COLUMN buy_time DATETIME DEFAULT NULL COMMENT '购买时间',
  MODIFY COLUMN installation_position CHAR(1) DEFAULT NULL COMMENT '安装位置（1厨房 2过道 3阳台 4其他）',
  MODIFY COLUMN user_number VARCHAR(64) DEFAULT NULL COMMENT '用户编号（远程值，对应 sb_gas_user.gas_supply_certificate_number）',
  MODIFY COLUMN gas_company_id VARCHAR(32) DEFAULT NULL COMMENT '燃气公司ID',
  MODIFY COLUMN gas_the_company_id VARCHAR(32) DEFAULT NULL COMMENT '燃气公司ID（系统侧）',
  MODIFY COLUMN user_id VARCHAR(32) DEFAULT NULL COMMENT '用户ID（远程）',
  MODIFY COLUMN gas_stoves_factory VARCHAR(100) DEFAULT NULL COMMENT '生产厂家',
  MODIFY COLUMN gas_stoves_num INT DEFAULT NULL COMMENT '燃具数量';

ALTER TABLE ops_gas_stoves_info COMMENT = '燃具信息表（外部燃气系统同步）';
