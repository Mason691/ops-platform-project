# ops-platform-project — 金地燃气聚合展示引流平台

> **Harness 工程文件索引**  
> 编码规范 → `MEMORY.md` | 用户画像 → `user.md` | 产品理念 → `soul.md` | 功能规格 → `Specs/`

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
| 底座 SDK | `com.aip:ai-platform-sdk:1.0.0-SNAPSHOT`（JDK 17） |

**环境参数**：
- 远程服务器：`104.238.165.57`（jason / aB740602）
- 后端端口：8084
- 对外地址：`https://a.15891589.xyz`（Nginx → 后端 + 前端静态文件）
- 数据库：`mall_db @ 104.238.165.57`（root / aB740602）
- Maven 本地仓库：`/home/jason/m2_local`
- **启动命令**：`JAVA_HOME=/home/jason/jdk17/jdk-17.0.9+9 mvn clean package && java -jar backend/target/ops-platform-1.0.0-SNAPSHOT.jar`

---

## 包名约定

- Java：`com.aip.ops.*`
- 表前缀：`gs_`（燃气平台业务表）
- 前端路由 base：`/ops/`（H5 模式）

---

## 目录结构

```
ops-platform-project/
├── backend/               # Spring Boot 后端
│   ├── src/main/java/com/aip/ops/
│   │   ├── OpsApplication.java
│   │   ├── config/        # SecurityConfig, RestTemplateConfig
│   │   ├── controller/    # PlatformController, PublicAccountController
│   │   ├── entity/        # 10 entities (Gs* + PublicAccountConfig)
│   │   ├── mapper/        # 10 MyBatis-Plus mappers
│   │   └── service/       # IPublicAccountConfigService + impl
│   └── pom.xml
├── frontend/              # uni-app H5 + 微信小程序前端
│   ├── src/pages/         # 13 pages (首页/活动/商品/我的/订单/地址/积分/优惠券)
│   └── package.json
├── sql/init.sql           # 10 tables + seed data
├── docs/
├── Specs/
├── CLAUDE.md              # 本文件
├── MEMORY.md              # 编码规范 + ADR
├── user.md                # 用户画像
└── soul.md                # 产品理念
```

---

## 核心接口

| 接口前缀 | 认证 | 说明 |
|---|---|---|
| `/api/ops/auth/**` | 无需 | 用户登录 |
| `/api/ops/product/**` | 无需 | 商品浏览 |
| `/api/ops/activity/**` | 无需 | 活动浏览 |
| `/api/public-account/**` | 无需 | 公众号配置查询 |
| `/api/ops/order/**` | JWT | 积分兑换订单 |
| `/api/ops/user/**` | JWT | 用户信息 |
| `/api/ops/address/**` | JWT | 收货地址 CRUD |
| `/api/ops/points/**` | JWT | 积分流水 |
| `/api/ops/coupon/**` | JWT | 优惠券 |

---

## 多公众号配置

表 `sys_public_account_config`，一个组织可配置多个公众号，标记默认。
公开接口 `GET /api/public-account/config?corpId=3` 返回 `{appId, jsDomain, name}`。
管理后台接口：`/api/ops/public-account-config` CRUD。

---

## 环境配置

前端入口地址通过 `.env` 配置：

| 变量 | 说明 | 示例 |
|---|---|---|
| `VITE_OPS_ENTRY` | 运营平台入口地址（供 qiankun 主应用加载） | `https://a.15891589.xyz` |
| `VITE_CORP_ID` | 默认组织 ID | `3` |

**API 协议统一**：
- H5 模式：使用相对路径（`/api`），由 Nginx 反代统一 HTTPS 协议，避免混合内容拦截
- 小程序模式：使用完整 URL（`https://a.15891589.xyz/api`），微信强制 HTTPS
- 本地 dev：Vite proxy 将 `/api` 转发到 `localhost:8084`

## 启动方式

```bash
# 后端
cd backend && mvn clean package -DskipTests && java -jar target/ops-platform-1.0.0-SNAPSHOT.jar

# 前端 (H5)
cd frontend && npm install && npm run build:h5
# 输出: dist/build/h5/，部署到 Nginx /ops/ 路径

# 前端 (小程序)
cd frontend && npm run build:mp-weixin
# 微信开发者工具导入 dist/build/mp-weixin/
```

---

最后更新：2026-06-10 | 负责人：架构师
