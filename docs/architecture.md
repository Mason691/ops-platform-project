# 架构与集成

> 从 `CLAUDE.md` 拆分（2026-11-16）。核心强制规则见 `CLAUDE.md`，本文件保存架构细节与集成方式。

---

## Controllers（35 个）

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
| `BusinessDataController` | `/api/ops/admin/business-data` | 增值业务：两段式异步导入/提成计算/销售统计/异步导出/品类提成汇总/模板下载/状态流转 + 经办人核对 + **财务核对** + 重新匹配会员/按批次重匹配——详见 `docs/business-data.md` |
| `CommissionRuleController` | `/api/ops/admin/commission` | 个人/公司提成规则 CRUD（支持组织层级继承） |
| `ValueAddedProductController` | `/api/ops/admin/value-added/product` | 增值产品 CRUD + 列表按品类/品牌/型号(like)/状态筛选；2026-10-15 整表清空后重建为 95 个型号（万和15/万家乐21/美的18/小松鼠41，id 144-238，`sql/ops_value_added_product_import_20261015*.sql`），同日补入万和壁挂炉 32 个型号（id 239-270，`sql/ops_value_added_product_import_20261015_wanhe_wallboiler.sql`），现共 127 个型号 |
| `ProductPriceController` | `/api/ops/admin/product-price` | **产品售价**：按 SCM 商品维护销售价/零售底价/集团最低价，运营商可维护、城市合伙人只读；SCM 商品搜索 + 品类映射 `PRODUCT_CATEGORY` |
| `AdminPaymentQrController` | `/api/ops/admin/payment-qr` | **收款码配置**：元数据/上传/鉴权取图（运营商维护，取图逐次 SHA-256 校验） |
| `EmployeePaymentQrController` | `/api/ops/employee/business-data/payment-qr` | **员工收款码取图**：员工 JWT 鉴权，按员工组织解析所属运营商，逐次哈希校验 |
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
| `MemberLedgerController` | `/api/ops/admin/member-ledger` | **会员台账**：按平台会员聚合列表（所属公司/网格员列+筛选）+ 关联设备弹窗 + 台账 CRUD + 安检设备创建（异步）+ filter-options 下拉 |
| `CategoryLifecycleRuleController` | `/api/ops/admin/category-lifecycle-rules` | **品类使用年限规则**：CRUD + 品类下拉选项 + 按品类查询 |
| `FfCategoryController` | `/api/ops/admin/ff-category` | **财务分类（财务管理 → 分类管理）**：分页/详情/新增/编辑/逻辑删除/Excel 上传导入/服务器路径导入（仅超管）；权限码 `finance:category:*`，超管绕过 |
| **员工通道** | | |
| `EmployeeAuthController` | `/api/ops/auth/employee` | **员工** 登录（手机号+密码+验证码；微信公众号网页授权登录/绑定/绑定状态），独立于会员认证 |
| `EmployeeController` | `/api/ops/employee` | 员工身份校验 + 个人业务数据查询 + **手工新增业务数据**（表单选项/会员查询/批次/凭证）+ 头像上传 |
| `EmployeeAiController` | `/api/ops/ai/employee` | **员工** AI 助手（chat + async + SSE stream + config + profile + report） |
| **会员通道** | | |
| `AiChatProxyController` | `/api/ops/ai` | **会员** AI 客服代理（chat + voice + config + profile + member personalization + skills） |
| `MemberProfileProxyController` | `/api/ops/member` | 会员信息代理到底座 |
| **内部/代理** | | |
| `FileUploadController` | `/api/ops/files` | 图片/视频上传 |
| `PartnerController` | `/api/ops/admin/partners` | 城市合伙人管理（代理底座 internal API） |
| `InternalBusinessDataController` | `/api/internal/business-data` | SCM 退货同步回调 `POST /scm-sale-return`（X-Internal-Token，生成/删除负数分录） |
| `InternalWorkflowController` | `/api/internal/workflow` | 工作流业务回调 `POST /business-order/event`（X-Internal-Token；NODE_ACTION / NODE_ENTERED / INSTANCE_*） |


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
- **员工微信授权登录（2026-11-12）**：公众号 H5 新增 `GET /wechat/authorize-url`（一次性 state 5 分钟）、`POST /wechat/login`、`POST /wechat/bind`、`GET /wechat/bind-status`。`WechatOAuthServiceImpl` 用 `sys_public_account_config` 的 appId/appSecret 服务端换取 openid，再调底座 `POST /api/auth/employee/wechat/login`（X-Internal-Token）。底座新增 `sys_user_wechat_bind` 绑定表（迁移 `sql/migrate-user-wechat-bind.sql`，**先于底座 JAR**），密码/微信登录共用 `EmployeeSessionService` 签发同一套员工 JWT；底座「用户管理」可查看/解绑。公众号 OAuth（`snsapi_base`）拿不到手机号，**首次绑定须先密码登录后在「我的」完成**，不自动创建员工账号。`wechat.oauth.redirect-uri` 必须与公众号「网页授权域名」一致（默认 `https://boss.jdzn99.com/ops/`）。
- **员工组织切换（2026-11-15）**：工作台顶部 `OrgSwitcher` + 我的页「切换组织」入口 → `OrgSwitchSheet`（组织/角色/当前/最近登录，二次确认）。复用底座 `GET /api/auth/orgs`、`POST /api/auth/switch-org`，切换成功后替换员工 token 与组织/权限缓存（`utils/employeeSession.js`、`hooks/useOrgSwitch.js`），工作台统计重拉；失败保持原组织，401 回登录。dev 代理 `/api/auth` 到底座（vite 配置），避免被 ops 后端吞掉。
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

