<template>
  <view v-if="loading" class="ocr-panel">
    <text class="spinner"></text>
    <text class="ocr-text">正在识别收据号…</text>
  </view>
  <view v-else-if="message || hasResult" class="ocr-panel" :class="{ 'panel-warn': warning }">
    <text v-if="message" class="ocr-text" :class="{ warn: warning }">{{ message }}</text>
    <view v-if="showChoices" class="ocr-choices">
      <view class="ocr-chip" :class="{ active: currentChoice === 'RED' }"
            @click="emit('switch', 'RED')">
        <text class="dot red"></text>
        <text class="chip-label">红色</text>
        <text class="chip-value">{{ red }}</text>
      </view>
      <view class="ocr-chip" :class="{ active: currentChoice === 'BLUE' }"
            @click="emit('switch', 'BLUE')">
        <text class="dot blue"></text>
        <text class="chip-label">蓝色</text>
        <text class="chip-value">{{ blue }}</text>
      </view>
    </view>
    <view v-else-if="review || candidates.length > 1" class="ocr-choices">
      <view v-for="(item, index) in candidates" :key="item.value + '-' + index"
            class="ocr-chip" :class="{ active: selectedValue === String(item.value) }"
            @click="emit('select', item.value)">
        <text class="dot" :class="item.color === 'BLUE' ? 'blue' : (item.color === 'RED' ? 'red' : 'gray')"></text>
        <text class="chip-label">{{ item.color === 'BLUE' ? '蓝色' : (item.color === 'RED' ? '红色' : '候选') }}</text>
        <text class="chip-value">{{ item.value }}</text>
      </view>
    </view>
    <view v-else-if="red || blue" class="ocr-choices">
      <view class="ocr-chip active" @click="emit('switch', red ? 'RED' : 'BLUE')">
        <text class="dot" :class="red ? 'red' : 'blue'"></text>
        <text class="chip-label">{{ sameValue ? '红/蓝一致' : (red ? '红色' : '蓝色') }}</text>
        <text class="chip-value">{{ red || blue }}</text>
      </view>
    </view>
    <text v-if="(review || candidates.length > 1) && candidates.length" class="ocr-pick-hint">
      点击号码可直接填入收据号
    </text>
    <text v-if="manual && hasCandidates" class="ocr-manual">已手动填写，识别候选保留供参考</text>
    <text v-if="canRetry && warning" class="ocr-retry" @click="emit('retry')">重新识别</text>
  </view>
</template>

<script setup>
defineProps({
  loading: { type: Boolean, default: false },
  message: { type: String, default: '' },
  warning: { type: Boolean, default: false },
  red: { type: String, default: '' },
  blue: { type: String, default: '' },
  hasResult: { type: Boolean, default: false },
  hasCandidates: { type: Boolean, default: false },
  showChoices: { type: Boolean, default: false },
  sameValue: { type: Boolean, default: false },
  currentChoice: { type: String, default: '' },
  manual: { type: Boolean, default: false },
  canRetry: { type: Boolean, default: false },
  review: { type: Boolean, default: false },
  candidates: { type: Array, default: () => [] },
  selectedValue: { type: String, default: '' }
})
const emit = defineEmits(['switch', 'select', 'retry'])
</script>

<style scoped>
.ocr-panel {
  margin-top: 12rpx; padding: 16rpx 18rpx;
  background: #FAFAFA; border: 2rpx solid #F0F0F0; border-radius: 12rpx;
}
.ocr-panel.panel-warn { background: #FFF8F3; border-color: #FFE0CC; }
.ocr-text { display: block; font-size: 22rpx; color: #666; line-height: 1.6; }
.ocr-text.warn { color: #C2410C; }
.ocr-choices { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 12rpx; }
.ocr-chip {
  display: flex; align-items: center; gap: 8rpx;
  min-height: 56rpx; padding: 8rpx 18rpx; box-sizing: border-box;
  border: 2rpx solid #E5E5E5; border-radius: 999rpx; background: #fff;
  cursor: pointer;
}
.ocr-chip.active { border-color: #FF6B35; background: #FFF3EC; }
.dot { width: 14rpx; height: 14rpx; border-radius: 50%; flex: 0 0 auto; }
.dot.red { background: #E53935; }
.dot.blue { background: #2563EB; }
.dot.gray { background: #9CA3AF; }
.chip-label { font-size: 22rpx; color: #666; }
.ocr-pick-hint { display: block; margin-top: 8rpx; font-size: 21rpx; color: #C2410C; }
.chip-value {
  font-size: 24rpx; color: #333; font-weight: 600; letter-spacing: 1rpx;
}
.ocr-manual { display: block; margin-top: 8rpx; font-size: 21rpx; color: #999; }
.ocr-retry {
  display: inline-block; margin-top: 8rpx; padding: 6rpx 0;
  font-size: 22rpx; color: #FF6B35;
}
.spinner {
  display: inline-block; width: 24rpx; height: 24rpx; margin-right: 10rpx;
  vertical-align: -4rpx; border-radius: 50%;
  border: 3rpx solid #FFD9C8; border-top-color: #FF6B35;
  animation: spin .7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
