# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **工程文件索引**  
> 编码规范 + ADR → `MEMORY.md` | 用户画像 → `user.md` | 产品理念 → `soul.md` | 功能规格 → `Specs/`

---

## 项目定位

金地燃气聚合展示引流平台，作为独立业务子系统运行，后端端口 8084。
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
- 对外地址：`https://a.15891589.xyz`
- 数据库：`mall_db @ 104.238.165.57`（root / aB740602）
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

# 前端 H5 生产构建
cd frontend && npm run build:h5        # 输出 dist/build/h5/
# 生产 base 为 /subsystem/ops/（由 vite.config.js mode=production 控制）

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

**Nginx 路由**（`/etc/nginx/conf.d/a.15891589.xyz.conf`）：
- `/subsystem/ops/` → 前端静态文件（`dist/build/h5/`）
- `/api/ops/**` → 后端 8084
- `/uploads/ops/**` → alias 到 `/home/jason/ops-platform-project/uploads/ops/`（Nginx 直接读磁盘）
- `/uploads/ops/mall/` → symlink 到 `/home/jason/uploads/`（mall 商品图片）
- **⚠️ 修改 Nginx 配置需要 sudo**，开发用户 jason 无 sudo 权限

**验证部署**：
```bash
curl -sI https://a.15891589.xyz/subsystem/ops/          # 前端
curl -s https://a.15891589.xyz/api/ops/product/page?pageNum=1&pageSize=5  # 后端
```

---

## 包名与命名约定

- Java：`com.aip.ops.*`
- 表前缀：`gs_`（燃气平台业务表）
- 前端路由 base：`/subsystem/ops/`（生产）/ `/`（本地 dev）

---

## 目录结构

```
ops-platform-project/
├── backend/
│   ├── src/main/java/com/aip/ops/
│   │   ├── OpsApplication.java
│   │   ├── config/          # SecurityConfig, AuthConfig, JacksonConfig, RestTemplateConfig
│   │   ├── controller/      # 8 controllers
│   │   │   ├── PlatformController.java      # 核心：商品/活动/订单/积分/优惠券/直播室
│   │   │   ├── AdminController.java         # 管理后台：活动/商品 CRUD、预约查询、首页统计
│   │   │   ├── AuthController.java          # 登录/注册
│   │   │   ├── PublicAccountController.java  # 公众号配置查询
│   │   │   ├── FileUploadController.java    # /api/ops/files/upload — 图片/视频上传
│   │   │   ├── AiChatProxyController.java  # AI 客服代理
│   │   │   ├── HomeController.java          # 公开首页数据（banner/视频/推荐）
│   │   │   └── HomeConfigController.java    # 首页配置 CRUD + 批量排序
│   │   ├── entity/          # 18 entities（全部继承 SDK BaseEntity）
│   │   ├── mapper/          # 18 MyBatis-Plus mappers
│   │   ├── service/         # 6 service interfaces + 5 impls
│   │   ├── client/          # 子系统间 HTTP 客户端
│   │   │   ├── MemberSyncClient.java        # 会员同步到底座平台
│   │   │   └── ProductBatchClient.java      # mall-service 批量商品查询
│   │   ├── agent/           # AI DomainAgent
│   │   │   └── OpsAgent.java                # @AgentComponent，路由 AI 助手业务问答
│   │   └── tool/            # MCP 工具
│   │       └── OpsOrderQueryTool.java       # @McpTool，按条件查询订单
│   └── pom.xml
├── frontend/
│   ├── src/pages/           # page modules（含 admin/activity-form 活动编辑器）
│   ├── src/pages/admin/     # 管理后台页面（活动编辑器，内联 CropperJS）
│   ├── src/components/      # 共享组件（ImageCropperModal.vue 为旧版，已被 admin/activity-form 内联替代）
│   ├── static/              # 静态资源（jweixin-1.6.0.js 等）
│   ├── vite.config.js       # base: /subsystem/ops/ (prod), proxy /api → 8084 (dev)
│   ├── .env                 # VITE_OPS_ENTRY + VITE_CORP_ID
│   └── package.json
├── sql/init.sql             # 10 tables + seed data
├── Specs/                   # 功能规格文档
├── MEMORY.md                # 编码规范（强制）+ ADR
└── uploads/                 # 文件上传输出（ops/img/, ops/video/），Nginx 直接读取
```

---

## 核心接口

| 接口前缀 | 认证 | 说明 |
|---|---|---|
| `/api/ops/auth/**` | 无需 | 用户登录/注册 |
| `/api/ops/product/**` | 无需 | 商品浏览 |
| `/api/ops/activity/**` | 无需 | 活动浏览 |
| `/api/ops/files/**` | JWT | 文件上传（图片 ≤2MB, 视频 ≤50MB） |
| `/api/public-account/**` | 无需 | 公众号配置查询 |
| `/api/ops/order/**` | JWT | 积分兑换订单 |
| `/api/ops/user/**` | JWT | 用户信息 |
| `/api/ops/address/**` | JWT | 收货地址 CRUD |
| `/api/ops/points/**` | JWT | 积分流水 |
| `/api/ops/coupon/**` | JWT | 优惠券 |
| `/api/ops/home-data` | 无需 | 首页公开数据（banner/视频/推荐），支持 `?corpId=` |

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

### 首页数据流
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
- 预约提醒（`ActivityReminderService`）：活动开始前 24h/1h 通过公众号发送提醒
- 活动关联商品（`OpsActivityProduct`）：多对多关系
- 直播室（`OpsLiveRoom`）：直播预约功能

