# PR：员工新增业务订单改为 4 步向导制单

## 标题

`feat(ops-h5): 员工新增业务订单改为 4 步向导制单`

## 动机

公众号 H5「员工工作台 → 业务数据 → 新增业务订单」原为单页长表单：批次、客户、经办人、产品、收款、凭证全部纵向排列，移动端需要长距离滚动，必填项缺失时只能整体提交后统一报错，且选项控件大量使用原生下拉。本次将其重构为「基础信息 → 商品明细 → 收款信息 → 确认提交」4 步向导，目标是：

- 分步推进，降低单屏信息密度与填写压力；
- 每步就地校验，失败不回退、不打断填写心流；
- 选项少的字段直接点选，选项多的字段底部弹层搜索；
- 确认页对前序遗漏项就地补填，避免“提交失败 → 返回上一步”的往返；
- 增加草稿保护、提交遮罩、成功页与订单定位。

## 模块级变更

### 前端（`~/ops-platform-project/frontend`）

- **页面编排**：`pages/employee/business-data-new.vue` 重写为向导容器，包含吸顶步骤条、提示条、步骤切换动画、固定底部操作栏、提交遮罩与成功页。
- **状态机**：新增 `hooks/useBusinessWizard.js`，负责选项加载、分步校验、确认页全量校验、草稿、会员查询、提交、步骤跳转与重置。
- **步骤组件**：
  - `BusinessStepBasic.vue`：批次/客户手机/客户/小区/详细地址/经办人；
  - `BusinessStepProducts.vue` + `BusinessProductCard.vue`：产品卡片增删、品类/品牌/型号联动、单价/数量/金额；
  - `BusinessStepReceipt.vue`：是否特批、逐产品提成比例、市场收款日期、是否收款、凭证、备注；
  - `BusinessStepConfirm.vue`：待补充事项就地补填 + 只读汇总 + 修改入口 + 合计；
  - `BusinessSuccess.vue`：订单编号、批次、金额、明细条数、继续制单/查看订单。
- **原子控件**：`BusinessWizardSteps`、`FieldChips`、`BottomSheetSelect`、`SegmentedControl`、`QuantityStepper`、`AnimatedAmount`、`BusinessAgentPicker`。
- **工具**：`utils/businessForm.js` 增加步骤错误归类、缺失项描述、备注校验与小区必填；`utils/fieldFocus.js` 提供滚动/聚焦/震动。
- **其它**：`BusinessVoucherUploader.vue` 增加上传进度；`pages/employee/business-data.vue` 支持成功页「查看订单」按批次定位（带 60 秒时效，避免陈旧标记）。
- **删除**：被替代的 `BusinessEntryForm.vue`、`BusinessAgentSelector.vue`、`BusinessProductList.vue`、`BusinessProductForm.vue`、`BusinessReceiptForm.vue`。

### 后端（`~/ops-platform-project/backend`）

- `EmployeeBusinessEntryRequest` 新增可选 `remark`（≤500 字）。
- `OpsBusinessOrder` 复用 `BaseEntity.remark`（`ops_business_order.remark` 列已存在，无需迁移）。
- `BusinessOrderService.createOrder(...)` 增加 `remark` 参数，同步手工新增、Excel 导入、历史补建 3 处调用（导入取首行备注、补建传 `null`）。
- 手工新增同时把 `remark` 写入子表 `ops_business_order_item.remark`，保证导出「备注」列口径与导入一致；订单列表/详情 `buildOrderView` 返回 `remark`。
- 手工新增接口将小区改为**必填**（`communityId`），与前端口径一致。

### 文档

- `Specs/员工手工新增业务-规格说明.md` 增加第 9 节「制单界面分步向导」与验证记录。
- `docs/CHANGELOG.md` 增加 2026-11-16 条目（标注待部署）。

## 影响范围

- 仅影响员工公众号 H5 的「新增业务订单」页面及其提交接口；会员端、管理后台、Excel 导入、SCM 协同、工作流发起均不改路径与响应结构。
- `POST /api/ops/employee/business-data/manual` 请求体新增可选 `remark`，向后兼容；`communityId` 由选填改为必填，仅该接口生效。
- 数据库无新增迁移；依赖已存在的 `ops_business_order.remark` 列（已核对生产存在）。

