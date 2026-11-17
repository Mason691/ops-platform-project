<template>
  <view class="chips" :class="{ compact }">
    <view v-for="option in normalized" :key="String(option.value)"
          class="chip" :class="{ on: isSelected(option.value), disabled }"
          @click="pick(option)">
      <text class="chip-label">{{ option.label }}</text>
      <text v-if="isSelected(option.value)" class="chip-check">✓</text>
    </view>
    <text v-if="!normalized.length" class="chips-empty">{{ emptyText }}</text>
  </view>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 直接点选标签组（替代下拉框）。
 * 单选用 value；多选用 value 数组。选中态为主色描边 + 浅底 + 对勾。
 */
const props = defineProps({
  options: { type: Array, default: () => [] },
  modelValue: { type: [String, Number, Array], default: '' },
  multiple: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  compact: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无可选项' }
})
const emit = defineEmits(['update:modelValue', 'change'])

const normalized = computed(() => (props.options || [])
  .filter(option => option && option.value !== undefined && option.value !== null)
  .map(option => ({
    value: option.value,
    label: option.label !== undefined ? String(option.label) : String(option.value)
  })))

function isSelected(value) {
  if (props.multiple) {
    return (props.modelValue || []).some(item => String(item) === String(value))
  }
  return props.modelValue !== null && props.modelValue !== undefined
    && String(props.modelValue) === String(value)
}

function pick(option) {
  if (props.disabled) return
  let next
  if (props.multiple) {
    const list = Array.isArray(props.modelValue) ? [...props.modelValue] : []
    const index = list.findIndex(item => String(item) === String(option.value))
    if (index >= 0) list.splice(index, 1)
    else list.push(option.value)
    next = list
  } else {
    if (String(props.modelValue) === String(option.value)) return
    next = option.value
  }
  emit('update:modelValue', next)
  emit('change', next)
}
</script>

<style scoped>
.chips { display: flex; flex-wrap: wrap; gap: 16rpx; }
.chip {
  display: flex; align-items: center; gap: 8rpx;
  min-height: 64rpx; padding: 10rpx 24rpx; box-sizing: border-box;
  border: 2rpx solid #e6e6e6; border-radius: 12rpx;
  background: #fafafa; color: #555; font-size: 26rpx;
  transition: border-color .18s, background-color .18s, color .18s;
}
.chip:active { transform: scale(.97); }
.chip.on {
  border-color: #FF6B35; background: #FFF3ED;
  color: #FF6B35; font-weight: 600;
}
.chip.disabled { opacity: .55; }
.chip-check { font-size: 22rpx; }
.chips.compact .chip { min-height: 56rpx; padding: 6rpx 20rpx; font-size: 24rpx; }
.chips-empty { font-size: 24rpx; color: #999; line-height: 1.6; }
</style>
