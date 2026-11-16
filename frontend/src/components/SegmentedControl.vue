<template>
  <view class="segmented" :class="{ disabled }">
    <view v-if="activeIndex >= 0" class="seg-indicator"
          :class="'seg-pos-' + activeIndex"></view>
    <view v-for="(option, index) in normalized" :key="String(option.value)"
          class="seg-btn" :class="{ on: index === activeIndex }"
          @click="pick(option)">
      <text>{{ option.label }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 分段选择器（是/否等少量互斥选项），直接点选，带滑动指示条。
 * 通过 modelValue 与 options 的 value 严格比较（字符串归一化）。
 */
const props = defineProps({
  modelValue: { type: [String, Number, Boolean], default: '' },
  options: {
    type: Array,
    default: () => [
      { value: true, label: '是' },
      { value: false, label: '否' }
    ]
  },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'change'])

const normalized = computed(() => (props.options || []).map(option => ({
  value: option.value,
  label: String(option.label)
})))

const activeIndex = computed(() => normalized.value.findIndex(
  option => String(option.value) === String(props.modelValue)))

function pick(option) {
  if (props.disabled || String(props.modelValue) === String(option.value)) return
  emit('update:modelValue', option.value)
  emit('change', option.value)
}
</script>

<style scoped>
.segmented {
  position: relative; display: flex; align-items: center;
  background: #f2f2f2; border-radius: 14rpx; padding: 6rpx;
  overflow: hidden;
}
.segmented.disabled { opacity: .6; }
.seg-indicator {
  position: absolute; top: 6rpx; bottom: 6rpx; left: 6rpx;
  width: calc(50% - 8rpx);
  background: #fff; border-radius: 10rpx;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, .08);
  transition: transform .2s ease;
}
/* 两段式选择器（是/否）：用类切换位置，避免内联 calc 样式不刷新 */
.seg-indicator.seg-pos-0 { transform: translateX(0); }
.seg-indicator.seg-pos-1 { transform: translateX(calc(100% + 8rpx)); }
.seg-btn {
  position: relative; z-index: 1; flex: 1;
  height: 64rpx; line-height: 64rpx; text-align: center;
  font-size: 27rpx; color: #777;
}
.seg-btn.on { color: #FF6B35; font-weight: 600; }
</style>