## 测试建议

1. **构建**：后端 `mvn -f backend/pom.xml clean package -DskipTests -o`；前端 `npm run build:h5`、`npm run build:mp-weixin`。
2. **真机微信 H5**（发布后必须）：
   - 完整走 4 步，确认步骤条状态、左右滑动过渡、固定底栏与安全区；
   - 每步缺失必填时停留本步、顶部提示、红框与滚动聚焦；
   - 从确认页点「修改」回看并清空某必填，再点步骤条直达确认页，验证「待补充事项」就地补填且不返回前页；
   - 特批=是时逐产品填写提成比例；收款=是时拍照/相册上传凭证，验证进度、预览、删除与未上传拦截；
   - 提交中遮罩防重复；首次失败（如批次已上报）停留确认页且数据不丢；成功后展示订单编号并可继续制单/查看订单；
   - 填写部分内容后退出再进入，验证草稿恢复；键盘弹起时输入框不被固定底栏/键盘遮挡。
3. **接口回归**：手工新增带 `remark` 落库到 `ops_business_order.remark`；不带小区应返回「请选择小区」；普通 Excel 导入不受 `createOrder` 签名变化影响。

## 风险提示

- **小区必填**：生产现有 6 个组织均有小区（27~234 个），但新组织若未配置小区将无法制单；页面会提示联系管理员维护。已同步收紧后端校验。当前小区下拉与 `resolveCommunity` 均按员工所属 `corp_id` 过滤，**不包含其它组织或全局（corp_id=0）小区**；这是现有数据主权边界，跨组织小区如需共享需另行设计。
- **提成比例粒度**：需求文档第 3 步描述为单个「提成比例」，但后端 `commission_rate` 以产品行为粒度（特批时每行独立）。本次保留逐产品录入，在收款信息页以产品列表展开，不弱化既有能力；如需改为整单一个比例，需要另行确认业务口径。
- **草稿本地存储**：按 `corp_id + 员工 userId` 隔离，含客户手机/姓名/地址等 PII，7 天过期，提交成功即清除；同设备不同员工不会串读。若安全规范要求更短 TTL 或加密，可在后续调整。
- **依赖列**：`ops_business_order.remark` 列已在生产确认存在；若其他环境建表脚本未包含该列，需要先补列。
- **未执行项**：本次已完成生产部署（提交 `ce939f4`，2026-11-16 15:26），但尚未在真机微信中验证键盘遮挡/拍照上传与真实员工制单；这两项需由用户完成。

## 生产发布记录（2026-11-16 15:26）

- 提交：`ce939f4 feat(ops-h5): 员工新增业务订单改为 4 步向导`
- 后端：JAR sha `0dab53e9d7b7e468600a6ca9236fe4ce8b98d37580f5dd3b5065ff5d7faccdc9`；镜像 `boss-platform/ops:wizard-20261116_152621`（`sha256:b3857c98…`），容器 `boss-ops-backend` healthy。
- 前端：`index-D_T91--V.js`、`pages-employee-business-data-new.CFQJ8p6i.js`；`boss-ops-nginx` reload。
- 备份：`ops-backend/app.jar.bak-wizard-20261116_152621`、镜像 `boss-platform/ops:rollback-wizard-20261116_152621`、前端 `html/ops.bak-wizard-20261116_152621.tgz`。
- 冒烟：`/actuator/health` UP；`https://boss.jdzn99.com/ops/` 200（标题「金地燃气」）；新 chunk 200 且包含「待补充事项」；`/api/ops/employee/business-data/manual/options` 无 token 返回 401（路由正常）。
- 回滚：重新 tag `rollback-wizard-20261116_152621` 为 `latest` 后 `docker compose up -d ops-backend`；前端解压备份 tgz 并 reload `boss-ops-nginx`。
- 注意：生产 `/data2` 磁盘使用率 98%（余约 2.0G），后续发布前建议清理旧备份。

## 验证记录（本地）

