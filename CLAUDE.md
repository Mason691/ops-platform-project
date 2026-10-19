# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **工程文件索引**  
> 编码规范 + ADR → `MEMORY.md`（⚠️ 第四节合规状态表为 2026-06-22 改前快照，已全部过时，以本文件为准）| 用户画像 → `user.md` | 产品理念 → `soul.md` | 功能规格 → `Specs/` | 变更历史 → `docs/CHANGELOG.md`

---

## 快速命令

```bash
# restart.sh — 本地开发一键操作（推荐）
./restart.sh             # 默认: 停止后端 + 编译打包 + 启动后端（等同于 all）
./restart.sh backend     # 仅重启后端（stop + start，JAR 不存在时自动编译）
./restart.sh frontend    # 仅重启前端 Vite dev server（stop + start，端口 3035）
./restart.sh all         # 停止后端 + 编译 + 启动后端（默认行为）
./restart.sh rebuild     # 强制重编译后端 + 重启
./restart.sh dev         # 停止前后端 + 编译后端 + 启动后端 + 启动前端 dev server
./restart.sh stop        # 停止所有服务（后端 8084 + 前端 3035）
./restart.sh build       # 构建前端 H5 生产包 + 后端 JAR

# 后端编译（离线模式，本地 JDK 17）
JAVA_HOME=/home/jason/jdk17 /home/jason/apache-maven-3.9.9/bin/mvn -f backend/pom.xml clean package -DskipTests -o

# 后端本地运行（需要 JDK 17 在 PATH）
java -jar backend/target/ops-platform-1.0.0-SNAPSHOT.jar

# 前端 H5 dev server（端口 3035，Vite proxy /api → localhost:8084）
cd frontend && npm run dev:h5

# 前端 H5 生产构建（含 jweixin-1.6.0.js 复制 + static 路径替换）
cd frontend && npm run build:h5        # 输出 dist/build/h5/

# 前端小程序 dev server（微信开发者工具导入 dist/dev/mp-weixin）
cd frontend && npm run dev:mp-weixin

# 前端小程序构建
cd frontend && npm run build:mp-weixin  # 输出 dist/build/mp-weixin/

# 部署：测试环境 104.238.165.57 已于 2026-08-18 退役，所有发布直接上生产 → 见「生产部署」
```

**无自动化测试**：项目当前无单元测试或集成测试，所有验证依赖手动检查和生产监控。

---

## 项目定位

运营平台，作为独立业务子系统运行，后端端口 8084。
**核心定位**：聚合展示 · 引流 · 积分消耗。**不提供交易功能**，所有商品购买跳转第三方小程序。

依赖 `ai-platform-sdk` 接入底座的安全、多租户、MCP、Agent 等能力。

### 规格驱动开发 (SDD)

新功能先在 `Specs/` 创建中文规格文档（以 `Specs/增值业务-规格说明.md` 为参考模板，`specs/template.md` 尚不存在），包含：功能概述、用户故事、验收标准、API 设计、多租户/安全考虑。完成后在规格中标记 ✅。

现有规格：
- `Specs/增值业务-规格说明.md`
- `Specs/活动管理-规格说明.md`
- `specs/annual-plan-spec.md`（英文）

---

## 技术栈

| 层次 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2.5 · MyBatis-Plus 3.5.7 · MySQL 8.0 · Kafka（可选） |
| Java | 17（Eclipse Temurin 17.0.9） |
| 前端 | uni-app（编译目标 H5 + mp-weixin），无 Pinia/Vuex |
| 底座 SDK | `com.aip:ai-platform-sdk:1.0.0-SNAPSHOT`（本地仓库 `/home/jason/m2_local`） |

**服务器**：

| 环境 | IP | 用户/密码 | 域名 | 部署方式 |
|------|-----|---------|------|---------|
| 生产 | `113.250.60.41` | root / `j_ly[4=2Gh1?9]` | `boss.jdzn99.com` | Docker compose（`/data2/boss-platform/`） |
| ~~测试~~ | ~~`104.238.165.57`~~ | — | ~~`a.15891589.xyz`~~ | **已退役（2026-08-18）**，勿再部署 |

- 数据库：生产 `mall_db @ 113.250.60.41`（Docker 容器 `mysql`，root / `admin.2026`）；同实例另有 `ai_platform` 库
- ⚠️ `application.yml` 里 `spring.datasource.url` 与 `platform.base-url` 的**签入默认值仍指向已退役的 104.238.165.57**（3306 已封禁）。本地起后端必须用环境变量覆盖，生产由 compose 注入 `SPRING_DATASOURCE_URL` / `PLATFORM_BASE_URL`
- Maven 本地仓库：`/home/jason/m2_local`（离线模式必须 `-o`）

---

## 包名与命名约定

- Java：`com.aip.ops.*`
- 表前缀：`ops_`（运营平台业务表），`biz_`（跨子系统共享业务表），`sys_`（系统级配置表）
- 前端路由 base：`/ops/`（生产 hash 模式）

---

## 新增业务模块模式

项目中每个管理后台模块遵循统一的分层模式，新增模块时按以下清单逐层实现：

| 层 | 文件 | 模式 |
|---|---|---|
| 实体 | `entity/OpsXxx.java` | 继承 `BaseEntity`，`@TableName("ops_xxx")`，含 `corpId` + `deleteFlag` |
| Mapper | `mapper/OpsXxxMapper.java` | 继承 `BaseMapper<OpsXxx>`，复杂查询写在 `resources/mapper/OpsXxxMapper.xml` |
| Service | `service/XxxService.java` + `impl/XxxServiceImpl.java` | 继承 `ServiceImpl<Mapper, Entity>`，分页用 `Page<Entity>` |
| Controller | `controller/XxxController.java` | RESTful CRUD + 分页查询，所有写操作校验 `corpId` |
| 导入（可选） | `imports/XxxImportHandler.java` | 实现 `ImportHandler` 接口，分阶段校验（FILE→HEADER→ROW→INSERT），全量通过后 JDBC batch 原子写入 |
| 导出（可选） | `export/XxxExportHandler.java` | 实现 `ExportHandler` 接口，TSV → Python openpyxl 转 XLSX，降级 TSV |
| 前端页面 | `pages/admin/xxx.vue` | uni-app 页面，注册到 `pages.json` |
| 前端 API | `api/index.js` | 在 `api` 对象中添加方法，使用 `request` 实例 |

**数据隔离要求**：
- 所有查询自动带 `WHERE corp_id = {current}`（MyBatis-Plus `TenantLineHandler`）
- 写操作前必须校验数据归属当前组织（`OrgRoleService.isOperatorOrSuperAdmin()` 判定角色）
- 禁止跨库 JOIN（产品信息通过 `ProductBatchClient`，组织信息通过 `PlatformInternalClient`）
- 城市合伙人（`orgRoleId=5`）仅可查看，不可写入

**参考实现**：`GasUserController` + `GasUserImportHandler`（含导入）、`AnnualPlanController`（含权限守卫）、`CommissionRuleController`（含组织层级继承）

---

## 目录结构

