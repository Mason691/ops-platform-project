# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **工程文件索引**  
> 编码规范 + ADR → `MEMORY.md`（⚠️ 第四节合规状态表为 2026-06-22 改前快照，已全部过时，以本文件为准）| 用户画像 → `user.md` | 产品理念 → `soul.md` | 功能规格 → `Specs/`

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

# 前端小程序构建
cd frontend && npm run build:mp-weixin  # 输出 dist/build/mp-weixin/

# 部署到测试环境（104.238.165.57）
cd frontend && npm run build:h5
ssh jason@104.238.165.57 "mkdir -p /home/jason/aa-platform/html/aa/ops"
scp -r dist/build/h5/* jason@104.238.165.57:/home/jason/aa-platform/html/aa/ops/

# 后端部署到测试环境
cd backend && JAVA_HOME=/home/jason/jdk17 /home/jason/apache-maven-3.9.9/bin/mvn clean package -DskipTests -o
scp target/ops-platform-1.0.0-SNAPSHOT.jar jason@104.238.165.57:~/ops-platform-project/backend/target/
ssh jason@104.238.165.57 "kill \$(lsof -ti:8084) 2>/dev/null; sleep 1; \
  nohup /home/jason/jdk17/bin/java -jar ~/ops-platform-project/backend/target/ops-platform-1.0.0-SNAPSHOT.jar > /tmp/ops.log 2>&1 &"
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
| 测试 | `104.238.165.57` | jason / aB740602 | `a.15891589.xyz` | JAR 直接运行 + host nginx |
| 生产 | `113.250.60.41` | root / `j_ly[4=2Gh1?9]` | `boss.jdzn99.com` | Docker compose（`/data2/boss-platform/`） |

- 数据库：`mall_db @ 104.238.165.57`（root / aB740602，Docker 容器中）
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
│   │   ├── entity/          # 35 entities（全部继承 SDK BaseEntity，SysImportTask 例外；含 MerchantMiniappConfig, OperatorMerchant）
│   │   ├── mapper/          # 36 MyBatis-Plus mappers
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
│   ├── src/api/index.js     # 集中 API 注册（148 个方法）
│   ├── src/utils/           # request.js, image.js, config.js, jssdk.js, auth-bridge.js
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
| `BusinessDataController` | `/api/ops/admin/business-data` | 增值业务：Excel 两段式**异步**导入（`/import/check` 上传落盘+校验任务线程池解析+品牌型号匹配 → `/import/confirm` 携带 checkTaskId+确认决策复用落盘文件入库（不二次上传），双端点立即返回 taskId 前端轮询 `/import/{taskId}/status`，含收据号唯一性校验）/提成计算（按批次选择）/销售统计（按收据号+品类+型号去重）/异步导出（筛选含 品牌/型号）/品类提成汇总（提成金额+销售金额双文件 × 全部汇总+逐月 sheet 打包 ZIP，**销售金额按（收据号+公司）去重且归属规则明确**：含户内美装销售(itemKey 6)则归销售品类、否则按品类中文名排序取首品类（SQL 内 itemKey→中文名 CASE 映射，ROW_NUMBER 排序 salePri→catName→id）、提成全明细汇总）/模板下载/状态流转 + 经办人核对 |

### 增值业务数据（Business Data）

`ops_business_data` 表 2026-09-03 起字段结构：`receipt_date` 展示名改为**市场收款日期**（字段名保留 varchar(50)），其后新增 `finance_receipt_date`（DATE 财务收款日期，可空）与 `fund_dynamic_rows`（INT 资金动态表横排数，可空）。迁移脚本 `sql/migrate_business_data_finance_fields.sql`（已执行）。

- **导入（2026-09-20 起两段式，2026-09-22 起全异步任务化）**：`POST /import/check`（multipart 上传→落盘 `{app.import.upload-dir}` UUID 文件名→创建 `sys_import_task`（taskType=`business_data_check`）→提交 `importExecutor` 线程池→**立即返回 taskId**；工作线程重建 SecurityContext 后解析+校验+匹配，结果 JSON 写入 progress_msg）→ 前端轮询 `GET /import/{taskId}/status`（2s 间隔，DONE 时 `result` 字段=业务结果 JSON）→ `POST /import/confirm`（`checkTaskId`+`confirmations` 表单参数，**复用校验任务落盘文件不二次上传**——合伙人慢上行链路只需传一次；校验任务类型/状态/归属/文件在盘→并发互斥（`business_data_confirm` PENDING/PROCESSING 计数+confirmLock 锁内检查建任务）→ 创建 `business_data_confirm` 任务提交线程池→立即返回 taskId→轮询）。**线程池**：`AsyncImportSupport.importExecutor`（2~4 线程+有界队列100+AbortPolicy，满载返回"系统繁忙"）；**启动清扫**：`sweepInterruptedTasks` 将遗留 PENDING/PROCESSING 的两类任务标记 FAILED（重启中断+并发守卫解锁）。**工作线程租户上下文**：`withSecurityContext` 用任务记录重建 LoginUser+UsernamePasswordAuthenticationToken（无上下文时 TenantLineHandler 会跳过租户过滤→跨租户泄漏）；**事务边界**在 `BusinessDataImportHandler.confirm()`（`@Transactional`，异步工作线程无外层事务，须由本方法保证全量或全不），worker 的 markDone/markFailed 独立提交不受业务回滚影响；confirm 完成后 worker 删除暂存文件（重试=重新上传）。**confirm 缺 `checkTaskId`/`confirmations` 参数**（页面还跑着旧版前端——旧 confirm 为 multipart 重传文件——时必然发生）由手动校验返回友好提示「请刷新页面后重新导入」，不再触发 SDK 兜底的「系统内部错误，请联系管理员」。`sys_import_task.progress_msg` 已扩为 MEDIUMTEXT（迁移 `sql/migrate_import_task_progress_msg_mediumtext.sql`，大文件错误列表/候选产品 JSON 超 500 字符）。旧 `/import`、`/import/submit` 已删除，Kafka 兜底路径 `execute()` = 零决策 confirm（不使用本线程池）。**品牌型号校验顺序**：是否特比="是"或非核心四品类（燃气灶/抽油烟机/热水器/壁挂炉以外）跳过匹配校验（仍做精确匹配提取底价）→ 精确匹配 → 已确认别名 → 模糊候选（同品牌+同品类 key+归一化产品型号包含关键词、按 id 排序）→ 失败「未找到匹配的品牌型号」。模糊匹配项在 check 结果中集中返回（`pendingItems`：品牌/品类/Excel 型号/涉及行号/候选产品含三价格），主站前端弹窗逐项确认（唯一匹配标注"最接近的完整型号"）；确认关系落库 `ops_business_model_alias`（唯一键 `(corp_id, brand, category, model_keyword, delete_flag)`，迁移 `sql/migrate_business_model_alias.sql` 已执行——**新库部署必须先执行**），同型号免重复确认；确认后文件内相同 品牌+品类+关键词 的行批量套用该产品（零售底价/集团最低价落库，销售价保留 Excel 值）。必填列含 市场收款日期/财务收款日期/资金动态表横排数（表头 contains 匹配——旧模板缺列直接拒绝）。市场收款日期**必填**（空→行错误）；财务收款日期可空，但格式错误拒绝该行（连带整文件拒绝）；**横排数可空、允许文字**（模板该列为文字格式）——纯整数/`12.0`/文字中首个整数（`12排`→12）解析入库，全角数字经 NFKC 归一化（`２６０`→260），无数字文字不报错落库空。日期解析（销售/市场/财务三处共用 `normalizeDate`）支持 `2026.1.2`（点分）、`2026/2/13`（斜杠，月/日单数字）、**日期区间取首日**（`2026/2/13-2026/2/12`→`2026-02-13`，分隔符 `-`/`~`/`—`/`至`）与全角输入。多 sheet 模板按「前 10 行含必填列名最多」自动选 sheet + 自动定位表头行。
- **模板下载**：`GET /template` 优先读配置路径 `app.import.template-path`（默认 `/home/jason/1.xlsx`；生产为 volume 挂载 `/app/templates/1.xlsx` + 环境变量 `APP_IMPORT_TEMPLATE_PATH`，便于不改 JAR 更新模板），缺失回退 classpath `templates/business_data_template.xlsx`；下载文件名固定 **增值业务数据模板.xlsx**。
- **模板源文件**：`/home/jason/1.xlsx`（12 sheet 工作簿，业务数据 sheet 为导入源）。修改该模板必须**手术式 XML 补丁**——openpyxl 重存会丢弃其它 sheet 的图片（27 个 media/drawing 条目，2026-09-03 曾踩坑）。2026-09-20 补丁新增 **备注** 列（Q1，表头 17 列：收据号 客户 品类 品牌 型号 销售价 公司 部门 经办人 经办人手机号 是否特批 提成比例 销售日期 市场收款日期 财务收款日期 资金动态表横排数 备注），补丁文件已部署到生产容器 `/app/templates/1.xlsx`。2026-09-21 补丁将 **资金动态表横排数** 列（P 列）格式改为**文字**（numFmtId=49 "@"）：styles.xml 末尾追加新 cellXf（index 558，复制原样式 47 的 font/border 但 numFmtId=49——样式 47 与 F 列共享不能原地改）、sheet10.xml 中 P2/P3 单元格 s= 改 558、`<cols>` 拆分 min15-16 为 min15-max15 + min16-max16(style=558) 使新行继承文字格式；仅 2 个 XML 条目变更、27 个 media/drawing 条目字节一致；导入 Handler `toInteger` 已兼容文字单元格纯整数与数字单元格 "12.0"，无需改代码。注意：**本地 `/home/jason/1.xlsx` 已同步为生产 17 列版本（md5 ed7902e2…）**，生产 host 备份 `/data2/boss-platform/templates/1.xlsx.bak-20260921`。
- **部门/备注字段（2026-09-20）**：`ops_business_data` 新增 `department VARCHAR(100) 部门`（位于 agent 前，迁移 `sql/migrate_business_data_department.sql` 已执行，存量 remark 的「部门: X」前缀回填到 department 并清空）。Excel 部门列 → department、备注列 → remark **直接映射**（旧「部门: 」前缀 hack 已删除）；无 备注 列的旧文件兼容（列索引 -1 守卫，remark 落 NULL）。
- **导出**：异步/同步两条导出路径表头均含 市场收款日期/财务收款日期/资金动态表横排数（紧跟 收据号 之后），销售价 前含 **收款金额**、销售价 后含 零售底价/集团最低价。2026-09-20 起表头 22 列：公司 批次号 客户 **部门** 经办人 品牌 品类 型号 收款金额 销售价 零售底价 集团最低价 销售日期 收据号 市场收款日期 财务收款日期 资金动态表横排数 返利比例 返利金额 状态 **备注 是否特批**（是否特批在 备注 后，取 is_special 原始值）。**筛选条件 2026-09-20 起含 品牌/型号**：/export 请求体 brand/model 写入 filters JSON → BusinessDataExportHandler 解析并 qw.eq 过滤；列表 API 同条件精确匹配（countFiltered/aggregateStats/categoryStats 三个注解 SQL 均含 brand/model `<if>` 条件，统计与行数一致）。主站筛选栏在 品类 后新增 品牌/型号 输入框。**Kafka 不可用时的同步降级已修复**：`syncExportFallback` 调 `service.exportExcel(...)`（写文件版本，支持全部 6 个筛选）落盘 `{upload.base-dir}/exports/` 后 `markDone(真实路径, Files.size)`——旧实现传 null response + 假路径 "/home/jason/exports/" + 0 字节必坏，且丢 category 参数。行序按 **收据号分组相邻**（`sortByReceiptNo`：空/无收据号沉底、组内 id 升序）。收款金额默认=该行销售价；同一订单拆分多行（收据号+客户相同、多经办人，不要求品类/型号相同）仅导出顺序首行显示（=组内最早录入行）、其余留空，判定逻辑 `BusinessDataServiceImpl.computeSplitOrderNonFirstIds`。零售底价/集团最低价由导入时从增值产品精确匹配落库；存量空值行（如武山型号只写关键字）导出时**关键字兜底匹配**——品牌+品类相同、产品型号归一化（去空格/转小写）后包含业务型号关键字、**唯一命中才填充**（歧义留空），仅内存填充导出文件不写库，逻辑 `BusinessDataServiceImpl.fillExportPrices/matchProductExactOrKeyword`（生产模拟：武山 285 行命中、0 歧义；壁挂炉类产品表无记录仍为空）。品类 `qimi` 是 PRODUCT_CATEGORY 字典 itemKey（item_value=气密性检测），静态兜底字典含映射，导出显示中文。
- 列表 API 直接返回实体，新增字段自动序列化给前端展示。
| `CommissionRuleController` | `/api/ops/admin/commission` | 个人/公司提成规则 CRUD（支持组织层级继承） |
| `ValueAddedProductController` | `/api/ops/admin/value-added/product` | 增值产品 CRUD + 列表按品类/品牌/型号(like)/状态筛选 |
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
| `MemberLedgerController` | `/api/ops/admin/member-ledger` | **会员台账**：分页查询/按小区+品类+经办人+创建时间筛选/批量导入/安检设备创建（异步） |
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
- 两个 token 在 `request.js` 中按优先级使用（`ops_token` 优先，fallback `ops_employee_token`）
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

**覆盖范围**：截至 2026-08，已覆盖 18 个管理后台 Controller（`AdminController`、`CommissionRuleController`、`AnnualPlanController`、`BusinessDataController`、`CategoryLifecycleRuleController`、`GasUserController`、`GridManagerController`、`MemberLedgerController`、`HomeConfigController`、`ValueAddedProductController`、`ActivityReviewController`、`AdminStationController`、`StationController`、`EmployeeController`、`MemberController`、`PartnerController`、`PublicAccountController`、`BattleMapController`）。

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

**Async AI Chat 基础设施**（`kafka/AiChatTaskProducer`, `kafka/AiChatResultConsumer`, `kafka/AiChatStatusConsumer`, `service/AiChatTaskService`, `service/SseEmitterManager`）：
```
前端 POST /api/ops/ai/employee/chat/async
  → AiChatTaskProducer.publishTask() → Kafka topic "ai-chat-task"
  → AiChatResultConsumer / AiChatStatusConsumer → 调用底座 AI → 更新 ai_chat_task 表
  → 前端 SSE GET /chat/{taskId}/stream → SseEmitterManager 推送结果
```
- Kafka 不可用时自动降级为同步调用
- 表 `ai_chat_task`（由 `migrate_ai_chat_task.sql` 创建）

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

**配置**（`application.yml`）：
```yaml
battle-map:
  etl:
    auto-refresh: true
    init-on-startup: true        # 启动时全量初始化
    cron: "0 */30 * * * *"      # 每 30 分钟增量刷新