- 后端 `mvn clean package -DskipTests -o` 通过。
- 前端 `npm run build:h5`、`npm run build:mp-weixin` 通过。
- Puppeteer 无头端到端（模拟接口，390×844）通过：4 步走通、分步拦截与错误清除（红框 + 顶部提示条）、负数单价/小数数量/超范围提成比例拦截、批次弹层/新建批次/旧批次回显、品牌与型号弹层、多经办人（2 产品 × 2 经办人 = 4 条明细）、确认页就地补填且输入完整保留、凭证上传/删除/未上传拦截、首次提交失败不清空、重试成功、继续制单重置、草稿保存与恢复；页面无 JS 错误。

## 代码审查后修复（正确性）

- **错误反馈可见性**：顶部提示条 `top` 改为 `calc(var(--window-top) + 16rpx)`，避免被 H5 原生导航栏遮挡；各步骤错误控件增加红色描边。
- **凭证上传原子回写**：多张凭证全部上传完成后只回写一次，并在组件卸载时中止进行中的 XHR，避免确认页“待补充事项”卡片因第一张完成而卸载、后续图片丢失。
- **旧校验恢复（防静默改写）**：销售单价保留原始输入（负数显示“必须大于0”而非改成正数）；销售数量保留小数/负号（显示“必须为正整数”而非拼成整数）；提成比例不再 clamp（超范围显示“0-100”错误而非改成 100）。
- **手机号/数量输入**：手机号改为 `type="tel"` 并保留 `maxlength`，避免 `type="number"` 下多输一位导致“显示号码 ≠ 提交号码”。
- **会员查询防串步**：查询结果增加“发起步骤”校验，切步后丢弃迟到响应；未命中会员时仅清理上一次**自动回填**的姓名/小区，用户手填内容保留。
- **草稿健壮性**：恢复时过滤已离职/不在当前组织的经办人；页面被系统返回时立即落盘最后 600ms 的编辑；提交成功后取消排队草稿，避免已提交数据被重新存为草稿。
- **分段选择器**：无匹配值时不再回退高亮第一项；`模型值` 与高亮保持一致。
- **弹层滚动锁**：改为模块级引用计数，多个弹层实例并存时不会因某个兄弟组件卸载而提前解锁页面。
- **批次选中态**：从“查看更多”选择旧批次后，已选批次始终回显在标签行；订单列表的批次选择器增加 `:value` 同步，打开时高亮当前批次。
- **确认页产品卡**：产品卡错误过滤排除 `commissionRate`；仅缺提成比例的行改由收款信息卡片展示；确认页的重复/合并错误允许直接删除行，无需返回商品明细。
- **备注一致性**：`OpsBusinessOrder` 复用 `BaseEntity.remark`（避免重复声明）；订单列表/详情返回 `remark`；手工单同步写子表 remark，导出「备注」列与导入口径一致；导入订单头取首行备注。
- **其它**：`MAX_REMARK` 常量统一；`loadOptions` 改为 `try/finally` 管理 loading；小程序上传进度接入 `uni.uploadFile` 的 `onProgressUpdate`。

## 改进建议（不阻塞本次）

- **校验性能**：`validateStep` 目前先执行全量 `validateBusinessForm` 再按步骤过滤；`form` 的 deep watch 在错误展示后每次输入都会重算全部产品行。产品行数很多（20~50）时可考虑拆分为按步骤的增量校验与错误 diff。
- **草稿写入**：`saveDraft` 每次 600ms 防抖都同步 `JSON.stringify` 整个表单；可改为仅步骤切换/失焦写入或使用异步存储。
- **弹层选项规模**：品牌/型号弹层一次性渲染全部选项，SCM 基础资料数量很大时可引入虚拟列表或分页搜索。
- **产品选项索引**：每张产品卡独立扫描/排序 `meta.materials`；产品行多时可预建 `category→brands`、`brand+category→models` 索引复用。
- **金额动效**：每个产品卡 + 合计 + 底栏各有一个 `AnimatedAmount`，单价变化会同时启动多个 rAF；可聚合为一个合计动画。
- **字段注册表**：步骤/标签映射目前分散在 `businessForm.js`、`BusinessStepProducts.vue` 与模板 `v-if` 中，后续可收敛为单一 `{key, step, label}` 注册表。


