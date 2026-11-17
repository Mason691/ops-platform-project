# 物资领用 / 物资核销（员工 H5）

> 归属：公众号员工工作台；页面形态参照「业务数据订单」3 步向导（见全局 Harness `aip-form-ux`）。
> 与 SCM「物料领取/核销」是不同业务域：本功能面向员工物资申请，**不绑仓库、不扣库存**；核销基于领用单回写余量。

## 1. 业务规则（已确认口径）

| # | 规则 |
|---|---|
| 1 | 领用为**申请制**：不选择仓库、不扣减库存、不做超库存阻断（数据源无仓库维度） |
| 2 | 领用人默认本人且不可取消，可添加本组织同事代领（最多 20 人）；组织归属仍为提交人所属组织 |
| 3 | 物料数据源为供应链商品类别/商品资料；提交时服务端按 `materialId` 回填名称/品牌/规格/单位 |
| 4 | 同一物料重复添加：前端与后端都**合并数量**（不产生重复明细） |
| 5 | 核销允许**部分核销**：每条 `核销数量 + 退回数量 ≤ 可核销余量`，整单至少一条 > 0；未处理余量留待下次核销 |
| 6 | 同一领用单同一时间只允许一张 `PENDING` 核销单 |
| 7 | 使用凭证**必填至少 1 个**（JPG/PNG/WEBP/PDF ≤10MB，最多 5 个） |
| 8 | 审批：物资领用、物资核销均为 `发起 → 部长审核`；审批通过核销后回写领用明细 `written_off_qty/returned_qty` 并重算 `write_off_status` |
| 9 | 状态：领用 `PENDING/APPROVED/REJECTED/ISSUED`（ISSUED 预留发放环节）；核销 `PENDING/APPROVED/REJECTED` |
| 10 | 单号：`LY-yyyyMMdd-NNN`、`WX-yyyyMMdd-NNN`（`ops_material_seq` 原子自增） |

## 2. 数据表（sql/material_requisition_init.sql）

- `ops_material_requisition`：单号、主领用人、`applicant_user_ids/names`（逗号分隔，FIND_IN_SET 可见性）、部门、用途、期望领用日期、状态、流程实例/节点、审批意见/时间、`write_off_status`。
- `ops_material_requisition_entry`：`material_id` + 名称/类别/品牌/规格/单位快照、`quantity`、`written_off_qty`、`returned_qty`。
- `ops_material_write_off`：单号、领用单 ID/号、领用人/部门快照、核销人（多选）、核销日期、使用说明、凭证 URL（JSON）、状态、流程字段。
- `ops_material_write_off_entry`：`requisition_entry_id` + 物料快照、`requisition_qty`（原领用数量）、`remaining_qty`（创建时可核销余量快照）、`write_off_qty`、`return_qty`。
- `ops_material_seq`：`(bill_type, bill_date)` 唯一键 + 原子 UPDATE。

## 3. 接口（员工 JWT，`/api/ops/employee/material`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/options` | 员工/组织/部门、同组织员工、供应链商品类别 |
| GET | `/materials?category=&keyword=&pageNum=&pageSize=` | 商品搜索（名称/编码/型号/品牌，类别过滤） |
| POST | `/requisitions` | 提交领用（服务端回填商品快照并同步发起审批） |
| GET | `/requisitions` | 当前员工领用记录（status/keyword/dateFrom/dateTo/pageNum/pageSize） |
| GET | `/requisitions/{id}` | 领用详情（含明细余量与当前审批人） |
| GET | `/requisitions/pickable?keyword=&dateFrom=&dateTo=` | 可核销领用单（已通过/已发放、未完成、无审批中核销单） |
| POST | `/write-offs` | 提交核销（允许部分核销；防重复 PENDING） |
| GET | `/write-offs` | 当前员工核销记录（status/keyword/dateFrom/dateTo） |
| GET | `/write-offs/{id}` | 核销详情（含凭证与审批意见） |
| POST | `/voucher` | 使用凭证上传（multipart `file`） |

