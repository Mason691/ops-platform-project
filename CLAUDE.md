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

**环境参数**：
- 远程服务器：`104.238.165.57`（jason / aB740602）
- 后端端口：8084
- 对外地址：`https://boss.jdzn99.com`
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

**原则**：始终本地构建，SCP 上传到远程。服务器内存不足，不要远程编译。

```bash
# H5 前端部署
cd frontend && npm run build:h5
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null jason@104.238.165.57 \
  "mkdir -p /home/jason/ops-platform-project/frontend/dist/build/h5"
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -r dist/build/h5/* jason@104.238.165.57:/home/jason/ops-platform-project/frontend/dist/build/h5/

# 后端部署（注意：服务器默认 JDK 8，必须用 JDK 17 绝对路径）
cd backend && JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package -DskipTests -o
scp target/ops-platform-1.0.0-SNAPSHOT.jar jason@104.238.165.57:~/ops-platform-project/backend/target/
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null jason@104.238.165.57 "kill \$(lsof -ti:8084) 2>/dev/null; sleep 1; \
  nohup /home/jason/jdk17/bin/java -jar /home/jason/ops-platform-project/backend/target/ops-platform-1.0.0-SNAPSHOT.jar > /tmp/ops.log 2>&1 &"
```

**生产环境路由**：
- H5 前端 base：`/aa/ops/`（`.env.production` 中 `VITE_BASE=/aa/ops/`，`manifest.json` hash mode base `/aa/ops/`）
- Nginx 反代：`/api/ops/**` → 后端 8084
- 静态文件：`/uploads/ops/**` → Nginx alias 到 `/home/jason/ops-platform-project/uploads/ops/`
- 商品图片：`/uploads/ops/mall/` → symlink 到 `/home/jason/uploads/`
- **⚠️ 修改 Nginx 配置需要 sudo**，开发用户 jason 无 sudo 权限

**验证部署**：
```bash
curl -sI https://boss.jdzn99.com/aa/ops/          # 前端
curl -s https://boss.jdzn99.com/api/ops/product/page?pageNum=1&pageSize=5  # 后端
```

---

## 包名与命名约定

- Java：`com.aip.ops.*`
- 表前缀：`ops_`（运营平台业务表），`biz_`（跨子系统共享业务表），`sys_`（系统级配置表）
- 前端路由 base：`/aa/ops/`（生产 hash 模式）

---

## 目录结构

```
ops-platform-project/
├── backend/
│   ├── src/main/java/com/aip/ops/
│   │   ├── OpsApplication.java
│   │   ├── config/          # SecurityConfig, AuthConfig, JacksonConfig, RestTemplateConfig
│   │   ├── controller/      # 11 controllers
│   │   │   ├── PlatformController.java      # 核心：商品/活动/订单/积分/优惠券/直播室/用户
│   │   │   ├── AdminController.java         # 管理后台：活动/商品 CRUD、预约查询、首页统计、商家绑定
│   │   │   ├── AuthController.java          # 登录/注册
│   │   │   ├── PublicAccountController.java  # 公众号配置查询
│   │   │   ├── FileUploadController.java    # /api/ops/files/upload — 图片/视频上传
│   │   │   ├── AiChatProxyController.java  # AI 客服代理（chat + voice + config）
│   │   │   ├── HomeController.java          # 公开首页数据（banner/视频/推荐）
│   │   │   ├── HomeConfigController.java    # 首页配置 CRUD + 批量排序
│   │   │   ├── PartnerController.java       # 城市合伙人管理（代理底座 internal API）
│   │   │   ├── StationController.java       # H5 端站长申请/状态/信息（代理底座 internal API）
│   │   │   └── AdminStationController.java  # 管理后台站长审批/列表/统计（代理底座 internal API）
│   │   ├── entity/          # 18 entities（全部继承 SDK BaseEntity，100% 合规）
│   │   ├── mapper/          # 18 MyBatis-Plus mappers（均继承 BaseMapper，5 个有自定义 SQL）
│   │   ├── service/         # 5 service interfaces + 5 impls + 2 concrete services
│   │   │   ├── impl/UserServiceImpl.java         # 通过 PlatformInternalClient 调用底座 SSO
│   │   │   ├── impl/OpsProductServiceImpl.java   # 商品管理 + ProductBatchClient 批量查询
│   │   │   ├── impl/OpsActivityServiceImpl.java  # 活动管理
│   │   │   ├── impl/HomeConfigServiceImpl.java   # 首页配置 + 公开数据
│   │   │   ├── impl/PublicAccountConfigServiceImpl.java
│   │   │   ├── PlatformMerchantService.java      # 通过 PlatformInternalClient 查组织信息
│   │   │   └── ActivityReminderService.java      # @Scheduled 预约提醒（5 分钟轮询）
│   │   ├── client/          # 子系统间 HTTP 客户端
│   │   │   ├── MemberSyncClient.java        # 会员同步到底座平台
│   │   │   └── ProductBatchClient.java      # mall-service 批量商品查询
│   │   ├── agent/           # AI DomainAgent
│   │   │   └── OpsAgent.java                # @AgentComponent，路由 AI 助手业务问答
│   │   └── tool/            # MCP 工具
│   │       └── OpsOrderQueryTool.java       # @McpTool，按条件查询订单
│   └── pom.xml
├── frontend/
│   ├── src/pages/           # 22 page files，10 个目录（见下方"前端页面路由"）
│   ├── src/components/      # 共享组件（ImageCropperModal.vue 已被 activity-form 内联替代）
│   ├── src/api/index.js     # 集中 API 注册（~45 个方法）
│   ├── src/utils/           # request.js, image.js, config.js, jssdk.js, auth-bridge.js
│   ├── src/config.js        # BASE_URL + DEFAULT_CORP_ID
│   ├── src/App.vue          # Minimal: onLaunch/onShow + 全局 CSS
│   ├── src/main.js          # createSSRApp + initAuthBridge() + initJssdk()
│   ├── src/pages.json       # uni-app 路由 + 5-tab 导航栏配置
│   ├── src/manifest.json    # H5 hash mode base /aa/ops/
│   ├── static/              # 静态资源（jweixin-1.6.0.js 等）
│   ├── index.html           # 入口 HTML（含内联微信 JSSDK 初始化脚本）
│   ├── vite.config.js       # base 由 VITE_BASE 控制 (prod: /aa/ops/), proxy /api → 8084
│   ├── .env                 # VITE_OPS_ENTRY + VITE_CORP_ID
│   ├── .env.development     # VITE_OPS_ENTRY=http://localhost:3035
│   ├── .env.production      # VITE_BASE=/aa/ops/ VITE_OPS_ENTRY=https://boss.jdzn99.com
│   └── package.json
├── sql/init.sql             # 18 tables + seed data
├── sql/                     # 14 migration SQL files
├── Specs/                   # 功能规格文档
├── MEMORY.md                # 编码规范（强制）+ ADR（⚠️ 合规状态表已过期）
├── soul.md                  # 产品理念与设计哲学
├── user.md                  # 用户画像
├── docs/                    # 文档
└── uploads/                 # 文件上传输出（ops/img/, ops/video/），Nginx 直接读取
```

