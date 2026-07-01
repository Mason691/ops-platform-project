/**
 * 底座平台认证桥接模块
 *
 * 当 ops-platform 在底座平台 iframe 中运行时，接收来自父窗口的
 * 认证信息（token、用户信息等），写入 uni.storage 供 API 请求使用。
 * 独立运行时（非 iframe），使用用户自主登录的 token。
 */

// 底座平台父窗口 origin，生产环境应限制为底座域名
const BASE_ORIGIN = 'https://boss.jdzn99.com'

/**
 * 初始化底座认证桥接
 * 监听来自底座平台父窗口的 postMessage 事件
 */
export function initAuthBridge() {
  // #ifdef H5
  window.addEventListener('message', (event) => {
    // 校验消息来源（宽松模式：接受来自同源或底座域名的消息）
    if (!isTrustedOrigin(event.origin)) return

    const { type, payload } = event.data || {}
    if (!type || !payload) return

    switch (type) {
      case 'AIP_AUTH':
        handleAuthMessage(payload)
        break
      case 'AIP_NAVIGATE':
        handleNavigateMessage(payload)
        break
      default:
        break
    }
  })

  // 主动向父窗口请求认证信息（iframe 场景）
  if (window.parent !== window) {
    window.parent.postMessage({ type: 'AIP_REQUEST_AUTH' }, '*')
  }
  // #endif
}

function isTrustedOrigin(origin) {
  if (!origin || origin === 'null') return true // file:// 或同源
  try {
    const url = new URL(origin)
    return url.hostname === window.location.hostname ||
           url.href === BASE_ORIGIN
  } catch {
    return false
  }
}

function handleAuthMessage(payload) {
  const { token, corpId, userId, permissions, isAdmin } = payload
  if (token) {
    // 使用 uni 存储 API 保存认证信息
    uni.setStorageSync('ops_token', token)
    if (corpId != null) uni.setStorageSync('corp_id', String(corpId))
    if (userId != null) uni.setStorageSync('ops_user_id', String(userId))
    if (permissions) uni.setStorageSync('ops_permissions', JSON.stringify(permissions))
    if (isAdmin) uni.setStorageSync('ops_is_admin', '1')
  }
}

function handleNavigateMessage(payload) {
  const { path } = payload || {}
  if (!path) return
  // 提取子路径并在 uni-app 内导航
  const basePath = '/aa/ops'
  const subPath = path.startsWith(basePath + '/')
    ? path.slice(basePath.length)
    : path === basePath ? '/pages/index/index' : null
  if (subPath) {
    // 将底座路由映射到 uni-app 页面路由
    // 简单处理：回到首页，由用户自行导航
    const pages = getCurrentPages()
    if (pages.length > 0) {
      uni.navigateTo({ url: '/pages/index/index', fail: () => {} })
    }
  }
}
