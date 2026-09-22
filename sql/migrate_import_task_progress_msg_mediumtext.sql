-- 2026-09-22 业务数据两段式导入异步化：
-- check/confirm 的业务结果 JSON（errors 行错误列表 / pendingItems 候选产品 / 统计）存入 progress_msg，
-- 大文件校验错误可能超 500 字符，扩为 MEDIUMTEXT
ALTER TABLE sys_import_task MODIFY COLUMN progress_msg MEDIUMTEXT COMMENT '进度描述';
