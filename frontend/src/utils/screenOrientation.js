/**
 * H5 横屏辅助：优先 Fullscreen + Screen Orientation API，失败时由页面
 * 提示用户手动旋转。仅被 H5 签字组件引用，小程序端不会加载。
 */

let requestedFullscreen = false

export function isLandscapeViewport() {
  return typeof window !== 'undefined' && window.innerWidth > window.innerHeight
}

/**
 * 尝试进入全屏并锁定横屏。
 * iOS Safari/微信 WebView 通常不支持元素全屏或 orientation.lock，此时
 * 静默失败，返回当前视口是否已经横屏，由调用方展示“请手动横屏”提示。
 */
export async function requestLandscape(element) {
  if (isLandscapeViewport()) return true
  if (typeof document !== 'undefined' && element && element.requestFullscreen
      && !document.fullscreenElement) {
    try {
      await element.requestFullscreen()
      requestedFullscreen = true
    } catch (e) {
      requestedFullscreen = false
    }
  }
  try {
    if (typeof screen !== 'undefined' && screen.orientation
        && typeof screen.orientation.lock === 'function') {
      await screen.orientation.lock('landscape')
      return true
    }
  } catch (e) {
    // 不支持方向锁定：交给用户手动旋转
  }
  return isLandscapeViewport()
}

/** 关闭签字页时解除方向锁定并退出由本页发起的全屏。 */
export function exitLandscape() {
  try {
    if (typeof screen !== 'undefined' && screen.orientation
        && typeof screen.orientation.unlock === 'function') {
      screen.orientation.unlock()
    }
  } catch (e) {
    // ignore
  }
  try {
    if (requestedFullscreen && typeof document !== 'undefined'
        && document.fullscreenElement && document.exitFullscreen) {
      document.exitFullscreen().catch(() => {})
    }
  } catch (e) {
    // ignore
  }
  requestedFullscreen = false
}

/**
 * 监听视口变化（旋转、全屏进出、地址栏收起）。
 * @returns 取消监听函数
 */
export function onViewportChange(handler) {
  if (typeof window === 'undefined') return () => {}
  const run = () => handler()
  window.addEventListener('resize', run)
  window.addEventListener('orientationchange', run)
  return () => {
    window.removeEventListener('resize', run)
    window.removeEventListener('orientationchange', run)
  }
}