### 积分体系
- 预扣机制（ADR-002）：兑换时预扣积分，支付成功回调确认，失败/超时退还
- 积分流水表 `GsPointsLog`

### 底座架构合规（ADR-005，2026-06-22）

> 详细合规清单见 `MEMORY.md` 第四节。以下记录已落地的关键模式。

**实体继承 BaseEntity**：所有 entity 继承 `com.aip.sdk.entity.BaseEntity`（统一审计字段 `createTime`/`modifyTime`/`createUser`/`modifyUser`/`deleteFlag` 等）。
- 必须加 `@EqualsAndHashCode(callSuper = true)`
- 不要手动声明 `createTime`/`modifyTime`/`updateTime` 字段
- `corpId` 字段已添加到所有业务表（迁移脚本：`sql/ops_baseentity_migration.sql`）

**认证（平台 SSO）**：`UserServiceImpl` 不再自签 JWT。改为通过 `PlatformInternalClient` 调用 `POST /api/auth/sso/token`，
传入 `identifierType=PHONE`、`identifier`、`nickname`、`avatar`、`subsystem="ops-platform"`、`corpId`。
- `MemberSyncClient`：注册/登录时同步会员到底座平台（`POST /api/internal/member/register-or-get`），非致命操作
- `jwtSecret` 配置和 `nimbus-jose-jwt` 依赖已移除

**MCP + Agent 集成**：
- `tool/OpsOrderQueryTool.java`：`@McpTool`，工具名 `ops_query_order`，按订单号/状态查询，多租户安全过滤
- `agent/OpsAgent.java`：`@AgentComponent`，名称 `"OPS"`，处理业务综合问答（订单/商品统计）

**子系统间通信**（替代跨库 JOIN）：
- `client/ProductBatchClient.java`：调用 mall-service `POST /api/internal/product/batch-query`（配置 `mall-service.base-url`），替代 `GsProductMapper` 的 7 处跨库 JOIN
- `service/PlatformMerchantService.java`：通过 `PlatformInternalClient` 查询组织信息，替代 `MerchantMapper` 跨库查 `ai_platform.sys_corp`
- 认证方式：`X-Internal-Token` 请求头（平台共享密钥）

---

## 前端环境变量（.env）

| 变量 | 说明 | 值 |
|---|---|---|
| `VITE_OPS_ENTRY` | 运营平台入口地址 | `https://a.15891589.xyz` (prod) / `http://localhost:3035` (dev) |
| `VITE_CORP_ID` | 默认组织 ID | `4` (prod) |

**API 协议**：
- H5 同样使用完整 HTTPS URL（`https://a.15891589.xyz/api`）、小程序亦同
- 本地 dev：Vite proxy 将 `/api` 转发到 `localhost:8084`

**⚠️ H5 图片 URL 必须用完整 HTTPS 路径**（`config.js` 中 `BASE_URL = 'https://a.15891589.xyz'`）：
uni-app 的 `<image>` 组件在 H5 中渲染为 `<div style="background-image: url(...)">`。
CSS `url()` 把根相对路径（如 `/uploads/ops/img/xxx.jpg`）解析到页面 base
`/subsystem/ops/`，导致实际请求 `/subsystem/ops/uploads/...` → 404。
因此在 H5 模式下必须返回完整 `https://a.15891589.xyz/uploads/...` 绝对 URL。
前端 `utils/image.js` 的 `assetUrl()` 通过 `BASE_URL + path` 拼接完整 URL，
其中 `BASE_URL` 从 `VITE_OPS_ENTRY` 环境变量读取。

---

## 已知陷阱

| 陷阱 | 说明 |
|------|------|
| **服务器 JDK** | 默认 JDK 8，运行 ops-platform JAR 必须用 `/home/jason/jdk17/bin/java` |
| **Maven offline** | 必须 `-o`（离线模式），本地仓库 `/home/jason/m2_local` |
| **uni-app H5 image** | `<image>` 渲染为 CSS `background-image`，CSS `url()` 把根相对路径解析到页面 base `/subsystem/ops/` → 图片 URL 必须用完整 HTTPS 路径 |
| **Nginx sudo** | jason 用户无 sudo 权限，修改 Nginx 配置需要联系管理员 |
| **MySQL** | 在 Docker 容器中，通过 `docker exec $(docker ps -q \| head -1) mysql ...` 访问 |
| **文件上传目录** | 后端 `app.upload.base-dir` 配置为绝对路径；如果使用了 `user.dir`，文件会落到 `backend/uploads/`，Nginx 无法访问 |
| **商品图片来源** | `ops_product` 只是映射表，实际商品数据在 `mall_product` 表 |
| **活动封面** | `ops_activity` 同时有 `cover_image` 和 `cover_url` 两个字段，部分老数据 `cover_url` 可能为 NULL，后端 `PlatformController` 已做回退处理 |
| **Nginx 缓存** | `/subsystem/ops/assets/` 30 天 immutable 缓存，部署后如果文件没变（相同 hash）则不更新；`index.html` 有 `no-cache` 头 |
| **首页配置 corpId** | `/api/ops/home-data` 是公开接口（无 JWT），必须传 `?corpId=` 参数；否则回退 corpId=3，看不到 corp_id=4 的数据 |
| **mall-service 连通性** | `ProductBatchClient` 依赖 mall-service（`mall-service.base-url`，默认 localhost:8083）；mall-service 不可用时批量商品查询失败 |
| **BaseEntity 字段命名** | SDK `BaseEntity` 使用 `modifyTime`（不是 `updateTime`）；手动设置审计字段时注意命名一致 |

---

最后更新：2026-06-22 | 负责人：架构师
