## 标题

feat: H5 视频号跳转全面优化 — 统一 finder-preview URL 并新增主页入口

## 动机

金厨美家公众号 H5 跳转视频号直播时持续黑屏，根因是 `wx.openChannelsLive` JSSDK API 要求公众号与视频号主体一致，主体不一致时 API 静默失败（`wx.openFinderView begin/end` 后无画面）。

解决方案分三步：
1. **统一跳转方式**：所有页面改用 `finder-preview` URL 跳转（`https://channels.weixin.qq.com/finder-preview/pages/profile?finderUserName=...`），绕过 JSSDK 主体校验
2. **新增主页入口**：活动详情页直播间卡片下方新增「前往视频号主页」按钮，引导用户访问频道主页
3. **修复 URL 端点**：从 `pages/live` 改为 `pages/profile`，`pages/live` 仅在频道正在直播时有画面，`pages/profile` 始终可渲染

## 模块级变更列表

### `frontend/src/utils/jssdk.js` — 核心跳转逻辑
- `getFinderPreviewUrl()` URL 从 `pages/live` 改为 `pages/profile`
- 新增 `openChannelsProfile(finderUsername)` — 跳转视频号主页
- 新增 `getChannelsProfileUrl(finderUsername)` — 生成主页 URL

### `frontend/src/pages/activity/detail.vue` — 活动详情页
- 直播卡片下方新增「前往视频号主页」入口（🏠 图标 + 箭号）
- 新增 `openChannelHome()` 函数，复用已有非微信引导弹窗

### `frontend/src/pages/index/index.vue` — 首页
- `openLive()` 移除内联 `wx.openChannelsLive` 调用（-19 行）
- 改为调用 `openChannelsLive()` from jssdk

### `frontend/src/pages/product/index.vue` — 商品页
- `openLive()` 移除内联 `wx.openChannelsLive` 调用（-15 行）
- 改为调用 `openChannelsLive()` from jssdk

### `frontend/src/pages/live/index.vue` — 直播广场
- `openLive()` 移除内联 `wx.openChannelsLive` 调用（-20 行）
- 改为调用 `openChannelsLive()` from jssdk

## 影响范围

| 范围 | 说明 |
|------|------|
| **受影响页面** | 首页、商品页、直播广场、活动详情页 — 共 4 个 H5 页面 |
| **受影响组件** | `LiveStatusCard.vue`（已使用 jssdk，无需改动） |
| **用户体验变化** | 非微信环境保持 toast/引导弹窗不变；微信内从 `wx.openChannelsLive` → `window.location.href` 跳转 |
| **后端** | 无影响 |
| **净代码** | +65 / -63 行，删除 63 行冗余的 `realWx` 检测和 fallback 逻辑 |

## 测试建议

1. **微信内测试**（重点）：
   - 从首页直播入口点击 → 应跳转到视频号主页
   - 从商品页直播 banner 点击 → 同上
   - 从直播广场点击直播间卡片 → 同上
   - 从活动详情页点击「前往视频号主页」→ 同上
   - 从活动详情页点击「📺 观看直播」→ 同上
2. **非微信浏览器测试**：
   - 各入口点击应弹出引导遮罩/提示，不会跳转
3. **边界条件**：
   - `finderUsername` 为空 → toast "直播信息不完整"
   - 无活跃直播间时 → 频道主页正常显示

## 风险提示

- **低风险**：仅前端跳转方式变更，不涉及后端/数据库
- **已知限制**：`finder-preview/pages/profile` 跳转后展示的是视频号主页而非具体直播间；若频道正在直播，直播入口会突出显示在主页头部
- **回滚方案**：如有问题，将 `getFinderPreviewUrl` 中 `pages/profile` 改回 `pages/live` 即可
