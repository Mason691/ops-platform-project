-- 2026-11-17 收款码安全改造：后台配置入口移除，改为 jar 内置 + ECDSA 验签
-- 执行库：mall_db + ai_platform（生产 MySQL 必须带 --default-character-set=utf8mb4）
-- 幂等：可重复执行

USE mall_db;

-- 历史收款码配置全部废弃；表保留用于审计，应用不再读取
UPDATE ops_payment_qr_config
SET status = 0, delete_flag = 1
WHERE delete_flag = 0;

USE ai_platform;

-- 从 AI 实体注册表移除收款码配置，避免 AI 助手查询/操作历史收款码
DELETE FROM sys_entity_config
WHERE entity_class = 'com.aip.ops.entity.OpsPaymentQrConfig';
