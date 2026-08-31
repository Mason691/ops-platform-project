-- ops_member_ledger 字段注释乱码修复（2026-08-30）
-- 背景：建表时客户端字符集为 latin1，中文注释被双重编码损坏（å…³è” 类乱码）。
-- 表数据为空（0 行），仅注释受损，无数据风险。
-- 执行要求：必须 --default-character-set=utf8mb4，否则再次双重编码损坏。
--   例：docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p mall_db < fix_member_ledger_comments.sql
-- 幂等：重复执行结果一致。MODIFY 保留原类型/可空性/默认值，仅重写注释。

ALTER TABLE ops_member_ledger
    MODIFY COLUMN activity_id  bigint        NULL DEFAULT NULL COMMENT '关联 ops_activity.id',
    MODIFY COLUMN grade        varchar(10)   NULL DEFAULT NULL COMMENT '档次（高/中/低）',
    MODIFY COLUMN sales_price  decimal(10,2) NULL DEFAULT NULL COMMENT '销售价格',
    MODIFY COLUMN receipt_no   varchar(50)   NULL DEFAULT NULL COMMENT '收据号',
    MODIFY COLUMN receipt_date date          NULL DEFAULT NULL COMMENT '收款日期',
    MODIFY COLUMN agent        varchar(500)  NULL DEFAULT NULL COMMENT '经办员工ID(逗号分隔)';
