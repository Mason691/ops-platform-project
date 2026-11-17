<template>
  <view class="stepper">
    <view class="step-btn" :class="{ disabled: atMin }" @click="step(-1)">－</view>
    <input class="step-input" :value="displayValue" type="number"
           maxlength="5" @input="onInput" @focus="onFocus" />
    <view class="step-btn" :class="{ disabled: atMax }" @click="step(1)">＋</view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { keepFieldVisible } from '@/utils/fieldFocus'

/** 销售数量步进器：可直接输入，也可用 -/＋ 调整，最小 1、最大 9999。 */
const props = defineProps({
  modelValue: { type: [String, Number], default: 1 },
  min: { type: Number, default: 1 },
  max: { type: Number, default: 9999 },
  /** 聚焦时滚动到的字段容器 id（移动端防键盘遮挡） */
  focusId: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const displayValue = computed(() => {
  const value = props.modelValue
  return value === null || value === undefined || value === '' ? '' : String(value)
})
const atMin = computed(() => Number(props.modelValue) <= props.min)
const atMax = computed(() => Number(props.modelValue) >= props.max)

function emitValue(value) {
  emit('update:modelValue', value)
}

function step(delta) {
  const current = Number(props.modelValue)
  const base = Number.isFinite(current) && current > 0 ? current : props.min
  const next = Math.min(props.max, Math.max(props.min, base + delta))
  emitValue(String(next))
}

function onFocus() {
  if (props.focusId) keepFieldVisible(props.focusId)
}

function onInput(event) {
  // 不静默剔除小数/负号：保留原始输入，交给 validateBusinessForm 提示
  // “销售数量必须为正整数”，避免 1.5 被拼成 15、-2 被改成 2 后照常提交。
  emitValue(String(event.detail.value ?? ''))
}
</script>

<style scoped>
.stepper { display: flex; align-items: center; height: 80rpx; }
.step-btn {
  width: 72rpx; height: 72rpx; line-height: 68rpx; text-align: center;
  border: 2rpx solid #e6e6e6; border-radius: 10rpx;
  background: #fff; color: #FF6B35; font-size: 32rpx;
}
.step-btn:active { background: #FFF3ED; }
.step-btn.disabled { color: #ccc; border-color: #eee; background: #fafafa; }
.step-input {
  flex: 1; min-width: 0; height: 72rpx !important; min-height: 72rpx !important; margin: 0 12rpx;
  text-align: center; font-size: 28rpx; color: #333;
  background: #f7f7f7; border-radius: 10rpx;
}
</style>