```

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
- **会员台账**（`MemberLedgerController` `/api/ops/admin/member-ledger`）：`ops_member_ledger` 表，增值业务销售记录，按小区/品类/经办人多维筛选，是作战地图和提成计算的数据源。列表接口每行附带 transient 展示字段：memberName（gas_user→sys_member 链）、memberPhone（gas_user.phone，会员唯一识别，前端「会员」列名称下叠加手机号）、communityName、regionName（**区县地区**= 小区所属区县，链路 `sys_community.region_id → sys_region.region_name`，未配置时 "-"）、gridManagerName、activityTitle；前端主站 MemberLedgerList.vue 在「小区」列前展示「区县地区」列

### 安检设备创建会员台账（Stove Ledger）

会员台账页顶部「安检设备创建」按钮（仅超管/运营商可见可操作，城市合伙人不可见，后端 `OrgRoleService.isOperatorOrSuperAdmin()` 守卫）：按 设备→燃气用户→平台会员 链路，将每台安检设备异步生成一条台账明细。同一会员多台设备 → 多行。

**数据链路**：`ops_gas_stoves_info.gas_user_id` → `ops_gas_user.platform_member_id` → `ai_platform.sys_member`。

**API**（`MemberLedgerController` `/api/ops/admin/member-ledger`）：
- `POST /from-stoves/submit` — 提交异步任务（Kafka `async-import-task`，taskType=`member_ledger_stoves`），可见 corp CSV 写入 task.remark 供 Handler 做数据范围过滤（未关联用户的设备只有 s.corp_id，故范围子句为 `s.corp_id OR gu.corp_id` 双匹配）
- `GET /from-stoves/{taskId}/status` — 任务轮询；progressMsg 为 `JSON|人话` 双格式（前端解析 JSON 精确计数 + 原因）
- `GET /` — 分页列表新增 createTimeStart/createTimeEnd 筛选；memberKeyword 搜索链路：sys_member（昵称/手机号）→ gas_user.phone 匹配 + 兜底 gas_user.full_name/phone 直搜

**处理器**：`StoveLedgerCreateHandler` — 单条 INSERT...SELECT + NOT EXISTS（gas_stoves_id 去重）+ 唯一索引 `(gas_stoves_id, delete_flag)` 兜底，逻辑删除后可重建。计数：created/skipped/failed（未关联燃气用户/未转化会员/未知燃具种类，附原因文本）。品类映射 `gas_stoves_class` 1→燃气灶 2→热水器 3→挂壁炉→壁挂炉（PRODUCT_CATEGORY itemKey 1/3/4）；usage_date 取 installation_time→buy_time→startup_time 首个非空；community_id 取燃气用户所属小区。

**迁移**：`sql/migrate_member_ledger_stoves.sql`（platform_member_id/gas_stoves_id 列 + uk_ledger_stoves 唯一键 + idx_platform_member 索引）。

**前端**（平台主站 `~/ai-native-platform/frontend/src/views/ops/`）：`MemberLedgerList.vue` 按钮（`canEdit` 由 `/ops/admin/permission/profile` 的 canEditProducts 控制）+ `MemberLedgerStovesDialog.vue` 三态弹窗（确认/轮询 2s/结果手动关闭），关闭时 `@done` 刷新列表。

### 微信手机号授权（WechatPhoneService）

`WechatPhoneService` 通过微信 code + session_key 解密获取用户手机号，用于小程序端静默授权。只在微信小程序环境生效。

### 燃气用户外部导入 & 会员转换

燃气用户（`ops_gas_user`）可从外部燃气平台 PolarDB 批量导入，并支持异步转换为平台会员。

**外部数据源**：`GasDataSourceConfig` 按需创建独立 HikariCP 连接池（`jinyuan_info`），不注册 Spring Bean 以免覆盖主数据源。

**导入流程**：
- `POST /api/ops/admin/gas-user/gas-import/query` — 预览外部源记录数
- `POST /api/ops/admin/gas-user/import-from-gas-platform` — 提交异步导入任务
- `GasUserImportHandler`（taskType=`gas_user_import`）— 读取外部 Polardb `sb_gas_user` 表，按 `user_no` 去重，自动创建小区记录，调用高德地理编码
- 导入参数 `company-filter`（默认 `德保`）过滤来源公司

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

H5 端使用 `wx-open-launch-weapp` 开放标签跳转小程序，经多次迭代后最终方案：

**核心组件**：`frontend/src/components/MpLauncher.vue` — 动态创建微信开放标签，每次点击重建 DOM 元素以绕过微信标签复用限制

**关键演化**：`weixin://` scheme（不可用）→ `finder-preview` URL → `wx-open-launch-weapp` 标签（innerHTML 注入绕过 Vue 编译器）→ 微信 URL Link API → 最终回到 `wx-open-launch-weapp` + 每次点击动态重建

