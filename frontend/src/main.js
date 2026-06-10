import { createSSRApp } from 'vue'
import App from './App.vue'
import { initAuthBridge } from './utils/auth-bridge.js'
import { initJssdk } from './utils/jssdk.js'

export function createApp() {
  const app = createSSRApp(App)
  // #ifdef H5
  // 初始化底座平台认证桥接（iframe 嵌入模式下监听父窗口 postMessage）
  initAuthBridge()
  // 初始化微信 JSSDK（仅在微信浏览器中生效）
  initJssdk()
  // #endif
  return { app }
}
