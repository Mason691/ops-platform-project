import { createSSRApp } from 'vue'
import App from './App.vue'
import { initAuthBridge } from './utils/auth-bridge.js'
import { initJssdk } from './utils/jssdk.js'

// ── 微信浏览器 document.title 同步（避免双层标题） ──
// 路由路径 → 页面标题映射，与 pages.json 中的 navigationBarTitleText 保持同步
const PAGE_TITLE_MAP = {
  'pages/ai/chat': 'AI 助手',
  'pages/index/index': '金地燃气',
  'pages/activity/index': '精彩活动',
  'pages/activity/detail': '活动详情',
  'pages/product/index': '积分商城',
  'pages/product/detail': '商品详情',
  'pages/mine/index': '我的',
  'pages/order/list': '兑换记录',
  'pages/order/detail': '订单详情',
  'pages/address/list': '收货地址',
  'pages/address/add': '编辑地址',
  'pages/points/log': '积分明细',
  'pages/coupon/list': '优惠券',
  'pages/auth/login': '登录',
  'pages/auth/register': '注册',
  'pages/live/index': '直播广场',
  'pages/booking/list': '我的预约',
  'pages/station/apply': '站长申请',
  'pages/station/center': '站长中心',
  'pages/employee/dashboard': '员工工作台',
  'pages/employee/company-select': '选择公司',
  'pages/employee/business-data': '业务数据',
  'pages/employee/ai-chat': 'AI助手',
}

/**
 * 根据当前 uni-app 路由更新 document.title，
 * 使微信浏览器原生标题栏的文字与 H5 页面内导航栏标题一致。
 */
function syncDocumentTitle() {
  // #ifdef H5
  if (typeof document === 'undefined') return
  try {
    const pages = getCurrentPages()
    if (pages && pages.length > 0) {
      const route = pages[pages.length - 1].route
      document.title = PAGE_TITLE_MAP[route] || '金地燃气'
    } else {
      document.title = '金地燃气'
    }
  } catch (_) {
    document.title = '金地燃气'
  }
  // #endif
}

export function createApp() {
  const app = createSSRApp(App)

  // ── 全局混入：每个页面 onShow 时同步 document.title ──
  app.mixin({
    onShow() {
      syncDocumentTitle()
    },
  })

  // #ifdef H5
  // 初始化底座平台认证桥接（iframe 嵌入模式下监听父窗口 postMessage）
  initAuthBridge()
  // 初始化微信 JSSDK（仅在微信浏览器中生效）
  initJssdk()
  // #endif
  return { app }
}
