# 业务数据订单工作流

> 从 `CLAUDE.md` 拆分（2026-11-16）。统一工作流组件的接入规范另见 `~/.claude/skills/aip-workflow/SKILL.md`。

---

## 业务数据订单工作流（2026-11-14，替代原派工单体系）

**业务状态 `biz_status`（字典 `business_order_status`，0~6）由底座工作流组件驱动；`calc_status` 仅保留提成维度，两者完全解耦。**

- **底座工作流扩展**：`wf_definition.callback_url`；`wf_definition_node` 增 `node_type`（START/APPROVAL）、`biz_status`、`return_mode`（NONE/INITIATOR/PREVIOUS）、`return_biz_status`、`action_type`、`form_schema`、`form_url`；`wf_definition_node_approver` 支持 `approver_role_name + approver_scope`（按订单归属组织解析企业角色员工）；新增内部发起 `POST /api/internal/wf/instance/start`（X-Internal-Token + 显式 corpId）；节点动作同步回调（失败不推进）、节点进入/实例完成/驳回/终止状态回调（异步重试 + 异常中心）、驳回退回发起人/上一节点、业务动作返回 `assigneeUserId` 时动态指派下一节点。迁移 `doc/migration_workflow_business_flow.sql`。
- **ops 数据模型**：`ops_business_order` 增 `biz_status`（默认 0）、`wf_instance_id`、`wf_node_order`、`wf_node_name`、`finance_check_conclusion`、`finance_check_remark`；新增 `ops_business_flow_record`（节点处理留痕，`task_id+action_type` 幂等）；迁移 `sql/migrate_business_order_biz_status.sql`（含旧派工状态回填，**先于新 JAR**）。
- **流程联动**：导入/员工手工新增事务提交后异步发起实例；`POST /api/internal/workflow/business-order/event`（X-Internal-Token）处理 `NODE_ACTION`（财务核对/派工出库/施工/验收/售后）与 `NODE_ENTERED/INSTANCE_*`（写 `biz_status`）。派工出库走 SCM `POST /api/internal/sale-outbound/from-dispatch`（taskId 幂等、已有出库单拦截、入户随访不出库）；财务核对不通过/验收不通过以带 `formData` 的驳回先落库再退回；财务暂缓保存字段但不推进。
- **列表/导出**：订单列表合并「订单编号/收据号」、移除是否收款/财务核对列、新增业务状态筛选与「重新提交」；导出同步调整列并显示字典中文名。
- **删除**：旧派工单 5 张表（归档后删除）、H5 派工 4 页与「待处理」Tab、平台派工/公众号模板 2 页、财务核对弹窗、菜单/路由；保留 `WechatOfficialAccountClient` 仅供公众号 JSSDK access_token。
- **配置说明**：`docs/业务数据订单工作流配置说明.md`（字典、角色、节点、表单、消息模板、微信申请、nginx、测试、回滚）。
- **模板**：`doc/add_business_order_status_dict.sql`、`doc/remove_dispatch_wx_template_menus.sql`、`sql/archive_and_drop_dispatch_wx.sql`、`sql/rollback_archive_dispatch_wx.sql`。

---

## 业务订单退回草稿 / 作废退款（2026-11-18）

- **状态扩展**：字典 `business_order_status` 新增 `-1 / 作废退款`；退回草稿沿用 `0 经办人上报` + `ops_business_order.draft_flag=1`。
- **退回草稿**：经办人（任一经办人）在状态 0/1/2 发起 → 平台内部终止当前 `biz_order_flow` 实例（待办 SKIPPED、终止消息）→ 状态 0、`draft_flag=1`、清空 `wf_*`；H5 复用 5 步制单编辑（原订单编号/业务组号），提交后新实例重新进入部长审核。
- **作废退款**：终止正常流程 → 新流程 `biz_order_void_flow`（`OPS_BUSINESS_ORDER_VOID`）：部长审核 → 财务审核 → 总经办审核；部长/财务驳回退回经办人（INITIATOR），总经办驳回退回财务（PREVIOUS）；终审动作 `OPS_VOID_COMPLETE` 置 `biz_status=-1`、明细 `calc_status=已作废`、提成 0 并锁定。
- **拦截**：已出库（SCM fail-closed）、已上报集团、已生成以旧换新采购单、已有 PENDING 退款；作废审批中不可编辑/退草稿/重复退款；退款驳回后提供「重新提交审批」。
- **数据**：`sql/migrate_business_order_draft_refund.sql`（`draft_flag` + `ops_business_refund`）；`sql/business_order_void_workflow_seed.sql`（字典 -1 + 流程定义/节点/角色审批人）。
- **平台**：内部终止接口 `POST /api/internal/wf/instance/{id}/terminate`（X-Internal-Token）；消息模板复用现有场景。
- **统计**：作废订单保留列表供审计，但排除员工销售/提成合计、员工月度/品类统计、管理端汇总/品类提成汇总/作战地图。

---

## 工作业务日志与订单流程进度（2026-11-17）

- **底座日志接口**：`GET /api/wf/log/my`（当前员工发起/处理的流程操作，业务类型/动作/日期筛选 + 分页）、`GET /api/wf/log/detail?key=L:<logId>|I:<instanceId>`（详情，含 `formData` 附件提取）。发起记录由 `wf_instance` 合成（`I:`），历史实例无需补日志；审批操作人优先取 `wf_task.approver_name` 真实姓名；日志/实例逻辑删除仍可查并返回 `deleted` 标记。
- **底座内部进度接口**：`POST /api/internal/wf/instance/progress`（X-Internal-Token，批量 ≤100）返回当前/下一节点 ID、名称、`biz_status`、当前/下一处理人；供子系统订单列表一次取数，避免逐单调用。
- **节点表单留痕**：`WorkflowEngineService.publishLog` 将审批/驳回提交的 `formData` 写入 `wf_log.detail_json`，日志详情可展示附件。
- **ops 员工端**：`GET /api/ops/employee/business-data/orders`（当前组织 + 当前经办人，订单主行 + `flow` 进度）、`GET .../orders/{orderId}`（详情 + `flowRecords`）；H5 业务数据页由月度汇总改为订单主行 + 展开明细 + `BusinessFlowProgress` 步骤条；工作台新增「工作业务日志」卡片，新增日志列表/详情/订单详情三页。
- **权限**：日志只按 JWT 用户过滤；订单固定 `corp_id + agent_phone`；业务单据跳转按对应单据接口二次校验。
- **无 DDL**：不新增表；`wf_log.action` 枚举不变（发起由查询合成）。

---