```
ops-platform-project/
├── backend/
│   ├── src/main/java/com/aip/ops/
│   │   ├── OpsApplication.java
│   │   ├── config/          # SecurityConfig, AuthConfig, RestTemplateConfig, WebMvcConfig, MybatisPlusConfig, JacksonConfig, GasDataSourceConfig
│   │   ├── controller/      # 29 controllers（见下方）
│   │   ├── entity/          # 36 entities（31 个继承 SDK BaseEntity；5 个例外见「已知陷阱」；含 MerchantMiniappConfig, OperatorMerchant, OpsBusinessModelAlias）
│   │   ├── mapper/          # 37 MyBatis-Plus mappers（含 OpsBusinessDataEmployeeMapper 员工业务数据专用）
│   │   ├── service/         # 18 service interfaces + 9 standalone services + 18 impls
│   │   ├── aspect/          # AOP 切面（OperationLogAspect）
│   │   ├── client/          # 子系统间 HTTP 客户端（MemberSyncClient, ProductBatchClient）
│   │   ├── agent/           # AI DomainAgent（OpsAgent.java）
│   │   ├── tool/            # MCP 工具（OpsOrderQueryTool, OpsEmployeeBusinessDataTool）
│   │   ├── export/          # 通用异步导出策略（ExportHandler 接口 + 策略模式）
│   │   ├── imports/         # 通用异步导入策略（ImportHandler 接口 + 策略模式：BusinessDataImportHandler, GasUserImportHandler, MemberConvertHandler, GasStovesImportHandler）
│   │   ├── kafka/           # Kafka — 4 consumers, 1 producer, 3 message DTOs（条件加载）
│   │   ├── scheduler/        # 定时任务（BattleMapEtlBootstrap, BattleMapEtlScheduler）
│   │   ├── dto/             # 数据传输对象
│   │   └── util/            # 工具类（ImageUrlUtil, AesUtils, AmapGeocodeUtil 等）
│   └── pom.xml
├── frontend/
│   ├── src/main.js          # uni-app 入口
│   ├── src/manifest.json    # uni-app 配置（H5 base 路径等）
│   ├── src/pages/           # uni-app 页面（29 个路由，含 Tab/C 端/员工/管理）
│   ├── src/components/      # 跨页面复用组件（ImageCropperModal, LiveStatusCard, EmployeeTabBar, Pager）
│   ├── src/api/index.js     # 集中 API 注册（约 150 个方法）
│   ├── src/utils/           # request.js, image.js, jssdk.js, auth-bridge.js
│   ├── src/config.js        # BASE_URL + DEFAULT_CORP_ID
│   ├── src/static/          # 静态资源（jweixin-1.6.0.js 等）
│   ├── index.html           # 入口 HTML（含内联微信 JSSDK 初始化脚本）
│   ├── vite.config.js       # base 由 VITE_BASE 控制 (prod: /ops/), proxy /api → 8084
│   └── package.json
├── sql/                     # init.sql + 迁移脚本
├── Specs/                   # 功能规格文档（中文）
├── specs/                   # 功能规格文档（英文，如 annual-plan-spec.md）
├── docs/                    # 附加文档（testing.md 手动测试指南）
├── restart.sh               # 本地开发一键重启脚本（支持 backend/frontend/all/rebuild/dev/stop/build）
├── Dockerfile               # 生产 Docker 镜像
├── MEMORY.md                # 编码规范（强制）+ ADR
├── soul.md                  # 产品理念与设计哲学
├── user.md                  # 用户画像
└── uploads/                 # 文件上传输出目录
```

### Controllers（29 个）

| Controller | 端点前缀 | 说明 |
|---|---|---|
| **C 端接口** | | |
| `PlatformController` | `/api/ops/product`, `/api/ops/activity`, `/api/ops/order`, `/api/ops/points`, `/api/ops/coupon`, `/api/ops/live-rooms`, `/api/ops/user` | 核心 C 端接口 |
| `AuthController` | `/api/ops/auth` | 会员登录/注册 |
| `HomeController` | `/api/ops/home-data` | 公开首页数据（banner/视频/推荐） |
| `PublicAccountController` | `/api/public-account` | 公众号配置查询 + JSSDK 签名 |
| `StationController` | `/api/ops/station` | H5 端站长申请/状态/信息 |
| **管理后台** | | |
| `AdminController` | `/api/ops/admin` | 活动/商品/直播室 CRUD、预约查询、首页统计、商家绑定 |
| `AdminStationController` | `/api/ops/admin/station` | 站长审批/列表/统计 |
| `BusinessDataController` | `/api/ops/admin/business-data` | 增值业务：两段式异步导入/提成计算/销售统计/异步导出/品类提成汇总/模板下载/状态流转 + 经办人核对——详见下方「增值业务数据」节 |
| `CommissionRuleController` | `/api/ops/admin/commission` | 个人/公司提成规则 CRUD（支持组织层级继承） |
| `ValueAddedProductController` | `/api/ops/admin/value-added/product` | 增值产品 CRUD + 列表按品类/品牌/型号(like)/状态筛选；2026-10-15 整表清空后重建为 95 个型号（万和15/万家乐21/美的18/小松鼠41，id 144-238，`sql/ops_value_added_product_import_20261015*.sql`），同日补入万和壁挂炉 32 个型号（id 239-270，`sql/ops_value_added_product_import_20261015_wanhe_wallboiler.sql`），现共 127 个型号 |
| `MemberController` | `/api/ops/admin/members` | 会员管理：分页查询/详情/启用禁用 |
| `ExportTaskController` | `/api/ops/admin/export` | 通用异步导出：提交/状态轮询/下载/历史 |
| `PermissionController` | `/api/ops/admin/permission` | 权限概要查询 |
| `HomeConfigController` | `/api/ops/admin/home-configs` | 首页配置 CRUD + 批量排序 |
| `BattleMapController` | `/api/ops/admin/battle-map` | **作战地图看板**：ETL 触发/经办人·网格员·品类·小区 TOP5/summary/趋势/下钻详情 |
| `ActivityReviewController` | `/api/ops/admin/activity-review` | **活动复盘**：复盘 CRUD + 审核 + 费用台账 + 参与员工 |
| `AnnualPlanController` | `/api/ops/admin/annual-plans` | **年度任务**：子公司年度计划 CRUD + 审核 + 汇总 + Excel 导入 |
| `GasUserController` | `/api/ops/admin/gas-user` | **燃气用户**：分页查询/批量导入/小区关联 |
| `GasStovesController` | `/api/ops/admin/gas-stoves` | **燃具信息**：分页查询（用户号/种类/品牌筛选）/迁移预览/异步迁移导入/任务状态 |
| `GridManagerController` | `/api/ops/admin/grid-manager` | **网格员管理**：CRUD + 小区分配 |
| `MemberLedgerController` | `/api/ops/admin/member-ledger` | **会员台账**：按平台会员聚合列表 + 关联设备弹窗 + 台账 CRUD + 安检设备创建（异步） |
| `CategoryLifecycleRuleController` | `/api/ops/admin/category-lifecycle-rules` | **品类使用年限规则**：CRUD + 品类下拉选项 + 按品类查询 |
| **员工通道** | | |
| `EmployeeAuthController` | `/api/ops/auth/employee` | **员工** 登录（手机号+密码+验证码），独立于会员认证 |
| `EmployeeController` | `/api/ops/employee` | 员工身份校验 + 个人业务数据查询 + 头像上传 |
| `EmployeeAiController` | `/api/ops/ai/employee` | **员工** AI 助手（chat + async + SSE stream + config + profile + report） |
| **会员通道** | | |
| `AiChatProxyController` | `/api/ops/ai` | **会员** AI 客服代理（chat + voice + config + profile + member personalization + skills） |
| `MemberProfileProxyController` | `/api/ops/member` | 会员信息代理到底座 |
| **内部/代理** | | |
| `FileUploadController` | `/api/ops/files` | 图片/视频上传 |
| `PartnerController` | `/api/ops/admin/partners` | 城市合伙人管理（代理底座 internal API） |

