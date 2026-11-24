<template>
  <view class="construct-timeline">
    <view v-if="!logs.length" class="empty">
      <text>暂无施工流转记录</text>
    </view>
    <view v-for="log in logs" :key="log.id" class="item">
      <view class="rail">
        <view class="dot" :class="tone(log)"></view>
        <view v-if="log !== logs[logs.length - 1]" class="line"></view>
      </view>
      <view class="body">
        <view class="head">
          <text class="action" :class="tone(log)">{{ log.displayText || log.actionName }}</text>
          <text class="time">{{ fmt(log.operateTime) }}</text>
        </view>
        <text v-if="log.displayHint" class="meta">{{ log.displayHint }}</text>
        <text v-else-if="!log.displayText" class="meta">
          {{ log.operatorName || '—' }}
          <text v-if="log.toStatusName"> · {{ log.toStatusName }}</text>
        </text>
        <text v-if="locationText(log)" class="meta">
          📍 {{ locationText(log) }}
          <text v-if="log.locationStatus === 'FAILED'" class="warn-tag">定位失败</text>
        </text>
        <text v-if="log.remark" class="remark" :class="{ warn: isWarn(log) }">
          {{ log.remark }}
        </text>
        <view v-if="(log.attachments || []).length" class="photos">
          <image v-for="file in log.attachments" :key="file.id" :src="assetUrl(file.url)"
                 class="photo" mode="aspectFill" @click="preview(log.attachments, file)" />
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { assetUrl } from '@/utils/image'

defineProps({
  logs: { type: Array, default: () => [] }
})

function tone(log) {
  const action = log && log.action
  if (action === 'REJECT' || action === 'CUSTOMER_REFUSE') return 'danger'
  if (action === 'ACCEPT' || action === 'START' || action === 'SUBMIT_ACCEPTANCE') return 'primary'
  if (action === 'CUSTOMER_CONFIRM') return 'success'
  if (action === 'ARRIVE') return 'warning'
  return 'info'
}

function isWarn(log) {
  return log && (log.action === 'REJECT' || log.action === 'CUSTOMER_REFUSE')
}

function locationText(log) {
  if (!log) return ''
  if (log.locationAddress) return log.locationAddress
  if (log.locationLng != null && log.locationLat != null) {
    return Number(log.locationLng).toFixed(6) + ', ' + Number(log.locationLat).toFixed(6)
  }
  return ''
}

function fmt(value) {
  return value ? String(value).replace('T', ' ') : ''
}

function preview(files, current) {
  const urls = (files || []).map(file => assetUrl(file.url))
  uni.previewImage({ urls, current: assetUrl(current.url) })
}
</script>

<style scoped>
.construct-timeline { display: flex; flex-direction: column; }
.empty { font-size: 24rpx; color: #A9AEB8; padding: 12rpx 0; }
.item { display: flex; gap: 16rpx; }
.rail { width: 24rpx; display: flex; flex-direction: column; align-items: center; }
.dot { width: 16rpx; height: 16rpx; border-radius: 50%; margin-top: 8rpx; background: #C9CDD4; }
.dot.primary { background: #2F6FEB; }
.dot.success { background: #0B7A42; }
.dot.warning { background: #FF6B35; }
.dot.danger { background: #C62828; }
.line { flex: 1; width: 2rpx; background: #EEEFF2; margin: 6rpx 0; }
.body { flex: 1; min-width: 0; padding-bottom: 24rpx; display: flex; flex-direction: column; gap: 4rpx; }
.head { display: flex; align-items: baseline; justify-content: space-between; gap: 12rpx; }
.action { font-size: 26rpx; font-weight: 600; color: #1D2129; }
.action.danger { color: #C62828; }
.time { font-size: 20rpx; color: #A9AEB8; flex-shrink: 0; }
.meta { font-size: 22rpx; color: #86909C; line-height: 1.5; word-break: break-all; }
.warn-tag {
  margin-left: 8rpx; font-size: 18rpx; color: #B26A00;
  background: #FFF7E6; padding: 1rpx 8rpx; border-radius: 4rpx;
}
.remark { font-size: 23rpx; color: #4E5969; background: #F7F8FA; border-radius: 8rpx; padding: 10rpx 12rpx; }
.remark.warn { color: #C62828; background: #FFF1F0; }
.photos { display: flex; flex-wrap: wrap; gap: 10rpx; margin-top: 4rpx; }
.photo { width: 120rpx; height: 120rpx; border-radius: 8rpx; background: #F2F3F5; }
</style>
