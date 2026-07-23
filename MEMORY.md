# MEMORY.md — 团队共同记忆

> 本文件记录所有**强制性编码规范**、**架构决策记录（ADR）** 和**术语表**。

---

## 一、编码规范（强制）

### 1.1 函数大小
- **每个方法不超过 40 行**，超出必须拆分
- 组件不超过 300 行
- 卫语句优先，嵌套深度不超过 3 层

### 1.2 注释规范
- **只写 WHY，不写 WHAT**：代码本身说明做什么，注释解释为什么这样做
- 关键业务逻辑必须有注释说明意图

### 1.3 命名规范
- Java: `com.aip.ops.*`
- 表前缀: `ops_`（运营平台业务表），`biz_`（跨子系统共享业务表），`sys_`（系统级配置表）
- 前端路由: `/ops/`（生产环境 hash 模式）

---

## 二、架构决策记录 (ADR)

### ADR-001: 平台定位
**决策**: 聚合展示引流平台，不提供交易功能，所有购买跳转第三方小程序。  
**原因**: 货源在合作商家小程序完成，本平台仅做积分消耗和导流。  

### ADR-002: 积分预扣 + 回调退还
**决策**: 用户兑换商品时预扣积分，第三方支付成功后回调确认，失败/超时退还。  
**原因**: 防止用户在本平台扣积分后不完成支付。

### ADR-003: 共用 mall_db
**决策**: 复用 mall_db 数据库，表前缀 ops_ 区分运营平台业务。  
**原因**: 底座平台已管理 mall_db，避免多数据源维护成本。

### ADR-004: 多公众号配置独立表
**决策**: `sys_public_account_config` 独立存储多公众号配置，支持组织级别的默认公众号标记。  
**原因**: 不同合作商家可能有不同公众号，需要动态切换。

---

## 三、环境数据库配置（ADR-006）

**决策**：生产环境与测试环境数据库严格隔离，禁止交叉使用。

| 环境 | 业务库 | 底座平台库 | 外部数据源 |
|------|--------|-----------|-----------|
| **生产** `113.250.60.41` | `mall_db` | `ai_platform` | `parking-db.mysql.polardb.rds.aliyuncs.com/jinyuan_info` |
| **测试** `104.238.165.57` | `mall_db_test` | `ai_platform_test` | 同上（共享外部源） |

**规则**：
- 生产容器启动必须用 `SPRING_DATASOURCE_URL=.../mall_db`，禁用 `mall_db_test`
- `.env` 中 `DB_NAME_MALL=mall_db`（生产），`mall_db_test`（测试）
- 禁止在任何环境配置中交叉混用
- 修改数据库配置需确认目标环境

**原因**：2026-07-23 发现生产环境 `docker run` 命令错误使用了 `mall_db_test`，导致 17,745 条导入数据全部写入测试库，生产库为空。同一 MySQL 实例同时存在 `mall_db` 和 `mall_db_test` 两个库，极易混淆。

---

## 四、术语表

| 术语 | 说明 |
|---|---|
| ops-platform | 运营平台（Operations Platform） |
| 聚合展示 | 聚合多家合作商家的商品/活动进行展示 |
| 引流 | 引导用户跳转到第三方小程序完成购买 |
| 积分 | 燃气缴费充值赠送的虚拟货币，用于兑换商品 |
| 预扣 | 用户确认兑换时先扣积分，支付成功后才正式消耗 |

---

最后更新：2026-07-05
- [Build & Deploy Workflow](build-deploy-workflow.md) — always build locally, deploy to remote, never compile on server

---

## 四、底座架构合规 (ADR-005, 2026-06-22)

> 依据：`ai-native-platform/specs/subsystem-architecture-standard.md`
> 
> **⚠️ 以下合规状态表为 2026-06-22 改前快照，仅保留作历史记录。**
> **当前实际状态（2026-06-25）：所有 O1~O9 已修复。详见 `CLAUDE.md`「底座架构合规」章节。**

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-06-22 | 所有业务实体必须继承 `com.aip.sdk.entity.BaseEntity` | 统一审计字段、逻辑删除等 |
| 2026-06-22 | 地区、字典等基础数据必须通过底座平台 API 获取 | 避免数据不一致 |
| 2026-06-22 | 鉴权必须复用底座 `PlatformAuthenticationFilter`，JWT 由底座 SSO 签发 | 统一安全策略 |
| 2026-06-22 | 禁止跨库查询 `ai_platform.*` 和 `mall_db.*` 表 | 统一通过底座 API 代理 |
| 2026-06-22 | 核心业务能力注册为 `@McpTool` 和 `@AgentComponent` | AI 助手集成 |

### 合规状态（2026-06-22 快照 — 已全部修复）

| 检查项 | 状态 | 修复后状态 |
|--------|------|------|
| 实体继承 BaseEntity | ~~❌ 12%~~ | ✅ 100%（全部 18 个实体） |
| 认证（平台 SSO） | ~~❌~~ | ✅ UserServiceImpl 通过 PlatformInternalClient 调用底座 SSO |
| 数据访问（无跨库查询） | ~~❌~~ | ✅ ProductBatchClient + PlatformMerchantService 替代跨库 JOIN |
| 统一响应 R<T> | ✅ | 使用 `com.aip.sdk.result.R` |
| 统一异常 BusinessException | ✅ | 使用 SDK 的 `BusinessException`（通过 SdkGlobalExceptionHandler） |
| SDK 依赖 | ✅ | `ai-platform-sdk:1.0.0-SNAPSHOT` |
| Auth Filter | ✅ | 注入 `PlatformAuthenticationFilter` |
| MCP 集成 | ~~❌~~ | ✅ OpsOrderQueryTool (@McpTool) + OpsAgent (@AgentComponent) |
| 多租户 corp_id | ~~❌~~ | ✅ 所有实体含 corpId 字段 |

### 待整改清单（2026-06-22 快照 — 已全部修复）

| # | 偏离项 | 当前状态 |
|---|--------|------|
| O1 | 15 个实体未继承 BaseEntity | ✅ 全部 18 个实体已继承 |
| O2 | UserServiceImpl 自签 JWT | ✅ 已改为调用底座 SSO |
| O3 | MerchantMapper 跨库查 ai_platform.sys_corp | ✅ MerchantMapper 已删除，PlatformMerchantService 替代 |
| O4 | GsProductMapper 跨库 JOIN mall_product (7处) | ✅ 重构为 ProductBatchClient |
| O5 | OpsActivityProductMapper 跨库 JOIN mall_product | ✅ SQL 仅 JOIN 本地表 |
| O6 | 无 MCP 工具和 DomainAgent | ✅ OpsOrderQueryTool + OpsAgent 已创建 |
| O7 | 6 张表缺 corp_id 列 | ✅ 已迁移 |
| O8 | JWT secret 硬编码 | ✅ 不再自签 JWT（secret 仅用于验证） |
| O9 | 前端 auth-bridge.js 无 SSO 验证 | ✅ isTrustedOrigin() 检查已实现 |