**独立测试页**：`frontend/src/static/mp-test.html`（用于微信 URL Link 功能测试）

### 直播室管理（Live Room）

运营商可配置面向 C 端的直播室，H5 端展示并引导用户跳转观看。

**实体**：`OpsLiveRoom`（`ops_live_room` 表）— 标题、封面、开播时段、状态（0=预告 1=直播中 2=已结束）、回放 URL、观看人数、`live_link`（第三方直播链接，如腾讯云/阿里云等）

**架构演进**：2026-07 从视频号专用（finderUsername/jumpUrl/schemeTicket）简化为**纯 `live_link` 字段**，支持任意第三方直播平台跳转。

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

```
DB (相对路径) → ImageUrlUtil → API JSON (相对路径)
  → 前端 assetUrl() + BASE_URL → 完整 HTTPS URL
  → Nginx /uploads/ops/ → 磁盘文件
```

- `ImageUrlUtil`：规范化路径，完整 URL 提取相对路径，占位图 → null
- 前端 `utils/image.js`：`assetUrl()` 空值 → 占位 SVG，相对路径 → `BASE_URL + path`
- **H5 必须用完整 HTTPS 路径**：uni-app `<image>` 渲染为 CSS `background-image`，CSS `url()` 将根相对路径解析到页面 base `/ops/` → 404

