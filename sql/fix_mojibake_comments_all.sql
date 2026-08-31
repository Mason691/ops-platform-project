-- 全库乱码注释批量修复（2026-08-30）
-- 机理（两种客户端导致双重编码，字节级无损反转）：
--   1. latin1 客户端（Linux docker exec mysql 默认）—— utf8 字节逐字节按 latin1 存
--   2. cp1252 客户端（Windows 工具/脚本）—— utf8 字节逐字节按 Windows-1252 存
-- 执行要求：--default-character-set=utf8mb4（否则再次双重编码损坏！）
-- 例：docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p < fix_mojibake_comments_all.sql
-- 幂等：仅重写注释，保留类型/可空性/默认值/索引。2026-08-30 已执行并验证清零。

ALTER TABLE `ai_platform`.`ai_document`
    MODIFY COLUMN `file_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '文件路径',
    MODIFY COLUMN `allow_children` tinyint NOT NULL DEFAULT 0 COMMENT '是否允许下级组织查看';
ALTER TABLE `ai_platform`.`ai_file_analysis`
    MODIFY COLUMN `corp_id` bigint NOT NULL COMMENT '租户ID',
    MODIFY COLUMN `user_id` bigint NULL COMMENT '上传用户ID',
    MODIFY COLUMN `conversation_id` bigint NULL COMMENT '关联会话ID',
    MODIFY COLUMN `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原始文件名',
    MODIFY COLUMN `file_path` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '服务器存储路径',
    MODIFY COLUMN `file_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '文件后缀',
    MODIFY COLUMN `file_size` bigint NULL COMMENT '文件大小',
    MODIFY COLUMN `extracted_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '文件解析后的纯文本',
    MODIFY COLUMN `analysis_result` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'AI分析结果',
    MODIFY COLUMN `question` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '用户提问',
    MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 0 COMMENT '0=处理中 1=已就绪 2=失败',
    COMMENT 'AI文件分析任务';
ALTER TABLE `ai_platform`.`sys_corp`
    MODIFY COLUMN `region_id` bigint NULL COMMENT '业务区域ID（关联 sys_region.id）',
    MODIFY COLUMN `region_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '业务区域路径，如 北京市/北京市/朝阳区',
    MODIFY COLUMN `platform_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '组织自定义平台名称';
ALTER TABLE `ai_platform`.`sys_dept`
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    MODIFY COLUMN `corp_id` bigint NOT NULL DEFAULT 0 COMMENT '租户ID',
    MODIFY COLUMN `parent_id` bigint NOT NULL DEFAULT 0 COMMENT '父部门ID（0=顶级）',
    MODIFY COLUMN `dept_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '部门名称',
    MODIFY COLUMN `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '部门描述',
    MODIFY COLUMN `leader_user_id` bigint NULL COMMENT '部门负责人用户ID（关联 sys_user.id）',
    MODIFY COLUMN `update_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '乐观锁辅助字段',
    MODIFY COLUMN `unique_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '业务唯一标识',
    MODIFY COLUMN `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN `modify_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    MODIFY COLUMN `create_user` bigint NULL COMMENT '创建人ID',
    MODIFY COLUMN `modify_user` bigint NULL COMMENT '修改人ID',
    MODIFY COLUMN `create_user_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '创建人姓名',
    MODIFY COLUMN `modify_user_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '修改人姓名',
    MODIFY COLUMN `sort` int NULL DEFAULT 0 COMMENT '排序',
    MODIFY COLUMN `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '备注',
    MODIFY COLUMN `delete_flag` tinyint NULL DEFAULT 0 COMMENT '逻辑删除: 0=正常, 1=已删除',
    COMMENT '部门表';
ALTER TABLE `ai_platform`.`sys_member`
    MODIFY COLUMN `birthday` date NULL COMMENT '出生日期';
ALTER TABLE `ai_platform`.`sys_user`
    MODIFY COLUMN `position` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '职别',
    MODIFY COLUMN `dept_id` bigint NULL COMMENT '所属部门ID',
    MODIFY COLUMN `force_password_change` tinyint(1) NULL DEFAULT 0 COMMENT '首次登录后是否强制修改密码（1=是）';
ALTER TABLE `mall_db`.`ops_activity`
    MODIFY COLUMN `partner_corp_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '合伙公司ID(逗号分隔)',
    MODIFY COLUMN `is_operator_initiated` tinyint NULL DEFAULT 0 COMMENT '是否运营商发起(1=是 0=合伙人发起)';
