# PR：业务数据订单改为底座工作流驱动，下线硬编码财务核对/派工/公众号模板

## 一、动机

原增值业务订单的「财务核对、派工、派工单公众号消息」是 ops 内的硬编码链路：
列表按钮、专用表、专用模板配置、接单自动出库彼此耦合，业务状态只能通过
`calc_status` 间接表达，无法配置、无法追溯、无法复用。本次按产品要求：

1. 业务状态 `biz_status`（0~6）完全由底座工作流组件驱动，与提成状态 `calc_status` 解耦；
2. 删除旧派工单、公众号模板配置、硬编码财务核对入口与接单出库逻辑；
3. 财务核对/派工出库/施工/验收/售后改为工作流节点动作，节点表单由配置决定；
4. 交付可直接照做的配置说明（字典、角色、流程、节点、表单、公众号模板、nginx）。

## 二、模块级变更

### 底座平台（ai-native-platform）

- **工作流数据模型**：`wf_definition.callback_url`；`wf_definition_node` 新增
  `node_type/biz_status/return_mode/return_biz_status/action_type/form_schema/form_url`；
  `wf_definition_node_approver` 新增 `approver_role_name/approver_scope`，`approver_user_id` 改为可空。
  迁移 `doc/migration_workflow_business_flow.sql`。
- **引擎**：跳过 START 节点；节点动作同步回调（失败不推进）；节点进入/实例完成/驳回/终止
  状态回调（事务提交后异步、3 次重试、异常中心留痕）；驳回按 `return_mode` 退回发起人或上一节点；
  业务动作返回 `assigneeUserId` 时动态指派下一节点处理人；按企业角色 + 组织范围解析审批人。
- **接口**：内部发起 `POST /api/internal/wf/instance/start`（X-Internal-Token + 显式 corpId）；
  `RejectRequest` 支持 `formData`；`ApproveRequest` 支持 `formData`；任务详情返回节点配置。
- **前端**：工作流定义编辑器支持回调地址、节点类型、业务状态值、驳回方式、节点动作、
  表单 JSON、业务页 URL、按角色审批人；任务详情展示节点信息、通用动态表单、业务处理入口。
- **字典/菜单 SQL**：`doc/add_business_order_status_dict.sql`（0~6）、
  `doc/remove_dispatch_wx_template_menus.sql`（菜单 + 实体配置下线）。

### ops 运营平台

- **数据模型**：`ops_business_order` 新增 `biz_status/wf_instance_id/wf_node_order/wf_node_name/
  finance_check_conclusion/finance_check_remark`；新增 `ops_business_flow_record`（节点处理留痕，
  `task_id+action_type` 幂等）。迁移 `sql/migrate_business_order_biz_status.sql`（含旧派工状态回填）。
- **流程联动**：导入/员工手工新增事务提交后异步发起实例；内部回调
  `POST /api/internal/workflow/business-order/event` 处理：
  - `NODE_ACTION`：财务核对（PASS/REJECT/HOLD、凭证/银行回单留痕）、派工出库
    （SCM 未审核出库单、taskId 幂等、已有出库单拦截、入户随访不出库）、施工/验收/售后留痕；
  - `NODE_ENTERED/INSTANCE_*`：写 `biz_status`、实例/节点信息。
- **列表/详情/导出**：合并「订单编号/收据号」、移除是否收款/财务核对列、新增业务状态
  标签与筛选、「重新提交」入口；导出新增业务状态列、移除是否收款、保留计算状态、支持按业务状态过滤。
- **删除**：旧派工单 5 张表（`sql/archive_and_drop_dispatch_wx.sql` 归档后删除）、
  `DispatchOrder*`/`EmployeeDispatch*`/`DispatchMessage*`/`WxMsgTemplate*`/`DispatchNoGenerator`/
  `CompanyCodeResolver`/`FinancePermissionService` 等后端代码；H5 派工 4 页与「待处理」Tab；
  平台派工单/公众号模板 2 页、财务核对 3 个弹窗、路由与 API。