### 前端架构

- **无 Pinia/Vuex**：`uni.getStorageSync()` 持久化 token/user，页面 `ref()` 管理本地状态
- **无 Vue Router**：uni-app 文件路由（`pages.json`），`uni.navigateTo()` + URL 参数
- **双 Token 体系**：`request.js` 优先使用 `ops_token`（会员），fallback 使用 `ops_employee_token`（员工）。401 时清除两者
- **iframe SSO**：`auth-bridge.js` 监听 `postMessage`（`AIP_AUTH` / `AIP_NAVIGATE`），信任同源和 `boss.jdzn99.com`
- **公众号深链**：`App.vue` 支持 `?route=pages/employee/login` 和 `?from=employee` 查询参数跳转（公众号自定义菜单不支持 hash fragment）
- **API 集中管理**：`frontend/src/api/index.js` 导出 `api` 对象（148 个方法），统一使用 `request` 实例
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
员工页面（已注册）：`pages/employee/login`（员工登录）、`pages/employee/ai-chat`（员工 AI 助手 H5）、`pages/employee/dashboard`、`pages/employee/business-data`、`pages/employee/profile`  
员工页面（未注册）：`pages/employee/company-select`（文件存在但未在 pages.json 注册）

### 关键前端组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `MpLauncher` | `components/MpLauncher.vue` | 微信开放标签跳转小程序 — 每次点击动态重建 DOM 绕过微信标签复用限制 |
| `EmployeeTabBar` | `components/EmployeeTabBar.vue` | 员工端底部导航栏（AI助手/业务数据/我的） |
| `ImageCropperModal` | `components/ImageCropperModal.vue` | 图片裁剪上传弹窗，uni-app `<image>` 的 `mode` 裁剪封装 |
| `LiveStatusCard` | `components/LiveStatusCard.vue` | 直播状态卡片（预告/直播中/结束 + 开播倒计时） |
| `Pager` | `components/Pager.vue` | 通用分页器，配合列表页使用 |

