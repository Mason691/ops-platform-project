<script setup>
/**
 * 新建小区定位：状态 + 地址编辑 + 区县不一致提示。
 * 逻辑全部在 useCommunityLocation，本组件只负责展示与事件。
 */
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, default: 'idle' },
  address: { type: String, default: '' },
  coordsText: { type: String, default: '—' },
  sourceText: { type: String, default: '' },
  hasLocation: { type: Boolean, default: false },
  locating: { type: Boolean, default: false },
  failureReason: { type: String, default: '' },
  regeoError: { type: String, default: '' },
  mismatchText: { type: String, default: '' }
})
defineEmits(['update:address', 'relocate', 'skip'])

const statusText = computed(() => {
  if (props.status === 'locating') return '正在获取当前位置…'
  if (props.status === 'success') return '已定位，地址已自动填充'
  if (props.status === 'partial') return '已获取坐标，地址解析失败'
  if (props.status === 'failed') return props.failureReason || '定位失败，可手动填写地址'
  return '将自动获取当前位置并填充地址'
})
</script>

<template>
  <view class="loc-panel">
    <view class="loc-status" :class="'is-' + status">
      <view class="loc-dot" />
      <text class="loc-text">{{ statusText }}</text>
      <text v-if="hasLocation && sourceText" class="loc-source">{{ sourceText }}</text>
    </view>

    <view class="loc-actions">
      <text v-if="hasLocation" class="coord-text">坐标 {{ coordsText }}</text>
      <text v-if="locating" class="link skip" @click="$emit('skip')">跳过定位</text>
      <text v-else class="link" @click="$emit('relocate')">重新定位</text>
    </view>

    <text v-if="regeoError" class="loc-warn">{{ regeoError }}</text>

    <text class="label">小区详细地址</text>
    <textarea class="address-input" :value="address" maxlength="500" :disabled="locating"
              placeholder="定位成功后自动填充，也可手动输入"
              @input="$emit('update:address', $event.detail.value)" />
    <text class="hint">位置仅用于记录小区地址，便于派工与施工导航</text>

    <view v-if="mismatchText" class="mismatch">
      <text>{{ mismatchText }}</text>
    </view>
  </view>
</template>

<style scoped>
.loc-panel { margin-top: 22rpx; }
.loc-status {
  display: flex; align-items: center; gap: 10rpx;
  padding: 16rpx 18rpx; border-radius: 12rpx; background: #f7f7f7;
}
.loc-status.is-locating { background: #FFF8F4; }
.loc-status.is-success { background: #F0FAF4; }
.loc-status.is-partial, .loc-status.is-failed { background: #FFF9E6; }
.loc-dot { width: 12rpx; height: 12rpx; border-radius: 50%; background: #bbb; flex: none; }
.is-locating .loc-dot { background: #FF6B35; }
.is-success .loc-dot { background: #07A65A; }
.is-partial .loc-dot, .is-failed .loc-dot { background: #D48806; }
.loc-text { flex: 1; font-size: 24rpx; color: #333; line-height: 1.4; }
.loc-source { font-size: 21rpx; color: #999; flex: none; }
.loc-actions {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 8rpx; min-height: 48rpx;
}
.coord-text { font-size: 21rpx; color: #999; }
.link { font-size: 23rpx; color: #FF6B35; padding: 8rpx 0 8rpx 16rpx; }
.skip { color: #999; }
.loc-warn { display: block; font-size: 22rpx; color: #B26A00; margin-top: 6rpx; line-height: 1.5; }
.label { display: block; font-size: 25rpx; color: #666; margin: 18rpx 0 12rpx; }
.address-input {
  width: 100%; height: 132rpx !important; min-height: 132rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 16rpx 20rpx;
  font-size: 27rpx; color: #1D2129; box-sizing: border-box; line-height: 1.5;
}
.hint { display: block; font-size: 21rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
.mismatch {
  margin-top: 14rpx; padding: 14rpx 18rpx; border-radius: 10rpx;
  background: #FFF9E6; font-size: 22rpx; color: #B26A00; line-height: 1.5;
}
</style>
