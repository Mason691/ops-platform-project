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
JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package -DskipTests -o
java -jar backend/target/ops-platform-1.0.0-SNAPSHOT.jar

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

# 后端部署
cd backend && JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package -DskipTests -o
scp target/ops-platform-1.0.0-SNAPSHOT.jar jason@104.238.165.57:~/ops-platform-project/backend/target/
ssh jason@104.238.165.57 "cd ~/ops-platform-project && kill \$(lsof -ti:8084) 2>/dev/null; sleep 1; \
  nohup java -jar backend/target/ops-platform-1.0.0-SNAPSHOT.jar > backend/app.log 2>&1 &"
```

**Nginx 路由**（`/etc/nginx/conf.d/a.15891589.xyz.conf`）：
- `/subsystem/ops/` → 前端静态文件（`dist/build/h5/`）
- `/api/ops/**` → 后端 8084
- `/uploads/ops/**` → 文件上传目录

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
│   │   ├── controller/      # 6 controllers
│   │   │   ├── PlatformController.java      # 核心：商品/活动/订单/积分/优惠券/直播室
│   │   │   ├── AdminController.java         # 管理后台：活动/商品 CRUD、预约查询、首页统计
│   │   │   ├── AuthController.java          # 登录/注册
│   │   │   ├── PublicAccountController.java  # 公众号配置查询
│   │   │   ├── FileUploadController.java    # /api/ops/files/upload — 图片/视频上传
│   │   │   └── AiChatProxyController.java  # AI 客服代理
│   │   ├── entity/          # 17 entities
│   │   ├── mapper/          # 18 MyBatis-Plus mappers
│   │   └── service/         # 5 service interfaces + 4 impls（含 ActivityReminderService）
│   └── pom.xml
├── frontend/
│   ├── src/pages/           # 14 page modules（index/activity/product/mine/order/address/points/coupon/booking/live/ai/auth）
│   ├── src/components/      # 共享组件（ImageCropperModal 等）
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

**SDK 透传路径**（无需 JWT，由底座内部 Token 保护）：`/actuator/**`, `/api/public-account/**`, `/api/ops/auth/**`, `/api/ops/product/**`, `/api/ops/activity/**`

---

## 关键后端特性

### 文件上传（FileUploadController）
- 路径：`POST /api/ops/files/upload`（JWT）
- 图片：jpg/png/gif/webp/bmp，≤2MB
- 视频：mp4/mov/avi/webm，≤50MB
- 自动 H.265→H.264 转码（ffmpeg）+ 首帧截图作为封面
- 存储：`uploads/ops/img/{yyyyMMdd}/` 和 `uploads/ops/video/{yyyyMMdd}/`
- 返回：`{url, type, size, videoCoverUrl}`

### 活动系统
- 预约提醒（`ActivityReminderService`）：活动开始前 24h/1h 通过公众号发送提醒
- 活动关联商品（`OpsActivityProduct`）：多对多关系
- 直播室（`OpsLiveRoom`）：直播预约功能

### 积分体系
- 预扣机制（ADR-002）：兑换时预扣积分，支付成功回调确认，失败/超时退还
- 积分流水表 `GsPointsLog`

---

## 前端环境变量（.env）

| 变量 | 说明 | 值 |
|---|---|---|
| `VITE_OPS_ENTRY` | 运营平台入口地址 | `https://a.15891589.xyz` (prod) / `http://localhost:3035` (dev) |
| `VITE_CORP_ID` | 默认组织 ID | `4` (prod) |

**API 协议**：
- H5 使用相对路径（`/api`），Nginx 反代统一 HTTPS，避免混合内容
- 小程序使用完整 URL（`https://a.15891589.xyz/api`），微信强制 HTTPS
- 本地 dev：Vite proxy 将 `/api` 转发到 `localhost:8084`

---

最后更新：2026-06-21 | 负责人：架构师
