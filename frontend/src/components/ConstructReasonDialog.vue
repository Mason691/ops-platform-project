<template>
  <view v-if="visible" class="overlay">
    <view class="dialog">
      <text class="title">{{ title }}</text>
      <textarea v-model="reason" class="textarea" maxlength="500"
                :placeholder="placeholder" />
      <text class="counter">{{ reason.trim().length }} / 500（不少于 {{ minLength }} 字）</text>
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
import { ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '填写原因' },
  placeholder: { type: String, default: '请填写原因（不少于5个字）' },
  minLength: { type: Number, default: 5 },
  submitting: { type: Boolean, default: false }
})
const emit = defineEmits(['update:visible', 'confirm'])
const reason = ref('')

watch(() => props.visible, open => {
  if (open) reason.value = ''
})

function close() {
  if (props.submitting) return
  emit('update:visible', false)
}

function confirm() {
  const text = reason.value.trim()
  if (text.length < props.minLength) {
    uni.showToast({ title: `原因不少于${props.minLength}个字`, icon: 'none' })
    return
  }
  if (text.length > 500) {
    uni.showToast({ title: '原因不能超过500字', icon: 'none' })
    return
  }
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
  width: 100%; max-width: 560px; background: #fff; border-radius: 20rpx;
  padding: 32rpx; box-sizing: border-box;
}
.title { display: block; font-size: 30rpx; font-weight: 700; color: #1D2129; margin-bottom: 20rpx; }
.textarea {
  width: 100%; box-sizing: border-box; min-height: 180rpx; background: #F7F8FA;
  border-radius: 10rpx; padding: 18rpx; font-size: 26rpx; color: #1D2129;
}
.counter { display: block; text-align: right; font-size: 20rpx; color: #A9AEB8; margin-top: 8rpx; }
.actions { display: flex; gap: 16rpx; margin-top: 24rpx; }
.btn {
  flex: 1; height: 80rpx; line-height: 80rpx; border-radius: 40rpx;
  font-size: 27rpx; text-align: center; border: none;
}
.btn.danger { background: #C62828; color: #fff; }
.btn.danger[disabled] { opacity: .55; }
.btn.ghost { background: #F2F3F5; color: #4E5969; }
</style>
