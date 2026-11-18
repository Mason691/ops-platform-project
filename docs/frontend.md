# 前端架构、路由与组件

> 从 `CLAUDE.md` 拆分（2026-11-16）。两套前端（ops H5 与平台主站）的隔离规则见 `CLAUDE.md`「已知陷阱」。

---

## 前端架构

- **无 Pinia/Vuex**：`uni.getStorageSync()` 持久化 token/user，页面 `ref()` 管理本地状态
- **无 Vue Router**：uni-app 文件路由（`pages.json`），`uni.navigateTo()` + URL 参数
- **iframe SSO**：`auth-bridge.js` 监听 `postMessage`（`AIP_AUTH` / `AIP_NAVIGATE`），信任同源和 `boss.jdzn99.com`
- **公众号深链**：`App.vue` 支持 `?route=pages/employee/login` 与 `?from=employee` 查询参数跳转（公众号菜单不支持 hash）
- **API 集中管理**：`frontend/src/api/index.js` 导出 `api` 对象（约 171 个方法），统一使用 `request` 实例
- **H5 hash 路由**：base 由构建时 `VITE_BASE` 环境变量控制

## 前端页面路由（pages.json）

入口页：`pages/ai/chat`（AI 优先）。5 个底部 Tab：

| Tab | 页面 | 说明 |
|-----|------|------|
| AI助手 | `pages/ai/chat`, `pages/ai/settings` | AI 对话（语音输入、TTS、个性化设置）— **入口页** |
| 商品 | `pages/product/index`, `pages/product/detail` | 积分商城 |
| 首页 | `pages/index/index` | 轮播/视频/推荐/直播/优惠券 |
| 活动 | `pages/activity/index`, `pages/activity/detail`, `pages/live/index` | 活动 + 直播广场 |
| 我的 | `pages/mine/index`, `pages/order/*`, `pages/address/*`, `pages/points/log`, `pages/coupon/list`, `pages/auth/*`, `pages/booking/list` | 用户中心 |

管理页面（已注册，不在 Tab 中）：`pages/admin/commission-rules`, `pages/admin/gas-user`, `pages/admin/annual-plan`, `pages/admin/live-room-manage`  
管理页面（文件存在，未注册到 pages.json，由平台主站 componentMap 加载）：`pages/admin/activity-form`, `pages/admin/home-config`, `pages/admin/business-data`, `pages/admin/members`  
站长页面：`pages/station/apply`, `pages/station/center`  
员工页面（已注册）：`pages/employee/login`、`pages/employee/wechat-callback`（微信授权回调）、`pages/employee/ai-chat`（AI 助手 H5）、`pages/employee/dashboard`、`pages/employee/business-data`、`pages/employee/business-data-new`（手工新增业务 4 步向导）、`pages/employee/verified-business`（已核对业务 + 发起采购申请；直接调 SCM `/api/scm/pur-order/h5/*`，由 host nginx 转发、携带员工 JWT，不经 ops 后端）、`pages/employee/flow-todo`（工作流待办列表）、`pages/employee/flow-task`（任务处理，`businessType` 区分 `OPS_BUSINESS_ORDER` 与 SCM `PUR_ORDER` 审批）、`pages/employee/work-log`（工作业务日志列表，业务类型/动作/日期筛选 + 分页）、`pages/employee/work-log-detail`（日志详情，附件预览与业务单据跳转）、`pages/employee/order-detail`（员工订单详情，含流程进度与节点处理记录）、`pages/employee/profile`  
员工页面（未注册）：`pages/employee/company-select`（见陷阱表）  
员工 H5 深链：公众号模板消息跳 `https://boss.jdzn99.com/ops/?route=pages/employee/flow-task&taskId=..&instanceId=..`，未登录跳 `login?redirect=<完整参数>`，登录后回跳；缺参展示友好提示，不抛技术错误。

## 关键前端组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `EmployeeTabBar` | `components/EmployeeTabBar.vue` | 员工端底部导航栏（AI助手/主页/待办/我的） |
| `BusinessStepBasic` / `BusinessStepProducts` / `BusinessStepPayment` / `BusinessStepVoucher` / `BusinessStepConfirm` / `BusinessWizardSteps` / `BusinessSuccess` | `components/BusinessStep*.vue` 等 | 员工手工新增业务 5 步向导（2026-11-18 收款信息拆分为支付信息/凭证信息），状态机在 `hooks/useBusinessWizard.js`，校验共用 `utils/businessForm.js` |
| `BusinessAgentPicker` / `BusinessProductCard` / `BusinessSpecialApproval` / `BusinessSpecialFileUploader` / `BusinessAttachmentUploader` / `BusinessRefundSheet` / `BusinessCommunityCreateSheet` / `BusinessMonthList` | `components/Business*.vue` | 经办人勾选、产品行（部长及以上可展开零售底价）、低价特批文件与提成比例、收据图片原图上传/支付凭证与合同图片压缩（`imageCompress.js`）、退款申请表单、制单新建小区弹层、月度明细 |
| `OrgSwitcher` / `OrgSwitchSheet` | `components/OrgSwitch*.vue` | 员工多组织切换入口与底部弹层（复用底座 orgs/switch-org） |
| `EmployeePasswordLogin` / `EmployeeWechatLogin` / `EmployeeWechatBindCard` | `components/Employee*Login*.vue` 等 | 员工登录页三块：密码登录、微信授权登录、微信绑定卡片 |
| `PaymentQr` | `components/PaymentQr.vue` | 鉴权收款码展示（员工制单收款步骤，取图后逐次 SHA-256 校验） |
| `ImageCropperModal` | `components/ImageCropperModal.vue` | 图片裁剪上传弹窗，uni-app `<image>` 的 `mode` 裁剪封装 |
| `LiveStatusCard` | `components/LiveStatusCard.vue` | 直播状态卡片（预告/直播中/结束 + 开播倒计时） |
| `Pager` | `components/Pager.vue` | 通用分页器，配合列表页使用 |

---