**覆盖范围**：截至 2026-08 已覆盖全部 18 个管理后台 Controller。

### AI 助手架构（双通道：会员 + 员工）

平台有两个 AI 代理通道，共用底座 Hermes Agent：

**会员 AI**（`AiChatProxyController` `/api/ops/ai`）：
- `POST /chat` — 同步聊天，转发到底座 `POST /api/ai/chat/send`
- 语音：`POST /voice/recognize`（ASR）、`POST /voice/synthesize`（TTS），代理到底座 internal API

**员工 AI**（`EmployeeAiController` `/api/ops/ai/employee`）：
- `POST /chat` — 同步聊天，自动注入员工身份上下文（phone/company/role）
- `POST /chat/async` — 异步聊天：提交到 Kafka → 底座处理 → 返回 taskId
- `GET /chat/{taskId}/stream` — SSE 实时推送结果（替代轮询）
- `GET /chat/{taskId}/status` — 轮询降级（Kafka 不可用时）
- `GET /config` / `GET /profile` — 员工 AI 配置与个性化问候
- 员工上下文通过 `employeeContext` 参数注入底座 Agent，底座据此激活员工专用工具（`ops_query_employee_business_data`）

**Async AI Chat 基础设施**（`AiChatTaskProducer`/`AiChatResultConsumer`/`AiChatStatusConsumer`/`AiChatTaskService`/`SseEmitterManager`）：前端 POST /chat/async → Kafka topic `ai-chat-task` → Consumer 调底座 AI 更新 `ai_chat_task` 表（`migrate_ai_chat_task.sql` 创建）→ SSE stream 推送；Kafka 不可用自动降级同步

### MCP 工具 & Agent

- `OpsOrderQueryTool`：`@McpTool`，工具名 `ops_query_order`，按订单号/状态查询，多租户安全过滤
- `OpsEmployeeBusinessDataTool`：`@McpTool`，工具名 `ops_query_employee_business_data`，查询员工增值业务数据（月份/品类/提成），限定当前员工手机号
- `OpsAgent`：`@AgentComponent`，名称 `"OPS"`，处理业务综合问答（订单/商品统计）


## 微信手机号授权（WechatPhoneService）

`WechatPhoneService` 通过微信 code + session_key 解密获取用户手机号，用于小程序端静默授权。只在微信小程序环境生效。


## 异步导出/导入（Kafka + 策略模式）

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


## 子系统间通信（替代跨库 JOIN）

- `ProductBatchClient`：调用 mall-service `POST /api/internal/product/batch-query`，替代跨库查 `mall_product`
- `PlatformMerchantService`：通过 `PlatformInternalClient` 查询组织信息，替代跨库查 `ai_platform.sys_corp`
- `MemberSyncClient`：注册/登录时同步会员到底座
- 所有 mapper SQL 仅 JOIN 本地表（`ops_*`, `biz_*`），不再跨库


## 图片服务链路

- `ImageUrlUtil`：规范化路径，完整 URL 提取相对路径，占位图 → null
- 前端 `utils/image.js`：`assetUrl()` 空值 → 占位 SVG，相对路径 → `BASE_URL + path`（H5 必须完整 HTTPS 路径，见陷阱表）

