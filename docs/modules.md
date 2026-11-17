# 业务模块说明

> 从 `CLAUDE.md` 拆分（2026-11-16）。模块分层实现模板见 `CLAUDE.md`「新增业务模块模式」。

---

## 作战地图看板（Battle Map Dashboard）

ETL 预计算 + 缓存架构，将原始业务数据聚合为只读看板，按组织维度隔离。

**数据流**：`ops_member_ledger` / `ops_business_data` / `ops_activity` / `ops_activity_review` / `ops_gas_user` → `BattleMapEtlService` → 6 张预计算表（`ops_map_*`）→ `BattleMapController` 只读查询

**6 张 ETL 表**：
| 表 | 说明 | 数据源 |
|---|---|---|
| `ops_map_employee_performance` | 经办人业绩 TOP5（rank_order，按 agent 姓名分组） | `ops_business_data`（`calc_status IN ('已计算','经办人已核对')`） |
| `ops_map_category_sales` | 品类销售额 TOP5（品类名经 `ai_platform.sys_dict_item` 翻译，跨库需 COLLATE） | `ops_business_data`（同 calc_status 过滤） |
| `ops_map_grid_performance` | 网格员业绩 TOP5（按负责小区台账销售额汇总） | `ops_grid_manager` + `ops_grid_manager_community` + `ops_member_ledger` |
| `ops_map_activity` | 活动统计（报名数/费用/覆盖小区/销售额） | `ops_activity` + `ops_activity_booking` + `ops_activity_review` |
| `ops_map_community` | 小区销售分布（total_sales） | `ops_gas_user` + `ops_member_ledger` |
| `ops_map_org_summary` | 组织汇总（总销售额/活动数/员工数/小区数） | 各源表聚合 |

**核心服务**：
- `BattleMapEtlService` — ETL 重算引擎：`refreshOrg(corpId)` 单组织全量重算，`refreshAllOrgs()` 全量重算所有活跃组织。每张表按 org_id 物理删除后重写。组织+下级聚合：运营商看板数据 = 自身 + 旗下所有合伙人数据。
- `BattleMapCache` — 二级缓存：内存 `ConcurrentHashMap`（30min TTL）+ 前端 `localStorage`。

**配置**：`battle-map.etl.*` 三项见 `docs/configuration.md`（auto-refresh / init-on-startup / cron，默认每 30 分钟增量刷新）。

**API 端点**：
- `POST /refresh-cache` — 单组织 ETL 刷新
- `POST /refresh-all` — 全量重算
- `GET /summary` — 组织汇总（含 region 定位、short_name 标题）
- `GET /employee-performance` — 经办人 TOP5
- `GET /grid-performance` — 网格员业绩 TOP5
- `GET /category-sales` — 品类销售额 TOP5
- `GET /community-top5` — 小区业绩 TOP5
- `GET /community-stats` — 小区销售分布
- `GET /community/{id}/detail` — 小区下钻（经办人 + 台账明细 + 品类分布）
- `GET /employee/detail` — 经办人下钻（负责小区 + 参与活动）
- `GET /trend` — 近 6 月趋势（活动次数 + 销售额，缺月补 0）

**组织边界定位**：`summary` 接口直读 `ai_platform.sys_corp.region_id/region_name`，沿 region 树向上归一到区县级（region_level≤3），取高德 `adcode` 用于地图边界。

## 活动复盘（Activity Review）

城市合伙人提交活动复盘，运营商审批。含费用台账和销售台账。

**实体**：`OpsActivityReview`（复盘主表）+ `OpsActivityReviewCost`（费用明细）

**流程**：合伙人提交复盘（reviewStatus=0 待审核）→ 运营商审批（reviewStatus=1 通过 / 2 驳回）→ 驳回后可编辑重提。通过后锁定不可编辑/删除。

**API**：`ActivityReviewController` `/api/ops/admin/activity-review`
- `GET /` — 分页列表（支持活动标题/状态/日期筛选，组织数据隔离）
- `GET /{id}` — 复盘详情 + 费用明细 + 活动销售台账
- `POST /` — 创建复盘
- `PUT /{id}` — 编辑（仅待审核/驳回状态）
- `DELETE /{id}` — 删除（仅待审核/驳回状态）
- `PUT /{id}/approve` — 审批（通过/驳回 + 审批意见）
- `GET /available-activities` — 可选活动列表
- `GET /employees` — 本组织员工列表（供参与人选择）

## 提成规则（Commission Rules）

