-- 存量回填 ops_gas_user.gas_company_id（2026-08-27）
-- 已导入的 11.8 万燃气用户需要按 user_no（= sb_gas_user.id）从外部库取 gas_company_id 回填，
-- 否则新字段对存量数据为空，灶具按「公司ID + 供气证号」的关联匹配无法命中。
-- 幂等：重复执行结果一致（UPDATE JOIN 覆盖为同值，临时表最后清理）。
--
-- 完整执行步骤（先外部导出映射，再执行本脚本）：
--   1) 生产宿主机导出外部映射行（QUOTE 转义，一行一个值组）：
--      docker exec -i mysql mysql -h parking-db.mysql.polardb.rds.aliyuncs.com \
--        -u jinyuan_kaifa_only -p'3GB^N&4NNI76UwhdlOcpYdS#' -N -B jinyuan_info \
--        -e "SELECT CONCAT('(', QUOTE(id), ',', QUOTE(gas_company_id), '),') FROM sb_gas_user" > /tmp/gcu_map_rows.sql
--   2) 生成装载语句（建临时表 + 多行 INSERT）。
--      注意：user_no 必须显式 COLLATE utf8mb4_0900_ai_ci，与 ops_gas_user.user_no 一致
--      （mall_db 库默认 utf8mb4_unicode_ci，不一致会报 1267 Illegal mix of collations）：
--      echo 'DROP TABLE IF EXISTS mall_db._tmp_gas_company_map; CREATE TABLE mall_db._tmp_gas_company_map (user_no VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL PRIMARY KEY, gas_company_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci) ENGINE=InnoDB; INSERT INTO mall_db._tmp_gas_company_map VALUES' > /tmp/gcu_load.sql
--      cat /tmp/gcu_map_rows.sql | sed '$s/,$/;/' >> /tmp/gcu_load.sql
--   3) 执行装载：
--      docker exec -i mysql mysql -uroot -padmin.2026 --default-character-set=utf8mb4 < /tmp/gcu_load.sql
--   4) 执行本脚本（UPDATE JOIN 回填 + 清理）：
--      docker exec -i mysql mysql -uroot -padmin.2026 --default-character-set=utf8mb4 < backfill_gas_user_company_id.sql
--   5) 验证：
--      SELECT gas_company_id, COUNT(*) FROM mall_db.ops_gas_user WHERE delete_flag=0 GROUP BY gas_company_id ORDER BY 2 DESC LIMIT 10;
USE mall_db;

UPDATE mall_db.ops_gas_user u
JOIN mall_db._tmp_gas_company_map m ON u.user_no = m.user_no
SET u.gas_company_id = m.gas_company_id
WHERE u.delete_flag = 0;

DROP TABLE IF EXISTS _tmp_gas_company_map;