### 增值业务数据（Business Data）

`ops_business_data` 表 2026-09-03 起字段结构：`receipt_date` 展示名改为**市场收款日期**（字段名保留 varchar(50)），其后新增 `finance_receipt_date`（DATE 财务收款日期，可空）与 `fund_dynamic_rows`（INT 资金动态表横排数，可空）。迁移脚本 `sql/migrate_business_data_finance_fields.sql`（已执行）。2026-10-16 再增 `customer_phone`（存量行 NULL）+ `platform_member_id`（= `ai_platform.sys_member.id`），迁移 `sql/migrate_business_data_platform_member.sql`（**必须先于新 JAR 执行**）。同日全平台废除 sys_member.member_id（M号 DROP），统一标识=memberDbId（sys_member.id），MemberConvertHandler 改 GeneratedKeyHolder。

- **导入（两段式全异步，2026-09-20 起）**：`POST /import/check`（multipart 上传→落盘 `{app.import.upload-dir}` UUID 文件→建 `sys_import_task`（`business_data_check`）→提交 `AsyncImportSupport.importExecutor`（2~4 线程+队列100+AbortPolicy）→立即返回 taskId；工作线程重建 SecurityContext 后解析+校验+匹配，结果 JSON 写 progress_msg）→ 前端 2s 轮询 `GET /import/{taskId}/status`（DONE 时 `result`=业务结果 JSON）→ `POST /import/confirm`（`checkTaskId`+`confirmations`，**复用落盘文件不二次上传**——合伙人慢上行链路只传一次；校验任务类型/状态/归属/文件在盘→并发互斥（PENDING/PROCESSING 计数+confirmLock）→建 `business_data_confirm` 任务→立即返回 taskId→轮询）。**启动清扫** `sweepInterruptedTasks`：遗留 PENDING/PROCESSING 标 FAILED。**租户上下文**：`withSecurityContext` 用任务记录重建 LoginUser（否则 TenantLineHandler 跳过滤→跨租户泄漏）。**事务边界**：`BusinessDataImportHandler.confirm()` `@Transactional` 保证全量或全不（异步线程无外层事务）；worker markDone/markFailed 独立提交；完成后删暂存文件（重试=重新上传）。confirm 缺 `checkTaskId`/`confirmations`（旧版前端缓存）手动校验返回「请刷新页面后重新导入」。`progress_msg` 已扩 MEDIUMTEXT。旧 `/import`、`/import/submit` 已删除，Kafka 兜底 `execute()`=零决策 confirm（不用本线程池）。
- **品牌型号校验顺序**：是否特批="是"或非核心四品类（燃气灶/抽油烟机/热水器/壁挂炉以外）跳过匹配（仍精确匹配提取底价）→ 精确匹配 → 已确认别名 → 模糊候选（同品牌+同品类 key+归一化型号包含关键词、按 id 排序）→ 失败「未找到匹配的品牌型号」。模糊项集中返回 `pendingItems`（品牌/品类/Excel 型号/行号/候选产品含三价格），主站弹窗逐项确认（唯一匹配标注"最接近的完整型号"）；确认关系落库 `ops_business_model_alias`（唯一键 `(corp_id, brand, category, model_keyword, delete_flag)`，迁移 `sql/migrate_business_model_alias.sql`——**新库部署必须先执行**），同型号免重复确认；确认后同 品牌+品类+关键词 行批量套用（零售底价/集团最低价落库，销售价保留 Excel 值）。
- **必填列（12 个，精确匹配缺列拒）**：客户/客户手机/品类/品牌/型号/销售价/公司/经办人/经办人手机号/市场收款日期/财务收款日期/资金动态表横排数。**客户手机**必填 + 11 位大陆手机号，按手机号归属会员：命中 `ai_platform.sys_member`（uk_phone 唯一）写 platform_member_id，未命中报「客户手机号未关联平台会员，请先完成会员转化」。市场收款日期**必填**；财务收款日期可空但格式错拒行（连带整文件）；横排数可空允许文字（纯整数/`12.0`/文字中首个整数，全角数字 NFKC 归一化，无数字落库空）。日期解析 `normalizeDate`（销售/市场/财务共用）：点分 `2026.1.2`、斜杠、**区间取首日**（分隔符 `-`/`~`/`—`/`至`）、全角。多 sheet 按「前 10 行含必填列名最多」自动选 sheet + 定位表头行。
- **模板**：`GET /template` 优先读 `app.import.template-path`（默认 `/home/jason/1.xlsx`；生产 volume 挂载 `/app/templates/1.xlsx` + `APP_IMPORT_TEMPLATE_PATH` 环境变量），缺失回退 classpath `templates/business_data_template.xlsx`；文件名固定 **增值业务数据模板.xlsx**。源文件 `/home/jason/1.xlsx`（12 sheet，18 列表头：收据号 客户 客户手机 品类 品牌 型号 销售价 公司 部门 经办人 经办人手机号 是否特批 提成比例 销售日期 市场收款日期 财务收款日期 资金动态表横排数 备注）。**修改必须手术式 XML 补丁**——openpyxl 重存会丢其它 sheet 图片（27 个 media/drawing 条目）。本地与生产 `/app/templates/1.xlsx` 已同步（md5 948cea25…），host 备份 `1.xlsx.bak-20261016`。
- **部门/备注字段**：`department VARCHAR(100)`（迁移 `sql/migrate_business_data_department.sql` 已执行，存量 remark「部门: X」前缀回填并清空）。Excel 部门列→department、备注列→remark 直接映射（旧前缀 hack 已删除）；无备注列旧文件兼容（列索引 -1 守卫）。
- **导出**：异步/同步两路径表头统一 22 列：公司 批次号 客户 **部门** 经办人 品牌 品类 型号 收款金额 销售价 零售底价 集团最低价 销售日期 收据号 市场收款日期 财务收款日期 资金动态表横排数 返利比例 返利金额 状态 **备注 是否特批**。筛选含 品牌/型号/市场收款日期起止（请求体 brand/model/receiptDateStart/receiptDateEnd → filters JSON → ExportHandler qw.eq/ge/le；列表 countFiltered/aggregateStats/categoryStats 三个注解 SQL 同步 `<if>` 条件，`receipt_date` 全量 ISO 可直接字符串区间比较）。**Kafka 降级已修复**：`syncExportFallback` 调 `service.exportExcel(...)`（写文件版，支持全部 8 筛选）落盘 `{upload.base-dir}/exports/` 后 `markDone(真实路径, Files.size)`。行序按**收据号分组相邻**（`sortByReceiptNo`：空沉底、组内 id 升序）。收款金额默认=销售价；同订单拆分多行（收据号+客户相同、多经办人）仅首行显示（`computeSplitOrderNonFirstIds`）。底价存量空值行导出时**关键字兜底匹配**（品牌+品类相同+归一化型号包含关键字、唯一命中才填充、歧义留空、仅内存不写库，`fillExportPrices/matchProductExactOrKeyword`）。品类 `qimi`=气密性检测（静态兜底字典）。
- 列表 API 直接返回实体，新增字段自动序列化给前端展示。

