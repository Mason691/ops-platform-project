-- 回滚：ops_* → gs_*（如需回退到旧前缀）
-- ops_member → gs_user 请用 rollback_ops_member_to_gs_user.sql
USE `mall_db`;

RENAME TABLE IF EXISTS `ops_points_log`  TO `gs_points_log`;
RENAME TABLE IF EXISTS `ops_product`     TO `gs_product`;
RENAME TABLE IF EXISTS `ops_activity`    TO `gs_activity`;
RENAME TABLE IF EXISTS `ops_order`       TO `gs_order`;
RENAME TABLE IF EXISTS `ops_coupon`      TO `gs_coupon`;
RENAME TABLE IF EXISTS `ops_user_coupon` TO `gs_user_coupon`;
RENAME TABLE IF EXISTS `ops_address`     TO `gs_address`;
RENAME TABLE IF EXISTS `ops_click_log`   TO `gs_click_log`;
