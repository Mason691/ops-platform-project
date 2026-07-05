-- migrate_report_token.sql
-- Adds report_token column to sys_ai_chat_task for persisting AI-generated report download tokens.
-- The token is issued by the platform's ReportFileService when the Hermes Agent generates
-- a downloadable report (Excel/PDF/Word), and is used by the frontend to download the file.

ALTER TABLE sys_ai_chat_task
    ADD COLUMN report_token VARCHAR(64) DEFAULT NULL COMMENT '报告下载令牌（由平台 ReportFileService 生成）'
    AFTER error_msg;