### 前端页面路由（pages.json）

入口页：`pages/ai/chat`（AI 优先设计）。22 个页面，5 个底部 Tab：

| Tab | 页面 | 说明 |
|-----|------|------|
| AI助手 | `pages/ai/chat` | AI 对话（语音输入、TTS）— **入口页** |
| 商品 | `pages/product/index`, `pages/product/detail` | 积分商城 |
| 首页 | `pages/index/index` | 轮播/视频/推荐/直播/优惠券 |
| 活动 | `pages/activity/index`, `pages/activity/detail`, `pages/live/index` | 活动 + 直播广场 |
| 我的 | `pages/mine/index`, `pages/order/*`, `pages/address/*`, `pages/points/log`, `pages/coupon/list`, `pages/auth/*`, `pages/booking/list` | 用户中心 |

管理页面（不在 Tab 中）：`pages/admin/activity-form`, `pages/admin/home-config`  
站长页面（不在 Tab 中）：`pages/station/apply`, `pages/station/center`

**前端架构特点**：
- **无 Pinia/Vuex**：不使用集中式状态管理，用 `uni.getStorageSync()` 持久化 token/user 信息，页面用 `ref()` 管理本地状态
- **无 Vue Router**：uni-app 文件路由（`pages.json` 配置），页面间通过 `uni.navigateTo()` + URL 参数传参
- **iframe SSO**：H5 端通过 `auth-bridge.js` 监听父窗口 `postMessage`（`AIP_AUTH` / `AIP_NAVIGATE`），接收底座平台传递的 token/corpId/userId；信任同源和 `boss.jdzn99.com`
- **微信 JSSDK**：`index.html` 内联初始化脚本（不在 Vue 生命周期中），`static/jweixin-1.6.0.js` 构建时复制到 dist
- **H5 hash 路由**：manifest.json 配置 `hash` 模式 + base `/aa/ops/`

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
| `/api/ops/admin/**` | JWT | 管理后台（活动/商品/首页配置/站长审批） |
| `/api/ops/station/**` | JWT | 站长申请/状态/信息（代理底座 internal API） |

**AiChatProxyController 端点详情**（`/api/ops/ai`）：
- `POST /chat` — 转发 AI 对话到底座 `POST /api/ai/chat/send`（需 JWT）
- `GET /config` — AI 配置（无需认证，公开）
- `GET /profile` — 当前用户 AI 身份
- `POST /voice/recognize` — 语音识别（blob 上传）
- `POST /voice/recognize-wechat` — 微信语音识别
- `POST /voice/synthesize` — TTS 语音合成

**SDK 透传路径**（无需 JWT，由底座内部 Token 保护）：`/actuator/**`, `/api/public-account/**`, `/api/ops/auth/**`, `/api/ops/product/**`, `/api/ops/activity/**`

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

### 底座架构合规（ADR-005，2026-06-22）

> MEMORY.md 第四节合规状态表为**改前快照（12% 合规）**，已全部过时。当前合规状态以本文件为准。