个人提成（`OpsPersonalCommissionRule`）和公司提成（`OpsCompanyCommissionRule`），支持**组织层级继承**：子组织自动继承上级规则。

**提成类型**：按比例（percentage）或固定金额（fixed_amount），按品类（category）配置。

**品类字典**：提成规则品类下拉框从平台数据字典 `PRODUCT_CATEGORY` 动态加载（`CommissionRuleService.getCategories()`），不再硬编码。

**API**：`CommissionRuleController` `/api/ops/admin/commission`
- 个人规则 CRUD：`GET/POST/PUT/DELETE /personal`
- 公司规则 CRUD：`GET/POST/PUT/DELETE /company`
- `GET /categories` — 品类字典

## 品类使用年限规则（Category Lifecycle Rule）

按品类配置燃气设备使用年限、安检间隔和提醒天数，供设备生命周期管理使用。

**实体**：`OpsCategoryLifecycleRule`（`ops_category_lifecycle_rule` 表）— 品类 `category` + 使用年限 `maxYears`（年）+ 安检间隔 `inspectionInterval`（月）+ 提前提醒天数 `remindDays`

**权限**：运营商/超管可增删改查；城市合伙人仅可查看。写操作由 `OrgRoleService.isOperatorOrSuperAdmin()` 守卫。

**API**：`CategoryLifecycleRuleController` `/api/ops/admin/category-lifecycle-rules`
- `GET /` — 分页列表（按品类筛选）
- `GET /category-options` — 品类下拉选项（PRODUCT_CATEGORY 字典）
- `GET /by-category/{category}` — 按品类查询单条规则
- `GET /{id}` — 详情
- `POST /` — 创建（运营商/超管）
- `PUT /{id}` — 编辑（运营商/超管）
- `DELETE /{id}` — 删除（运营商/超管）

## 年度任务计划（Annual Plan）

运营商为各子公司设定年度品类任务金额，支持 Excel 批量导入和汇总分析。

**实体**：`OpsAnnualPlan`（`ops_annual_plan` 表）— 年度 `year` + 组织 `orgId` + 品类 `category` + 年度目标金额 `annualAmount`

**权限**：运营商/超管可增删改审；城市合伙人仅可查看。写操作由 `OrgRoleService.isOperatorOrSuperAdmin()` 守卫。

**API**：`AnnualPlanController` `/api/ops/admin/annual-plans`
- `GET /` — 分页列表（按年度/组织/品类/审核状态筛选，组织数据隔离）
- `GET /{id}` — 详情（含数据 scope 校验）
- `POST /` — 创建（仅运营商）
- `PUT /{id}` — 编辑（仅运营商）
- `DELETE /{id}` — 删除（仅运营商）
- `PUT /{id}/approve` — 审核通过
- `GET /summary` — 集团年度总金额汇总
- `GET /summary-by-category` — 集团各品类汇总
- `GET /org-detail` — 子公司各品类明细
- `POST /import` — Excel 批量导入（仅运营商）

## 燃气用户 / 网格员 / 会员台账

三个管理后台模块，均为标准 CRUD + 分页查询 + 组织数据隔离：

- **燃气用户**（`GasUserController` `/api/ops/admin/gas-user`）：`ops_gas_user` 表，按小区筛选 + 关键字搜索
- **网格员管理**（`GridManagerController` `/api/ops/admin/grid-manager`）：`ops_grid_manager` + `ops_grid_manager_community` 关联表，网格员可分配多个小区
- **会员台账**（`MemberLedgerController` `/api/ops/admin/member-ledger`）：`ops_member_ledger` 表仍是作战地图 ETL 与提成计算数据源，但 2026-10-16 起**列表按平台会员聚合**（AGG_SQL 按会员聚合（仅展示有设备的会员）；字段 platformMemberId/memberName/memberPhone/communityNames/regionNames/corpNames/gridManagerNames/stoveCount/businessCount；筛选项 memberKeyword/communityName/sources/corpId/gridManagerId）。**所属公司**=双腿 corp_id→sys_corp.name 聚合；**网格员**=安检侧小区→gmc→`ops_grid_manager.real_name`（gmc 无 delete_flag，子查询 MIN 防翻倍），业务腿无网格员（gm 筛选计数收缩，同小区筛选语义）。⚠️ 生产 gmc 4 条映射均指向已清空小区（列多空待补分配）。设备明细走 `GET /member/{platformMemberId}/devices`（两组；使用日期：业务=市场收款日期、安检=安装时间）。**销售组按收据号合并**：同收据号 distinct 非空经办人>1 → 合并为一条计 1（经办人"、"连接、multiAgent=true），否则逐条计，空收据号各行独立（AGG_SQL 与 mergeBusinessDevices 同口径须同步改）。活动解耦：列表零活动字段，activity_id 列与 CRUD 保留（复盘页销售台账编辑器直写）；/members /categories /communities 三端点已删。跨库直读 sys_member/sys_community，不可达降级空列表。