- **保留**：`WechatOfficialAccountClient`（公众号 JSSDK access_token）、`ScmStockOutClient`
  （工作流派工出库）、`BusinessDataFinanceService`（节点动作）。
- **文档**：`docs/业务数据订单工作流配置说明.md`、`docs/CHANGELOG.md`、`CLAUDE.md` 更新。

## 三、影响范围

- 数据库：`ai_platform.wf_*`、`ai_platform.sys_dict_*`、`ai_platform.sys_menu`、
  `ai_platform.sys_entity_config`、`mall_db.ops_business_order`、`mall_db.ops_business_flow_record`。
- 接口：ops 业务数据列表/详情/导出/导入/员工手工新增；平台工作流定义/任务/实例/消息；
  ops 内部回调；SCM `from-dispatch`（调用方由派工服务改为工作流动作）。
- 前端：平台业务数据页、工作流定义/任务详情、新增流程处理页；ops H5 工作台/底部导航。
- 部署：需按 `docs/业务数据订单工作流配置说明.md` 的顺序执行迁移、发布 JAR/前端、
  归档删除旧表、下线菜单，并增加 host nginx `/ops/business-data/flow-task` 白名单。

## 四、测试建议

已执行：

- 底座 `mvn -pl web -am compile -DskipTests` 通过；
- 底座工作流单测 `WorkflowRulesTest`、`MessageTemplateRendererTest` 通过；
- ops `mvn -f backend/pom.xml compile -o` 通过；
- 平台主站 `npm run build` 通过；ops H5 `npm run build:h5` 通过；
- `git diff --check` 无空白错误；`pages.json`、`application.yml` JSON/YAML 解析通过。

待部署环境执行：

1. 执行两份迁移 + 字典 SQL，发布两端 JAR 与前端；
2. 按配置说明创建角色/流程/节点/消息模板；
3. 走通：员工新增 → 部长审核 → 财务核对（通过/不通过/暂缓）→ 派工出库（SCM 出库单）
   → 施工中（动态指派）→ 施工完成 → 售后；验证每步 `biz_status`、待办、公众号消息；
4. 验证驳回退回发起人/上一节点、重新提交、重复派工拦截、已上报集团锁定；
5. 验证列表/导出列与业务状态筛选、`calc_status` 不受工作流影响；
6. 验证旧表已归档删除、旧菜单/页面/接口无残留。

## 五、风险与已知限制

1. **跨系统一致性**：节点动作是 platform → ops 同步 HTTP，动作成功但 platform 事务回滚的极小概率
   会导致业务已执行而任务未落库；ops 侧按 `taskId+actionType` 幂等，重试可恢复；状态回调失败
   落工作流异常中心，需人工重推。回调地址增加主机白名单（`workflow.business-callback-allowed-hosts`），
   防止携带内部令牌的请求被配置到外部地址。
2. **角色审批人**：按 `sys_corp_user_role.role_name` 解析；角色名不一致/组织未绑定时节点无处理人，
   发起或推进失败并提示；需按配置说明建角色。
3. **动态指派**：仅派工节点 → 下一节点生效；退回上一节点时按节点配置角色重新解析（不保留原指派）。
4. **消息模板**：沿用工作流组件「按场景」模板（TASK_CREATED/APPROVED/REJECTED/...），
   通过 `{nodeName}` 区分节点；未实现“每节点绑定不同模板 ID”，可作为后续增强。
5. **终止流程**：当前版本终止实例不改变 `biz_status`，未内置「已终止」字典项。
6. **Temporal 模式**：动作结果（动态指派）通过 task-completed 事件传递；Temporal 模式走旧事件
   路径会丢失动态指派，生产默认关闭 Temporal，如需开启需补充 Activity 参数。
7. **未执行生产迁移/发布/真实公众号发送**：本地无生产库权限，需按部署步骤在发布窗口执行并验收。
