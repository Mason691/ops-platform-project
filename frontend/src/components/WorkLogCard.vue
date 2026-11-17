<template>
  <view class="work-log-card">
    <view class="log-head">
      <text class="log-title">工作业务日志</text>
      <text class="log-all" @click="goAll">查看全部 ›</text>
    </view>

    <view v-if="loading" class="log-state"><text>加载中...</text></view>
    <view v-else-if="logs.length" class="log-list">
      <view v-for="log in logs" :key="log.key" class="log-row" @click="openDetail(log)">
        <view class="log-main">
          <view class="log-line1">
            <text class="log-type">{{ log.businessTypeName || '业务' }}</text>
            <text class="log-action" :class="actionClass(log.action)">{{ log.actionName || log.action }}</text>
            <text class="log-time">{{ shortTime(log.createTime) }}</text>
          </view>
          <text class="log-summary">{{ log.businessSummary || log.businessId || '—' }}</text>
          <text class="log-node">{{ log.nodeName || '—' }} · {{ log.operatorName || '—' }}</text>
        </view>
        <text class="log-arrow">›</text>
      </view>
    </view>
    <view v-else class="log-state"><text>暂无操作记录</text></view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api'

/**
 * 工作台「工作业务日志」卡片：最近 5 条本人发起/处理的流程操作。
 * 接口不可用时静默展示空态，不影响工作台其它卡片。
 */
const loading = ref(true)
const logs = ref([])

onMounted(load)

async function load() {
  if (!uni.getStorageSync('ops_employee_token')) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const data = await api.workflowMyLogs({ pageNum: 1, pageSize: 5 })
    logs.value = Array.isArray(data && data.records) ? data.records : []
  } catch (e) {
    logs.value = []
  } finally {
    loading.value = false
  }
}

function goAll() {
  uni.navigateTo({ url: '/pages/employee/work-log' })
}

function openDetail(log) {
  if (!log || !log.key) return
  uni.navigateTo({
    url: '/pages/employee/work-log-detail?key=' + encodeURIComponent(log.key)
  })
}

function actionClass(action) {
  if (action === 'REJECT' || action === 'TERMINATE') return 'stop'
  if (action === 'INITIATE') return 'initiate'
  return 'pass'
}

function shortTime(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(5, 16) : text
}

defineExpose({ reload: load })
</script>

<style scoped>
.work-log-card {
  margin: 24rpx 24rpx 0; background: #fff; border-radius: 20rpx;
  padding: 24rpx; box-shadow: 0 2rpx 16rpx rgba(0, 0, 0, .04);
  display: flex; flex-direction: column; gap: 16rpx;
}
.log-head { display: flex; align-items: center; justify-content: space-between; }
.log-title { font-size: 28rpx; font-weight: 700; color: #1D2129; }
.log-all { font-size: 23rpx; color: #FF6B35; }
.log-list { display: flex; flex-direction: column; }
.log-row {
  display: flex; align-items: center; gap: 12rpx; padding: 16rpx 0;
  border-bottom: 1rpx solid #F2F3F5;
}
.log-row:last-child { border-bottom: none; }
.log-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.log-line1 { display: flex; align-items: center; gap: 10rpx; }
.log-type { font-size: 22rpx; color: #4E5969; font-weight: 600; }
.log-action { font-size: 20rpx; padding: 1rpx 8rpx; border-radius: 4rpx; background: #E8F8E8; color: #0B7A42; }
.log-action.stop { background: #FFF1F0; color: #C62828; }
.log-action.initiate { background: #E8F3FF; color: #2F6FEB; }
.log-time { margin-left: auto; font-size: 20rpx; color: #A9AEB8; }
.log-summary {
  font-size: 24rpx; color: #1D2129;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.log-node { font-size: 21rpx; color: #86909C; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.log-arrow { font-size: 30rpx; color: #C9CDD4; flex-shrink: 0; }
.log-state { text-align: center; padding: 24rpx 0; font-size: 23rpx; color: #A9AEB8; }
</style>