## 安检设备创建会员台账（Stove Ledger）

台账页顶部「安检设备创建」按钮（仅超管/运营商，后端 `OrgRoleService.isOperatorOrSuperAdmin()` 守卫）：按 设备→燃气用户→平台会员 链路，将每台安检设备异步生成一条台账明细。同一会员多台设备 → 多行。

**数据链路**：`ops_gas_stoves_info.gas_user_id` → `ops_gas_user.platform_member_id` → `ai_platform.sys_member`。

**API**（`MemberLedgerController` `/api/ops/admin/member-ledger`）：
- `POST /from-stoves/submit` — 提交异步任务（Kafka `async-import-task`，taskType=`member_ledger_stoves`），可见 corp CSV 写入 task.remark 供 Handler 做数据范围过滤（未关联用户的设备只有 s.corp_id，故范围子句为 `s.corp_id OR gu.corp_id` 双匹配）
- `GET /from-stoves/{taskId}/status` — 任务轮询；progressMsg 为 `JSON|人话` 双格式（前端解析 JSON 精确计数 + 原因）
- `GET /` — 会员聚合列表（口径见「燃气用户 / 网格员 / 会员台账」节）

**处理器**：`StoveLedgerCreateHandler` — 单条 INSERT...SELECT + NOT EXISTS（gas_stoves_id 去重）+ 唯一索引 `(gas_stoves_id, delete_flag)` 兜底，逻辑删除后可重建。计数：created/skipped/failed（未关联燃气用户/未转化会员/未知燃具种类，附原因文本）。品类映射 `gas_stoves_class` 1→燃气灶 2→热水器 3→挂壁炉→壁挂炉（PRODUCT_CATEGORY itemKey 1/3/4）；usage_date 取 installation_time→buy_time→startup_time 首个非空；community_id 取燃气用户所属小区。

**迁移**：`sql/migrate_member_ledger_stoves.sql`（platform_member_id/gas_stoves_id 列 + uk_ledger_stoves 唯一键 + idx_platform_member 索引）。

**前端**（平台主站 `~/ai-native-platform/frontend/src/views/ops/`）：`MemberLedgerList.vue` 按钮（`canEdit` 由 `/ops/admin/permission/profile` 的 canEditProducts 控制）+ `MemberLedgerStovesDialog.vue` 三态弹窗（确认/轮询 2s/结果手动关闭），关闭时 `@done` 刷新列表；关联设备弹窗 `MemberDevicesDialog.vue`（multiAgent 行前置「多人参与」tag）。


## 燃气用户外部导入 & 会员转换

燃气用户（`ops_gas_user`）可从外部燃气平台 PolarDB 批量导入，并支持异步转换为平台会员。

**外部数据源**：`GasDataSourceConfig` 按需创建独立 HikariCP 连接池（`jinyuan_info`），不注册 Spring Bean 以免覆盖主数据源。

**导入流程**：
- `POST /api/ops/admin/gas-user/gas-import/query` — 预览外部源记录数
- `POST /api/ops/admin/gas-user/import-from-gas-platform` — 提交异步导入任务
- `GasUserImportHandler`（taskType=`gas_user_import`）— 读取外部 Polardb `sb_gas_user` 表，按 `user_no` 去重，自动创建小区记录，调用高德地理编码

**会员转换**（Kafka 异步）：
- `POST /api/ops/admin/gas-user/convert/submit` — 提交批量转换任务
- `MemberConvertHandler`（taskType=`member_convert`）— 异步将燃气用户转为平台 `sys_member`，同步性别/出生日期/地址/小区
- 通过现有 `ImportTaskConsumer` 消费（新增第 5 种异步任务类型）

## 燃具信息迁移（Gas Stoves）

燃具信息（`ops_gas_stoves_info`）为外部燃气系统 `jinyuan_info.sb_gas_stoves_info` 的全量同步镜像。主键 `id` 直接存外部表 id（`VARCHAR(32)`，非自增），`gas_stoves_class` 存平台字典 `GAS_APPLIANCE_TYPE` itemKey（1灶具 2热水器 3挂壁炉）。前端入口在主平台「增值业务 → 安检采集设备」（ops H5 无对应页面）。

