/**
 * 移动端表单定位与聚焦工具。
 *
 * H5（公众号）下直接操作 DOM：字段容器 id 由组件统一命名，点击标签、
 * 校验失败或输入框聚焦时滚动到可视区中部，避免被键盘/固定底栏遮挡。
 * 小程序端没有 DOM，退化为静默 no-op，保证编译与运行安全。
 */

const CENTER = { block: 'center', inline: 'nearest' }

function elementOf(id) {
  // #ifdef H5
  if (typeof document === 'undefined') return null
  return document.getElementById(id)
  // #endif
  // #ifndef H5
  return null
  // #endif
}

/** 聚焦字段内的输入控件（textarea 优先于 input）。 */
export function focusField(id) {
  const el = elementOf(id)
  if (!el) return
  // #ifdef H5
  const target = el.querySelector('textarea, input')
  if (target && typeof target.focus === 'function') {
    target.focus({ preventScroll: true })
  }
  // #endif
}

/** 滚动到指定字段容器中部。 */
export function scrollToField(id, behavior = 'smooth') {
  const el = elementOf(id)
  if (!el) {
    // 小程序/找不到元素时退回 uni 的选择器滚动
    try {
      uni.pageScrollTo({ selector: '#' + id, duration: 200 })
    } catch (e) {
      // 忽略：页面可能尚未渲染
    }
    return
  }
  // #ifdef H5
  try {
    el.scrollIntoView({ ...CENTER, behavior })
  } catch (e) {
    if (typeof el.scrollIntoView === 'function') el.scrollIntoView()
  }
  // #endif
}

/** 输入聚焦后延迟滚动，等待键盘弹起后再居中。 */
export function keepFieldVisible(id) {
  setTimeout(() => scrollToField(id), 260)
}

/** 校验失败时的轻微震动（微信内支持则生效）。 */
export function vibrateLight() {
  try {
    if (typeof uni !== 'undefined' && typeof uni.vibrateShort === 'function') {
      uni.vibrateShort({ type: 'light' })
    }
  } catch (e) {
    // 部分浏览器不支持震动，忽略
  }
}
