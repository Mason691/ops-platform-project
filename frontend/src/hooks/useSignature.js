import { getCurrentInstance, ref } from 'vue'

/**
 * Canvas 手写签名（uni-app H5 / 小程序通用）。
 *
 * <p>白底、圆角线段、随屏宽自适应；导出 PNG Base64 供客户告知书归档。
 * 组件内调用：`const sign = useSignature('customer-sign')`，模板中
 * `<canvas canvas-id="customer-sign" id="customer-sign" ...>` 绑定
 * `@touchstart="sign.start" @touchmove="sign.move" @touchend="sign.end"`。</p>
 */
export function useSignature(canvasId) {
  const instance = getCurrentInstance()
  const hasInk = ref(false)
  let ctx = null
  let drawing = false

  function context() {
    if (ctx) return ctx
    ctx = uni.createCanvasContext(canvasId, instance && instance.proxy)
    ctx.setStrokeStyle('#1D2129')
    ctx.setLineWidth(3)
    ctx.setLineCap('round')
    ctx.setLineJoin('round')
    return ctx
  }

  /** 白底：签字图片打印/归档时不透明，避免部分阅读器把透明区显示为黑块。 */
  function reset() {
    const c = context()
    c.setFillStyle('#FFFFFF')
    c.fillRect(0, 0, 1000, 1000)
    c.draw()
    hasInk.value = false
    drawing = false
  }

  function start(event) {
    const point = eventPoint(event)
    if (!point) return
    const c = context()
    c.beginPath()
    c.moveTo(point.x, point.y)
    drawing = true
    hasInk.value = true
  }

  function move(event) {
    if (!drawing) return
    const point = eventPoint(event)
    if (!point) return
    const c = context()
    c.lineTo(point.x, point.y)
    c.stroke()
    c.draw(true)
  }

  function end() {
    drawing = false
  }

  function clear() {
    reset()
  }

  /** 弹层重新打开时 Canvas 是新的 DOM 节点，必须丢弃旧 context 再初始化白底。 */
  function attach() {
    ctx = null
    reset()
  }

  /** 导出 PNG（data:image/png;base64,...）；无笔迹时 reject。 */
  function toBase64() {
    if (!hasInk.value) {
      return Promise.reject(new Error('请客户完成手写签字'))
    }
    return new Promise((resolve, reject) => {
      uni.canvasToTempFilePath({
        canvasId,
        fileType: 'png',
        quality: 1,
        success: res => toDataUrl(res.tempFilePath).then(resolve).catch(reject),
        fail: () => reject(new Error('签字图片生成失败，请重试'))
      }, instance && instance.proxy)
    })
  }

  function eventPoint(event) {
    const touch = (event.touches && event.touches[0])
      || (event.changedTouches && event.changedTouches[0])
      || event.detail
    if (!touch) return null
    if (typeof touch.x === 'number' && typeof touch.y === 'number') {
      return { x: touch.x, y: touch.y }
    }
    // #ifdef H5
    if (typeof touch.clientX === 'number' && event.currentTarget) {
      const rect = event.currentTarget.getBoundingClientRect()
      return { x: touch.clientX - rect.left, y: touch.clientY - rect.top }
    }
    // #endif
    return null
  }

  return { hasInk, start, move, end, clear, reset, attach, toBase64 }
}

function toDataUrl(tempFilePath) {
  // #ifdef H5
  return fetch(tempFilePath)
    .then(resp => resp.blob())
    .then(blob => new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result || ''))
      reader.onerror = () => reject(new Error('签字图片读取失败'))
      reader.readAsDataURL(blob)
    }))
  // #endif
  // #ifndef H5
  return new Promise((resolve, reject) => {
    uni.getFileSystemManager().readFile({
      filePath: tempFilePath,
      encoding: 'base64',
      success: res => resolve('data:image/png;base64,' + res.data),
      fail: () => reject(new Error('签字图片读取失败'))
    })
  })
  // #endif
}
