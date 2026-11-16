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