---

## 核心架构

### 认证与 SSO

- JWT 由底座平台统一签发（`POST /api/auth/sso/token`），子系统**不自签**
- `UserServiceImpl` 通过 `PlatformInternalClient` 调用底座 SSO
- `PlatformAuthenticationFilter`（来自 SDK）本地验证 JWT 签名
- 前端 H5 通过 `auth-bridge.js` 监听父窗口 `postMessage` 接收 token/corpId/userId
- 子系统间内部调用使用 `X-Internal-Token` 请求头认证

**双通道认证**：会员和员工使用**独立**的认证体系：
- **会员登录**：`AuthController` `/api/ops/auth/*` → 底座 SSO（PHONE 类型），token 存 `ops_token`
- **员工登录**：`EmployeeAuthController` `/api/ops/auth/employee/*` → 手机号+密码+图形验证码，由 `EmployeeAuthServiceImpl` 委托底座 `POST /api/auth/employee/login`（平台 `sys_user` 表鉴权），子系统不存储员工账号/密码，token 存 `ops_employee_token`
- 子系统只负责前置安全层：图形验证码校验、IP 频率限制（每分钟10次）、输入格式校验

**图形验证码**（`CaptchaService`）：
- 纯内存实现（无 Redis 依赖），适用于单实例部署
- 4 位字母数字（去除易混淆字符 I/O/0/1），5 分钟有效期，一次性使用
- 后台守护线程每 60 秒清理过期条目
- 员工登录强制验证码校验；`GET /api/ops/auth/employee/captcha` 返回 `{captchaId, captchaImage}`（PNG base64 data URI）

### 多租户隔离

- `corp_id` 从 JWT 解析，不接受请求参数传入
- 所有业务表含 `corpId` 字段
- 超管 `corpId=0` 查看全部数据

### 组织级数据隔离（三层角色模型）

```
超管（corpId=0）          → 查看全部，无限制
运营商（orgRoleId≠5）      → 查看自己 + 所有下属城市合伙人，完全 CRUD
城市合伙人（orgRoleId=5）  → 只查自己，只读（无写权限）
```

- `DataScopeService`：计算可见 corpId 集合，缓存 60 秒
- `OrgRoleService`：判定 org 角色类型，缓存 60 秒
- 安全兜底：底座 API 不可达 → 视为运营商（不阻断写），但数据 scope 回退到仅自己

### 操作日志审计（Operation Log Audit）

通过 `@OperationLog` 注解 + AOP 切面，对所有管理后台写操作自动记录审计日志。与主平台格式兼容。

**注解**：`com.aip.common.annotation.OperationLog`（定义在底座 `common` 模块），参数 `value` 为操作描述（如 "新增活动"、"编辑提成规则"）。

**AOP 切面**：`OperationLogAspect` — 拦截 `@OperationLog` 注解方法，捕获请求参数、响应结果、操作用户、IP、时间戳，异步写入 JSON-Lines 日志文件。

**日志存储**：`OperationLogService` — 按月分文件写入 `/home/jason/data/logs/operation/operation_YYYYMM.log`，每条日志一行 JSON，包含 `traceId`、`userId`、`corpId`、`ip`、`operation`、`params`、`result`、`timestamp`。

**覆盖范围**：截至 2026-08 已覆盖全部 18 个管理后台 Controller（见上方 Controller 表）。

### AI 助手架构（双通道：会员 + 员工）

平台有两个 AI 代理通道，共用底座 Hermes Agent：

**会员 AI**（`AiChatProxyController` `/api/ops/ai`）：
- `POST /chat` — 同步聊天，转发到底座 `POST /api/ai/chat/send`
- 语音：`POST /voice/recognize`（ASR）、`POST /voice/synthesize`（TTS），代理到底座 internal API
- ASR/TTS 使用 `asrRestTemplate`（300s 超时），普通 chat 使用 `aiRestTemplate`（120s 超时）

**员工 AI**（`EmployeeAiController` `/api/ops/ai/employee`）：
- `POST /chat` — 同步聊天，自动注入员工身份上下文（phone/company/role）
- `POST /chat/async` — 异步聊天：提交到 Kafka → 底座处理 → 返回 taskId
- `GET /chat/{taskId}/stream` — SSE 实时推送结果（替代轮询）
- `GET /chat/{taskId}/status` — 轮询降级（Kafka 不可用时）
- `GET /config` / `GET /profile` — 员工 AI 配置与个性化问候
- 员工上下文通过 `employeeContext` 参数注入底座 Agent，底座据此激活员工专用工具（`ops_query_employee_business_data`）

**Async AI Chat 基础设施**（`AiChatTaskProducer`/`AiChatResultConsumer`/`AiChatStatusConsumer`/`AiChatTaskService`/`SseEmitterManager`）：前端 POST /chat/async → Kafka topic `ai-chat-task` → Consumer 调底座 AI 更新 `ai_chat_task` 表（`migrate_ai_chat_task.sql` 创建）→ SSE stream 推送；Kafka 不可用自动降级同步

### MCP 工具 & Agent

- `OpsOrderQueryTool`：`@McpTool`，工具名 `ops_query_order`，按订单号/状态查询，多租户安全过滤
- `OpsEmployeeBusinessDataTool`：`@McpTool`，工具名 `ops_query_employee_business_data`，查询员工增值业务数据（月份/品类/提成），限定当前员工手机号
- `OpsAgent`：`@AgentComponent`，名称 `"OPS"`，处理业务综合问答（订单/商品统计）

### 作战地图看板（Battle Map Dashboard）

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

**配置**：`battle-map.etl.*` 三项见「关键配置」表（auto-refresh / init-on-startup / cron，默认每 30 分钟增量刷新）。

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

### 活动复盘（Activity Review）

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

### 提成规则（Commission Rules）

个人提成（`OpsPersonalCommissionRule`）和公司提成（`OpsCompanyCommissionRule`），支持**组织层级继承**：子组织自动继承上级规则。

**提成类型**：按比例（percentage）或固定金额（fixed_amount），按品类（category）配置。

**品类字典**：提成规则品类下拉框从平台数据字典 `PRODUCT_CATEGORY` 动态加载（`CommissionRuleService.getCategories()`），不再硬编码。

**API**：`CommissionRuleController` `/api/ops/admin/commission`
- 个人规则 CRUD：`GET/POST/PUT/DELETE /personal`
- 公司规则 CRUD：`GET/POST/PUT/DELETE /company`
- `GET /categories` — 品类字典

### 品类使用年限规则（Category Lifecycle Rule）

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

### 年度任务计划（Annual Plan）

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

### 燃气用户 / 网格员 / 会员台账

三个管理后台模块，均为标准 CRUD + 分页查询 + 组织数据隔离：