---

## 测试与验证

项目**无自动化测试**（无单元测试/集成测试）。所有验证依赖手动检查：

- **编译验证**：`JAVA_HOME=/home/jason/jdk17 /home/jason/apache-maven-3.9.9/bin/mvn -f backend/pom.xml compile -o`
- **后端 API**：`curl` 测试端点，查看日志 `tail -f /tmp/ops-backend.log`
- **前端 H5**：`npm run build:h5` → 部署到测试服务器 → 微信开发者工具打开 `https://a.15891589.xyz/ops/`
- **前端管理页**：平台主站 `https://a.15891589.xyz` 加载 `/ops/` 路由组件（非微前端，是 componentMap 直接加载）
- **微信 JSSDK**：必须在微信环境中测试，普通浏览器不可用（详见 `docs/testing.md`）
- **Kafka 功能**：仅在 `spring.kafka.enabled=true` 时可用（生产环境），测试环境 Kafka 禁用时自动降级为同步调用

---

## 关键配置

### 后端（application.yml）

| 配置键 | 说明 | 默认值 |
|---|---|---|
| `server.port` | 后端端口 | `8084` |
| `spring.datasource.url` | MySQL 连接 | `jdbc:mysql://104.238.165.57:3306/mall_db` |
| `platform.base-url` | 底座平台地址 | `http://104.238.165.57:8080` |
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
| `spring.kafka.enabled` | Kafka 开关 | `true`（生产）/ `false`（测试） |
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

**生产 Kafka 配置**：使用 SASL_PLAINTEXT 认证（`admin/admin123.`），bootstrap-server `113.250.60.41:9092`。测试环境 Kafka 禁用。

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

# 前端
cd frontend && npm run build:h5
# 上传到 /data2/boss-platform/html/ops/
```

生产 Docker 关键参数：
- ops-backend: `SERVER_PORT=8082`, `network_mode: host`
- Volume: `/data2/boss-platform/uploads:/app/uploads`
- `PLATFORM_BASE_URL=http://localhost:8080`（host 网络直连平台）
- `MALL_SERVICE_BASE_URL=http://localhost:8083`

生产 Nginx：`/ops/` → `boss-ops-nginx:80`，`/api/ops/**` → `ops-backend:8082`

### 生产数据库定时备份（2026-09-04 上线，落盘本机）

- **任务**：**本机**（开发机 WSL）crontab `0 2 * * *` 每日凌晨 2:00 全量备份生产 `ai_platform` + `mall_db`（CST）。本机需开机且 cron 服务运行，否则当天无备份
- **脚本**：本机 `/home/jason/data/db-backup.sh` — SSH 到生产 `docker exec mysqldump`（`--default-character-set=utf8mb4 --single-transaction --routines --triggers --events --set-gtid-purged=OFF`），管道回本机 gzip 落盘
- **产物**：本机 `/home/jason/data/<db>_<YYYYmmdd_HHMMSS>.sql.gz`（约 5~7MB/库），保留 7 天自动清理；日志同目录 `backup.log`
- 恢复方式（在生产服务器）：`gzip -dc <file> | docker exec -i mysql mysql --default-character-set=utf8mb4 -uroot -p`

---

## 已知陷阱

