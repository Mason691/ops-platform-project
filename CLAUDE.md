# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **工程文件索引**  
> 编码规范 + ADR → `MEMORY.md`（⚠️ 第四节合规状态表为 2026-06-22 改前快照，已全部过时，以本文件为准）| 用户画像 → `user.md` | 产品理念 → `soul.md` | 功能规格 → `Specs/`

---

## 快速命令

```bash
# 后端编译（离线模式，本地 JDK 17）
JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn -f backend/pom.xml clean package -DskipTests -o

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
cd backend && JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package -DskipTests -o
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

## 目录结构

```
ops-platform-project/
├── backend/
│   ├── src/main/java/com/aip/ops/
│   │   ├── OpsApplication.java
│   │   ├── config/          # SecurityConfig, AuthConfig, RestTemplateConfig, WebMvcConfig, MybatisPlusConfig, JacksonConfig
│   │   ├── controller/      # 21 controllers（见下方）
│   │   ├── entity/          # 26 entities（全部继承 SDK BaseEntity，SysImportTask 例外）
│   │   ├── mapper/          # 27 MyBatis-Plus mappers
│   │   ├── service/         # 22 service interfaces + 16 impls
│   │   ├── client/          # 子系统间 HTTP 客户端（MemberSyncClient, ProductBatchClient）
│   │   ├── agent/           # AI DomainAgent（OpsAgent.java）
│   │   ├── tool/            # MCP 工具（OpsOrderQueryTool, OpsEmployeeBusinessDataTool）
│   │   ├── export/          # 通用异步导出策略（ExportHandler 接口 + 策略模式）
│   │   ├── imports/         # 通用异步导入策略（ImportHandler 接口 + 策略模式）
│   │   ├── kafka/           # Kafka — 3 consumers, 1 producer, 3 message DTOs（条件加载）
│   │   ├── dto/             # 数据传输对象
│   │   └── util/            # 工具类（ImageUrlUtil 等）
│   └── pom.xml
├── frontend/
│   ├── src/main.js          # uni-app 入口
│   ├── src/manifest.json    # uni-app 配置（H5 base 路径等）
│   ├── src/pages/           # uni-app 页面（30 files，16 个目录）
│   ├── src/components/      # 跨页面复用组件（ImageCropperModal, LiveStatusCard）
│   ├── src/api/index.js     # 集中 API 注册（66+ 个方法）
│   ├── src/utils/           # request.js, image.js, config.js, jssdk.js, auth-bridge.js
│   ├── src/config.js        # BASE_URL + DEFAULT_CORP_ID
│   ├── src/static/          # 静态资源（jweixin-1.6.0.js 等）
│   ├── index.html           # 入口 HTML（含内联微信 JSSDK 初始化脚本）
│   ├── vite.config.js       # base 由 VITE_BASE 控制 (prod: /ops/), proxy /api → 8084
│   └── package.json
├── sql/                     # init.sql + 迁移脚本
├── Specs/                   # 功能规格文档
├── MEMORY.md                # 编码规范（强制）+ ADR
├── soul.md                  # 产品理念与设计哲学
├── user.md                  # 用户画像
└── uploads/                 # 文件上传输出目录
```

### Controllers（21 个）

| Controller | 端点前缀 | 说明 |
|---|---|---|
| `PlatformController` | `/api/ops/product`, `/api/ops/activity`, `/api/ops/order`, `/api/ops/points`, `/api/ops/coupon`, `/api/ops/live-rooms`, `/api/ops/user` | 核心 C 端接口 |
| `AdminController` | `/api/ops/admin` | 管理后台：活动/商品/直播室 CRUD、预约查询、首页统计、商家绑定 |
| `AuthController` | `/api/ops/auth` | 会员登录/注册 |
| `EmployeeAuthController` | `/api/ops/auth/employee` | **员工** 登录（手机号+密码+验证码），独立于会员认证 |
| `AiChatProxyController` | `/api/ops/ai` | **会员** AI 客服代理（chat + voice + config + profile） |
| `EmployeeAiController` | `/api/ops/ai/employee` | **员工** AI 助手代理（chat + async + SSE stream + config + profile） |
| `EmployeeController` | `/api/ops/employee` | 员工身份校验 + 个人业务数据查询 |
| `MemberProfileProxyController` | `/api/ops/member` | 会员信息代理到底座 |
| `HomeController` | `/api/ops/home-data` | 公开首页数据（banner/视频/推荐） |
| `HomeConfigController` | `/api/ops/admin/home-configs` | 首页配置 CRUD + 批量排序 |
| `FileUploadController` | `/api/ops/files` | 图片/视频上传 |
| `PublicAccountController` | `/api/public-account` | 公众号配置查询 + JSSDK 签名 |
| `PartnerController` | `/api/ops/partner` | 城市合伙人管理（代理底座 internal API） |
| `StationController` | `/api/ops/station` | H5 端站长申请/状态/信息 |
| `AdminStationController` | `/api/ops/admin/station` | 管理后台站长审批/列表/统计 |
| `BusinessDataController` | `/api/ops/admin/business-data` | 增值业务：Excel 导入/提成计算/异步导出/模板下载 |
| `CommissionRuleController` | `/api/ops/admin/commission` | 个人/公司提成规则 CRUD |
| `ValueAddedProductController` | `/api/ops/admin/value-added/product` | 增值产品 CRUD |
| `MemberController` | `/api/ops/admin/members` | 会员管理：分页查询/详情/启用禁用 |
| `ExportTaskController` | `/api/ops/admin/export` | 通用异步导出：提交/状态轮询/下载/历史 |
| `PermissionController` | `/api/ops/admin/permission` | 权限概要查询 |

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

**Async AI Chat 基础设施**（`kafka/AiChatTaskProducer`, `kafka/AiChatTaskConsumer`, `service/AiChatTaskService`, `service/SseEmitterManager`）：
```
前端 POST /api/ops/ai/employee/chat/async
  → AiChatTaskProducer.publishTask() → Kafka topic "ai-chat-task"
  → AiChatTaskConsumer → 调用底座 AI → 更新 ai_chat_task 表
  → 前端 SSE GET /chat/{taskId}/stream → SseEmitterManager 推送结果
