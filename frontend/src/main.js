import { createSSRApp } from 'vue'
import App from './App.vue'
import { initAuthBridge } from './utils/auth-bridge.js'

export function createApp() {
  const app = createSSRApp(App)
  // 初始化底座平台认证桥接（H5 模式下监听父窗口 postMessage）
  // #ifdef H5
  initAuthBridge()
  // #endif
  return { app }
}