| 陷阱 | 说明 |
|------|------|
| **服务器 JDK** | 测试服务器默认 JDK 8，运行 JAR 必须用绝对路径；本地 JDK 17（Temurin 17.0.20.1）统一在 `/home/jason/jdk17`，Maven 在 `/home/jason/apache-maven-3.9.9/bin/mvn`（均不在 PATH，必须绝对路径）；Maven 本地仓库 `/home/jason/m2_local`（`~/.m2/settings.xml` 已配置 `localRepository`，离线编译必须 `-o`） |
| **Maven offline** | 手动编译必须 `-o`（离线模式），本地仓库 `/home/jason/m2_local`。`restart.sh` 不使用离线模式 |
| **生产 base 路径** | 生产 base 是 `/ops/`（`.env.production` `VITE_BASE=/ops/`），manifest.json 已同步为 `/ops/` |
| **H5 图片 URL** | uni-app `<image>` 渲染为 CSS `background-image`，根相对路径被解析到 `/ops/` → 必须用完整 HTTPS 路径 |
| **文件上传目录** | `app.upload.base-dir` 用绝对路径配置，不是 `user.dir`，否则 JAR 从不同目录启动时文件错位 |
| **ASR multipart** | 转发 ASR 请求时**不要手动设置 `Content-Type`**（不含 boundary 导致 422），让 Spring 自动生成 |
| **前端部署清理** | 每次部署前端必须 `rm -rf assets/*` 再上传，否则旧 chunk 残留导致新功能不生效 |
| **jweixin-1.6.0.js** | 必须用 `npm run build:h5`（含 `cp static/jweixin-1.6.0.js dist/build/h5/`），不能用 `npx uni build` |
| **BaseEntity 字段命名** | SDK 使用 `modifyTime`（不是 `updateTime`） |
| **增值业务物理删除** | `ops_business_data` 的 `batchDelete()` 用原始 JDBC 物理删除，不走 `@TableLogic` |
| **SysImportTask 未继承 BaseEntity** | 自行声明所有审计字段，是唯一例外 |
| **数据库环境隔离** | 生产与测试共享同一 MySQL 实例但使用不同数据库（生产 `mall_db` / 测试 `mall_db_test`），极易混淆。**部署时必须确认 `SPRING_DATASOURCE_URL` 指向正确的库**。历史事故：生产容器曾错误使用 `mall_db_test`，导致 17,745 条数据写入测试库而非生产库 |
| **无自动化测试** | 所有验证依赖手动检查和生产监控 |
| **首页配置 corpId** | `/api/ops/home-data` 公开接口（无 JWT），必须传 `?corpId=` 参数 |
| **Kafka 条件加载** | Consumer 仅在 `spring.kafka.enabled=true` 时加载，测试环境无 Kafka 降级同步 |
| **nimbus-jose-jwt** | 依赖保留用于 JWT 签名验证（PlatformAuthenticationFilter），不自签但需验签 |
| **平台缓存层** | 底座菜单/字典/地区/组织配置已有 Caffeine 缓存。子系统通过 PlatformInternalClient 调平台 API 自动命中缓存，**禁止**子系统侧再缓存或直查 DB |
| **AiChatProxyController vs EmployeeAiController** | 会员和员工是两个独立 AI 通道，不要混用。员工通道自动注入员工身份上下文 |
| **SseEmitterManager** | SSE 连接持有限时（默认 120s），超时自动关闭。前端需处理重连 |
| **CaptchaService 单实例** | 验证码存储在内存 `ConcurrentHashMap` 中，仅适用于单实例部署。多实例需替换为 Redis |
| **双 Token 隔离** | 会员 token（`ops_token`）和员工 token（`ops_employee_token`）是独立的存储 key。`request.js` 按优先级发送（会员优先），但两个 token 不能混用——员工 token 不能访问会员接口，反之亦然 |
| **ops-platform vs 平台主站** | `boss.jdzn99.com` 有两套前端：平台主站（`~/ai-native-platform/frontend`）和 ops H5（本项目）。`/ops/xxx` 路由在主站中通过 `componentMap` 直接加载 Vue 组件，不是微前端。改 ops H5 页面不影响主站 |
| **Kafka 消费者组冲突** | 同一 consumer group 内分区独占。多容器共享 `ai-platform-group` 时，旧容器（如 `determined_lehmann`）抢到 `async-export-task` 分区但连错数据库 → 任务僵死 PENDING → 新容器永远收不到消息。**部署新容器前必须 `docker stop/rm` 旧容器**，部署后 `docker ps` 确认无僵尸容器残留。参见 `[[kafka-consumer-group-conflict]]` |
| **旧容器/代码不留存** | **铁律**：新容器部署后旧容器必须立即清理（`docker stop + rm`）。旧代码、旧容器、旧配置不留存——git history 可随时恢复。参见 `[[no-legacy-code-retention]]`、`[[kafka-consumer-group-conflict]]` |
| **作战地图跨库查询** | `BattleMapController.summary()` 和 `region` 解析方法直读 `ai_platform.sys_corp` 和 `ai_platform.sys_region`（跨库）。这是仅有的例外——组织区域信息无法通过 API 获取。维护时注意表结构变更 |
| **作战地图数据源仅生产** | 作战地图源表（`ops_business_data`/`ops_gas_user`/`ops_member_ledger`/`ops_grid_manager`）仅在**生产库**有数据。测试 `mall_db` 无这些表的记录，ETL 只能在生产环境跑。参见 `[[battle-map-data-only-on-prod]]` |
| **ops_activity_review_cost 无 delete_flag** | 此表缺少逻辑删除字段，`costMapper.delete()` 走物理删除。重构时需注意 |
| **company-select 未注册路由** | `frontend/src/pages/employee/company-select.vue` 文件存在但**未在 pages.json 中注册路由**。该页面通过其他方式引用（如 `uni.navigateTo` 直接跳转），不要误删 |
| **mysql 客户端默认 latin1** | `docker exec mysql mysql` 不指定字符集时 `character_set_client/connection/results=latin1`（服务器为 utf8mb4）。含中文的 SQL（DDL 注释/INSERT/LIKE）必须加 `--default-character-set=utf8mb4`：不加则**写操作双重编码损坏数据**（2026-08-29 `sys_corp.gas_company_id` 注释被写坏已修复），读操作则**显示乱码**（数据无损）。判别：`HEX()` 看字节，`C3A7E280A1`（ç‡ƒ）序列=损坏，`E4`-`E9` 开头=正常。详见 `MEMORY.md` 1.4 节 |

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

