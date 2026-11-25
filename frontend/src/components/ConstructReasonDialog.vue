<template>
  <view v-if="visible" class="overlay" @touchmove.stop.prevent>
    <view class="dialog construct-reason-dialog" @touchmove.stop>
      <text class="title">{{ title }}</text>
      <textarea
        v-model="reason"
        class="textarea reason-textarea"
        :maxlength="maxLength"
        :focus="focused"
        :adjust-position="true"
        :cursor-spacing="24"
        :show-confirm-bar="false"
        :placeholder="placeholder"
      />
      <text class="counter">{{ reason.length }} / {{ maxLength }}（不少于 {{ minLength }} 字）</text>
      <text v-if="error" class="error">{{ error }}</text>
      <view class="actions">
        <button class="btn ghost" :disabled="submitting" @click="close">取消</button>
        <button class="btn danger" :disabled="submitting" @click="confirm">
          {{ submitting ? '提交中...' : '确认提交' }}
        </button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '填写原因' },
  placeholder: { type: String, default: '请填写原因（不少于5个字）' },
  requiredMessage: { type: String, default: '请填写原因' },
  minLength: { type: Number, default: 5 },
  maxLength: { type: Number, default: 200 },
  submitting: { type: Boolean, default: false }
})
const emit = defineEmits(['update:visible', 'confirm'])
const reason = ref('')
const focused = ref(false)
const error = ref('')

watch(() => props.visible, open => {
  if (!open) {
    focused.value = false
    return
  }
  reason.value = ''
  error.value = ''
  // 先复位再置 true，强制 textarea 的 focus 属性发生一次 false→true 变化
  focused.value = false
  nextTick(() => {
    if (!props.visible) return
    focused.value = true
    focusDom()
  })
})

/** 输入后清除就地错误提示，避免用户看到过期校验文案。 */
watch(reason, () => {
  if (error.value) error.value = ''
})

/**
 * H5 兜底：uni-app textarea 的 focus 属性在部分微信 WebView 不生效，
 * 直接聚焦内层原生 textarea。宿主高度必须由 CSS !important 撑开，
 * 否则内层绝对定位 textarea 高度为 0，DOM 也无法聚焦。
 */
function focusDom() {
  if (typeof document === 'undefined') return
  setTimeout(() => {
    if (!props.visible) return
    const el = document.querySelector('.construct-reason-dialog textarea')
    if (el && typeof el.focus === 'function') el.focus()
  }, 50)
}

function close() {
  if (props.submitting) return
  emit('update:visible', false)
}

function confirm() {
  if (props.submitting) return
  const text = String(reason.value || '').trim()
  if (!text) {
    error.value = props.requiredMessage
    return
  }
  if (text.length < props.minLength) {
    error.value = `原因不少于${props.minLength}个字`
    return
  }
  if (text.length > props.maxLength) {
    error.value = `原因不能超过${props.maxLength}字`
    return
  }
  error.value = ''
  emit('confirm', text)
}
</script>

<style scoped>
.overlay {
  position: fixed; inset: 0; z-index: 960; background: rgba(0, 0, 0, .45);
  display: flex; align-items: center; justify-content: center; padding: 40rpx;
  box-sizing: border-box;
}
.dialog {
  width: 100%; max-width: 560px; max-height: 85vh; overflow-y: auto;
  background: #fff; border-radius: 20rpx; padding: 32rpx; box-sizing: border-box;
}
.title { display: block; font-size: 30rpx; font-weight: 700; color: #1D2129; margin-bottom: 20rpx; }
.textarea {
  width: 100%; box-sizing: border-box; background: #F7F8FA;
  /* App.vue 全局 uni-textarea{height:auto!important} 会把宿主高度压塌，
     内层绝对定位 textarea 高度随之为 0，导致无法聚焦录入；必须显式 !important 覆盖。 */
  height: 240rpx !important;
  min-height: 240rpx !important;
  border-radius: 10rpx; padding: 18rpx; font-size: 26rpx; color: #1D2129;
  line-height: 1.6;
}
.counter { display: block; text-align: right; font-size: 20rpx; color: #A9AEB8; margin-top: 8rpx; }
.error { display: block; margin-top: 8rpx; font-size: 22rpx; color: #F53F3F; }
.actions { display: flex; gap: 16rpx; margin-top: 24rpx; }
.btn {
  flex: 1; height: 80rpx; line-height: 80rpx; border-radius: 40rpx;
  font-size: 27rpx; text-align: center; border: none;
}
.btn.danger { background: #C62828; color: #fff; }
.btn.danger[disabled] { opacity: .55; }
.btn.ghost { background: #F2F3F5; color: #4E5969; }
</style>
