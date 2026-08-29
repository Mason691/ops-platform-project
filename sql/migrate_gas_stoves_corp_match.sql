-- 燃具信息表 corp_id 关联规则重构（2026-08-29）
-- corp_id 不再取自燃气用户组织，改为：外部 gas_the_company_id → sys_corp.gas_company_id → sys_corp.id。
--   （需求字面的 sb_gas_stoves_info.gas_company_id 全表恒为 '181'，实测与 sys_corp 零匹配；
--    公司标识实际在 gas_the_company_id，2026-08-27 已确认。）
-- 新增 corp_match_status 标记：1=组织已匹配 0=未匹配异常（corp_id=0，待人工处理）。
-- 幂等：列已存在时跳过；兜底回填按当前 sys_corp 映射重算全部存量行。
-- ⚠️ 必须带字符集执行：mysql --default-character-set=utf8mb4（客户端默认 latin1 会写坏中文注释）
-- 执行方式（生产）：
--   docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -padmin.2026 mall_db < migrate_gas_stoves_corp_match.sql
USE mall_db;

DROP PROCEDURE IF EXISTS migrate_stoves_corp_match;
DELIMITER $$
CREATE PROCEDURE migrate_stoves_corp_match()
BEGIN
    -- 1) 新增组织匹配状态列（幂等）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'mall_db' AND TABLE_NAME = 'ops_gas_stoves_info' AND COLUMN_NAME = 'corp_match_status'
    ) THEN
        ALTER TABLE ops_gas_stoves_info
            ADD COLUMN corp_match_status TINYINT NOT NULL DEFAULT 0
            COMMENT '组织匹配状态（1=已匹配，corp_id 来自 sys_corp.gas_company_id 匹配；0=未匹配异常，corp_id=0 待人工处理）'
            AFTER corp_id;
    END IF;

    -- 2) 修正 corp_id 列注释（旧注释描述"取关联 ops_gas_user.corp_id"，已过时）
    ALTER TABLE ops_gas_stoves_info
        MODIFY COLUMN corp_id BIGINT NOT NULL DEFAULT 0
        COMMENT '组织ID（= sys_corp.id，匹配条件：sys_corp.gas_company_id = 外部 gas_the_company_id；未匹配=0 待人工处理）';

    -- 3) 兜底回填存量：按 gas_the_company_id → sys_corp.gas_company_id 重算 corp_id 与匹配状态。
    --    两侧显式 COLLATE 防 1267 排序冲突（跨库 UPDATE，同一 MySQL 实例）。
    UPDATE mall_db.ops_gas_stoves_info s
    JOIN ai_platform.sys_corp c
      ON c.gas_company_id COLLATE utf8mb4_0900_ai_ci = s.gas_the_company_id COLLATE utf8mb4_0900_ai_ci
     AND c.gas_company_id IS NOT NULL AND c.gas_company_id <> ''
    SET s.corp_id = c.id, s.corp_match_status = 1
    WHERE s.delete_flag = 0;

    -- 4) 无匹配组织的存量行 → 标记异常（corp_id=0，不自动分配）
    UPDATE mall_db.ops_gas_stoves_info s
    LEFT JOIN ai_platform.sys_corp c
      ON c.gas_company_id COLLATE utf8mb4_0900_ai_ci = s.gas_the_company_id COLLATE utf8mb4_0900_ai_ci
     AND c.gas_company_id IS NOT NULL AND c.gas_company_id <> ''
    SET s.corp_id = 0, s.corp_match_status = 0
    WHERE s.delete_flag = 0 AND c.id IS NULL;
END$$
DELIMITER ;
CALL migrate_stoves_corp_match();
DROP PROCEDURE migrate_stoves_corp_match;