最后更新：2026-09-22 | 负责人：架构师（2026-09-22 晚 业务数据导入两处缺陷修复（用户报「校验时还是报内部错误」——生产日志定位：彭阳 corp11 用户 19:58/19:59 两次 `import/confirm` 缺 `checkTaskId` 参数 → `MissingServletRequestParameterException` → SDK 兜底返回「系统内部错误，请联系管理员」；根因=用户浏览器缓存了旧版前端（旧 confirm 为 multipart 重传文件，慢上行链路 3.7MB 传完才被新后端参数绑定拒绝），check 任务 517/518 本身 DONE 且正确报出该文件唯一错误「第19行: 财务收款日期格式不正确」——用户文件财务收款日期含**斜杠日期区间** `2026/2/13-2026/2/12` 不被 normalizeDate 支持）：①confirm 的 checkTaskId/confirmations 改 `required=false` + 手动校验返回「导入校验任务已失效，请刷新页面后重新导入」，旧缓存页面也能得到可操作提示；②normalizeDate 支持日期区间取首段（分隔符 -/~/—/至，NFKC 已归一全角～－）与单值斜杠日期，错误文案补斜杠示例与区间说明；③前端 doImport/submitConfirm 加 taskId 缺失守卫。已部署生产（后端 JAR+容器重建、前端 rm -rf assets/* 后上传+nginx 容器 reload、新 chunk BusinessData-Kx8upuSn.js 200），E2E：故障单元格复刻（菊花A007+`2026/2/13-2026/2/12`+`173/404` 文字横排数，corp11 上下文）check 通过、四写法日期区间（斜杠区间/斜杠单值/中文至区间/全角波浪线区间）corp12 全流程落库首日验证、缺参 confirm 返回友好提示——E2ETEST 行/任务/文件全部清理；2026-09-22 业务数据导入异步化（用户报"城市合伙人导入 EXCEL 报超时，要求异步处理"——生产日志定位：corp12 合伙人 14:35:34 /import/check 服务端 125s（python 解析+主机 CPU 争用），前端 axios 全局 30s 超时先中断，前端报"timeout of 30000ms exceeded"；同类文件 14:37:40 重试服务端 7s、corp4 同文件 57ms 对比证实为瞬时争用放大）：两段式全异步任务化——check/confirm 双端点落盘+建 `sys_import_task`（taskType business_data_check/confirm）+ 提交 importExecutor 线程池立即返回 taskId，前端 2s 轮询 /import/{taskId}/status（DONE 时 result 字段=业务结果 JSON），confirm 复用 check 落盘文件不二次上传（慢上行链路只传一次）；AsyncImportSupport 线程池+启动清扫（遗留 PENDING/PROCESSING 标 FAILED 防并发守卫死锁）；工作线程 withSecurityContext 重建租户上下文（空上下文 TenantLineHandler 跳过滤→跨租户泄漏）；事务边界移至 handler.confirm（worker markDone/markFailed 独立提交）；progress_msg 扩 MEDIUMTEXT（迁移已执行）；confirm 并发互斥（hasPendingTask+confirmLock）；前端 importCheck timeout 放宽 600000、确认弹窗流程保持。已部署生产（后端 JAR+容器重建、前端 rm -rf assets/* 后上传+nginx reload、迁移执行），E2E 四场景全过：corp12 15 行 happy（check 1.42s vs 旧 125s、文字横排数、DB corp_id=12 验证）、坏日期 fail 路径（错误 JSON 轮询返回）、超管模糊匹配 confirm 路径（8 候选 JSON 往返+决策确认+别名记忆 product_id=131+底价 5670 落库）、双确认拒绝（文件已过期）——E2ETEST 行/别名/任务记录/临时文件全部清理；2026-09-22 业务数据导入异步化（用户报"城市合伙人导入 EXCEL 报超时，要求异步处理"——生产日志定位：corp12 合伙人 14:35:34 /import/check 服务端 125s（python 解析+主机 CPU 争用），前端 axios 全局 30s 超时先中断，前端报"timeout of 30000ms exceeded"；同类文件 14:37:40 重试服务端 7s、corp4 同文件 57ms 对比证实为瞬时争用放大）：两段式全异步任务化——check/confirm 双端点落盘+建 `sys_import_task`（taskType business_data_check/confirm）+ 提交 importExecutor 线程池立即返回 taskId，前端 2s 轮询 /import/{taskId}/status（DONE 时 result 字段=业务结果 JSON），confirm 复用 check 落盘文件不二次上传（慢上行链路只传一次）；AsyncImportSupport 线程池+启动清扫（遗留 PENDING/PROCESSING 标 FAILED 防并发守卫死锁）；工作线程 withSecurityContext 重建租户上下文（空上下文 TenantLineHandler 跳过滤→跨租户泄漏）；事务边界移至 handler.confirm（worker markDone/markFailed 独立提交）；progress_msg 扩 MEDIUMTEXT（迁移已执行）；confirm 并发互斥（hasPendingTask+confirmLock）；前端 importCheck timeout 放宽 600000、确认弹窗流程保持。已部署生产（后端 JAR+容器重建、前端 rm -rf assets/* 后上传+nginx reload、迁移执行），E2E 四场景全过：corp12 15 行 happy（check 1.42s vs 旧 125s、文字横排数、DB corp_id=12 验证）、坏日期 fail 路径（错误 JSON 轮询返回）、超管模糊匹配 confirm 路径（8 候选 JSON 往返+决策确认+别名记忆 product_id=131+底价 5670 落库）、双确认拒绝（文件已过期）——E2ETEST 行/别名/任务记录/临时文件全部清理；2026-09-21 增值业务导入模板「资金动态表横排数」列改文字格式：手术式 XML 补丁（新 cellXf numFmtId=49 + P2/P3 改 s= + 列样式，仅 2 条目变更 27 图片字节一致），已部署生产 host 挂载路径 /data2/boss-platform/templates/1.xlsx（备份 .bak-20260921）、本地 1.xlsx 同步、API 下载 E2E md5 一致 + openpyxl 验证 P 列 number_format=@；2026-09-20 晚 业务数据列表/导出新增品牌+型号精确筛选（用户报"品牌=万和 型号=Q1L16111 导出文件没有该数据"——前端筛选栏本无品牌/型号输入框、后端 /export 静默丢弃 brand/model、Kafka 降级路径假路径必坏三重缺陷一并修复），E2E 验证：brand+model 导出含该行、brand+category 118 行全匹配、全量 598 行=列表 total；2026-09-20 业务数据导入改两段式：/import/check 校验+品牌型号匹配解析（特批/非核心四品类跳过匹配、精确→别名→模糊候选→失败）+ /import/confirm 应用确认决策原子写入；确认关系落库 ops_business_model_alias 记忆（迁移 sql/migrate_business_model_alias.sql，部署前必须执行）；零售底价/集团最低价自确认产品落库、销售价保留 Excel 值；旧 /import、/import/submit 删除；主站前端新增型号匹配确认弹窗 BusinessDataModelConfirmDialog.vue；2026-09-12 品类提成汇总品类行序调整：户内美装三品类（销售/工程/运维）key 6,8,9 连排相邻、定尺波纹管 7 后移至三元组之后（CATEGORY_ORDER 常量，两文件全部 sheet 行序一致）；同日早前 品类提成汇总销售金额归属规则：含户内美装销售(itemKey 6)的订单销售金额归销售品类、无销售则按品类中文名排序取首品类（ROW_NUMBER 排序 salePri→catName→id，逐月 FIRST_VALUE 同排序），提成全明细不变（8 个收据号归属变化：总销售 5,376,742→5,405,446，+28,704 为胜者行金额差异、提成 344,299.60 不变、逐月对账一致）；同日早前 品类提成汇总销售金额按（收据号+公司）去重：ROW_NUMBER 窗口函数取组内首行（id 最小）sale_price 只计一次，提成全明细汇总不变；逐月 sheet 订单月份=组内首行 sale_date 月份，与全部汇总对账一致（生产数据对比：销售总额 5,938,205→5,376,742 消除重复 561,463，提成 344,299.60 完全不变，133 个收据号受影响，收据号 0039852 手验 5358→1786）；2026-09-11 业务数据导出按收据号分组排序（空收据号沉底、组内 id 升序，首行=组内最早录入行）；同日早前 增值产品管理列表增加型号模糊查询参数 model（前端早已发送、后端补接收）；同日早前 业务数据导出增加型号关键字兜底匹配：品牌+品类相同+产品型号含业务关键字、唯一命中填充零售底价/集团最低价（武山 285 行命中 0 歧义，仅导出文件不落库）+ qimi 品类字典兜底显示气密性检测；同日早前 收款金额拆分口径修正：收据号+客户相同+多经办人即拆分，不再要求品类/型号相同（收据号 0039852 案例）；同日再早 业务数据导出在 销售价 前新增 收款金额 列：默认=销售价，同一订单拆分多行仅首行显示；同日再早 业务数据异步导出在 销售价 后新增 零售底价/集团最低价 两列，与同步导出路径对齐；2026-09-03 增值业务数据字段结构调整：市场收款日期改名 + 新增财务收款日期/资金动态表横排数，导入必填校验/多 sheet 模板识别/模板下载配置路径 + 固定文件名/导出含新列/模板手术式 XML 补丁陷阱；同日品类提成汇总改双文件 ZIP：提成金额/销售金额各一文件 × 全部汇总+逐月 sheet，月度归组与全部汇总对账一致（2026-09-12 起口径改为按收据号+公司去重，见句首）；此前 2026-09-02 补录安检设备创建会员台账模块：数据链路/API/处理器/迁移/前端弹窗 + memberKeyword 兜底搜索列名修复；2026-09-01 补录燃具信息模块 GasStovesController/迁移流程/重建注意事项 + mysql 客户端 latin1 陷阱；2026-08-18 作战地图看板文档校准：6 张 ETL 表、经办人/品类 TOP5 数据源改为 ops_business_data + calc_status 过滤、新增网格员/品类/小区 TOP5 端点；修正控制器/服务层/操作日志覆盖/前端 API 计数；补充销售统计收据号+品类+型号去重规则）

### 自定义 Claude 命令

项目 `.claude/commands/` 目录提供两个自定义命令，定义在 `.claude/commands/pr.md` 和 `.claude/commands/loop.md`。

**`/pr`** — 基于当前 git diff 生成结构化 PR 描述，包含：
1. 标题（一句话概括）
2. 动机（为什么做、解决什么问题）
3. 模块级变更列表（按模块/文件分类）
4. 影响范围（受影响的模块/接口/页面/数据流）
5. 测试建议（reviewer 应重点测试的功能点/边界条件）
6. 风险提示（已知风险/未解决问题/潜在副作用）

**`/loop`** — 严格开发闭环，自动完成以下步骤，非极特殊情况不中断：
1. **plan** — 列出受影响文件、接口/数据流变更、潜在风险点；修改接口时列出所有引用方
2. **implement** — 按 plan 分步编写代码，每次只做最小原子改动
3. **verify** — 运行编译检查（`mvn compile`），失败立即修复，不通过不继续（项目无自动化测试，verify 仅验证编译通过）
4. **code-review** — 多角度审查（逻辑/安全/性能/规范/删除影响）；严重问题自动修复并跳回步骤3；风格建议记录但不阻塞
5. **PR** — 生成结构化 PR 描述
6. **结束** — 报告闭环完成，等待用户确认是否提交
