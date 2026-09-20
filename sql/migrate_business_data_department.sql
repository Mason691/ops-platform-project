-- 2026-09-20 业务数据新增 部门 字段（位于 agent 之前），并回填存量 remark 中的部门前缀
-- 前置：本迁移必须先于新 JAR 部署执行（加列向后兼容，旧 JAR 不受影响）
-- 注意：执行 mysql 客户端必须带 --default-character-set=utf8mb4（中文列注释防乱码）

ALTER TABLE ops_business_data
    ADD COLUMN department VARCHAR(100) NULL COMMENT '部门' AFTER receipt_no;

-- 存量行回填：旧导入逻辑将部门以「部门: X」前缀写入 remark，提取到 department 并清空 remark
-- 仅匹配前缀格式的行；remark 非该格式的行不动
-- SUBSTRING 按字符计数：'部门: X' = 部(1)门(2):(3)空格(4)X(5) → 从第 5 字符起
UPDATE ops_business_data
SET department = SUBSTRING(remark, 5),
    remark     = NULL
WHERE remark LIKE '部门: %';