ALTER TABLE `mall_db`.`ops_activity_review`
    MODIFY COLUMN `corp_id` bigint NOT NULL DEFAULT 0 COMMENT '组织ID',
    MODIFY COLUMN `activity_id` bigint NOT NULL COMMENT '关联 ops_activity.id',
    MODIFY COLUMN `summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '总结内容',
    MODIFY COLUMN `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '备注',
    MODIFY COLUMN `attachment` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '附件URL',
    MODIFY COLUMN `review_status` tinyint NULL DEFAULT 0 COMMENT '0=待审核 1=已通过 2=已驳回',
    MODIFY COLUMN `review_comment` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '评价内容',
    MODIFY COLUMN `review_by` bigint NULL COMMENT '审核人ID',
    MODIFY COLUMN `review_time` datetime NULL COMMENT '审核时间',
    MODIFY COLUMN `participant_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '参与员工ID(逗号分隔)',
    COMMENT '活动复盘主表';
ALTER TABLE `mall_db`.`ops_activity_review_cost`
    MODIFY COLUMN `review_id` bigint NOT NULL COMMENT '关联 ops_activity_review.id',
    MODIFY COLUMN `material` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '物料描述',
    MODIFY COLUMN `amount` decimal(10,2) NULL DEFAULT 0.00 COMMENT '费用金额',
    MODIFY COLUMN `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '备注',
    COMMENT '活动复盘费用明细';
ALTER TABLE `mall_db`.`ops_annual_plan`
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    MODIFY COLUMN `year` int NOT NULL COMMENT '年度',
    MODIFY COLUMN `org_id` bigint NOT NULL COMMENT '组织ID（子公司）',
    MODIFY COLUMN `category` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '品类代码',
    MODIFY COLUMN `plan_amount` decimal(12,2) NOT NULL DEFAULT 0.00 COMMENT '计划金额',
    MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 0 COMMENT '审核状态',
    MODIFY COLUMN `operator_id` bigint NULL COMMENT '运营商ID',
    MODIFY COLUMN `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '备注',
    MODIFY COLUMN `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    COMMENT '年度任务计划表';
ALTER TABLE `mall_db`.`ops_business_data`
    MODIFY COLUMN `usage_date` date NULL COMMENT '使用日期';
ALTER TABLE `mall_db`.`ops_map_activity`
    MODIFY COLUMN `activity_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '活动类型',
    MODIFY COLUMN `participant_count` int NULL DEFAULT 0 COMMENT '参与人数(预约)',
    MODIFY COLUMN `total_cost` decimal(14,2) NULL DEFAULT 0.00 COMMENT '活动费用',
    MODIFY COLUMN `community_count` int NULL DEFAULT 0 COMMENT '关联小区数',
    MODIFY COLUMN `community_names` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '关联小区',
    MODIFY COLUMN `sales_amount` decimal(14,2) NULL DEFAULT 0.00 COMMENT '活动带来的销售额',
    COMMENT '作战地图-活动统计表';
ALTER TABLE `mall_db`.`ops_map_category_sales`
    MODIFY COLUMN `org_id` bigint NOT NULL COMMENT '组织ID(=corp_id)',
    MODIFY COLUMN `category_key` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '品类代码(item_key)',
    MODIFY COLUMN `category_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '品类名称(字典中文)',
    MODIFY COLUMN `total_sales` decimal(14,2) NULL DEFAULT 0.00 COMMENT '销售额',
    MODIFY COLUMN `sales_count` int NULL DEFAULT 0 COMMENT '销售订单数',
    MODIFY COLUMN `rank_order` int NULL DEFAULT 0 COMMENT '排名',
    COMMENT '作战地图-品类销售额排名表';
