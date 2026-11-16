# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **工程文件索引**  
> 编码规范 + ADR → `MEMORY.md`（⚠️ 第四节合规状态表为 2026-06-22 改前快照，已全部过时，以本文件为准）| 用户画像 → `user.md` | 产品理念 → `soul.md` | 功能规格 → `Specs/` | 变更历史 → `docs/CHANGELOG.md`
>
> **细节文档（本文件只保留索引 + 强制规则）**：
> - 架构、Controllers、认证/多租户、AI/MCP、子系统通信 → `docs/architecture.md`
> - 前端架构、pages.json 路由、组件清单 → `docs/frontend.md`
> - 增值业务数据（表结构/导入导出/手工新增/财务核对）→ `docs/business-data.md`
> - 业务数据订单工作流 → `docs/workflow.md`
> - 作战地图/活动复盘/提成/燃气/燃具/直播等模块 → `docs/modules.md`
> - 配置全表（application.yml / RestTemplate / 前端环境变量）→ `docs/configuration.md`
> - 生产部署、nginx 白名单细节、数据库备份 → `docs/deployment.md`

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

# 前端 H5 dev server（端口 3035，Vite proxy /api → localhost:8084；/api/auth → 底座 8080）
cd frontend && npm run dev:h5

# 前端 H5 生产构建（含 jweixin-1.6.0.js 复制 + static 路径替换）
cd frontend && npm run build:h5        # 输出 dist/build/h5/

# 前端小程序 dev server（微信开发者工具导入 dist/dev/mp-weixin）
cd frontend && npm run dev:mp-weixin

# 前端小程序构建
cd frontend && npm run build:mp-weixin  # 输出 dist/build/mp-weixin/

```


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
- `Specs/员工手工新增业务-规格说明.md`
- `Specs/财务管理-分类管理-规格说明.md`
- `Specs/业务数据主子表拆分-规格说明.md`
- `Specs/业务数据退货反写-规格说明.md`
- `Specs/公众号消息模板组织共享-规格说明.md`
- `Specs/员工微信授权登录-规格说明.md`
- `Specs/派工单与公众号消息提醒-规格说明.md`（**已下线**：派工单体系 2026-11-14 由底座工作流替代，仅存历史参考）
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
├── backend/src/main/java/com/aip/ops/
│   ├── controller/ entity/ mapper/ service/   # 标准三层 + 独立业务服务（详见 docs/architecture.md）
│   ├── config/      # SecurityConfig, AuthConfig, RestTemplateConfig, MybatisPlusConfig, GasDataSourceConfig
│   ├── client/      # 子系统 HTTP 客户端（ProductBatchClient, ScmBaseDataClient, ScmStockOutClient, WorkflowPlatformClient, MemberSyncClient）
│   ├── imports/ export/  # 异步导入/导出策略（ImportHandler / ExportHandler）
│   ├── kafka/       # Kafka consumer/producer（条件加载）
│   ├── agent/ tool/ # AI DomainAgent + MCP 工具
│   ├── scheduler/   # 作战地图 ETL、业务数据对账
│   └── security/    # JwtTokenInspector（拒绝 sso 会员令牌）
├── frontend/
│   ├── src/pages/       # uni-app 页面（路由注册见 docs/frontend.md）
│   ├── src/components/  # 跨页面组件（BusinessStep* 向导、OrgSwitch*、PaymentQr 等）
│   ├── src/hooks/       # useBusinessWizard / useOrgSwitch / useProductPrice
│   ├── src/api/index.js # 集中 API 注册（约 171 方法）
│   └── vite.config.js   # prod base /ops/；dev proxy /api→8084、/api/auth→8080
├── sql/                 # 迁移脚本 + 历史导入生成器/产物
├── Specs/ specs/        # 功能规格（中/英）
├── docs/                # 细节文档：architecture / frontend / business-data / workflow / modules / configuration / deployment / CHANGELOG / testing
├── restart.sh           # 本地一键重启（backend/frontend/all/rebuild/dev/stop/build）
├── Dockerfile           # 生产镜像（含 Python3 + openpyxl）
└── MEMORY.md soul.md user.md
```

---

## 核心架构原则（强制）

