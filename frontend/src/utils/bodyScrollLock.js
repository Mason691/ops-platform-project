/**
 * H5 页面滚动锁（引用计数）。
 *
 * 页面可能同时存在多个底部弹层实例；如果任一实例卸载时直接把
 * body.overflow 清空，会把仍处于打开状态的弹层的锁一并解除。
 * 这里用模块级计数，只有最后一个弹层关闭/卸载时才恢复滚动。
 */

let lockCount = 0

export function lockBodyScroll() {
  // #ifdef H5
  if (typeof document === 'undefined') return
  lockCount += 1
  if (lockCount === 1) document.body.style.overflow = 'hidden'
  // #endif
}

export function unlockBodyScroll() {
  // #ifdef H5
  if (typeof document === 'undefined') return
  if (lockCount > 0) lockCount -= 1
  if (lockCount === 0) document.body.style.overflow = ''
  // #endif
}
