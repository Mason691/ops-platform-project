import { getCurrentInstance, ref } from 'vue'

/**
 * 小程序端手写签名兜底（uni-app Canvas）。
 *
 * <p>公众号 H5 使用 SignaturePad.vue + useSignaturePad（原生 Canvas、高清、
 * 横屏大区域）；本 hook 仅保证 mp-weixin 构建可用，接口与 H5 版本对齐
 * （attach/detach/undo/clear/validate/toBase64）。</p>
 */
export function useUniSignature(canvasId) {
  const instance = getCurrentInstance()
  const hasInk = ref(false)
  // 小程序端不做笔画历史，撤销按钮不展示；保留字段统一接口
  const canUndo = ref(false)
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

  function reset() {
    const c = context()
    c.setFillStyle('#FFFFFF')
    c.fillRect(0, 0, 1000, 1000)
    c.draw()
    hasInk.value = false
    canUndo.value = false
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

  function undo() {
    // 小程序端无笔画栈，撤销即清空（按钮默认不展示）
    reset()
  }

  function attach() {
    ctx = null
    reset()
  }

  function detach() {
    // 小程序 canvas 随页面存在，无需额外清理
  }

  function validate() {
    return hasInk.value
      ? { ok: true, message: '' }
      : { ok: false, message: '请客户完成手写签字' }
  }

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

  return { hasInk, canUndo, start, move, end, clear, undo, attach, detach, validate, toBase64 }
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