**实体继承 BaseEntity**：全部 18 个 entity 继承 `com.aip.sdk.entity.BaseEntity`（✅ 100% 合规）。
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
| `app.upload.base-dir` | 文件上传根目录（绝对路径） | `/home/jason/ops-platform-project/uploads` |
| `app.resource.base-url` | 静态资源对外域名（拼接图片 URL） | `https://boss.jdzn99.com` |
| `ops.remind.hours-before` | 活动预约提醒提前小时数 | `24,1` |

## 前端环境变量

| 文件 | 变量 | 值 |
|------|------|-----|
| `.env` (共享) | `VITE_OPS_ENTRY` | `https://boss.jdzn99.com` |
| `.env` (共享) | `VITE_CORP_ID` | `4` |
| `.env.development` | `VITE_OPS_ENTRY` | `http://localhost:3035` |
| `.env.production` | `VITE_BASE` | `/aa/ops/` |
| `.env.production` | `VITE_OPS_ENTRY` | `https://boss.jdzn99.com` |

**API 协议**：
- H5 和小程序均使用完整 HTTPS URL（`https://boss.jdzn99.com/api`）
- 本地 dev：Vite proxy 将 `/api` 和 `/uploads` 转发到 `localhost:8084`

**⚠️ H5 图片 URL 必须用完整 HTTPS 路径**（`config.js` 中 `BASE_URL = VITE_OPS_ENTRY`）：
uni-app 的 `<image>` 组件在 H5 中渲染为 `<div style="background-image: url(...)">`。
CSS `url()` 把根相对路径（如 `/uploads/ops/img/xxx.jpg`）解析到页面 base
`/aa/ops/`，导致实际请求 `/aa/ops/uploads/...` → 404。
因此在 H5 模式下必须返回完整 `https://boss.jdzn99.com/uploads/...` 绝对 URL。
前端 `utils/image.js` 的 `assetUrl()` 通过 `BASE_URL + path` 拼接完整 URL，
其中 `BASE_URL` 从 `VITE_OPS_ENTRY` 环境变量读取。

---

## 已知陷阱

| 陷阱 | 说明 |
|------|------|
| **服务器 JDK** | 默认 JDK 8，运行 ops-platform JAR 必须用 `/home/jason/jdk17/bin/java` |
| **Maven offline** | 必须 `-o`（离线模式），本地仓库 `/home/jason/m2_local` |
| **生产 base 路径** | 生产 base 是 `/aa/ops/`（`.env.production` 的 `VITE_BASE`），不是 vite.config.js 中的默认 `/subsystem/ops/` |
| **uni-app H5 image** | `<image>` 渲染为 CSS `background-image`，CSS `url()` 把根相对路径解析到页面 base `/aa/ops/` → 图片 URL 必须用完整 HTTPS 路径 |
| **Nginx sudo** | jason 用户无 sudo 权限，修改 Nginx 配置需要联系管理员 |
| **MySQL** | 在 Docker 容器中，通过 `docker exec $(docker ps -q \| head -1) mysql ...` 访问 |
| **文件上传目录** | 后端 `app.upload.base-dir` 配置为绝对路径；如果使用了 `user.dir`，文件会落到 `backend/uploads/`，Nginx 无法访问 |
| **商品图片来源** | `ops_product` 只是映射表，实际商品数据在 `mall_product` 表 |
| **活动封面** | `ops_activity` 同时有 `cover_image` 和 `cover_url` 两个字段，部分老数据 `cover_url` 可能为 NULL，后端 `PlatformController` 已做回退处理 |
| **Nginx 缓存** | `/aa/ops/assets/` 30 天 immutable 缓存，部署后如果文件没变（相同 hash）则不更新；`index.html` 有 `no-cache` 头 |
| **首页配置 corpId** | `/api/ops/home-data` 是公开接口（无 JWT），必须传 `?corpId=` 参数；否则回退 corpId=3，看不到 corp_id=4 的数据 |
| **mall-service 连通性** | `ProductBatchClient` 依赖 mall-service（`mall-service.base-url`，默认 localhost:8083）；mall-service 不可用时批量商品查询失败 |
| **BaseEntity 字段命名** | SDK `BaseEntity` 使用 `modifyTime`（不是 `updateTime`）；手动设置审计字段时注意命名一致 |
| **nimbus-jose-jwt** | 依赖仍在 pom.xml 中但 JWT 自签已移除（改用平台 SSO），可考虑清理 |
| **无自动化测试** | 项目当前无单元测试或集成测试；所有验证依赖手动检查和生产监控 |
| **MEMORY.md 过期** | MEMORY.md 第四节合规表为改前快照（12% 实体合规、自签 JWT、跨库 JOIN 等），这些问题已全部修复。当前合规状态以本文件为准 |
| **ImageCropperModal.vue** | 旧版组件（946 行），已被 `activity-form.vue` 内联 CropperJS 替代，可考虑删除 |
| **无 Pinia/Vuex** | 前端不使用集中式状态管理，token/userId/permissions 通过 `uni.getStorageSync()` 存取 |

---

最后更新：2026-06-25 | 负责人：架构师