- **燃气用户**（`GasUserController` `/api/ops/admin/gas-user`）：`ops_gas_user` 表，按小区筛选 + 关键字搜索
- **网格员管理**（`GridManagerController` `/api/ops/admin/grid-manager`）：`ops_grid_manager` + `ops_grid_manager_community` 关联表，网格员可分配多个小区
- **会员台账**（`MemberLedgerController` `/api/ops/admin/member-ledger`）：`ops_member_ledger` 表仍是作战地图 ETL 与提成计算数据源，但 2026-10-16 起**列表按平台会员聚合**（AGG_SQL 按会员聚合（仅展示有设备的会员）；字段 platformMemberId/memberName/memberPhone/communityNames/regionNames/stoveCount/businessCount；筛选项 memberKeyword/communityName/sources）。设备明细走 `GET /member/{platformMemberId}/devices`（两组）。**销售组按收据号合并**：同收据号 distinct 非空经办人>1 → 合并为一条计 1（经办人"、"连接、multiAgent=true），否则逐条计，空收据号各行独立（AGG_SQL 与 mergeBusinessDevices 同口径须同步改）。活动解耦：列表零活动字段，activity_id 列与 CRUD 保留（复盘页销售台账编辑器直写）；/members /categories /communities 三端点已删。跨库直读 sys_member/sys_community，不可达降级空列表。

### 安检设备创建会员台账（Stove Ledger）

会员台账页顶部「安检设备创建」按钮（仅超管/运营商可见可操作，城市合伙人不可见，后端 `OrgRoleService.isOperatorOrSuperAdmin()` 守卫）：按 设备→燃气用户→平台会员 链路，将每台安检设备异步生成一条台账明细。同一会员多台设备 → 多行。

**数据链路**：`ops_gas_stoves_info.gas_user_id` → `ops_gas_user.platform_member_id` → `ai_platform.sys_member`。

**API**（`MemberLedgerController` `/api/ops/admin/member-ledger`）：
- `POST /from-stoves/submit` — 提交异步任务（Kafka `async-import-task`，taskType=`member_ledger_stoves`），可见 corp CSV 写入 task.remark 供 Handler 做数据范围过滤（未关联用户的设备只有 s.corp_id，故范围子句为 `s.corp_id OR gu.corp_id` 双匹配）
- `GET /from-stoves/{taskId}/status` — 任务轮询；progressMsg 为 `JSON|人话` 双格式（前端解析 JSON 精确计数 + 原因）
- `GET /` — 会员聚合列表（口径见「燃气用户 / 网格员 / 会员台账」节）

**处理器**：`StoveLedgerCreateHandler` — 单条 INSERT...SELECT + NOT EXISTS（gas_stoves_id 去重）+ 唯一索引 `(gas_stoves_id, delete_flag)` 兜底，逻辑删除后可重建。计数：created/skipped/failed（未关联燃气用户/未转化会员/未知燃具种类，附原因文本）。品类映射 `gas_stoves_class` 1→燃气灶 2→热水器 3→挂壁炉→壁挂炉（PRODUCT_CATEGORY itemKey 1/3/4）；usage_date 取 installation_time→buy_time→startup_time 首个非空；community_id 取燃气用户所属小区。

**迁移**：`sql/migrate_member_ledger_stoves.sql`（platform_member_id/gas_stoves_id 列 + uk_ledger_stoves 唯一键 + idx_platform_member 索引）。

**前端**（平台主站 `~/ai-native-platform/frontend/src/views/ops/`）：`MemberLedgerList.vue` 按钮（`canEdit` 由 `/ops/admin/permission/profile` 的 canEditProducts 控制）+ `MemberLedgerStovesDialog.vue` 三态弹窗（确认/轮询 2s/结果手动关闭），关闭时 `@done` 刷新列表；关联设备弹窗 `MemberDevicesDialog.vue`（multiAgent 行前置「多人参与」tag）。

### 微信手机号授权（WechatPhoneService）

`WechatPhoneService` 通过微信 code + session_key 解密获取用户手机号，用于小程序端静默授权。只在微信小程序环境生效。

### 燃气用户外部导入 & 会员转换

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

### 燃具信息迁移（Gas Stoves）

燃具信息（`ops_gas_stoves_info`）为外部燃气系统 `jinyuan_info.sb_gas_stoves_info` 的全量同步镜像。主键 `id` 直接存外部表 id（`VARCHAR(32)`，非自增），`gas_stoves_class` 存平台字典 `GAS_APPLIANCE_TYPE` itemKey（1灶具 2热水器 3挂壁炉）。前端入口在主平台「增值业务 → 安检采集设备」（ops H5 无对应页面）。

**API**（`GasStovesController` `/api/ops/admin/gas-stoves`）：
- `GET /` — 分页列表（用户号/燃具种类/品牌筛选，数据 scope 隔离，富化燃气用户姓名 + 组织名）
- `POST /preview` — 迁移预览（外部总数/本地已有/预计新增/待补全关联数）
- `POST /submit` — 触发异步全量迁移（Kafka `async-import-task`，taskType=`gas_stoves`）
- `GET /{taskId}/status` — 任务状态轮询

**处理器**：`GasStovesImportHandler` — 按外部 id 分页增量读取；corp 匹配链：`sb_gas_stoves_info.gas_the_company_id` → `ai_platform.sys_corp.gas_company_id` → `sys_corp.id`，无关联时取同步触发组织。

**燃气用户关联**：`gas_user_id` 匹配规则 `ops_gas_user.gas_company_id = s.gas_the_company_id AND ops_gas_user.gas_supply_certificate_number = s.user_number`（需求字面的 `gas_company_id` 匹配实测 0 条，不可用）。关联字段历经 `user_no` → `gas_user_id` 重构：`sql/migrate_gas_stoves_user_no.sql` → `migrate_gas_stoves_gas_user_id.sql`（回填）→ `migrate_gas_stoves_drop_user_no.sql`（**删列段必须在新 JAR 部署后执行**，旧 JAR 仍查询 user_no 列）。

**重建注意**：`migrate_gas_stoves.sql` 是重建脚本，`CREATE TABLE IF NOT EXISTS` 不会覆盖旧表（旧版为 BIGINT 自增 id + remote_id）。升级已有旧表的库必须先 `DROP TABLE ops_gas_stoves_info;` 再建表，然后重跑「迁移导入」重新同步。2026-08-31 该表曾随 `sys_community`/`ops_gas_user` 被物理清空，重导入即恢复路径（参见 `[[sys-community-gas-user-wipe-2026-08-31]]`）。

### 微信小程序跳转（H5）

H5 端跳转第三方小程序统一走 **JSSDK `wx.navigateToMiniProgram`**（不是 `wx-open-launch-weapp` 开放标签）：

- `pages/product/detail.vue` — 详情页「进入小程序」按钮 + 兑换成功后延时自动跳转，参数取 `product.miniappConfig`（appId/path）
- `pages/activity/detail.vue` — `WeixinJSBridge.invoke('navigateToMiniProgram', ...)`，取 `activity.miniapp_app_id`
- `pages/index/index.vue` — banner `jumpPath` 跳转

**注意**：`wx-open-launch-weapp` 仅在 `main.js` 注册为自定义元素，**无业务使用**；历史方案（`weixin://` / URL Link / 开放标签 + `MpLauncher.vue`）均已废弃，`components/` 下**不存在** MpLauncher.vue，勿照旧文档复原。

### 直播室管理（Live Room）

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

### 异步导出/导入（Kafka + 策略模式）

导出采用 `ExportHandler` 接口 + 策略模式，导入对称使用 `ImportHandler`：