内部回调：`POST /api/internal/workflow/material/event`（X-Internal-Token，fail-closed），
处理 `NODE_ENTERED / INSTANCE_COMPLETED / INSTANCE_REJECTED / INSTANCE_TERMINATED`。

## 4. 工作流（sql/material_requisition_workflow_seed.sql）

- `OPS_MATERIAL_REQUISITION_APPROVAL` / businessType `OPS_MATERIAL_REQUISITION`
- `OPS_MATERIAL_WRITE_OFF_APPROVAL` / businessType `OPS_MATERIAL_WRITE_OFF`
- 节点：`发起(START)` → `部长审核(APPROVAL, lastNode)`；审批人按企业角色「部长」+ `OPERATOR_TREE` 解析
- 回调地址：`http://localhost:8082/api/internal/workflow/material/event`（host 网络 localhost 在底座白名单）
- 定义 key 可在 `application.yml` 的 `material.requisition.*` / `material.write-off.*` 覆盖

## 5. 前端（ops H5）

| 页面 | 说明 |
|---|---|
| `pages/employee/material-requisition-new` | 3 步向导：领用信息 → 物料明细 → 确认提交；草稿恢复、提交防重、成功页审批人 |
| `pages/employee/material-requisition-records` | 领用记录（状态/关键字/日期筛选 + 分页） |
| `pages/employee/material-requisition-detail` | 领用详情 + 审批意见 + 明细余量 + 发起核销 |
| `pages/employee/material-write-off-new` | 3 步向导：核销信息 → 物料核销 → 确认提交（凭证在确认页上传） |
| `pages/employee/material-write-off-records` | 核销记录（状态/单号/日期筛选 + 分页） |
| `pages/employee/material-write-off-detail` | 核销详情 + 凭证预览 + 审批意见 |

组件：`MaterialRequisitionStepInfo`、`MaterialWriteOffStepInfo`、`MaterialItemsStep`、
`MaterialRequisitionItemCard`、`MaterialWriteOffItemCard`、`MaterialPickerSheet`、
`RequisitionPickerSheet`、`MaterialRequisitionConfirm`、`MaterialWriteOffConfirm`、
`MaterialVoucherUploader`、`MaterialFlowSuccess`；复用 `BusinessWizardSteps`、`FieldChips`、
`BottomSheetSelect`、`QuantityStepper`、`BusinessAgentPicker`、`fieldFocus`、`Pager`。

## 6. 部署顺序

1. `sql/material_requisition_init.sql` → 生产 `mall_db`
2. ops 后端 JAR（8082）
3. `sql/material_requisition_workflow_seed.sql` → `ai_platform`
4. H5 `dist/build/h5` → `/data2/boss-platform/html/ops`
5. 员工重新登录后可见工作台「物资领用」「物资核销」入口

## 7. 验证清单

1. 3 步流程正常，步骤条吸顶/回退/未访问不可点。
2. 类别筛选后商品弹层只展示该类别；名称关键字过滤；连续添加保留类别。
3. 重复物料合并数量，不产生重复行。
4. 用途/日期/领用人/类别/商品/数量缺失时不能下一步；确认页可就地补填并提交。
5. 提交生成 `LY-` 单号，记录/详情展示正确，成功页显示下一审批人。
6. 核销选择领用单后自动带出全部明细，领用数量/可核销余量正确；不能新增物料。
7. 核销类别/名称筛选只影响展示，清空恢复；已填数据不变。
8. 核销+退回超过余量、负数/非整数、全部为 0 均被拦截；凭证必填。
9. 同一领用单存在待审核核销单时再次提交被拦截。
10. 审批通过核销后领用明细已核销/已退回与核销状态正确回写；重复核销不会超量。
11. 真机微信验证键盘遮挡、安全区、草稿恢复、提交防重。