**API**（`GasStovesController` `/api/ops/admin/gas-stoves`）：
- `GET /` — 分页列表（用户号/燃具种类/品牌筛选，数据 scope 隔离，富化燃气用户姓名 + 组织名）
- `POST /preview` — 迁移预览（外部总数/本地已有/预计新增/待补全关联数）
- `POST /submit` — 触发异步全量迁移（Kafka `async-import-task`，taskType=`gas_stoves`）
- `GET /{taskId}/status` — 任务状态轮询

**处理器**：`GasStovesImportHandler` — 按外部 id 分页增量读取；corp 匹配链：`sb_gas_stoves_info.gas_the_company_id` → `ai_platform.sys_corp.gas_company_id` → `sys_corp.id`，无关联时取同步触发组织。

**燃气用户关联**：`gas_user_id` 匹配规则 `ops_gas_user.gas_company_id = s.gas_the_company_id AND ops_gas_user.gas_supply_certificate_number = s.user_number`（需求字面的 `gas_company_id` 匹配实测 0 条，不可用）。关联字段历经 `user_no` → `gas_user_id` 重构：`sql/migrate_gas_stoves_user_no.sql` → `migrate_gas_stoves_gas_user_id.sql`（回填）→ `migrate_gas_stoves_drop_user_no.sql`（**删列段必须在新 JAR 部署后执行**，旧 JAR 仍查询 user_no 列）。

**重建注意**：`migrate_gas_stoves.sql` 是重建脚本，`CREATE TABLE IF NOT EXISTS` 不会覆盖旧表（旧版为 BIGINT 自增 id + remote_id）。升级已有旧表的库必须先 `DROP TABLE ops_gas_stoves_info;` 再建表，然后重跑「迁移导入」重新同步。2026-08-31 该表曾随 `sys_community`/`ops_gas_user` 被物理清空，重导入即恢复路径（参见 `[[sys-community-gas-user-wipe-2026-08-31]]`）。

## 微信小程序跳转（H5）

H5 端跳转第三方小程序统一走 **JSSDK `wx.navigateToMiniProgram`**（不是 `wx-open-launch-weapp` 开放标签）：

- `pages/product/detail.vue` — 详情页「进入小程序」按钮 + 兑换成功后延时自动跳转，参数取 `product.miniappConfig`（appId/path）
- `pages/activity/detail.vue` — `WeixinJSBridge.invoke('navigateToMiniProgram', ...)`，取 `activity.miniapp_app_id`
- `pages/index/index.vue` — banner `jumpPath` 跳转

**注意**：`wx-open-launch-weapp` 仅在 `main.js` 注册为自定义元素，**无业务使用**；历史方案（`weixin://` / URL Link / 开放标签 + `MpLauncher.vue`）均已废弃，`components/` 下**不存在** MpLauncher.vue，勿照旧文档复原。

## 直播室管理（Live Room）

运营商可配置面向 C 端的直播室，H5 端展示并引导用户跳转观看。

**实体**：`OpsLiveRoom`（`ops_live_room` 表）— 标题、封面、开播时段、状态（0=预告 1=直播中 2=已结束）、回放 URL、观看人数、`live_link`（第三方直播链接，如腾讯云/阿里云等）

**纯 `live_link` 字段**（2026-07 起，支持任意第三方直播平台跳转）。

**C 端 API**（`PlatformController` `/api/ops/live-rooms`）：
- `GET /active` — 获取进行中 + 预告的直播间（公开，按状态+排序）
- `GET /{id}` — 直播间详情（含 liveStatus 计算、倒计时）
- `POST /{id}/remind` — 设置开播提醒

**管理后台 API**（`AdminController` `/api/ops/admin`）：
- `GET /live-rooms` — 分页列表
- `POST /live-rooms` — 创建直播室
- `PUT /live-rooms/{id}` — 编辑
- `DELETE /live-rooms/{id}` — 删除
- `GET /live-rooms/{id}` — 详情
- `PUT /live-rooms/{id}/status` — 更新状态（预告→直播中→已结束）
- `GET /live-rooms/{id}/qrcode` — 生成二维码
- `GET /live-rooms/available` — 可选直播列表（供活动关联使用）

**管理前端**：`pages/admin/live-room-manage.vue`（已注册到 pages.json）

