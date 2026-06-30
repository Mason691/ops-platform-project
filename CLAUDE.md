# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **工程文件索引**  
> 编码规范 + ADR → `MEMORY.md`（⚠️ 合规表已过期，以本文件为准）| 用户画像 → `user.md` | 产品理念 → `soul.md` | 功能规格 → `Specs/`

---

## 项目定位

运营平台，作为独立业务子系统运行，后端端口 8084。
**核心定位**：聚合展示 · 引流 · 积分消耗。**不提供交易功能**，所有商品购买跳转第三方小程序。

依赖 `ai-platform-sdk` 接入底座的安全、多租户、MCP、Agent 等能力。

---

## 技术栈

| 层次 | 技术 |
|---|---|
| 后端 | Spring Boot 3.2.5 · MyBatis-Plus 3.5.7 · MySQL 8.0 |
| Java | 17（Eclipse Temurin 17.0.9） |
| 前端 | uni-app（编译目标 H5 + mp-weixin） |
| 底座 SDK | `com.aip:ai-platform-sdk:1.0.0-SNAPSHOT`（JDK 17，本地仓库 `/home/jason/m2_local`） |

**服务器**：

| 环境 | IP | 用户/密码 | 域名 | 部署方式 |
|------|-----|---------|------|---------|
| 测试 | `104.238.165.57` | jason / aB740602 | `a.15891589.xyz` | Docker + host nginx |
| 生产 | `113.250.60.41` | root / `j_ly[4=2Gh1?9]` | `boss.jdzn99.com` | Docker compose（`/data2/boss-platform/`） |

- 后端端口：8084（测试）/ 8082（生产 Docker，`network_mode: host`）
- 数据库：`mall_db @ 104.238.165.57`（root / aB740602，Docker 容器中）
- Maven 本地仓库：`/home/jason/m2_local`

---

## 启动与构建

```bash
# 后端（本地构建，JAR 上传到远程运行）
# 本地 JDK 在 /home/jason/jdk17/，但服务器默认 JDK 8，运行 JAR 必须指定完整路径
JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package -DskipTests -o
# 本地测试（JDK 17 已在 PATH）：
java -jar backend/target/ops-platform-1.0.0-SNAPSHOT.jar
# 远程运行（服务器 JDK 8 是默认，必须用绝对路径）：
# /home/jason/jdk17/bin/java -jar backend/target/ops-platform-1.0.0-SNAPSHOT.jar

# 前端 H5 dev server（端口 3035，Vite proxy /api → localhost:8084）
cd frontend && npm run dev:h5

# 前端 H5 生产构建（base 路径由 .env.production 的 VITE_BASE 控制）
cd frontend && npm run build:h5        # 输出 dist/build/h5/
# 构建后复制 jweixin-1.6.0.js 到 dist（package.json scripts 已包含此步骤）

# 前端小程序构建
cd frontend && npm run build:mp-weixin  # 输出 dist/build/mp-weixin/
```

---

## 部署

### 本地 → 生产（Docker 模式）

生产服务器 `113.250.60.41` 上通过 Docker compose 运行，部署目录 `/data2/boss-platform/`。

**原则**：始终本地构建，上传到远程。不要在生产服务器上编译（节省资源）。

```bash
# 1. 后端构建 + 部署
cd backend && JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package -DskipTests -o

# 上传 JAR 并重建容器（通过 Python paramiko 脚本，见 deploy-to-prod 模式）
# JAR → /data2/boss-platform/ops-backend/app.jar
# 然后: docker build -t boss-platform/ops:latest ./ops-backend/
#       docker compose up -d ops-backend

# 2. 前端构建 + 部署
cd frontend && npm run build:h5   # 输出 dist/build/h5/（含 jweixin-1.6.0.js + static/ 图标）
# 上传到 /data2/boss-platform/html/ops/
```

**生产 Docker 关键参数**（来自 `docker-compose.yml`）：
- ops-backend: `SERVER_PORT=8082`, `network_mode: host`
- Volume: `/data2/boss-platform/uploads:/app/uploads`
- `PLATFORM_BASE_URL=http://localhost:8080`（通过 host 网络直接访问 platform-backend）
- `MALL_SERVICE_BASE_URL=http://localhost:8083`

### 测试环境部署

```bash
# H5 前端部署
cd frontend && npm run build:h5
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null jason@104.238.165.57 \
  "mkdir -p /home/jason/aa-platform/html/aa/ops"
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -r dist/build/h5/* jason@104.238.165.57:/home/jason/aa-platform/html/aa/ops/

# 后端（测试环境 JAR 直接运行，非 Docker）
cd backend && JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package -DskipTests -o
scp target/ops-platform-1.0.0-SNAPSHOT.jar jason@104.238.165.57:~/ops-platform-project/backend/target/
ssh jason@104.238.165.57 "kill \$(lsof -ti:8084) 2>/dev/null; sleep 1; \
  nohup /home/jason/jdk17/bin/java -jar ~/ops-platform-project/backend/target/ops-platform-1.0.0-SNAPSHOT.jar > /tmp/ops.log 2>&1 &"
```

