-- 燃气用户表 corp_id 关联规则重构（2026-08-29）
-- corp_id 不再取任务发起组织，改为：外部 sb_gas_user.gas_company_id → sys_corp.gas_company_id → sys_corp.id。
--   （实测 sb_gas_user.gas_company_id 为有意义的公司ID：246广河 / 235甘肃金地(临洮) / 243武山 / 251康乐 /
--    245彭阳 / 228德保 均已匹配 sys_corp；未匹配公司 221乌兰金地 / 220海西金地 / 244天峻渝能 / 227柴旦金地
--    → corp_id=0 待人工处理，sys_corp 补录对应 gas_company_id 后重跑本脚本即可自愈。）
-- 新增 corp_match_status 标记：1=组织已匹配 0=未匹配异常（corp_id=0，待人工处理）。
-- 幂等：列已存在时跳过；兜底回填按当前 sys_corp 映射重算全部存量行。
-- ⚠️ 必须带字符集执行：mysql --default-character-set=utf8mb4（客户端默认 latin1 会写坏中文注释）
-- 执行方式（生产）：
--   docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -padmin.2026 mall_db < migrate_gas_user_corp_match.sql
USE mall_db;

DROP PROCEDURE IF EXISTS migrate_gas_user_corp_match;
DELIMITER $$
CREATE PROCEDURE migrate_gas_user_corp_match()
BEGIN
    -- 1) 新增组织匹配状态列（幂等）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_gas_user' AND COLUMN_NAME = 'corp_match_status'
    ) THEN
        ALTER TABLE ops_gas_user
            ADD COLUMN corp_match_status TINYINT NOT NULL DEFAULT 0
            COMMENT '组织匹配状态（1=已匹配，corp_id 来自 sys_corp.gas_company_id 匹配；0=未匹配异常，corp_id=0 待人工处理）'
            AFTER corp_id;
    END IF;

    -- 2) 修正 corp_id 列注释与默认值（旧注释仅写"组织ID"，来源不明确）
    ALTER TABLE ops_gas_user
        MODIFY COLUMN corp_id BIGINT NOT NULL DEFAULT 0
        COMMENT '组织ID（= sys_corp.id，匹配条件：sys_corp.gas_company_id = 外部 sb_gas_user.gas_company_id；未匹配=0 待人工处理）';

    -- 3) 兜底回填存量：按 gas_company_id → sys_corp.gas_company_id 重算 corp_id 与匹配状态。
    --    两侧显式 COLLATE 防排序冲突（跨库 UPDATE，同一 MySQL 实例）。
    UPDATE mall_db.ops_gas_user gu
    JOIN ai_platform.sys_corp c
      ON c.gas_company_id COLLATE utf8mb4_0900_ai_ci = gu.gas_company_id COLLATE utf8mb4_0900_ai_ci
     AND c.gas_company_id IS NOT NULL AND c.gas_company_id <> ''
    SET gu.corp_id = c.id, gu.corp_match_status = 1
    WHERE gu.delete_flag = 0;

    -- 4) 无匹配组织的存量行 → 标记异常（corp_id=0，不自动分配、不猜测）
    UPDATE mall_db.ops_gas_user gu
    LEFT JOIN ai_platform.sys_corp c
      ON c.gas_company_id COLLATE utf8mb4_0900_ai_ci = gu.gas_company_id COLLATE utf8mb4_0900_ai_ci
     AND c.gas_company_id IS NOT NULL AND c.gas_company_id <> ''
    SET gu.corp_id = 0, gu.corp_match_status = 0
    WHERE gu.delete_flag = 0 AND c.id IS NULL;
END$$
DELIMITER ;
CALL migrate_gas_user_corp_match();
DROP PROCEDURE migrate_gas_user_corp_match;