```
Controller → Service.createTask() → DB 记录
  → KafkaTemplate.send("async-export-task" / "async-import-task")
  → Consumer → handlerMap.get(taskType) → handler.export() / handler.execute()
  → 更新 task 状态 → 前端轮询
```

- Kafka 条件加载：`@ConditionalOnProperty(name = "spring.kafka.enabled")`，禁用时降级同步执行
- 导出：TSV → Python openpyxl 转 XLSX，降级 TSV
- 导入：分阶段校验（FILE→HEADER→ROW→INSERT），全量通过后 JDBC batch 原子写入
- 导入并发控制：同类型同时只能有一个进行中任务

### 子系统间通信（替代跨库 JOIN）

- `ProductBatchClient`：调用 mall-service `POST /api/internal/product/batch-query`，替代跨库查 `mall_product`
- `PlatformMerchantService`：通过 `PlatformInternalClient` 查询组织信息，替代跨库查 `ai_platform.sys_corp`
- `MemberSyncClient`：注册/登录时同步会员到底座
- 所有 mapper SQL 仅 JOIN 本地表（`ops_*`, `biz_*`），不再跨库

### 图片服务链路

- `ImageUrlUtil`：规范化路径，完整 URL 提取相对路径，占位图 → null
- 前端 `utils/image.js`：`assetUrl()` 空值 → 占位 SVG，相对路径 → `BASE_URL + path`
- **H5 必须用完整 HTTPS 路径**（原因见陷阱表「H5 图片 URL」）

### 前端架构

- **无 Pinia/Vuex**：`uni.getStorageSync()` 持久化 token/user，页面 `ref()` 管理本地状态
- **无 Vue Router**：uni-app 文件路由（`pages.json`），`uni.navigateTo()` + URL 参数
- **iframe SSO**：`auth-bridge.js` 监听 `postMessage`（`AIP_AUTH` / `AIP_NAVIGATE`），信任同源和 `boss.jdzn99.com`
- **公众号深链**：`App.vue` 支持 `?route=pages/employee/login` 和 `?from=employee` 查询参数跳转（公众号自定义菜单不支持 hash fragment）
- **API 集中管理**：`frontend/src/api/index.js` 导出 `api` 对象（约 150 个方法），统一使用 `request` 实例
- **H5 hash 路由**：实际 base 由构建时 `VITE_BASE` 环境变量控制

### 前端页面路由（pages.json）

入口页：`pages/ai/chat`（AI 优先）。5 个底部 Tab：

| Tab | 页面 | 说明 |
|-----|------|------|
| AI助手 | `pages/ai/chat`, `pages/ai/settings` | AI 对话（语音输入、TTS、个性化设置）— **入口页** |
| 商品 | `pages/product/index`, `pages/product/detail` | 积分商城 |
| 首页 | `pages/index/index` | 轮播/视频/推荐/直播/优惠券 |
| 活动 | `pages/activity/index`, `pages/activity/detail`, `pages/live/index` | 活动 + 直播广场 |
| 我的 | `pages/mine/index`, `pages/order/*`, `pages/address/*`, `pages/points/log`, `pages/coupon/list`, `pages/auth/*`, `pages/booking/list` | 用户中心 |

管理页面（已注册，不在 Tab 中）：`pages/admin/commission-rules`, `pages/admin/gas-user`, `pages/admin/annual-plan`, `pages/admin/live-room-manage`  
管理页面（文件存在，未注册到 pages.json，由平台主站 componentMap 加载）：`pages/admin/activity-form`, `pages/admin/home-config`, `pages/admin/business-data`, `pages/admin/members`  
站长页面：`pages/station/apply`, `pages/station/center`  
员工页面（已注册）：`pages/employee/login`、`pages/employee/ai-chat`（AI 助手 H5）、`pages/employee/dashboard`、`pages/employee/business-data`、`pages/employee/profile`  
员工页面（未注册）：`pages/employee/company-select`（见陷阱表）

### 关键前端组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `EmployeeTabBar` | `components/EmployeeTabBar.vue` | 员工端底部导航栏（AI助手/业务数据/我的） |
| `ImageCropperModal` | `components/ImageCropperModal.vue` | 图片裁剪上传弹窗，uni-app `<image>` 的 `mode` 裁剪封装 |
| `LiveStatusCard` | `components/LiveStatusCard.vue` | 直播状态卡片（预告/直播中/结束 + 开播倒计时） |
| `Pager` | `components/Pager.vue` | 通用分页器，配合列表页使用 |

---

## 测试与验证

项目**无自动化测试**（无单元测试/集成测试）。所有验证依赖手动检查：

- **编译验证**：`mvn -f backend/pom.xml compile -o`（完整命令见「快速命令」）
- **后端 API**：`curl` 测试端点，查看日志 `tail -f /tmp/ops-backend.log`
- **前端 H5**：`npm run build:h5` → 部署生产 → 微信开发者工具打开 `https://boss.jdzn99.com/ops/`
- **前端管理页**：平台主站 `https://boss.jdzn99.com` 加载 `/ops/` 路由组件（非微前端，是 componentMap 直接加载）
- **微信 JSSDK**：必须在微信环境中测试，普通浏览器不可用（详见 `docs/testing.md`）

---

## 关键配置

### 后端（application.yml）

| 配置键 | 说明 | 默认值 |
|---|---|---|
| `server.port` | 后端端口 | `8084` |
| `spring.datasource.url` | MySQL 连接 | `jdbc:mysql://104.238.165.57:3306/mall_db` ⚠️ 已退役主机，本地需覆盖 |
| `platform.base-url` | 底座平台地址 | `http://104.238.165.57:8080` ⚠️ 同上，生产由 `PLATFORM_BASE_URL` 覆盖 |
| `platform.subsystem-code` | 子系统标识 | `ops-platform` |
| `platform.internal-token` | 内部调用 Token | `aip-internal-svc-token-2026` |
| `platform.jwt-secret` | JWT 签名验证密钥（不自签） | 32+ 字节 |
| `platform.jwt-expiration` | JWT 过期时间（秒） | `43200`（12小时） |
| `spring.servlet.multipart.max-file-size` | 上传文件大小限制 | `50MB` |
| `spring.servlet.multipart.max-request-size` | 上传请求大小限制 | `50MB` |
| `mall-service.base-url` | mall 子系统地址 | `http://localhost:8083` |
| `app.upload.base-dir` | 上传根目录 | `/app/uploads`（Docker） |
| `app.import.upload-dir` | 异步导入文件暂存目录 | `${user.home}/data/imports`（Docker 环境变量覆盖） |
| `app.resource.base-url` | 静态资源域名 | `https://boss.jdzn99.com` |
| `spring.kafka.enabled` | Kafka 开关 | yml 签入 `true`；无 Kafka 环境自动降级同步 |
| `battle-map.etl.auto-refresh` | 作战地图定时 ETL | `true` |
| `battle-map.etl.init-on-startup` | 启动时全量 ETL | `true` |
| `battle-map.etl.cron` | ETL 刷新间隔 | `0 */30 * * * *`（每 30 分钟） |
| `ops.remind.hours-before` | 活动提醒提前小时数 | `24,1` |
| `gas.datasource.jdbc-url` | 外部燃气平台 PolarDB 连接 | `jdbc:mysql://parking-db.mysql.polardb.rds.aliyuncs.com:3306/jinyuan_info` |
| `gas.datasource.username` | 燃气平台 DB 用户名 | `jinyuan_kaifa_only` |
| `gas.import.company-filter` | 燃气导入公司过滤 | `德保` |
| `amap.key` | 高德地图 API Key（作战地图边界） | 需配置 |
| `platform.merchant-role-name` | 商家角色名称 | `销售商` |
| `logging.level.com.aip.ops` | 应用日志级别 | `DEBUG` |
| `management.endpoints.web.exposure.include` | Actuator 暴露端点 | `health,info` |
| `platform.sdk-permit-paths` | SDK 安全过滤器放行路径 | 6 个路径（`/actuator/**`, `/uploads/**` 等）。SecurityConfig 另有 5 个公开端点（`/api/ops/home-data`, `/api/ops/coupon/list`, `/api/ops/ai/config`, `/api/ops/live-rooms/**`, `/api/ops/admin/business-data/template`），合计 11 个免认证路径 |