- **JWT 由底座签发**：子系统不自签；`PlatformAuthenticationFilter` 本地验签。会员 token（`ops_token`）与员工 token（`ops_employee_token`）严格隔离；员工敏感接口用 `JwtTokenInspector` 拒绝 `token_type=sso` 的会员令牌。子系统间内部调用一律 `X-Internal-Token`。
- **多租户**：`corp_id` 只从 JWT 解析，不接受请求参数；含 `corp_id` 的表由 MyBatis-Plus `TenantLineHandler` 自动过滤。
- **组织数据隔离**：超管 `corpId=0` 全量；运营商管理自身 + 下属城市合伙人；城市合伙人（`orgRoleId=5`）只读。数据 scope 由 `DataScopeService` 计算。
- **空可见组织集合 ≠ 超管**：`getVisibleCorpIds()` 返回 `null`=超管全量、空集合=无任何权限；禁止把空集合当全量。写接口必须校验 `corp_id` 归属。
- **统一会员**：所有子系统用 `platform_member_id`（= `ai_platform.sys_member.id`）关联底座会员；会员 SSO 令牌由底座签发。
- **实体注册**：业务实体继承 `BaseEntity` + `@TableName`，并必须同步至平台 `sys_entity_config`（本项目用 `sql/ops_entity_config_seed.sql`），否则 AI 无法通过 `query_entity_data` 查询其数据。
- **禁止跨库查询/JOIN**：产品走 `ProductBatchClient`，组织走 `PlatformInternalClient`，SCM 走 `ScmBaseDataClient`/`ScmStockOutClient`，工作流走 `WorkflowPlatformClient`。
- **缓存强制**：菜单/字典/地区/组织配置必须走带 `@Cacheable` 的 Service，严禁直查 DB；写操作通过 `@CacheEvictMulti` 清缓存。
- **AI 助手**：平台唯一主 Agent（Hermes），所有端共用 `POST /api/ai/chat/stream|send`；子 Agent 对前端不可见，由主 Agent 路由。会员与员工是两个独立代理通道（`AiChatProxyController` / `EmployeeAiController`），勿混用。
- **改实现 → 立即删原代码**：禁止注释保留、`@Deprecated` 继续用、双路径并行。

> 认证/多租户/组织隔离/操作日志/AI/MCP 的完整细节见 `docs/architecture.md`。

---

## 测试与验证

项目**无自动化测试**（无单元/集成测试），也无 ESLint/TypeScript 检查。所有验证依赖手动检查：

- **编译验证**：`mvn -f backend/pom.xml compile -o`（完整命令见「快速命令」）
- **后端 API**：`curl` 测试端点，查看日志 `tail -f /tmp/ops-backend.log`
- **前端 H5**：`npm run build:h5` → 部署生产 → 微信开发者工具打开 `https://boss.jdzn99.com/ops/`（这是唯一的前端静态检查，能发现编译期错误，但不做 lint/类型检查）
- **前端管理页**：平台主站 `https://boss.jdzn99.com` 加载 `/ops/` 路由组件（非微前端，是 componentMap 直接加载）
- **微信 JSSDK**：必须在微信环境中测试，普通浏览器不可用（详见 `docs/testing.md`）

---

## 生产部署摘要（强制）

- **始终本地构建，上传远程**。后端：`cd backend && JAVA_HOME=/home/jason/jdk17 /home/jason/apache-maven-3.9.9/bin/mvn clean package -DskipTests -o`。
- **两套前端独立**：`~/ops-platform-project/frontend/` → `/data2/boss-platform/html/ops/`（ops H5）；`~/ai-native-platform/frontend/src/views/ops/` → `/data2/boss-platform/html/platform/`（主站管理页）。
- **新增 `/ops/xxx` 管理页必须加 host nginx 白名单**（`/data2/nginx/conf/conf.d/boss.conf`），否则刷新落入 Ops H5。校验 title：主站=「协同平台」，H5=「金地美家」。
- **前端部署先清 assets**：`rm -rf assets/*` 再上传，否则旧 chunk 残留导致新功能不生效。
- **只换 JAR 时用 overlay 构建**（`FROM boss-platform/ops:latest` + `COPY app.jar`），构建前 retag 旧镜像供回滚。