```
- Kafka 不可用时自动降级为同步调用
- 表 `ai_chat_task`（由 `migrate_ai_chat_task.sql` 创建）

### MCP 工具 & Agent

- `OpsOrderQueryTool`：`@McpTool`，工具名 `ops_query_order`，按订单号/状态查询，多租户安全过滤
- `OpsEmployeeBusinessDataTool`：`@McpTool`，工具名 `ops_query_employee_business_data`，查询员工增值业务数据（月份/品类/提成），限定当前员工手机号
- `OpsAgent`：`@AgentComponent`，名称 `"OPS"`，处理业务综合问答（订单/商品统计）

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
- **API 集中管理**：`frontend/src/api/index.js` 导出 `api` 对象（66+ 方法），统一使用 `request` 实例
- **H5 hash 路由**：实际 base 由构建时 `VITE_BASE` 环境变量控制

### 前端页面路由（pages.json）

入口页：`pages/ai/chat`（AI 优先）。5 个底部 Tab：

| Tab | 页面 | 说明 |
|-----|------|------|
| AI助手 | `pages/ai/chat` | AI 对话（语音输入、TTS）— **入口页** |
| 商品 | `pages/product/index`, `pages/product/detail` | 积分商城 |
| 首页 | `pages/index/index` | 轮播/视频/推荐/直播/优惠券 |
| 活动 | `pages/activity/index`, `pages/activity/detail`, `pages/live/index` | 活动 + 直播广场 |
| 我的 | `pages/mine/index`, `pages/order/*`, `pages/address/*`, `pages/points/log`, `pages/coupon/list`, `pages/auth/*`, `pages/booking/list` | 用户中心 |

管理页面（不在 Tab 中）：`pages/admin/activity-form`, `pages/admin/home-config`, `pages/admin/live-room-manage`  
站长页面：`pages/station/apply`, `pages/station/center`  
员工页面：`pages/employee/login`（员工登录）、`pages/employee/ai-chat`（员工 AI 助手 H5）、`pages/employee/dashboard`、`pages/employee/company-select`、`pages/employee/business-data`

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
| `app.resource.base-url` | 静态资源域名 | `https://boss.jdzn99.com` |
| `spring.kafka.enabled` | Kafka 开关 | `true`（生产）/ `false`（测试） |

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
cd backend && JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package -DskipTests -o
# JAR → /data2/boss-platform/ops-backend/app.jar
# docker build -t boss-platform/ops:latest ./ops-backend/
# docker compose up -d ops-backend

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

---

## 已知陷阱

| 陷阱 | 说明 |
|------|------|
| **服务器 JDK** | 测试服务器默认 JDK 8，运行 JAR 必须用 `/home/jason/jdk17/bin/java`；生产 Docker 用 `eclipse-temurin:17-jre` |
| **Maven offline** | 必须 `-o`（离线模式），本地仓库 `/home/jason/m2_local` |
| **生产 base 路径** | 生产 base 是 `/ops/`（`.env.production` `VITE_BASE=/ops/`），manifest.json 已同步为 `/ops/` |
| **H5 图片 URL** | uni-app `<image>` 渲染为 CSS `background-image`，根相对路径被解析到 `/ops/` → 必须用完整 HTTPS 路径 |
| **文件上传目录** | `app.upload.base-dir` 用绝对路径配置，不是 `user.dir`，否则 JAR 从不同目录启动时文件错位 |
| **ASR multipart** | 转发 ASR 请求时**不要手动设置 `Content-Type`**（不含 boundary 导致 422），让 Spring 自动生成 |
| **前端部署清理** | 每次部署前端必须 `rm -rf assets/*` 再上传，否则旧 chunk 残留导致新功能不生效 |
| **jweixin-1.6.0.js** | 必须用 `npm run build:h5`（含 `cp static/jweixin-1.6.0.js dist/build/h5/`），不能用 `npx uni build` |
| **BaseEntity 字段命名** | SDK 使用 `modifyTime`（不是 `updateTime`） |
| **增值业务物理删除** | `ops_business_data` 的 `batchDelete()` 用原始 JDBC 物理删除，不走 `@TableLogic` |
| **SysImportTask 未继承 BaseEntity** | 自行声明所有审计字段，是唯一例外 |
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

---

最后更新：2026-07-05 | 负责人：架构师（员工认证已重构为委托平台 sys_user）

### 自定义 Claude 命令

项目 `.claude/commands/` 目录提供两个自定义命令：

| 命令 | 说明 |
|------|------|
| `/pr` | 基于当前 git diff 生成结构化 PR 描述（标题/动机/变更/影响/测试/风险） |
| `/loop` | 严格开发闭环：plan → implement → verify → code-review → PR 描述，自动循环不中断 |