**生产 Kafka 配置**：使用 SASL_PLAINTEXT 认证（`admin/admin123.`），bootstrap-server `113.250.60.41:9092`。

### RestTemplate Beans

| Bean | 超时 | 用途 |
|---|---|---|
| `restTemplate`（默认） | 10s connect, 10s read | 通用内部调用 |
| `aiRestTemplate` | 10s connect, **120s** read | AI chat 代理（底座响应慢） |
| `asrRestTemplate` | 10s connect, **300s** read | ASR 语音识别（大音频文件） |

### 前端环境变量

| 文件 | 变量 | 值 |
|------|------|-----|
| `.env` | `VITE_OPS_ENTRY` | `https://boss.jdzn99.com` |
| `.env` | `VITE_CORP_ID` | `4` |
| `.env.development` | `VITE_OPS_ENTRY` | `http://localhost:3035` |
| `.env.production` | `VITE_BASE` | `/ops/` |
| `.env.production` | `VITE_OPS_ENTRY` | `https://boss.jdzn99.com` |

---

## 生产部署（Docker）

生产服务器 `113.250.60.41`（root），部署目录 `/data2/boss-platform/`。**始终本地构建，上传到远程。**

```bash
# 后端
cd backend && JAVA_HOME=/home/jason/jdk17 /home/jason/apache-maven-3.9.9/bin/mvn clean package -DskipTests -o
# JAR → /data2/boss-platform/ops-backend/app.jar
# docker build -t boss-platform/ops:latest ./ops-backend/
# docker compose up -d ops-backend
# Dockerfile 包含 HEALTHCHECK（30s 间隔，GET /actuator/health），Python3 + openpyxl 用于 TSV→XLSX 转换

# 前端 —— 两套前端独立，改哪边就部署哪边（见下表）
cd frontend && npm run build:h5        # ops H5 → /data2/boss-platform/html/ops/
```

**前端部署目标（勿混）**：

| 改动位置 | 上传目标 | 生效方式 |
|---|---|---|
| `~/ops-platform-project/frontend/`（C 端/员工端 H5，hash 路由） | `/data2/boss-platform/html/ops/` | `boss-ops-nginx`(9083) |
| `~/ai-native-platform/frontend/src/views/ops/`（管理后台页） | `/data2/boss-platform/html/platform/` | `docker exec boss-platform-nginx nginx -s reload` |

**新增 `/ops/xxx` 管理页必须同步加 host nginx 白名单**：两套前端共用 `/ops/` 前缀，host nginx（`/data2/nginx/conf/conf.d/boss.conf`）以**逐路由白名单**把管理页路由到主站 9081，未列入者落入 catch-all 被送到 Ops H5 —— 用户刷新管理页会看到公众号移动端（URL 出现 `#/` 即已中招；2026-08-27 在 `/ops/gas-stoves`、`/ops/category-lifecycle-rules` 上踩过）。加一行 `location ^~ /ops/新路径 { proxy_pass http://172.17.0.1:9081; proxy_http_version 1.1; proxy_set_header Host $host; proxy_read_timeout 300s; proxy_buffering off; }` 后执行 `docker exec nginx nginx -t && docker exec nginx nginx -s reload`。校验：`curl -s https://boss.jdzn99.com/ops/新路径 | grep -o '<title>[^<]*</title>'` → 主站=「协同平台」，H5=「金地燃气」。

生产 Docker 关键参数：
- ops-backend: `SERVER_PORT=8082`, `network_mode: host`
- Volume: `/data2/boss-platform/uploads:/app/uploads`
- `PLATFORM_BASE_URL=http://localhost:8080`（host 网络直连平台）
- `MALL_SERVICE_BASE_URL=http://localhost:8083`

生产 Nginx：`/ops/` → `boss-ops-nginx:80`，`/api/ops/**` → `ops-backend:8082`

### 生产数据库定时备份（2026-09-04 上线，落盘本机）

- **任务**：**本机**（开发机 WSL）crontab `0 2 * * *` 每日凌晨 2:00 全量备份生产 `ai_platform` + `mall_db`（CST）。本机需开机且 cron 服务运行，否则当天无备份。2026-10-15 起增 `@reboot` 补跑（`db-backup-if-stale.sh`：超 20h 未备份即补，幂等）
- **脚本**：本机 `/home/jason/data/db-backup.sh` — SSH 到生产 `docker exec mysqldump`（utf8mb4 + single-transaction + routines/triggers/events），管道回本机 gzip 落盘
- **产物**：本机 `/home/jason/data/<db>_<YYYYmmdd_HHMMSS>.sql.gz`（约 5~7MB/库），保留 7 天自动清理；日志同目录 `backup.log`。手工单表导出放 `manual/` 子目录可免被清理
- 恢复方式（在生产服务器）：`gzip -dc <file> | docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p`

---

## 已知陷阱

