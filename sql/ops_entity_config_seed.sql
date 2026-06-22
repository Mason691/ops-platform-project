-- ops-platform 子系统实体配置注册脚本
-- 所有继承 BaseEntity 的实体统一注册到平台 sys_entity_config 表
-- 表在 mall_db 库中，使用 mall_db.{table} 跨库前缀

INSERT INTO ai_platform.sys_entity_config
  (entity_class, subsystem, display_name, table_name, form_type, tool_name,
   fields_schema, enable_create, enable_list, enable_update, enable_delete,
   lifecycle, ai_available, status, sort, delete_flag)
VALUES
  ('com.aip.ops.entity.GsActivity',              'ops', 'GsActivity',              'mall_db.ops_activity',              'gsactivity',              'create_gsactivity',              '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.GsAddress',               'ops', 'GsAddress',               'mall_db.ops_address',               'gsaddress',               'create_gsaddress',               '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.GsClickLog',              'ops', 'GsClickLog',              'mall_db.ops_click_log',             'gsclicklog',              'create_gsclicklog',              '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.GsCoupon',                'ops', 'GsCoupon',                'mall_db.ops_coupon',                'gscoupon',                'create_gscoupon',                '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.GsOrder',                 'ops', 'GsOrder',                 'mall_db.ops_order',                 'gsorder',                 'create_gsorder',                 '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.GsPointsLog',             'ops', 'GsPointsLog',             'mall_db.ops_points_log',            'gspointslog',             'create_gspointslog',             '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.GsProduct',               'ops', 'GsProduct',               'mall_db.ops_product',               'gsproduct',               'create_gsproduct',               '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.GsUserCoupon',            'ops', 'GsUserCoupon',            'mall_db.ops_user_coupon',           'gsusercoupon',            'create_gsusercoupon',            '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.MerchantMiniappConfig',   'ops', 'MerchantMiniappConfig',   'mall_db.biz_merchant_miniapp_config', 'merchantminiappconfig', 'create_merchantminiappconfig', '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.OperatorMerchant',        'ops', 'OperatorMerchant',        'mall_db.biz_operator_merchant',      'operatormerchant',       'create_operatormerchant',        '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.OpsActivityBooking',      'ops', 'OpsActivityBooking',      'mall_db.ops_activity_booking',      'opsactivitybooking',     'create_opsactivitybooking',      '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.OpsActivityProduct',      'ops', 'OpsActivityProduct',      'mall_db.ops_activity_product',      'opsactivityproduct',     'create_opsactivityproduct',      '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.OpsHomeConfig',           'ops', 'OpsHomeConfig',           'mall_db.ops_home_config',           'opshomeconfig',          'create_opshomeconfig',           '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.OpsLiveRoom',             'ops', 'OpsLiveRoom',             'mall_db.ops_live_room',             'opsliveroom',            'create_opsliveroom',             '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.OpsMember',               'ops', 'OpsMember',               'mall_db.ops_member',                'opsmember',              'create_opsmember',               '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.OpsRemindLog',            'ops', 'OpsRemindLog',            'mall_db.ops_remind_log',            'opsremindlog',           'create_opsremindlog',            '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.ProductCategory',         'ops', 'ProductCategory',         'mall_db.biz_product_category',      'productcategory',        'create_productcategory',         '{}', 0,1,0,0, 'draft',0,1,0,0),
  ('com.aip.ops.entity.PublicAccountConfig',     'ops', 'PublicAccountConfig',     'mall_db.sys_public_account_config',  'publicaccountconfig',    'create_publicaccountconfig',     '{}', 0,1,0,0, 'draft',0,1,0,0)
ON DUPLICATE KEY UPDATE
  table_name = VALUES(table_name),
  form_type = VALUES(form_type),
  tool_name = VALUES(tool_name),
  modify_time = NOW();
