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
- 表前缀: `gs_`
- 前端路由: `/ops/`

---

## 二、架构决策记录 (ADR)

### ADR-001: 平台定位
**决策**: 聚合展示引流平台，不提供交易功能，所有购买跳转第三方小程序。  
**原因**: 货源在合作商家小程序完成，本平台仅做积分消耗和导流。  

### ADR-002: 积分预扣 + 回调退还
**决策**: 用户兑换商品时预扣积分，第三方支付成功后回调确认，失败/超时退还。  
**原因**: 防止用户在本平台扣积分后不完成支付。

### ADR-003: 共用 mall_db
**决策**: 复用 mall_db 数据库，表前缀 gs_ 区分燃气平台业务。  
**原因**: 底座平台已管理 mall_db，避免多数据源维护成本。

### ADR-004: 多公众号配置独立表
**决策**: `sys_public_account_config` 独立存储多公众号配置，支持组织级别的默认公众号标记。  
**原因**: 不同合作商家可能有不同公众号，需要动态切换。

---

## 三、术语表

| 术语 | 说明 |
|---|---|
| ops-platform | 运营平台（Operations Platform） |
| 聚合展示 | 聚合多家合作商家的商品/活动进行展示 |
| 引流 | 引导用户跳转到第三方小程序完成购买 |
| 积分 | 燃气缴费充值赠送的虚拟货币，用于兑换商品 |
| 预扣 | 用户确认兑换时先扣积分，支付成功后才正式消耗 |

---

最后更新：2026-06-10
- [Build & Deploy Workflow](build-deploy-workflow.md) — always build locally, deploy to remote, never compile on server

---

## 四、底座架构合规 (ADR-005, 2026-06-22)

> 依据：`ai-native-platform/specs/subsystem-architecture-standard.md`

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-06-22 | 所有业务实体必须继承 `com.aip.sdk.entity.BaseEntity` | 统一审计字段、逻辑删除等 |
| 2026-06-22 | 地区、字典等基础数据必须通过底座平台 API 获取 | 避免数据不一致 |
| 2026-06-22 | 鉴权必须复用底座 `PlatformAuthenticationFilter`，JWT 由底座 SSO 签发 | 统一安全策略 |
| 2026-06-22 | 禁止跨库查询 `ai_platform.*` 和 `mall_db.*` 表 | 统一通过底座 API 代理 |
| 2026-06-22 | 核心业务能力注册为 `@McpTool` 和 `@AgentComponent` | AI 助手集成 |

### 合规状态

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 实体继承 BaseEntity | ❌ 12% | 仅 OpsMember、PublicAccountConfig 合规，15 个待整改 |
| 认证（平台 SSO） | ❌ | UserServiceImpl 自签 JWT，需改为调用 POST /api/auth/sso/token |
| 数据访问（无跨库查询） | ❌ | MerchantMapper 跨库查 ai_platform，GsProductMapper 跨库 JOIN mall_product |
| 统一响应 R<T> | ✅ | 使用 `com.aip.sdk.result.R` |
| 统一异常 BusinessException | ✅ | 使用 SDK 的 `BusinessException`（通过 SdkGlobalExceptionHandler） |
| SDK 依赖 | ✅ | `ai-platform-sdk:1.0.0-SNAPSHOT` |
| Auth Filter | ✅ | 注入 `PlatformAuthenticationFilter`（但签发 JWT 时未走平台 SSO） |
| MCP 集成 | ❌ | 无 @McpTool 或 @AgentComponent |
| 多租户 corp_id | ❌ | 6 张表缺 corp_id 列 |

### 待整改清单

| # | 偏离项 | 严重度 | 文件 |
|---|--------|--------|------|
| O1 | 15 个实体未继承 BaseEntity | 高 | entity/*.java |
| O2 | UserServiceImpl 自签 JWT (generateJwt) | 严重 | service/impl/UserServiceImpl.java |
| O3 | MerchantMapper 跨库查 ai_platform.sys_corp | 严重 | mapper/MerchantMapper.java |
| O4 | GsProductMapper 跨库 JOIN mall_product (7处) | 严重 | mapper/GsProductMapper.java |
| O5 | OpsActivityProductMapper 跨库 JOIN mall_product | 严重 | mapper/OpsActivityProductMapper.java |
| O6 | 无 MCP 工具和 DomainAgent | 中 | 需新建 tool/ 和 agent/ 包 |
| O7 | 6 张表缺 corp_id 列 | 高 | DB migration 待执行 |
| O8 | JWT secret 硬编码 | 中 | application.yml |
| O9 | 前端 auth-bridge.js 无 SSO 验证 | 中 | frontend/src/utils/auth-bridge.js |
