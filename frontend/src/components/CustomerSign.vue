<template>
  <!-- #ifdef H5 -->
  <view v-if="visible" class="cs-overlay">
    <view class="cs-rotate-wrap" :style="rotateWrapStyle">
      <view class="cs-sheet">
        <view class="cs-head">
        <text class="cs-title">
          {{ mode === 'notice' ? (resign ? '重新签署告知书' : '客户签字确认') : '客户签字' }}
        </text>
        <text class="cs-close" @click="close">×</text>
      </view>

      <template v-if="mode === 'notice'">
        <scroll-view scroll-y class="cs-body">
          <CustomerNoticeContent :content="content" :existing-notice="existingNotice"
                                 :resign="resign" />
          <view class="cs-tip-card">
            <text class="cs-tip-title">横屏大字签字</text>
            <text class="cs-tip-text">
              进入后将全屏展示签字区域，横屏书写更清晰，支持撤销与重写；
              客户签完后点击「客户确认签字」。
            </text>
          </view>
        </scroll-view>
        <view class="cs-foot">
          <button class="cs-btn ghost" :disabled="submitting" @click="close">取消</button>
          <button class="cs-btn primary" :disabled="!contentReady || submitting"
                  @click="startSign">进入横屏签字</button>
        </view>
      </template>

      <template v-else>
        <view v-if="showGate" class="cs-gate">
          <text class="cs-gate-icon">↻</text>
          <text class="cs-gate-title">建议横屏签字</text>
          <text class="cs-gate-text">横屏后签字区域更大、笔画更清晰；点下方按钮立即切换</text>
          <button class="cs-btn primary cs-gate-btn" :disabled="submitting"
                  @click="chooseLandscape">横屏签字</button>
          <text class="cs-gate-fallback" @click="choosePortrait">竖屏签字</text>
        </view>

        <template v-else>
          <view class="cs-sign-bar">
            <text class="cs-link" @click="showNotice = true">查看告知书</text>
            <text class="cs-sign-hint">{{ signHint }}</text>
          </view>
          <SignaturePad ref="padRef" class="cs-pad" :rotation="rotatePanel ? 90 : 0"
                        @change="onPadChange" />
          <view class="cs-foot">
            <button class="cs-btn ghost" :disabled="submitting" @click="close">取消</button>
            <button class="cs-btn primary" :disabled="submitting || !padHasInk"
                    @click="submit">{{ submitting ? '提交中...' : '客户确认签字' }}</button>
          </view>
        </template>

        <view v-if="showNotice" class="cs-notice-mask" @click.self="showNotice = false">
          <view class="cs-notice-sheet">
            <view class="cs-notice-head">
              <text class="cs-notice-title">施工用户告知书</text>
              <text class="cs-close" @click="showNotice = false">×</text>
            </view>
            <scroll-view scroll-y class="cs-notice-body">
              <CustomerNoticeContent :content="content" :existing-notice="existingNotice"
                                     :resign="resign" />
            </scroll-view>
          </view>
        </view>
      </template>
      </view>
    </view>
  </view>
  <!-- #endif -->

  <!-- #ifndef H5 -->
  <view v-if="visible" class="cs-overlay">
    <view class="cs-sheet">
      <view class="cs-head">
        <text class="cs-title">{{ resign ? '重新签署告知书' : '客户签字确认' }}</text>
        <text class="cs-close" @click="close">×</text>
      </view>
      <scroll-view scroll-y class="cs-body">
        <CustomerNoticeContent :content="content" :existing-notice="existingNotice"
                               :resign="resign" />
        <view class="cs-sign-bar">
          <text class="cs-sign-hint">客户手写签名</text>
          <text class="cs-link" @click="uniSign.clear">清除重写</text>
        </view>
        <canvas class="cs-uni-canvas" canvas-id="customer-sign" id="customer-sign"
                disable-scroll="true" @touchstart="uniSign.start"
                @touchmove.stop.prevent="uniSign.move" @touchend="uniSign.end" />
      </scroll-view>
      <view class="cs-foot">
        <button class="cs-btn ghost" :disabled="submitting" @click="close">取消</button>
        <button class="cs-btn primary" :disabled="submitting || !uniHasInk"
                @click="submit">{{ submitting ? '提交中...' : '客户确认签字' }}</button>
      </view>
    </view>
  </view>
  <!-- #endif -->
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import CustomerNoticeContent from '@/components/CustomerNoticeContent.vue'
// #ifdef H5
import SignaturePad from '@/components/SignaturePad.vue'
import { getViewportSize, isLandscapeViewport, onViewportChange } from '@/utils/viewport'
// #endif
// #ifndef H5
import { useUniSignature } from '@/hooks/useUniSignature'
// #endif