| 陷阱 | 说明 |
|------|------|
| **本地 JDK/Maven 路径** | JDK 17 在 `/home/jason/jdk17`、Maven 在 `/home/jason/apache-maven-3.9.9/bin/mvn`（均不在 PATH，必须绝对路径）；本地仓库 `/home/jason/m2_local`，离线编译必须 `-o`。生产跑在 Docker（`eclipse-temurin:17-jre`）内，无此问题 |
| **Maven offline** | 手动编译必须 `-o`（离线模式），本地仓库 `/home/jason/m2_local`。`restart.sh` 不使用离线模式 |
| **生产 base 路径** | 生产 base 是 `/ops/`，实际由 `vite.config.js` 的 `mode === 'production'` 兜底给出。⚠️ `.env.production` 里的 `VITE_BASE=/ops/` **不生效**——代码读 `process.env.VITE_BASE`，而 Vite 的 `loadEnv` 不写入 `process.env`（改 `.env` 无效，除非 shell 里 export）。manifest.json 已同步为 `/ops/` |
| **H5 图片 URL** | uni-app `<image>` 渲染为 CSS `background-image`，根相对路径被解析到 `/ops/` → 必须用完整 HTTPS 路径 |
| **文件上传目录** | `app.upload.base-dir` 用绝对路径配置，不是 `user.dir`，否则 JAR 从不同目录启动时文件错位 |
| **ASR multipart** | 转发 ASR 请求时**不要手动设置 `Content-Type`**（不含 boundary 导致 422），让 Spring 自动生成 |
| **前端部署清理** | 每次部署前端必须 `rm -rf assets/*` 再上传，否则旧 chunk 残留导致新功能不生效 |
| **jweixin-1.6.0.js** | 必须用 `npm run build:h5`（= `uni build` + 复制 `static/jweixin-1.6.0.js` + 把产物里的 `"/static/` 重写为 `"/ops/static/`）。裸跑 `npx uni build` 会**漏掉静态路径重写**（`/ops/` 下静态资源 404），且 jweixin 版本不受控 |
| **BaseEntity 字段命名** | SDK 使用 `modifyTime`（不是 `updateTime`） |
| **增值业务物理删除** | `ops_business_data` 的 `batchDelete()` 用原始 JDBC 物理删除，不走 `@TableLogic` |
| **5 个实体未继承 BaseEntity** | `SysImportTask`、`SysExportTask`、`AiChatTask`、`OpsActivityReviewCost`、`OpsGridManagerCommunity` 自行声明审计字段；其余 31 个均继承。其中 `OpsActivityReviewCost` 无 `delete_flag`，`costMapper.delete()` 走物理删除 |
| **数据库环境隔离** | 生产同实例并置 `mall_db`（业务）与 `ai_platform`（底座）两库，极易混淆。**部署时必须确认 `SPRING_DATASOURCE_URL` 指向 `mall_db`**（历史事故：生产容器误用测试库，17,745 条数据写错库） |
| **无自动化测试** | 所有验证依赖手动检查和生产监控 |
| **首页配置 corpId** | `/api/ops/home-data` 公开接口（无 JWT），必须传 `?corpId=` 参数 |
| **Kafka 条件加载** | Consumer 仅在 `spring.kafka.enabled=true` 时加载，无 Kafka 环境自动降级为同步调用 |
| **nimbus-jose-jwt** | 依赖保留用于 JWT 签名验证（PlatformAuthenticationFilter），不自签但需验签 |
| **平台缓存层** | 底座菜单/字典/地区/组织配置已有 Caffeine 缓存。子系统通过 PlatformInternalClient 调平台 API 自动命中缓存，**禁止**子系统侧再缓存或直查 DB |
| **AiChatProxyController vs EmployeeAiController** | 会员和员工是两个独立 AI 通道，不要混用。员工通道自动注入员工身份上下文 |
| **SseEmitterManager** | SSE 连接持有限时（默认 120s），超时自动关闭。前端需处理重连 |
| **CaptchaService 单实例** | 验证码存储在内存 `ConcurrentHashMap` 中，仅适用于单实例部署。多实例需替换为 Redis |
| **双 Token 隔离** | 会员 token（`ops_token`）和员工 token（`ops_employee_token`）是独立的存储 key。`request.js` 按优先级发送（会员优先），但两个 token 不能混用——员工 token 不能访问会员接口，反之亦然；401 时清除两者 |
| **ops-platform vs 平台主站** | 两套前端详见「生产部署 → 前端部署目标」。主站 `/ops/xxx` 是 `componentMap` 直接加载 Vue 组件（非微前端），改 H5 不影响主站 |
| **Kafka 消费者组冲突** | 同一 group 内分区独占。多容器共享 `ai-platform-group` 时旧容器抢分区但连错库 → 任务僵死 PENDING、新容器收不到消息。**部署新容器前必须 `docker stop/rm` 旧容器**，部署后 `docker ps` 确认无残留。参见 `[[kafka-consumer-group-conflict]]` |
| **旧容器/代码不留存** | **铁律**：新容器部署后旧容器必须立即清理（`docker stop + rm`）。旧代码、旧容器、旧配置不留存——git history 可随时恢复。参见 `[[no-legacy-code-retention]]`、`[[kafka-consumer-group-conflict]]` |
| **作战地图跨库查询** | `BattleMapController.summary()` 和 `region` 解析方法直读 `ai_platform.sys_corp` 和 `ai_platform.sys_region`（跨库）。这是仅有的例外——组织区域信息无法通过 API 获取。维护时注意表结构变更 |
| **作战地图数据源仅生产** | 作战地图源表（`ops_business_data`/`ops_gas_user`/`ops_member_ledger`/`ops_grid_manager`）仅在**生产库**有数据，ETL 只能在生产环境跑。参见 `[[battle-map-data-only-on-prod]]` |
| **新增 /ops/xxx 管理页未加 nginx 白名单** | host nginx 逐路由白名单未列入的新管理页会被 catch-all 送到 Ops H5，刷新后看到公众号移动端（URL 出现 `#/`）。步骤见「生产部署 → 前端部署目标」 |
| **company-select 未注册路由** | `frontend/src/pages/employee/company-select.vue` 文件存在但**未在 pages.json 中注册路由**。该页面通过其他方式引用（如 `uni.navigateTo` 直接跳转），不要误删 |
| **mysql 客户端默认 latin1** | `docker exec mysql mysql` 不指定字符集时 client/connection/results=latin1（服务器 utf8mb4）。含中文 SQL 必须加 `--default-character-set=utf8mb4`：否则写操作双重编码损坏数据（2026-08-29 `sys_corp.gas_company_id` 注释损坏已修复）、读操作显示乱码（数据无损）。判别：`HEX()` 看字节，`C3A7E280A1`=损坏。详见 `MEMORY.md` 1.4 节 |
| **生产 MySQL 8.0.46 + 表间 COLLATE 不一致** | 生产 MySQL 实为 8.0.46，不支持 `ADD COLUMN IF NOT EXISTS` → 迁移 DDL 用 information_schema 条件化 PREPARE；`ops_business_data`=unicode_ci vs `ops_gas_user`/`ops_value_added_product`=0900_ai_ci，跨表 JOIN 须双侧显式 COLLATE，型号比对须 `COLLATE utf8mb4_bin` |

### UI/UX 质量规范（强制）

1. **开发前审视排版合理性**：元素对齐（label/input 是否在同一基线）、间距一致（gutter/行距统一）、溢出处理（长文本截断/换行、价格字段不挤压）、响应式（H5 320-428px 视口适配）
2. **开发中注重交互便捷性**：操作步骤最少化（单次点击完成、避免不必要的模态嵌套）、反馈即时（按钮 loading 态、toast 通知、空/错误/加载三态覆盖）、错误提示友好（中文、具体、可操作）
3. **开发完成后必须做 UI 审计**：
   - 逐一检查每个元素的**可见性**（不被底栏/导航遮挡、固定定位高度预留）
   - **对齐与间距**（输入框/按钮/表格列宽一致、标签宽度统一）
   - **溢出处理**（价格/长文本在窄屏下的换行与截断）
   - **颜色对比度**（灰色文本在浅背景上是否可读、品牌色点缀不过度）
   - **交互流畅度**（对话框打开/关闭无闪烁、输入框聚焦不跳动、滚动平滑）
   - 不符合标准的立即修正，修正后再构建部署

---

最后更新：2026-10-19 | 变更历史已迁移至 [docs/CHANGELOG.md](docs/CHANGELOG.md)，新变更请按时间倒序追加到该文件

### 自定义 Claude 命令

- **`/pr`** — 基于 git diff 生成结构化 PR 描述（标题/动机/模块级变更/影响范围/测试建议/风险提示）。定义见 `.claude/commands/pr.md`
- **`/loop`** — 严格开发闭环：plan → implement → verify（编译）→ code-review → PR → 结束，非极特殊情况不中断。定义见 `.claude/commands/loop.md`
