<template>
  <view v-if="visible" class="reject-mask" @touchmove.stop.prevent @click="onMask">
    <view class="reject-panel" @click.stop @touchmove.stop>
      <view class="reject-head">
        <text class="reject-title">{{ title }}</text>
        <text class="reject-close" @click="close">×</text>
      </view>
      <text class="reject-tip">请填写驳回意见（不少于 5 个字，最多 500 字）</text>
      <textarea
        v-model="note"
        class="reject-input"
        maxlength="500"
        :focus="focused"
        :adjust-position="true"
        :cursor-spacing="24"
        :show-confirm-bar="false"
        placeholder="例如：折旧费用过高，请重新核算"
      />
      <view class="reject-count">{{ note.length }}/500</view>
      <view class="reject-actions">
        <button class="reject-btn ghost" :disabled="submitting" @click="close">取消</button>
        <button class="reject-btn danger" :disabled="submitting" @click="confirm">
          {{ submitting ? '提交中...' : '确认驳回' }}
        </button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false },
  title: { type: String, default: '驳回审批' }
})

const emit = defineEmits(['update:visible', 'confirm'])
const note = ref('')
const focused = ref(false)

watch(() => props.visible, (value) => {
  if (!value) {
    focused.value = false
    return
  }
  note.value = ''
  // 先复位再置 true，强制 textarea 的 focus 属性发生一次 false→true 变化
  focused.value = false
  nextTick(() => {
    focused.value = true
    focusDom()
  })
}, { immediate: true })

/** H5 兜底：uni-app textarea 的 focus 属性在部分微信 WebView 不生效，直接聚焦内层 textarea。 */
function focusDom() {
  if (typeof document === 'undefined') return
  const el = document.querySelector('.reject-panel textarea')
  if (!el || typeof el.focus !== 'function') return
  setTimeout(() => {
    if (props.visible) el.focus()
  }, 50)
}

function toast(title) {
  uni.showToast({ title, icon: 'none', duration: 2500 })
}

function close() {
  if (props.submitting) return
  emit('update:visible', false)
}

function onMask() {
  close()
}

function confirm() {
  if (props.submitting) return
  const text = String(note.value || '').trim()
  if (text.length < 5) {
    toast('请填写驳回意见（不少于 5 个字）')
    return
  }
  if (text.length > 500) {
    toast('驳回意见不能超过 500 字')
    return
  }
  emit('confirm', text)
}
</script>

<style scoped>
.reject-mask {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
}
.reject-panel {
  width: 100%;
  box-sizing: border-box;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 28rpx 28rpx calc(28rpx + env(safe-area-inset-bottom));
  max-height: 80vh;
  overflow-y: auto;
}
.reject-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.reject-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #1D2129;
}
.reject-close {
  width: 56rpx;
  height: 56rpx;
  line-height: 52rpx;
  text-align: center;
  color: #86909C;
  font-size: 40rpx;
}
.reject-tip {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #86909C;
}
.reject-input {
  width: 100%;
  box-sizing: border-box;
  height: 320rpx;
  min-height: 320rpx;
  margin-top: 20rpx;
  padding: 22rpx;
  background: #F7F8FA;
  border-radius: 12rpx;
  font-size: 28rpx;
  color: #1D2129;
  line-height: 1.6;
}
.reject-count {
  margin-top: 8rpx;
  text-align: right;
  font-size: 22rpx;
  color: #A9AEB8;
}
.reject-actions {
  display: flex;
  gap: 20rpx;
  margin-top: 24rpx;
}
.reject-btn {
  flex: 1;
  height: 88rpx;
  line-height: 88rpx;
  border-radius: 44rpx;
  font-size: 30rpx;
  border: none;
}
.reject-btn.ghost {
  background: #fff;
  color: #4E5969;
  border: 1rpx solid #C9CDD4;
}
.reject-btn.danger {
  background: #F53F3F;
  color: #fff;
}
.reject-btn[disabled] {
  opacity: 0.6;
}
</style>
