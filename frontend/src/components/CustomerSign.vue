<template>
  <!-- #ifdef H5 -->
  <view v-if="visible" ref="overlayRef" class="cs-overlay">
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
          <text class="cs-gate-title">请将手机横过来</text>
          <text class="cs-gate-text">横屏后签字区域更大，笔画更清晰</text>
          <button class="cs-btn primary cs-gate-btn" :disabled="submitting"
                  @click="retryLandscape">尝试全屏横屏</button>
          <text class="cs-gate-fallback" @click="startPortrait">无法横屏？竖屏继续</text>
        </view>

        <template v-else>
          <view class="cs-sign-bar">
            <text class="cs-link" @click="showNotice = true">查看告知书</text>
            <text class="cs-sign-hint">{{ landscape ? '横屏签字' : '竖屏签字（建议横屏）' }}</text>
          </view>
          <SignaturePad ref="padRef" class="cs-pad" @change="onPadChange" />
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
import {
  exitLandscape,
  isLandscapeViewport,
  onViewportChange,
  requestLandscape
} from '@/utils/screenOrientation'
// #endif
// #ifndef H5
import { useUniSignature } from '@/hooks/useUniSignature'
// #endif

/**
 * 客户签字确认。
 *
 * H5：先阅读告知书，再进入全屏签字。优先请求全屏 + 横屏锁定；iOS/微信
 * 不支持时提示用户手动横过来，仍可竖屏降级。签字使用原生 Canvas 高清绘制，
 * 支持撤销/重写。
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
const overlayRef = ref(null)
const padRef = ref(null)
const mode = ref('notice')
const showNotice = ref(false)
const showGate = ref(false)
const portraitFallback = ref(false)
const landscape = ref(false)
const padHasInk = ref(false)
let removeViewportListener = null
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
  portraitFallback.value = false
  padHasInk.value = false
  landscape.value = isLandscapeViewport()
  stopViewportListener()
  // #endif
}

function cleanup() {
  // #ifdef H5
  stopViewportListener()
  showNotice.value = false
  exitLandscape()
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
function overlayElement() {
  const value = overlayRef.value
  return value ? (value.$el || value) : null
}

function startSign() {
  if (!contentReady.value) return toast('告知书内容加载中，请稍候')
  mode.value = 'sign'
  landscape.value = isLandscapeViewport()
  showGate.value = !landscape.value
  startViewportListener()
  if (!landscape.value) {
    requestLandscape(overlayElement()).then(ok => {
      if (ok) {
        landscape.value = true
        showGate.value = false
      }
    })
  }
}

function retryLandscape() {
  requestLandscape(overlayElement()).then(ok => {
    if (ok) {
      landscape.value = true
      showGate.value = false
      return
    }
    toast('请手动将手机横过来，或点击下方「竖屏继续」')
  })
}

function startPortrait() {
  portraitFallback.value = true
  showGate.value = false
}

function startViewportListener() {
  stopViewportListener()
  removeViewportListener = onViewportChange(() => {
    landscape.value = isLandscapeViewport()
    if (landscape.value && showGate.value) showGate.value = false
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