> Docker 参数、compose、迁移顺序、数据库备份与完整发布流程见 `docs/deployment.md`。

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
| **增值业务物理删除** | `ops_business_data` 的 `batchDelete()` 用原始 JDBC 物理删除，不走 `@TableLogic`（无逻辑删除路径）；守卫：选中含已上报集团行即整体拒绝（BusinessException，计数按可见 corp 限定） |
| **5 个实体未继承 BaseEntity** | `SysImportTask`、`SysExportTask`、`AiChatTask`、`OpsActivityReviewCost`、`OpsGridManagerCommunity` 自行声明审计字段；其余 37 个均继承。其中 `OpsActivityReviewCost` 无 `delete_flag`，`costMapper.delete()` 走物理删除 |
| **数据库环境隔离** | 生产同实例并置 `mall_db`（业务）与 `ai_platform`（底座）两库，极易混淆。**部署时必须确认 `SPRING_DATASOURCE_URL` 指向 `mall_db`**（历史事故：误用测试库 17,745 条数据写错库） |
| **首页配置 corpId** | `/api/ops/home-data` 公开接口（无 JWT），必须传 `?corpId=` 参数 |
| **Kafka 条件加载** | Consumer 仅在 `spring.kafka.enabled=true` 时加载，无 Kafka 环境自动降级为同步调用 |
| **AiChatProxyController vs EmployeeAiController** | 会员和员工是两个独立 AI 通道，勿混用。员工通道自动注入员工身份上下文 |
| **SseEmitterManager** | SSE 连接持有限时（默认 120s），超时自动关闭。前端需处理重连 |
| **CaptchaService 单实例** | 验证码存储在内存 `ConcurrentHashMap` 中，仅适用于单实例部署。多实例需替换为 Redis |
| **双 Token 隔离** | 会员 token（`ops_token`）和员工 token（`ops_employee_token`）是独立的存储 key。`request.js` 按 `token_type`（employee/member）选择发送哪个，未标记时员工优先；两个 token 不能混用——员工 token 不能访问会员接口，反之亦然；401 时清除两者。员工手工录入等敏感接口另用 `JwtTokenInspector` 拒绝 `token_type=sso` 的会员令牌（会员 `sub=sys_member.id` 可能与 `sys_user.id` 撞号越权） |
| **ops-platform vs 平台主站** | 两套前端详见 `docs/deployment.md` 与上方「生产部署摘要」。主站 `/ops/xxx` 是 `componentMap` 直接加载 Vue 组件（非微前端），改 H5 不影响主站 |
| **Kafka 消费者组冲突** | 同一 group 内分区独占。多容器共享 `ai-platform-group` 时旧容器抢分区但连错库 → 任务僵死 PENDING、新容器收不到消息。**部署新容器前必须 `docker stop/rm` 旧容器**，部署后 `docker ps` 确认无残留 |
| **旧容器/代码不留存** | **铁律**：新容器部署后旧容器必须立即清理（`docker stop + rm`）。旧代码、旧容器、旧配置不留存——git history 可随时恢复 |
| **作战地图跨库查询** | `BattleMapController.summary()` 和 `region` 解析方法直读 `ai_platform.sys_corp` 和 `ai_platform.sys_region`（跨库）。这是仅有的例外——组织区域信息无法通过 API 获取。维护时注意表结构变更 |
| **作战地图数据源仅生产** | 作战地图源表（`ops_business_data`/`ops_gas_user`/`ops_member_ledger`/`ops_grid_manager`）仅在**生产库**有数据，ETL 只能在生产环境跑。参见 `[[battle-map-data-only-on-prod]]` |
| **新增 /ops/xxx 管理页未加 nginx 白名单** | host nginx 逐路由白名单未列入的新管理页会被 catch-all 送到 Ops H5，刷新后看到公众号移动端。步骤见 `docs/deployment.md` |
| **company-select 未注册路由** | `frontend/src/pages/employee/company-select.vue` 文件存在但**未在 pages.json 中注册路由**。该页面通过其他方式引用（如 `uni.navigateTo` 直接跳转），不要误删 |
| **mysql 客户端默认 latin1** | `docker exec mysql mysql` 不指定字符集时 client/connection/results=latin1。含中文 SQL 必须加 `--default-character-set=utf8mb4`：否则写操作双重编码损坏数据、读操作显示乱码（数据无损）。判别：`HEX()` 看字节，`C3A7E280A1`=损坏 |
| **生产 MySQL 8.0.46 + 表间 COLLATE 不一致** | 生产 MySQL 实为 8.0.46，不支持 `ADD COLUMN IF NOT EXISTS` → 迁移 DDL 用 information_schema 条件化 PREPARE；`ops_business_data`=unicode_ci vs `ops_gas_user`/`ops_value_added_product`=0900_ai_ci，跨表 JOIN 须双侧显式 COLLATE，型号比对须 `COLLATE utf8mb4_bin` |
| **空可见组织集合 ≠ 超管** | `DataScopeService.getVisibleCorpIds()` 对无 `corp_id` claim 的令牌返回**空集合**（超管返回 `null`）。新增查询/写接口必须区分：`null`=超管全量，`empty`=无任何权限；禁止只用 `visible != null && !visible.isEmpty()` 判断后跳过 corp 过滤——空集合会退化为全量查询，造成跨租户读/删。主子表拆分代码已统一用 `noVisibleCorp()` / `requireCorpContext()` 守卫 |

## UI/UX 质量规范（强制）

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

最后更新：2026-11-16（CLAUDE.md 拆分为核心规则 + docs/ 细节文档） | 变更历史已迁移至 [docs/CHANGELOG.md](docs/CHANGELOG.md)，新变更请按时间倒序追加到该文件

## 自定义 Claude 命令

- **`/pr`** — 基于 git diff 生成结构化 PR 描述（标题/动机/模块级变更/影响范围/测试建议/风险提示）。定义见 `.claude/commands/pr.md`
- **`/loop`** — 严格开发闭环：plan → implement → verify（编译）→ code-review → PR → 结束，非极特殊情况不中断。定义见 `.claude/commands/loop.md`
