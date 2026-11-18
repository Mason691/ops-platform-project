<template>
  <view class="wizard-steps">
    <view class="track">
      <view class="track-fill" :style="{ width: progress }"></view>
    </view>
    <view class="steps-row">
      <view v-for="(item, index) in steps" :key="item.key || index"
            class="step-item" :class="{ active: index === current, done: index < current,
              locked: index > maxVisited }"
            @click="select(index)">
        <view class="circle">
          <text v-if="index < current" class="check">✓</text>
          <text v-else class="index">{{ index + 1 }}</text>
        </view>
        <text class="step-name">{{ item.title }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

/** 吸顶步骤条：已完成显示对勾，当前步高亮，未访问步骤不可点击。 */
const props = defineProps({
  steps: { type: Array, required: true },
  current: { type: Number, default: 0 },
  maxVisited: { type: Number, default: 0 }
})
const emit = defineEmits(['select'])

const progress = computed(() => {
  const total = props.steps.length - 1
  if (total <= 0) return '0%'
  return `${(Math.min(props.current, total) / total) * 100}%`
})

function select(index) {
  if (index === props.current || index > props.maxVisited) return
  emit('select', index)
}
</script>

<style scoped>
.wizard-steps {
  position: sticky; top: 0; z-index: 40;
  background: #fff; padding: 18rpx 12rpx 14rpx;
  box-shadow: 0 4rpx 12rpx rgba(0, 0, 0, .04);
}
.track {
  position: absolute; left: 10%; right: 10%; top: 42rpx;
  height: 4rpx; background: #f0f0f0; border-radius: 2rpx;
}
.track-fill {
  height: 100%; background: #FF6B35; border-radius: 2rpx;
  transition: width .25s ease;
}
.steps-row { position: relative; display: flex; }
.step-item {
  flex: 1; display: flex; flex-direction: column; align-items: center;
  gap: 8rpx;
}
.circle {
  width: 52rpx; height: 52rpx; border-radius: 50%;
  background: #f0f0f0; color: #999;
  display: flex; align-items: center; justify-content: center;
  font-size: 24rpx; font-weight: 600;
  border: 4rpx solid #fff; box-sizing: border-box;
  transition: background-color .2s, color .2s, transform .2s;
}
.step-item.done .circle { background: #FF6B35; color: #fff; }
.step-item.active .circle {
  background: #fff; color: #FF6B35;
  border-color: #FF6B35; transform: scale(1.08);
  box-shadow: 0 0 0 6rpx rgba(255, 107, 53, .12);
}
.step-name { font-size: 22rpx; color: #999; white-space: nowrap; }
.step-item.active .step-name { color: #FF6B35; font-weight: 600; }
.step-item.done .step-name { color: #666; }
.step-item.locked .circle { opacity: .6; }
.check { font-size: 24rpx; }
</style>
