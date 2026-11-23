import { computed, ref } from 'vue'

/**
 * H5 手写签名画板：原生 Canvas + Pointer Events。
 *
 * <p>相比 uni-app canvas：</p>
 * <ul>
 *   <li>按 devicePixelRatio 放大画布，导出 PNG 不再模糊；</li>
 *   <li>中点二次贝塞尔平滑，快速书写也不出现折线；</li>
 *   <li>笔画以归一化坐标保存，横竖屏切换后不会错位；</li>
 *   <li>支持撤销/重写，避免客户写错只能整页重来。</li>
 * </ul>
 *
 * <p>仅用于 H5（公众号工作台）；小程序端由 useUniSignature 兜底。</p>
 */

const INK_COLOR = '#1D2129'
const INK_WIDTH = 3.2
const MIN_PATH_LENGTH = 36

export function useSignaturePad() {
  const hasInk = ref(false)
  const canUndo = ref(false)
  const isEmpty = computed(() => !hasInk.value)

  let canvas = null
  let ctx = null
  let width = 0
  let height = 0
  let dpr = 1
  /** 画布是否被外层 CSS 顺时针旋转 90°（竖屏强制横屏签字）。 */
  let rotation = 0

  /** 笔画列表：points 使用归一化坐标 {nx, ny}，尺寸变化时按新宽高重绘。 */
  let strokes = []
  let current = null
  let drawing = false
  let activePointer = null
  let lastLocal = null
  let lastMid = null
  let bound = false

  function attach(host) {
    if (!host || typeof document === 'undefined') return
    detach()
    canvas = document.createElement('canvas')
    canvas.className = 'signature-native-canvas'
    canvas.setAttribute('aria-label', '客户手写签名区域')
    canvas.style.cssText = 'display:block;width:100%;height:100%;touch-action:none;cursor:crosshair;'
    host.appendChild(canvas)
    ctx = canvas.getContext('2d')
    width = 0
    height = 0
    bindEvents()
  }

  function detach() {
    unbindEvents()
    if (canvas && canvas.parentNode) {
      try { canvas.parentNode.removeChild(canvas) } catch (e) { /* 已移除 */ }
    }
    canvas = null
    ctx = null
    width = 0
    height = 0
    drawing = false
    activePointer = null
    lastLocal = null
    lastMid = null
    current = null
  }

  /** 由组件在布局完成后调用；尺寸变化时保留笔画并按比例重绘。 */
  function configure(size) {
    if (!canvas || !size) return
    const nextWidth = Math.max(1, Math.round(Number(size.width) || 0))
    const nextHeight = Math.max(1, Math.round(Number(size.height) || 0))
    const nextDpr = Math.min(
      typeof window !== 'undefined' && window.devicePixelRatio ? window.devicePixelRatio : 1,
      3)
    rotation = Number(size.rotation) === 90 ? 90 : 0
    if (nextWidth === width && nextHeight === height && nextDpr === dpr && ctx) return
    if (drawing) finish()
    width = nextWidth
    height = nextHeight
    dpr = nextDpr
    const pixelWidth = Math.round(width * dpr)
    const pixelHeight = Math.round(height * dpr)
    if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
      canvas.width = pixelWidth
      canvas.height = pixelHeight
    }
    ctx = canvas.getContext('2d')
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    redraw()
  }

  function clear() {
    if (drawing) finish()
    strokes = []
    hasInk.value = false
    canUndo.value = false
    redraw()
  }

  function undo() {
    if (drawing) finish()
    if (!strokes.length) return
    strokes.pop()
    hasInk.value = strokes.length > 0
    canUndo.value = strokes.length > 0
    redraw()
  }

  function validate() {
    if (!hasInk.value || !strokes.length) {
      return { ok: false, message: '请客户完成手写签字' }
    }
    if (pathLength() < MIN_PATH_LENGTH) {
      return { ok: false, message: '签名笔画过短，请完整签写姓名' }
    }
    return { ok: true, message: '' }
  }

  function toBase64() {
    if (!canvas) throw new Error('签字区域未就绪，请重试')
    if (!hasInk.value) throw new Error('请客户完成手写签字')
    return canvas.toDataURL('image/png')
  }

  // ── 绘制 ──────────────────────────────────────────────────────────

  function redraw() {
    if (!ctx) return
    ctx.save()
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, width, height)
    ctx.fillStyle = '#FFFFFF'
    ctx.fillRect(0, 0, width, height)
    for (const stroke of strokes) drawStrokePath(stroke)
    ctx.restore()
  }

  function drawStrokePath(stroke) {
    const points = stroke.points.map(point => ({
      x: point.nx * width,
      y: point.ny * height
    }))
    if (!points.length) return
    ctx.strokeStyle = INK_COLOR
    ctx.fillStyle = INK_COLOR
    ctx.lineWidth = stroke.width || INK_WIDTH
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    if (points.length === 1) {
      ctx.beginPath()
      ctx.arc(points[0].x, points[0].y, ctx.lineWidth / 2, 0, Math.PI * 2)
      ctx.fill()
      return
    }
    ctx.beginPath()
    ctx.moveTo(points[0].x, points[0].y)
    for (let i = 1; i < points.length - 1; i++) {
      const mid = midpoint(points[i], points[i + 1])
      ctx.quadraticCurveTo(points[i].x, points[i].y, mid.x, mid.y)
    }
    const last = points[points.length - 1]
    ctx.lineTo(last.x, last.y)
    ctx.stroke()
  }

  function begin(local) {
    if (!ctx || width <= 0 || height <= 0) return
    current = {
      width: INK_WIDTH,
      points: [{ nx: local.x / width, ny: local.y / height }]
    }
    strokes.push(current)
    hasInk.value = true
    canUndo.value = true
    lastLocal = local
    lastMid = local
    drawDot(local)
  }

  function drawDot(local) {
    if (!ctx) return
    ctx.beginPath()
    ctx.fillStyle = INK_COLOR
    ctx.arc(local.x, local.y, INK_WIDTH / 2, 0, Math.PI * 2)
    ctx.fill()
  }

  function extend(local) {
    if (!current || !lastLocal || !ctx) return
    if (distance(lastLocal, local) < 0.6) return
    const mid = midpoint(lastLocal, local)
    ctx.beginPath()
    ctx.moveTo(lastMid.x, lastMid.y)
    ctx.quadraticCurveTo(lastLocal.x, lastLocal.y, mid.x, mid.y)
    ctx.strokeStyle = INK_COLOR
    ctx.lineWidth = current.width
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.stroke()
    current.points.push({ nx: local.x / width, ny: local.y / height })
    lastMid = mid
    lastLocal = local
  }

  function finish() {
    if (current && lastLocal && lastMid && ctx) {
      ctx.beginPath()
      ctx.moveTo(lastMid.x, lastMid.y)
      ctx.lineTo(lastLocal.x, lastLocal.y)
      ctx.strokeStyle = INK_COLOR
      ctx.lineWidth = current.width
      ctx.lineCap = 'round'
      ctx.stroke()
    }
    current = null
    drawing = false
    activePointer = null
    lastLocal = null
    lastMid = null
  }

  // ── 指针事件 ──────────────────────────────────────────────────────

  function bindEvents() {
    if (!canvas || bound) return
    if (typeof window !== 'undefined' && 'PointerEvent' in window) {
      canvas.addEventListener('pointerdown', onPointerDown, { passive: false })
      canvas.addEventListener('pointermove', onPointerMove, { passive: false })
      canvas.addEventListener('pointerup', onPointerUp)
      canvas.addEventListener('pointercancel', onPointerUp)
    } else {
      canvas.addEventListener('touchstart', onTouchStart, { passive: false })
      canvas.addEventListener('touchmove', onTouchMove, { passive: false })
      canvas.addEventListener('touchend', onTouchEnd, { passive: false })
      canvas.addEventListener('touchcancel', onTouchEnd, { passive: false })
      canvas.addEventListener('mousedown', onMouseDown)
      window.addEventListener('mousemove', onMouseMove)
      window.addEventListener('mouseup', onMouseUp)
    }
    bound = true
  }

  function unbindEvents() {
    if (!bound || !canvas) return
    if (typeof window !== 'undefined' && 'PointerEvent' in window) {
      canvas.removeEventListener('pointerdown', onPointerDown)
      canvas.removeEventListener('pointermove', onPointerMove)
      canvas.removeEventListener('pointerup', onPointerUp)
      canvas.removeEventListener('pointercancel', onPointerUp)
    } else {
      canvas.removeEventListener('touchstart', onTouchStart)
      canvas.removeEventListener('touchmove', onTouchMove)
      canvas.removeEventListener('touchend', onTouchEnd)
      canvas.removeEventListener('touchcancel', onTouchEnd)
      canvas.removeEventListener('mousedown', onMouseDown)
      window.removeEventListener('mousemove', onMouseMove)
      window.removeEventListener('mouseup', onMouseUp)
    }
    bound = false
  }

  function onPointerDown(event) {
    if (event.pointerType === 'mouse' && event.button !== 0) return
    if (drawing) return
    event.preventDefault()
    drawing = true
    activePointer = event.pointerId
    try { canvas.setPointerCapture(event.pointerId) } catch (e) { /* 某些浏览器不支持 */ }
    begin(toLocal(event.clientX, event.clientY))
  }

  function onPointerMove(event) {
    if (!drawing || event.pointerId !== activePointer) return
    event.preventDefault()
    extend(toLocal(event.clientX, event.clientY))
  }

  function onPointerUp(event) {
    if (!drawing) return
    if (activePointer != null && event.pointerId !== activePointer) return
    finish()
  }

  function onTouchStart(event) {
    if (drawing) return
    const point = touchPoint(event)
    if (!point) return
    event.preventDefault()
    drawing = true
    begin(toLocal(point.x, point.y))
  }

  function onTouchMove(event) {
    if (!drawing) return
    const point = touchPoint(event)
    if (!point) return
    event.preventDefault()
    extend(toLocal(point.x, point.y))
  }

  function onTouchEnd(event) {
    if (!drawing) return
    event.preventDefault()
    finish()
  }

  function onMouseDown(event) {
    if (event.button !== 0 || drawing) return
    event.preventDefault()
    drawing = true
    begin(toLocal(event.clientX, event.clientY))
  }

  function onMouseMove(event) {
    if (!drawing) return
    extend(toLocal(event.clientX, event.clientY))
  }

  function onMouseUp() {
    if (drawing) finish()
  }

  function touchPoint(event) {
    const touch = (event.touches && event.touches[0])
      || (event.changedTouches && event.changedTouches[0])
    return touch ? { x: touch.clientX, y: touch.clientY } : null
  }

  function toLocal(clientX, clientY) {
    const rect = canvas.getBoundingClientRect()
    if (rotation === 90) {
      // 画布随外层顺时针旋转 90°：以画布中心为支点做逆变换。
      const centerX = rect.left + rect.width / 2
      const centerY = rect.top + rect.height / 2
      const dx = clientX - centerX
      const dy = clientY - centerY
      return {
        x: clamp(width / 2 + dy, 0, width),
        y: clamp(height / 2 - dx, 0, height)
      }
    }
    const scaleX = rect.width > 0 ? width / rect.width : 1
    const scaleY = rect.height > 0 ? height / rect.height : 1
    return {
      x: clamp((clientX - rect.left) * scaleX, 0, width),
      y: clamp((clientY - rect.top) * scaleY, 0, height)
    }
  }

  function pathLength() {
    let total = 0
    for (const stroke of strokes) {
      const points = stroke.points
      for (let i = 1; i < points.length; i++) {
        total += Math.hypot(
          (points[i].nx - points[i - 1].nx) * width,
          (points[i].ny - points[i - 1].ny) * height)
      }
    }
    return total
  }

  return {
    hasInk,
    canUndo,
    isEmpty,
    attach,
    detach,
    configure,
    clear,
    undo,
    validate,
    toBase64
  }
}

function midpoint(a, b) {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
}

function distance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}
