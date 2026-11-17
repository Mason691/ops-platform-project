# PR：修复工作流公众号消息“缺少任务ID”，跳转公众号 H5 员工待办处理页

## 一、动机

生产实例 1（业务订单 8765，corp 9）部长审核节点已生成任务 1/2/3，16:18 的
TASK_CREATED 消息发送成功，但模板跳转链接指向 PC 页
`/ops/business-data/flow-task?taskId={taskId}`，且 H5 没有员工工作流待办/任务页；
历史消息缺少 `instanceId`，参数为空时页面直接抛“缺少任务ID”，部长无法从微信进入处理。

本次修复目标：
1. 消息链接携带 `taskId + instanceId`，直接打开公众号 H5 员工工作台的任务处理页；
2. 发送前校验链接参数，参数为空/占位符未替换则不发消息并记录失败原因；
3. H5 提供待办列表与任务处理页，覆盖部长审核、财务核对、派工出库、施工中、施工完成、售后；
4. 未登录先登录，登录后带原参数回跳；缺参友好提示并返回待办/工作台。

## 二、模块级变更

### 底座（ai-native-platform）

- `MessageTemplateRenderer.validateJumpUrl`：TASK_CREATED 必须包含
  `{taskId}`+`{instanceId}`，渲染后参数非空、无未替换占位符；其他场景若 URL 引用
  `taskId/instanceId` 同样校验非空。新增单测覆盖有效、缺占位符、空值、路径占位符。
- `WorkflowNotificationService.sendWithTemplate`：渲染 URL 后先校验，失败不发送，
  写 `wf_message_send_log`（`SKIPPED` + `fail_reason`）与异常中心 `MESSAGE_URL_INVALID`；
  成功后也记录实际 URL。
- `WfMessageSendLog.url` + 迁移 `doc/migration_workflow_message_log_url.sql`（可重复执行）。

### ops H5（ops-platform-project/frontend）

- 新增 `pages/employee/flow-todo.vue`：员工待办列表（`/api/wf/task/todo`），未登录跳登录。
- 新增 `pages/employee/flow-task.vue`：任务处理页，读取 `taskId/instanceId`，
  加载 `/api/wf/task/{taskId}` + ops 订单详情；按节点动作渲染：
  - 部长审核：查看订单/商品 + 同意/驳回；
  - 财务核对：收据号、财务收款日期、横排数、是否收款、核对结论（通过/不通过/暂缓）、备注；
  - 派工出库：工单类型、上门员工、期望时间、施工商品、随访说明；
  - 施工中：施工/完工说明；
  - 施工完成：验收结论/说明；
  - 售后维护：售后内容/回访结果。
- `pages.json` 注册两页；`EmployeeTabBar` 增加「待办」；工作台新增「待办任务」卡片；
  `App.vue` 支持 `?route=pages/employee/flow-task&taskId=..&instanceId=..` 深链并保留参数；
  `api/index.js` 增加 `/api/wf/task/todo|{taskId}|approve|reject`、订单详情、派工员工、
  财务暂缓、`/api/dict/data`。
- 登录：未登录跳 `login?redirect=<任务页完整参数>`，登录后原参回跳；缺参友好提示。

### 生产配置

- `wf_message_template` TASK_CREATED URL：
  `https://boss.jdzn99.com/ops/?route=pages/employee/flow-task&taskId={taskId}&instanceId={instanceId}`；
  APPROVED/REJECTED/TERMINATED 指向 `?route=pages/employee/flow-todo`。
- 执行 `url` 列迁移；overlay 发布平台 JAR；部署 H5。

## 三、影响范围

- 底座：工作流消息发送（TASK_CREATED/APPROVED/REJECTED/TERMINATED）、消息日志表。
- ops H5：员工工作台底部导航/首页、待办列表、任务处理；无 ops 后端接口变更。
- 生产数据：`wf_message_send_log` 新增列；模板 URL 更新；历史成功消息标注不追溯原因。

## 四、测试建议

已执行：
- 底座 `MessageTemplateRendererTest`（含新增 URL 校验用例）、`WorkflowRulesTest` 通过；
- 底座 `mvn -pl web -am compile/package -DskipTests` 通过；
- H5 `npm run build:h5` 通过；生产 H5 chunk `pages-employee-flow-task/todo` 存在；
- 生产 platform/ops `/actuator/health` UP；
- 向 user 577/580 补发带 `taskId/instanceId` 的微信消息，`errcode=0`；
- H5 深链 `https://boss.jdzn99.com/ops/?route=pages/employee/flow-task&taskId=3&instanceId=1`
  返回「金地燃气」SPA，包含 flow-task/todo chunk。

待真机验证：
1. 部长在微信点击补发消息 → 打开 H5 任务处理页（当前部长审核）；
2. 未登录先登录，登录后回跳任务页；
3. 人为构造缺参链接 → 展示「链接缺少任务信息」+ 返回待办/工作台；
4. 后续节点（财务/派工/施工/验收）消息跳转；
5. 新订单消息的 `wf_message_send_log.url` 非空、无参数为空失败。

## 五、风险与已知限制

1. **历史消息不追溯**：16:18 及之前的消息仍指向 PC 页；已在 `wf_message_send_log`
   标注原因，并补发 task 2/3 的新链接。旧消息点击仍会失败，需使用新消息或 H5 待办入口。
2. **未登录回跳**依赖员工登录页现有 `redirect` 机制；微信 OAuth 回调优先于深链，
   若 URL 同时带 `code/state` 会先走 OAuth 回调（消息链接不带 code/state，正常）。
3. **H5 表单为移动端精简版**：施工照片上传、银行回单等富字段仍以 PC 流程处理页为主；
   H5 覆盖核心审批/核对/派工/施工/验收字段。
4. **TaskDetail 权限**：任务详情仍由平台 `WorkflowAccessService` 校验任务归属；
   H5 仅展示当前登录员工本人任务。
5. **消息发送校验为通用规则**：TASK_CREATED 模板必须包含 `{taskId}`+`{instanceId}`，
   其他业务类型若复用 TASK_CREATED 但链接不带这两个参数，会被跳过并记录异常。