/**
 * 客户签字确认。
 *
 * H5：先阅读告知书，再进入签字。竖屏时点「横屏签字」立即把整个签字面板
 * CSS 旋转 90°，不依赖微信/iOS 支持不稳定的 Fullscreen / Orientation Lock；
 * 设备真正横屏后自动取消 CSS 旋转。签字使用原生 Canvas 高清绘制，支持撤销/重写。
 * 小程序：保留 uni-app canvas 兜底（接口与 H5 对齐）。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  orderId: { type: [Number, String], default: '' },
  content: { type: String, default: '' },
  existingNotice: { type: Object, default: null },
  resign: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false }
})
const emit = defineEmits(['update:visible', 'submit'])

const contentReady = computed(() => String(props.content || '').trim().length > 0)

// #ifdef H5
const padRef = ref(null)
const mode = ref('notice')
const showNotice = ref(false)
const showGate = ref(false)
const forceLandscape = ref(false)
const viewport = ref(getViewportSize())
const landscape = ref(isLandscapeViewport())
const padHasInk = ref(false)
let removeViewportListener = null

/** 竖屏时页面自身旋转成横屏（设备无法旋转/微信锁竖屏时仍然可用）。 */
const rotatePanel = computed(() => forceLandscape.value && !landscape.value)
const rotateWrapStyle = computed(() => {
  if (!rotatePanel.value) return {}
  return {
    position: 'absolute',
    left: '50%',
    top: '50%',
    width: Math.max(1, viewport.value.height) + 'px',
    height: Math.max(1, viewport.value.width) + 'px',
    transform: 'translate(-50%, -50%) rotate(90deg)'
  }
})
const signHint = computed(() => {
  if (rotatePanel.value) return '横屏签字（手机顶部朝右横持）'
  return landscape.value ? '横屏签字' : '竖屏签字（建议横屏）'
})
// #endif

// #ifndef H5
const uniSign = useUniSignature('customer-sign')
const { hasInk: uniHasInk } = uniSign
// #endif

watch(() => props.visible, open => {
  if (open) {
    resetState()
    // #ifndef H5
    nextTick(() => uniSign.attach())
    // #endif
  } else {
    cleanup()
  }
}, { immediate: true })

onBeforeUnmount(cleanup)

function resetState() {
  // #ifdef H5
  mode.value = 'notice'
  showNotice.value = false
  showGate.value = false
  forceLandscape.value = false
  padHasInk.value = false
  viewport.value = getViewportSize()
  landscape.value = isLandscapeViewport()
  stopViewportListener()
  // #endif
}

function cleanup() {
  // #ifdef H5
  stopViewportListener()
  showNotice.value = false
  // #endif
  // #ifndef H5
  uniSign.detach()
  // #endif
}

function close() {
  if (props.submitting) return
  cleanup()
  emit('update:visible', false)
}

function submit() {
  // #ifdef H5
  submitH5()
  // #endif
  // #ifndef H5
  submitUni()
  // #endif
}

// #ifdef H5
function startSign() {
  if (!contentReady.value) return toast('告知书内容加载中，请稍候')
  mode.value = 'sign'
  viewport.value = getViewportSize()
  landscape.value = isLandscapeViewport()
  forceLandscape.value = landscape.value
  showGate.value = !landscape.value
  startViewportListener()
}

/** 立即切换为横屏布局（不等待系统 API），再提示用户横持手机。 */
function chooseLandscape() {
  forceLandscape.value = true
  showGate.value = false
  toast('已切换横屏，请将手机顶部朝右横过来签字')
}

function choosePortrait() {
  forceLandscape.value = false
  showGate.value = false
}

function startViewportListener() {
  stopViewportListener()
  removeViewportListener = onViewportChange(() => {
    viewport.value = getViewportSize()
    landscape.value = isLandscapeViewport()
    if (showGate.value && landscape.value) {
      showGate.value = false
      forceLandscape.value = true
    }
  })
}

function stopViewportListener() {
  if (!removeViewportListener) return
  removeViewportListener()
  removeViewportListener = null
}

function onPadChange(state) {
  padHasInk.value = !!(state && state.hasInk)
}

function submitH5() {
  const pad = padRef.value
  if (!pad) return toast('签字区域未就绪，请稍候')
  const check = pad.validate()
  if (!check.ok) return toast(check.message)
  let image
  try {
    image = pad.toBase64()
  } catch (e) {
    return toast((e && e.message) || '签字图片生成失败，请重试')
  }
  emit('submit', { signImageBase64: image })
}
// #endif

// #ifndef H5
function submitUni() {
  const check = uniSign.validate()
  if (!check.ok) return toast(check.message)
  uniSign.toBase64()
    .then(image => emit('submit', { signImageBase64: image }))
    .catch(e => toast((e && e.message) || '请客户完成手写签字'))
}
// #endif

function toast(title) {
  uni.showToast({ title, icon: 'none' })
}
</script>

<style scoped src="./customer-sign.css"></style>
