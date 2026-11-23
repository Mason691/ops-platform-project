/**
 * H5 视口工具：只负责尺寸/横竖屏判断与变化监听。
 *
 * <p>不再依赖 Fullscreen / Screen Orientation API：微信与 iOS 支持不稳定，
 * 横屏签字由页面自身 CSS 旋转实现（见 CustomerSign.vue）。</p>
 */

export function getViewportSize() {
  if (typeof window === 'undefined') return { width: 0, height: 0 }
  return { width: window.innerWidth, height: window.innerHeight }
}

export function isLandscapeViewport() {
  const { width, height } = getViewportSize()
  return width > height
}

/**
 * 监听旋转、地址栏收起、全屏进出等视口变化。
 * @returns 取消监听函数
 */
export function onViewportChange(handler) {
  if (typeof window === 'undefined') return () => {}
  const run = () => handler()
  window.addEventListener('resize', run)
  window.addEventListener('orientationchange', run)
  let orientation = null
  try {
    if (typeof screen !== 'undefined' && screen.orientation
        && typeof screen.orientation.addEventListener === 'function') {
      orientation = screen.orientation
      orientation.addEventListener('change', run)
    }
  } catch (e) {
    // 方向事件不可用时仅依赖 resize
  }
  return () => {
    window.removeEventListener('resize', run)
    window.removeEventListener('orientationchange', run)
    try {
      if (orientation) orientation.removeEventListener('change', run)
    } catch (e) {
      // ignore
    }
  }
}