**生产环境路由**：
- H5 前端 base：`/ops/`（`.env.production` 中 `VITE_BASE=/ops/`，`vite.config.js` 生产默认 `/ops/`）
- 注意：`manifest.json` H5 base 仍为 `/aa/ops/`，实际由构建时的 `VITE_BASE` 环境变量覆盖
- Nginx（生产 `boss.conf`）：`/ops/` → `boss-ops-nginx:80`，`/api/ops/**` → `ops-backend:8082`
- 静态文件：`/uploads/ops/**` → Docker volume 挂载 `/data2/boss-platform/uploads` → ops-backend 的 `WebMvcConfig` 从磁盘读取
- 商品图片：`/uploads/ops/mall/` — 生产环境为真实目录（测试环境为 symlink）

**验证部署**：
```bash
curl -sI https://boss.jdzn99.com/ops/          # 前端
curl -s https://boss.jdzn99.com/api/ops/product/page?pageNum=1&pageSize=5  # 后端
```

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
│   │   ├── config/          # SecurityConfig, AuthConfig, JacksonConfig, RestTemplateConfig, WebMvcConfig, MybatisPlusConfig
│   │   ├── controller/      # 15 controllers
│   │   │   ├── PlatformController.java      # 核心：商品/活动/订单/积分/优惠券/直播室/用户
│   │   │   ├── AdminController.java         # 管理后台：活动/商品/直播室 CRUD、预约查询、首页统计、商家绑定
│   │   │   ├── AuthController.java          # 登录/注册
│   │   │   ├── PublicAccountController.java  # 公众号配置查询
│   │   │   ├── FileUploadController.java    # /api/ops/files/upload — 图片/视频上传
│   │   │   ├── AiChatProxyController.java  # AI 客服代理（chat + voice + config）
│   │   │   ├── HomeController.java          # 公开首页数据（banner/视频/推荐）
│   │   │   ├── HomeConfigController.java    # 首页配置 CRUD + 批量排序
│   │   │   ├── PartnerController.java       # 城市合伙人管理（代理底座 internal API）
│   │   │   ├── StationController.java       # H5 端站长申请/状态/信息（代理底座 internal API）
│   │   │   ├── AdminStationController.java  # 管理后台站长审批/列表/统计（代理底座 internal API）
│   │   │   ├── BusinessDataController.java  # 增值业务：Excel 导入/提成计算/异步导出
│   │   │   ├── CommissionRuleController.java # 增值业务：个人/公司提成规则 CRUD
│   │   │   ├── ValueAddedProductController.java # 增值业务：增值产品 CRUD
│   │   │   └── PermissionController.java    # 权限概要查询（corpId/orgRoleType/canEdit）
│   │   ├── entity/          # 23 entities（全部继承 SDK BaseEntity，100% 合规）
│   │   ├── mapper/          # 23 MyBatis-Plus mappers（均继承 BaseMapper）
│   │   ├── service/         # 12 service interfaces + 8 impls + 3 concrete services
│   │   │   ├── impl/UserServiceImpl.java         # 通过 PlatformInternalClient 调用底座 SSO
│   │   │   ├── impl/OpsProductServiceImpl.java   # 商品管理 + ProductBatchClient 批量查询
│   │   │   ├── impl/OpsActivityServiceImpl.java  # 活动管理 + 直播室关联 + liveStatus 计算
│   │   │   ├── impl/HomeConfigServiceImpl.java   # 首页配置 + 公开数据
│   │   │   ├── impl/PublicAccountConfigServiceImpl.java
│   │   │   ├── impl/ValueAddedProductServiceImpl.java  # 增值产品 CRUD + 品类字典校验 + org-role 写权限
│   │   │   ├── impl/CommissionRuleServiceImpl.java   # 个人/公司提成规则 CRUD + org-role 写权限
│   │   │   ├── impl/BusinessDataServiceImpl.java     # Excel 导入/提成计算/异步导出 + org-scope 数据隔离
│   │   │   ├── PlatformMerchantService.java      # 通过 PlatformInternalClient 查组织信息
│   │   │   ├── ActivityReminderService.java      # @Scheduled 预约提醒（5 分钟轮询）
│   │   │   ├── DataScopeService.java             # 组织级数据可见范围（超管/运营商/城市合伙人）
│   │   │   ├── OrgRoleService.java               # 组织角色查询（superadmin/operator/partner）
│   │   │   └── OpsExportTaskService.java + impl  # 异步导出任务状态管理
│   │   ├── client/          # 子系统间 HTTP 客户端
│   │   │   ├── MemberSyncClient.java        # 会员同步到底座平台
│   │   │   └── ProductBatchClient.java      # mall-service 批量商品查询
│   │   ├── agent/           # AI DomainAgent
│   │   │   └── OpsAgent.java                # @AgentComponent，路由 AI 助手业务问答
│   │   ├── tool/            # MCP 工具
│   │   │   └── OpsOrderQueryTool.java       # @McpTool，按条件查询订单
│   │   ├── kafka/           # Kafka 消费者
│   │   │   └── OpsExportTaskConsumer.java   # 异步导出任务消费（ops-export-task → ops-export-result）
│   └── pom.xml
├── frontend/
│   ├── src/pages/           # 23 page files，15 个目录（见下方"前端页面路由"）
│   ├── src/components/      # 共享组件（LiveStatusCard.vue 直播状态卡片；ImageCropperModal.vue 已被 activity-form 内联替代）
│   ├── src/api/index.js     # 集中 API 注册（63 个方法）
│   ├── src/utils/           # request.js, image.js, config.js, jssdk.js, auth-bridge.js
│   ├── src/config.js        # BASE_URL + DEFAULT_CORP_ID
│   ├── src/App.vue          # Minimal: onLaunch/onShow + 全局 CSS
│   ├── src/main.js          # createSSRApp + initAuthBridge() + initJssdk()
│   ├── src/pages.json       # uni-app 路由 + 5-tab 导航栏配置
│   ├── src/manifest.json    # H5 hash mode base /aa/ops/
│   ├── static/              # 静态资源（jweixin-1.6.0.js 等）
│   ├── index.html           # 入口 HTML（含内联微信 JSSDK 初始化脚本）
│   ├── vite.config.js       # base 由 VITE_BASE 控制 (prod: /ops/), proxy /api → 8084
│   ├── .env                 # VITE_OPS_ENTRY + VITE_CORP_ID
│   ├── .env.development     # VITE_OPS_ENTRY=http://localhost:3035
│   ├── .env.production      # VITE_BASE=/ops/ VITE_OPS_ENTRY=https://boss.jdzn99.com
│   └── package.json
├── sql/init.sql             # 18 tables + seed data
├── sql/                     # 18 migration SQL files
├── Specs/                   # 功能规格文档
├── MEMORY.md                # 编码规范（强制）+ ADR（⚠️ 合规状态表已过期）
├── soul.md                  # 产品理念与设计哲学
├── user.md                  # 用户画像
├── docs/                    # 文档
└── uploads/                 # 文件上传输出（ops/img/, ops/video/），Nginx 直接读取
```

### 前端页面路由（pages.json）

入口页：`pages/ai/chat`（AI 优先设计）。23 个页面，5 个底部 Tab：

| Tab | 页面 | 说明 |
|-----|------|------|
| AI助手 | `pages/ai/chat` | AI 对话（语音输入、TTS）— **入口页** |
| 商品 | `pages/product/index`, `pages/product/detail` | 积分商城 |
| 首页 | `pages/index/index` | 轮播/视频/推荐/直播/优惠券 |
| 活动 | `pages/activity/index`, `pages/activity/detail`, `pages/live/index` | 活动 + 直播广场 |
| 我的 | `pages/mine/index`, `pages/order/*`, `pages/address/*`, `pages/points/log`, `pages/coupon/list`, `pages/auth/*`, `pages/booking/list` | 用户中心 |

管理页面（不在 Tab 中）：`pages/admin/activity-form`, `pages/admin/home-config`, `pages/admin/live-room-manage`  
站长页面（不在 Tab 中）：`pages/station/apply`, `pages/station/center`

**前端架构特点**：
- **无 Pinia/Vuex**：不使用集中式状态管理，用 `uni.getStorageSync()` 持久化 token/user 信息，页面用 `ref()` 管理本地状态
- **无 Vue Router**：uni-app 文件路由（`pages.json` 配置），页面间通过 `uni.navigateTo()` + URL 参数传参
- **iframe SSO**：H5 端通过 `auth-bridge.js` 监听父窗口 `postMessage`（`AIP_AUTH` / `AIP_NAVIGATE`），接收底座平台传递的 token/corpId/userId；信任同源和 `boss.jdzn99.com`
- **微信 JSSDK**：`index.html` 内联初始化脚本（不在 Vue 生命周期中），`static/jweixin-1.6.0.js` 构建时复制到 dist
- **H5 hash 路由**：manifest.json 配置 `hash` 模式 + base `/aa/ops/`，但实际 base 由构建时 `VITE_BASE` 覆盖为 `/ops/`

---

## 核心接口

| 接口前缀 | 认证 | 说明 |
|---|---|---|
| `/api/ops/auth/**` | 无需 | 用户登录/注册 |
| `/api/ops/product/**` | 无需 | 商品浏览 |
| `/api/ops/activity/**` | 无需 | 活动浏览 |
| `/api/ops/live-rooms/**` | 无需 | 直播室 |
| `/api/ops/home-data` | 无需 | 首页公开数据（banner/视频/推荐），支持 `?corpId=` |
| `/api/ops/files/**` | JWT | 文件上传（图片 ≤2MB, 视频 ≤50MB） |
| `/api/public-account/**` | 无需 | 公众号配置查询 + JSSDK 签名 |
| `/api/ops/order/**` | JWT | 积分兑换订单 |
| `/api/ops/user/**` | JWT | 用户信息 |
| `/api/ops/address/**` | JWT | 收货地址 CRUD |
| `/api/ops/points/**` | JWT | 积分流水 |
| `/api/ops/coupon/**` | JWT | 优惠券 |
| `/api/ops/ai/**` | 混合 | AI 聊天（chat 需 JWT，config 公开） |
| `/api/ops/admin/**` | JWT | 管理后台（活动/商品/首页配置/站长审批/增值业务） |
| `/api/ops/station/**` | JWT | 站长申请/状态/信息（代理底座 internal API） |
| `/api/ops/admin/permission/**` | JWT | 权限概要查询（corpId/orgRoleType/canEdit） |

**AiChatProxyController 端点详情**（`/api/ops/ai`）：
- `POST /chat` — 转发 AI 对话到底座 `POST /api/ai/chat/send`（需 JWT）
- `GET /config` — AI 配置（无需认证，公开）
- `GET /profile` — 当前用户 AI 身份
- `POST /voice/recognize` — 语音识别（blob 上传）
- `POST /voice/recognize-wechat` — 微信语音识别
- `POST /voice/synthesize` — TTS 语音合成

**SDK 透传路径**（无需 JWT，由底座内部 Token 保护）：`/actuator/**`, `/api/public-account/**`, `/api/ops/auth/**`, `/api/ops/product/**`, `/api/ops/activity/**`

**增值业务管理端点**（`/api/ops/admin`，均需 JWT）：
- `ValueAddedProductController`（`/api/ops/admin/value-added/product`）— 增值产品 CRUD
- `CommissionRuleController`（`/api/ops/admin/commission`）— 个人/公司提成规则 CRUD
- `BusinessDataController`（`/api/ops/admin/business-data`）— Excel 导入/提成计算/异步导出

---

## 关键后端特性

### 图片服务架构

图片从数据库到前端渲染的完整链路：

```
DB (相对路径) → Backend ImageUrlUtil → API JSON (相对路径)
  → 前端 assetUrl() 拼接 BASE_URL → 完整 HTTPS URL
  → 浏览器请求 → Nginx /uploads/ops/ → 磁盘文件
```

- **Backend `ImageUrlUtil`**（`util/ImageUrlUtil.java`）：规范化所有图片路径
  - `normalizeImage(path)` — 完整 HTTPS URL → 提取相对路径；占位图域名（`via.placeholder.com` 等）→ `null`
  - `normalizeProductImage(path)` — mall 商品图片提取文件名 → `/uploads/ops/mall/{filename}`
  - `resolveActivityCover(coverUrl, coverImage)` — 回退逻辑：coverUrl 为空时用 coverImage
- **前端 `utils/image.js`**：`assetUrl()` 空值返回占位 SVG；HTTPS/data: 原样；相对路径拼 `BASE_URL + path`
- **Nginx**：`location /uploads/ops/` → alias `/home/jason/ops-platform-project/uploads/ops/`
- **商品图片**：上游 mall_product 表存完整 HTTPS URL，Backend 提取文件名后映射到 `/uploads/ops/mall/`
  该路径通过 symlink `uploads/ops/mall → /home/jason/uploads/` 由 Nginx 读磁盘

### 首页配置管理（Home Config）

- **数据库**：`ops_home_config` 表，按 `operator_id` + `config_type`（banner/video/recommend）隔离
- **Admin API**：`HomeConfigController`（`/api/ops/admin/home-configs/**`）— CRUD + 批量排序
  - `GET /api/ops/admin/activities/available-videos` — 可选活动视频（type='activity' + video_url 非空）
  - `GET /api/ops/admin/products/recommendable` — 可推荐商品（绑定销售商的上架商品）
- **Public API**：`HomeController`（`GET /api/ops/home-data`）— 无需认证，返回 `{banners, videos, recommends}`
  - 支持 `?corpId=` 参数指定运营商
- **Admin 页面**：`pages/admin/home-config.vue`（3 Tab：广告轮播/精彩视频/热门推荐）
  - Banner 上传使用 CropperJS（15:8 宽高比）+ FileUploadController
- **H5 首页**：`pages/index/index.vue` 消费 home-data，动态渲染轮播/视频/推荐

### 文件上传
- 路径：`POST /api/ops/files/upload`（JWT）
- 图片：jpg/png/gif/webp/bmp，≤2MB
- 视频：mp4/mov/avi/webm，≤50MB
- 自动 H.265→H.264 转码（ffmpeg）+ 首帧截图作为封面
- **上传目录通过 `app.upload.base-dir` 配置为绝对路径**（`/home/jason/ops-platform-project/uploads`），
  **不是** `System.getProperty("user.dir")`，否则 JAR 从不同目录启动时文件会落到错误位置

### 管理后台活动编辑器
- 前端页面：`pages/admin/activity-form.vue`，路由注册在 `pages.json`
- 入口：我的 → ✏️ 活动管理（需登录），或直接 URL `#/pages/admin/activity-form`
- 封面裁剪器已**内联**到页面中（非独立组件），包含 CropperJS 800×800 容器、fillZoom 自适应缩放、
  圆形/方形实时预览、缩放/旋转/重置工具栏
- 开发模式自动登录：`ensureLogin()` 用测试账号 `13900000001/admin123` mock 登录
- 测试按钮：未上传封面时可点击"测试图片1/2"跳过文件选择器直接打开裁剪器
- 后端 mock 登录支持：`mockPhone` 字段（`RegisterRequest` / `WechatLoginRequest`），
  `UserServiceImpl.resolvePhone()` 优先读取 mockPhone，跳过微信解密

### 活动系统
- 预约提醒（`ActivityReminderService`）：`@Scheduled(fixedRate = 300_000)`（每 5 分钟），活动开始前 24h/1h 通过公众号发送提醒
- 活动关联商品（`OpsActivityProduct`）：多对多关系
- 直播室（`OpsLiveRoom`）：直播预约功能

### 积分体系
- 预扣机制（ADR-002）：兑换时预扣积分，支付成功回调确认，失败/超时退还
- 积分流水表 `OpsPointsLog`

### 站长系统（Station）
- **H5 端**（`StationController` `/api/ops/station`）：提交申请、查询申请状态、查询站长信息 — 全部代理到底座 `POST /api/internal/station/**`
- **管理后台**（`AdminStationController` `/api/ops/admin/station`）：分页申请列表、审批通过/拒绝、站长列表、启用/停用、统计 — 全部代理到底座
- 前端页面：`pages/station/apply`（申请）、`pages/station/center`（站长中心）
- 认证方式：JWT + `X-Internal-Token` 代理底座

### 组织级数据隔离与权限（Org-level Data Scope）

**三层角色模型**（超管 → 运营商 → 城市合伙人）：

```
超管（corpId=0）          → 查看全部数据，无限制
运营商（orgRoleId≠5）      → 查看自己 + 所有下属城市合伙人的数据，完全 CRUD
城市合伙人（orgRoleId=5）  → 只查自己数据，只读（无写权限）
```

- **`DataScopeService`**：计算当前用户可见的 corpId 集合。超管返回 `null`（查全部），运营商返回 `{self + children}`，合伙人返回 `{self}`。结果缓存 60 秒。
  - 被 `BusinessDataController` 的查询/导出/提成计算使用
  - 被 `OpsExportTaskConsumer`（Kafka 消费者）使用（通过 `getVisibleCorpIdsForCorp()`，Kafka 无 SecurityContext）
- **`OrgRoleService`**：判定当前用户 org 角色类型。供 `PermissionController` 和增值业务模块使用，与 `DataScopeService` 独立缓存。
- **`PermissionController`**（`GET /api/ops/admin/permission/profile`）：返回 `{corpId, orgRoleType, canEditProducts, canEditRules}`，前端用此控制页面编辑按钮的可见性。
- **增值业务写保护**：`ValueAddedProductController`、`CommissionRuleController`、`BusinessDataController` 的写操作均调用 `orgRoleService.isOperatorOrSuperAdmin()` 校验，城市合伙人拒绝写入。
- **安全兜底**：任何异常（底座 API 不可达等）→ 视为运营商（不阻断写操作），但数据 scope 回退到仅自己（安全优先）。

### 异步导出（Kafka）

用户在前端提交导出请求 → `BusinessDataController` 创建 `OpsExportTask` 记录 → `KafkaTemplate.send("ops-export-task", ...)` → `OpsExportTaskConsumer` 消费：

```
Controller → Kafka (ops-export-task) → Consumer → DB 查询 → TSV 写入磁盘
  → Python openpyxl 转 XLSX → 更新 task 状态 → Kafka (ops-export-result) → 前端轮询
```

关键细节：
- **导出目录**：`/app/uploads/exports/`（Docker volume 持久化，宿主机 `/data2/boss-platform/uploads/exports/`）
- **数据隔离**：Consumer 通过 `DataScopeService.getVisibleCorpIdsForCorp(task.corpId)` 获取组织级数据范围
- **文件格式**：优先 XLSX（Python openpyxl 转换），降级 TSV（Python 不可用时）
- **`OpsExportTask`** 表状态：`PENDING` → `PROCESSING` → `DONE` / `FAILED`
- **条件启用**：`@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")`，Kafka 不可用时消费者静默不加载

### 直播室管理（Live Room）

- **数据库**：`ops_live_room` 表（经 `migrate_live_room_ext.sql` 扩展了 `replay_url`, `viewer_count`, `actual_start_time`, `actual_end_time`）
- **Admin 端点**（`AdminController`）：
  - `GET /api/ops/admin/live-rooms` — 分页列表（含筛选）
  - `POST /api/ops/admin/live-rooms` — 创建
  - `PUT /api/ops/admin/live-rooms/{id}` — 编辑
  - `DELETE /api/ops/admin/live-rooms/{id}` — 删除
  - `GET /api/ops/admin/live-rooms/{id}` — 详情（含回放URL/观看人数）
  - `PUT /api/ops/admin/live-rooms/{id}/status` — 手动切换状态（0=预告→1=直播中→2=已结束），自动记录 `actual_start_time`/`actual_end_time`
  - `GET /api/ops/admin/live-rooms/{id}/qrcode` — 返回页面路径+视频号信息（供前端 qrcodejs 渲染）
- **H5 端**：`LiveStatusCard.vue` 组件 — 根据直播状态展示倒计时/观看人数/回放入口
  - 三种状态：预告（倒计时天/时/分/秒）→ 直播中（🔥 观看人数 + 📺 跳转按钮）→ 已结束（回放或暂无）
  - 非微信环境弹引导遮罩（"请使用微信客户端打开"）
  - 跳转通过 `wx.openChannelsLive({ finderUserName })` 打开视频号直播间
- **管理页面**：`pages/admin/live-room-manage.vue` — 直播列表 CRUD + 状态切换 + 二维码

### 活动-直播室关联

- `OpsActivity` 新增 `live_room_id` 字段（关联视频号直播室）+ 瞬态字段 `liveRoomInfo`
- `OpsActivityServiceImpl.getActivityDetail()` 返回 `liveStatus` 结构（`{displayStatus, viewerCount, hasReplay, countdownMs}`），供前端 `LiveStatusCard` 渲染
- 活动列表接口批量填充 `liveRoomInfo`（仅对 `type=live_reserve` 活动），封面图通过 `ImageUrlUtil` 规范化
- Admin 活动详情也返回关联直播室信息

### 底座架构合规（ADR-005，2026-06-22）

> MEMORY.md 第四节合规状态表为**改前快照（12% 合规）**，已全部过时。当前合规状态以本文件为准。

**实体继承 BaseEntity**：全部 23 个 entity 继承 `com.aip.sdk.entity.BaseEntity`（✅ 100% 合规）。
- 必须加 `@EqualsAndHashCode(callSuper = true)`
- 不要手动声明 `createTime`/`modifyTime`/`updateTime` 字段
- `corpId` 字段已添加到所有业务表

**认证（平台 SSO）**：`UserServiceImpl` 不再自签 JWT（✅ 已整改）。
改为通过 `PlatformInternalClient` 调用 `POST /api/auth/sso/token`，
传入 `identifierType=PHONE`、`identifier`、`nickname`、`avatar`、`subsystem="ops-platform"`、`corpId`。
- `MemberSyncClient`：注册/登录时同步会员到底座平台（`POST /api/internal/member/register-or-get`），非致命操作
- `jwtSecret` 配置仍保留在 application.yml 中但不再用于自签

**MCP + Agent 集成**（✅ 已完成）：
- `tool/OpsOrderQueryTool.java`：`@McpTool`，工具名 `ops_query_order`，按订单号/状态查询，多租户安全过滤
- `agent/OpsAgent.java`：`@AgentComponent`，名称 `"OPS"`，处理业务综合问答（订单/商品统计）

**子系统间通信**（替代跨库 JOIN）（✅ 已整改）：
- `client/ProductBatchClient.java`：调用 mall-service `POST /api/internal/product/batch-query`（配置 `mall-service.base-url`），替代了跨库 JOIN mall_product
- `service/PlatformMerchantService.java`：通过 `PlatformInternalClient` 查询组织信息，替代了跨库查 `ai_platform.sys_corp`
- 所有 mapper SQL 仅 JOIN 本地表（`ops_*`, `biz_*`），不再跨库
- 认证方式：`X-Internal-Token` 请求头（平台共享密钥）

### AI 语音代理链路（ASR / TTS）

H5 前端的语音输入（ASR）和语音播报（TTS）通过 ops backend 代理至底座平台：

```
H5 浏览器 (getUserMedia / wx.startRecord)
  → POST /api/ops/ai/voice/recognize  (multipart audio blob)
  → AiChatProxyController → RestTemplate → platform-backend /api/internal/asr/recognize
  → InternalAsrController → resolveVoiceCallConfig(corpId, sys_org_ai_config)
  → 3rd-party ASR API (SiliconFlow FunAudioLLM/SenseVoiceSmall)

TTS 同理：
  → POST /api/ops/ai/voice/synthesize ({ text })
  → AiChatProxyController → platform-backend /api/internal/tts/synthesize
  → InternalTtsController → TtsSynthesizeService → 3rd-party TTS API
  → 返回 data:audio/mpeg;base64,... → new Audio(url).play()
```

**注意事项**：
- ASR/TTS 配置存储在底座 `sys_org_ai_config` 表（按 `corp_id`），API Key AES 加密存储
- 内部调用使用 `X-Internal-Token` 认证（共享密钥 `aip-internal-svc-token-2026`）
- 转发 multipart 请求时**不要手动设置 `Content-Type`**，让 Spring 自动生成带 boundary 的 header，否则三方 ASR 返回 422

---

## 关键后端配置（application.yml）

| 配置键 | 说明 | 默认值 |
|---|---|---|
| `server.port` | 后端端口 | `8084` |
| `spring.datasource.url` | MySQL 连接 | `jdbc:mysql://104.238.165.57:3306/mall_db` |
| `spring.servlet.multipart.max-file-size` | 上传文件大小限制 | `50MB` |
| `platform.base-url` | 底座平台地址 | `http://104.238.165.57:8080` |
| `platform.subsystem-code` | 子系统标识 | `ops-platform` |
| `platform.internal-token` | 子系统间调用认证 Token（X-Internal-Token 头） | `aip-internal-svc-token-2026` |
| `platform.jwt-secret` | 底座 JWT 签名密钥（验证用，不自签） | 32+ 字节 |
| `platform.jwt-expiration` | JWT 过期时间（秒） | `43200`（12h） |
| `platform.merchant-role-name` | 销售商角色名 | `销售商` |
| `mall-service.base-url` | mall 子系统地址（ProductBatchClient 用） | `http://localhost:8083` |
| `app.upload.base-dir` | 文件上传根目录 | `/app/uploads`（Docker 容器内）→ 挂载宿主机 `/data2/boss-platform/uploads` |
| `app.resource.base-url` | 静态资源对外域名（拼接图片 URL） | `https://boss.jdzn99.com` |
| `ops.remind.hours-before` | 活动预约提醒提前小时数 | `24,1` |
| `spring.kafka.enabled` | 是否启用 Kafka 异步导出 | `true`（生产）/ `false`（测试无 Kafka 时） |

## 前端环境变量

| 文件 | 变量 | 值 |
|------|------|-----|
| `.env` (共享) | `VITE_OPS_ENTRY` | `https://boss.jdzn99.com` |
| `.env` (共享) | `VITE_CORP_ID` | `4` |
| `.env.development` | `VITE_OPS_ENTRY` | `http://localhost:3035` |
| `.env.production` | `VITE_BASE` | `/ops/` |
| `.env.production` | `VITE_OPS_ENTRY` | `https://boss.jdzn99.com` |

**API 协议**：
- H5 和小程序均使用完整 HTTPS URL（`https://boss.jdzn99.com/api`）
- 本地 dev：Vite proxy 将 `/api` 和 `/uploads` 转发到 `localhost:8084`

**⚠️ H5 图片 URL 必须用完整 HTTPS 路径**（`config.js` 中 `BASE_URL = VITE_OPS_ENTRY`）：
uni-app 的 `<image>` 组件在 H5 中渲染为 `<div style="background-image: url(...)">`。
CSS `url()` 把根相对路径（如 `/uploads/ops/img/xxx.jpg`）解析到页面 base
`/ops/`，导致实际请求 `/ops/uploads/...` → 404。
因此在 H5 模式下必须返回完整 `https://boss.jdzn99.com/uploads/...` 绝对 URL。
前端 `utils/image.js` 的 `assetUrl()` 通过 `BASE_URL + path` 拼接完整 URL，
其中 `BASE_URL` 从 `VITE_OPS_ENTRY` 环境变量读取。

---

## 已知陷阱

| 陷阱 | 说明 |
|------|------|
| **服务器 JDK** | 测试服务器默认 JDK 8，运行 JAR 必须用 `/home/jason/jdk17/bin/java`；生产 Docker 用 `eclipse-temurin:17-jre` 基础镜像 |
| **Maven offline** | 必须 `-o`（离线模式），本地仓库 `/home/jason/m2_local` |
| **生产 base 路径** | 生产 base 是 `/ops/`（`.env.production` `VITE_BASE=/ops/`，`vite.config.js` 生产默认 `/ops/`） |
| **生产部署** | 生产服务器 `113.250.60.41`（root），Docker compose 管理，JAR 放在 `/data2/boss-platform/ops-backend/app.jar` |
| **WebMvcConfig** | 生产环境通过 Spring Boot 的 `WebMvcConfig.addResourceHandlers` 从磁盘提供 `/uploads/**`，替代 Nginx 直接读磁盘 |
| **uni-app H5 image** | `<image>` 渲染为 CSS `background-image`，CSS `url()` 把根相对路径解析到页面 base `/ops/` → 图片 URL 必须用完整 HTTPS 路径 |
| **manifest.json base** | manifest.json 中 `h5.router.base` 仍为 `/aa/ops/`，实际 base 由构建时 `VITE_BASE` 环境变量覆盖 |
| **文件上传目录** | 生产 Docker：容器内 `/app/uploads`，挂载宿主机 `/data2/boss-platform/uploads`；测试：`/home/jason/ops-platform-project/uploads` |
| **商品图片来源** | `ops_product` 只是映射表，实际商品数据在 `mall_product` 表，通过 `ProductBatchClient` HTTP 调用 mall-service 获取详情 |
| **活动封面** | `ops_activity` 同时有 `cover_image` 和 `cover_url` 两个字段，部分老数据 `cover_url` 可能为 NULL，`ImageUrlUtil.resolveActivityCover()` 已做回退处理 |
| **Nginx 缓存** | `/ops/assets/` 30 天 immutable 缓存；`index.html` 有 `no-cache` 头 |
| **首页配置 corpId** | `/api/ops/home-data` 是公开接口（无 JWT），必须传 `?corpId=` 参数 |
| **mall-service 连通性** | `ProductBatchClient` 依赖 mall-service（生产 `http://localhost:8083`，host 网络模式）；mall-service 不可用时批量商品查询失败 |
| **BaseEntity 字段命名** | SDK `BaseEntity` 使用 `modifyTime`（不是 `updateTime`）；手动设置审计字段时注意命名一致 |
| **jsDomain 来源** | `PublicAccountController.publicConfig()` 从请求 `Host` 头自动获取 jsDomain，不再依赖数据库配置 |
| **ASR multipart** | 转发 ASR 请求时不要手动设置 `Content-Type: multipart/form-data`（不含 boundary 会导致 422），让 Spring 自动生成 |
| **jweixin-1.6.0.js** | 必须用 `npm run build:h5` 而非 `npx uni build`，前者包含 `cp static/jweixin-1.6.0.js dist/build/h5/` 步骤 |
| **nimbus-jose-jwt** | 依赖仍在 pom.xml 中但 JWT 自签已移除（改用平台 SSO），可考虑清理 |
| **MetaObjectHandler** | ops 是独立 Spring Boot 应用，`MybatisPlusConfig.java` 自行注册 `MetaObjectHandler`（审计字段自动填充）。平台 web 模块的 `MybatisPlusConfig` 不会自动扫描到 ops |
| **增值业务物理删除** | `ops_business_data` 的 `batchDelete()` 使用原始 JDBC 物理删除，不走 `@TableLogic`。原因：导入的统计明细纠错时需直接删除。参见 `Specs/增值业务-规格说明.md` |
| **operatorId 废弃** | 增值业务四张表的 `operator_id` 已废弃，新代码使用 `corp_id`。`operator_id` 保留向后兼容 |
| **OpsExportTask 物理字段** | `OpsExportTask` 实体显式声明了 `createTime`（因 SQL 使用 `DEFAULT CURRENT_TIMESTAMP`），需注意与 BaseEntity `createTime` 的重复映射。`finishTime` 不是审计字段（业务时间） |
| **Kafka 条件加载** | `OpsExportTaskConsumer` 仅在 `spring.kafka.enabled=true` 时加载。测试环境无 Kafka → 导出为同步 TSV 文件返回 |
| **DataScopeService 缓存** | `DataScopeService` 和 `OrgRoleService` 均有 60s 本地 Caffeine-like ConcurrentHashMap 缓存。新增组织或变更角色后最多 60s 生效 |
| **城市合伙人只读** | 城市合伙人（orgRoleId=5）在增值模块无写权限。前端通过 `/api/ops/admin/permission/profile` 获取权限后隐藏编辑按钮，后端 `OrgRoleService.isOperatorOrSuperAdmin()` 兜底校验 |
| **无自动化测试** | 项目当前无单元测试或集成测试；所有验证依赖手动检查和生产监控 |
| **无 Pinia/Vuex** | 前端不使用集中式状态管理，token/userId/permissions 通过 `uni.getStorageSync()` 存取 |

---

最后更新：2026-06-30 | 负责人：架构师
