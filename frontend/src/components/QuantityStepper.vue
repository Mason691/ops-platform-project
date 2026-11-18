<template>
  <view class="stepper" :class="{ compact }">
    <view class="step-btn" :class="{ disabled: atMin }"
          @touchstart.stop="startPress(-1)" @touchmove="endPress"
          @touchend="endPress" @touchcancel="endPress"
          @click="onClick(-1)">－</view>
    <input class="step-input" :value="displayValue" type="number" inputmode="numeric"
           maxlength="5" @input="onInput" @focus="onFocus" @blur="onBlur" />
    <view class="step-btn" :class="{ disabled: atMax }"
          @touchstart.stop="startPress(1)" @touchmove="endPress"
          @touchend="endPress" @touchcancel="endPress"
          @click="onClick(1)">＋</view>
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount } from 'vue'
import { keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 数量步进器：可直接输入，也可用 -/＋ 调整；长按连续加减。
 * 按钮触控区 ≥44px（微信 H5），数字区固定最小宽度，避免跳动。
 */
const props = defineProps({
  modelValue: { type: [String, Number], default: 1 },
  min: { type: Number, default: 1 },
  max: { type: Number, default: 9999 },
  /** 聚焦时滚动到的字段容器 id（移动端防键盘遮挡） */
  focusId: { type: String, default: '' },
  /** 紧凑模式：物料行内使用，数字区固定宽度 */
  compact: { type: Boolean, default: false },
  longPressDelay: { type: Number, default: 400 },
  longPressInterval: { type: Number, default: 100 }
})
const emit = defineEmits(['update:modelValue', 'blur'])

const displayValue = computed(() => {
  const value = props.modelValue
  return value === null || value === undefined || value === '' ? '' : String(value)
})
const atMin = computed(() => Number(props.modelValue) <= props.min)
const atMax = computed(() => Number(props.modelValue) >= props.max)

let pressTimer = null
let repeatTimer = null
let longPressFired = false

function emitValue(value) {
  emit('update:modelValue', value)
}

function step(delta) {
  const current = Number(props.modelValue)
  const base = Number.isFinite(current) && current > 0 ? current : props.min
  const next = Math.min(props.max, Math.max(props.min, base + delta))
  emitValue(String(next))
}

function clearPress() {
  if (pressTimer) { clearTimeout(pressTimer); pressTimer = null }
  if (repeatTimer) { clearInterval(repeatTimer); repeatTimer = null }
}

function startPress(delta) {
  clearPress()
  longPressFired = false
  pressTimer = setTimeout(() => {
    longPressFired = true
    step(delta)
    repeatTimer = setInterval(() => step(delta), Math.max(50, props.longPressInterval))
  }, Math.max(200, props.longPressDelay))
}

function endPress() {
  clearPress()
}

function onClick(delta) {
  // 长按已连续步进时，touchend 后的 click 不再重复加 1
  if (longPressFired) { longPressFired = false; return }
  step(delta)
}

function onFocus() {
  if (props.focusId) keepFieldVisible(props.focusId)
}

function onBlur() {
  emit('blur')
}

function onInput(event) {
  // 不静默剔除小数/负号：保留原始输入，交给校验层提示
  // “销售数量必须为正整数”，避免 1.5 被拼成 15、-2 被改成 2 后照常提交。
  emitValue(String(event.detail.value ?? ''))
}

onBeforeUnmount(clearPress)
</script>

<style scoped>
.stepper { display: flex; align-items: center; min-height: 88rpx; }
.step-btn {
  width: 88rpx; height: 88rpx; min-width: 44px; min-height: 44px;
  display: flex; align-items: center; justify-content: center;
  border: 2rpx solid #e6e6e6; border-radius: 12rpx;
  background: #fff; color: #FF6B35; font-size: 36rpx;
  user-select: none; box-sizing: border-box;
}
.step-btn:active { background: #FFF3ED; }
.step-btn.disabled { color: #ccc; border-color: #eee; background: #fafafa; }
.step-input {
  flex: 1; min-width: 0; height: 88rpx !important; min-height: 44px !important; margin: 0 10rpx;
  text-align: center; font-size: 32rpx; color: #333;
  background: #f7f7f7; border-radius: 12rpx;
}
.stepper.compact .step-input {
  flex: none; width: 96rpx; min-width: 48px;
}
</style>