ALTER TABLE `mall_db`.`ops_map_community`
    MODIFY COLUMN `community_id` bigint NOT NULL COMMENT '小区ID(sys_community.id)',
    MODIFY COLUMN `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '小区地址',
    MODIFY COLUMN `user_count` int NULL DEFAULT 0 COMMENT '用户数',
    MODIFY COLUMN `activity_count` int NULL DEFAULT 0 COMMENT '活动次数',
    MODIFY COLUMN `ledger_count` int NULL DEFAULT 0 COMMENT '台账数',
    MODIFY COLUMN `agent_count` int NULL DEFAULT 0 COMMENT '经办人数',
    MODIFY COLUMN `total_sales` decimal(14,2) NULL DEFAULT 0.00 COMMENT '销售额',
    MODIFY COLUMN `grid_manager_names` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '网格员',
    COMMENT '作战地图-小区分布表';
ALTER TABLE `mall_db`.`ops_map_employee_performance`
    MODIFY COLUMN `agent_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '台账经办人原始标识(agent,用于详情反查)',
    MODIFY COLUMN `employee_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '经办人姓名(解析后)',
    MODIFY COLUMN `department` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '所属部门/组织名称',
    MODIFY COLUMN `primary_community` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '所属/主战小区(销售额最高的小区)',
    MODIFY COLUMN `covered_communities` int NULL DEFAULT 0 COMMENT '覆盖小区数',
    MODIFY COLUMN `total_sales` decimal(14,2) NULL DEFAULT 0.00 COMMENT '累计销售额',
    MODIFY COLUMN `sales_count` int NULL DEFAULT 0 COMMENT '销售订单数',
    MODIFY COLUMN `rank_order` int NULL DEFAULT 0 COMMENT '排名',
    COMMENT '作战地图-经办人业绩表';
ALTER TABLE `mall_db`.`ops_map_grid_performance`
    MODIFY COLUMN `org_id` bigint NOT NULL COMMENT '组织ID(=corp_id)',
    MODIFY COLUMN `grid_manager_id` bigint NOT NULL COMMENT '关联 ops_grid_manager.id',
    MODIFY COLUMN `employee_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '网格员姓名(从sys_user解析)',
    MODIFY COLUMN `department` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '所属部门/组织名称',
    MODIFY COLUMN `total_sales` decimal(14,2) NULL DEFAULT 0.00 COMMENT '销售额',
    MODIFY COLUMN `community_count` int NULL DEFAULT 0 COMMENT '负责小区数',
    MODIFY COLUMN `rank_order` int NULL DEFAULT 0 COMMENT '排名',
    COMMENT '作战地图-网格员业绩排名表';
ALTER TABLE `mall_db`.`ops_map_org_summary`
    MODIFY COLUMN `org_id` bigint NOT NULL COMMENT '组织ID(=corp_id)',
    MODIFY COLUMN `total_communities` int NULL DEFAULT 0 COMMENT '小区总数(有燃气用户的小区)',
    MODIFY COLUMN `covered_communities` int NULL DEFAULT 0 COMMENT '覆盖小区数(有台账的小区)',
    MODIFY COLUMN `coverage_rate` decimal(5,1) NULL DEFAULT 0.0 COMMENT '覆盖率%',
    MODIFY COLUMN `total_activities` int NULL DEFAULT 0 COMMENT '活动次数',
    MODIFY COLUMN `month_activities` int NULL DEFAULT 0 COMMENT '本月活动次数',
    MODIFY COLUMN `total_sales` decimal(14,2) NULL DEFAULT 0.00 COMMENT '总销售额(台账)',
    MODIFY COLUMN `total_commission` decimal(14,2) NULL DEFAULT 0.00 COMMENT '总提成(增值业务)',
    MODIFY COLUMN `total_users` int NULL DEFAULT 0 COMMENT '总用户数',
    MODIFY COLUMN `etl_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'ETL时间',
    COMMENT '作战地图-组织汇总表';
ALTER TABLE `mall_db`.`ops_personal_commission_rule`
    MODIFY COLUMN `commission_amount` decimal(10,2) NULL COMMENT '提成金额，当提成类型为固定金额时使用';
ALTER TABLE `mall_db`.`ops_value_added_product`
    MODIFY COLUMN `modify_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    MODIFY COLUMN `corp_id` bigint NULL DEFAULT 0 COMMENT '租户ID',
    MODIFY COLUMN `update_key` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '更新键',
    MODIFY COLUMN `unique_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '唯一ID',
    MODIFY COLUMN `delete_flag` int NULL DEFAULT 0 COMMENT '逻辑删除',
    MODIFY COLUMN `sort` int NULL COMMENT '排序',
    MODIFY COLUMN `create_user` bigint NULL COMMENT '创建人ID',
    MODIFY COLUMN `modify_user` bigint NULL COMMENT '修改人ID',
    MODIFY COLUMN `create_user_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '创建人名称',
    MODIFY COLUMN `modify_user_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '修改人名称';

-- 表注释（解码原文即含「员」字，疑似原 DDL 笔误；如需改为「活动台账关联表」执行下句）
ALTER TABLE `mall_db`.`ops_activity_ledger` COMMENT '活动员台账关联表';
