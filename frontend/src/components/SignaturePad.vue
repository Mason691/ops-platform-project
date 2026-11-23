<template>
  <view class="signature-pad">
    <view ref="wrapRef" class="signature-wrap">
      <view ref="stageRef" class="signature-stage" :style="stageStyle"></view>
      <view class="signature-guide" :style="stageStyle"></view>
      <view v-if="!hasInk" class="signature-placeholder" :style="stageStyle">
        <text class="placeholder-title">请客户在此签名</text>
        <text class="placeholder-tip">
          {{ landscape ? '横屏书写，笔画更清晰' : '建议横屏握持手机后签名' }}
        </text>
      </view>
    </view>

    <view class="signature-tools">
      <button class="tool-btn" :disabled="!canUndo" @click="undo">撤销</button>
      <button class="tool-btn" :disabled="!hasInk" @click="clear">重写</button>
      <text class="tool-tip">笔画清晰 · 支持撤销</text>
    </view>
  </view>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useSignaturePad } from '@/hooks/useSignaturePad'

/**
 * H5 横屏签名板。
 *
 * <p>画布按容器尺寸自适应：横屏时铺满整块区域；竖屏时使用一块更宽的
 * 横向签名区并居中，避免旧版 340rpx 小框写不下名字。原生 Canvas 由
 * useSignaturePad 创建，Vue 只负责占位/参考线，避免虚拟 DOM 与
 * 命令式 canvas 节点互相覆盖。</p>
 */

const emit = defineEmits(['change'])
const wrapRef = ref(null)
const stageRef = ref(null)
const stageStyle = ref({ left: '0px', top: '0px', width: '0px', height: '0px' })
const landscape = ref(false)

const {
  hasInk,
  canUndo,
  attach: attachPad,
  detach: detachPad,
  configure,
  clear: clearPad,
  undo: undoPad,
  validate,
  toBase64
} = useSignaturePad()

let observer = null
let rafId = 0
let retryTimer = null
let retryCount = 0

watch([hasInk, canUndo], () => {
  emit('change', { hasInk: hasInk.value, canUndo: canUndo.value })
}, { immediate: true })

onMounted(() => {
  nextTick(() => {
    const stage = domOf(stageRef.value)
    if (stage) attachPad(stage)
    const wrap = domOf(wrapRef.value)
    if (wrap && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(scheduleLayout)
      observer.observe(wrap)
    }
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', scheduleLayout)
      window.addEventListener('orientationchange', scheduleLayout)
    }
    layout()
    // 横竖屏切换/gate 收起后 flex 布局可能尚未结算，下一帧再量一次
    setTimeout(layout, 60)
  })
})

onBeforeUnmount(() => {
  if (observer) {
    observer.disconnect()
    observer = null
  }
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', scheduleLayout)
    window.removeEventListener('orientationchange', scheduleLayout)
  }
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
  detachPad()
})

function scheduleLayout() {
  if (typeof requestAnimationFrame === 'undefined') {
    layout()
    return
  }
  if (rafId) return
  rafId = requestAnimationFrame(() => {
    rafId = 0
    layout()
  })
}

function layout() {
  const wrap = domOf(wrapRef.value)
  if (!wrap) return retryLayout()
  const isLandscape = typeof window !== 'undefined'
    && window.innerWidth > window.innerHeight
  landscape.value = isLandscape
  const rect = wrap.getBoundingClientRect()
  const padding = 6
  const availableWidth = Math.max(0, rect.width - padding * 2)
  const availableHeight = Math.max(0, rect.height - padding * 2)
  if (availableWidth < 40 || availableHeight < 40) return retryLayout()
  retryCount = 0

  let height = availableHeight
  let top = padding
  if (!isLandscape) {
    // 竖屏降级：仍给一块横向比例的大签名区，居中留白，避免又回到小框
    height = Math.min(availableHeight, Math.max(260, availableWidth * 0.78))
    top = padding + Math.max(0, (availableHeight - height) / 2)
  }
  stageStyle.value = {
    left: padding + 'px',
    top: top + 'px',
    width: availableWidth + 'px',
    height: height + 'px'
  }
  nextTick(() => configure({ width: availableWidth, height }))
}

/** 容器刚插入时可能量到 0 高度；短间隔重试几次，等 flex 布局结算。 */
function retryLayout() {
  if (retryTimer || retryCount >= 12) return
  retryCount += 1
  retryTimer = setTimeout(() => {
    retryTimer = null
    layout()
  }, 50)
}

function undo() {
  undoPad()
}

function clear() {
  clearPad()
}

function domOf(value) {
  if (!value) return null
  return value.$el || value
}

defineExpose({ validate, toBase64, undo, clear })
</script>

<style scoped>
.signature-pad {
  flex: 1; min-height: 0; display: flex; flex-direction: column;
}
.signature-wrap {
  position: relative; flex: 1; min-height: 0; background: #F7F8FA;
  border-radius: 12px; overflow: hidden;
}
.signature-stage {
  position: absolute; z-index: 0; background: #fff;
  border: 1px solid #E5E6EB; border-radius: 10px;
  box-sizing: border-box; overflow: hidden;
}
.signature-guide {
  position: absolute; z-index: 1; pointer-events: none;
}
.signature-guide::after {
  content: ''; position: absolute; left: 8%; right: 8%; bottom: 20%;
  border-bottom: 1px dashed #E5E6EB;
}
.signature-placeholder {
  position: absolute; z-index: 2; pointer-events: none;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 4px;
}
.placeholder-title { font-size: 14px; color: #4E5969; font-weight: 600; }
.placeholder-tip { font-size: 10px; color: #86909C; }
.signature-tools {
  flex-shrink: 0; height: 42px; display: flex; align-items: center; gap: 8px;
  padding: 0 4px;
}
.tool-btn {
  margin: 0; padding: 0 16px; height: 34px; line-height: 34px;
  border-radius: 17px; font-size: 13px; color: #2F6FEB;
  background: #EEF4FF; border: none;
}
.tool-btn[disabled] { color: #C9CDD4; background: #F2F3F5; }
.tool-tip { margin-left: auto; font-size: 10px; color: #86909C; padding-right: 4px; }
</style>
