<template>
  <view class="work-log-card" @click="goAll">
    <view class="log-head">
      <text class="log-title">工作业务日志</text>
      <text class="log-all">查看全部 ›</text>
    </view>

    <view v-if="loading" class="log-state"><text>加载中...</text></view>

    <view v-else-if="error" class="log-state">
      <text class="log-error">{{ error }}</text>
      <view class="log-retry" @click.stop="load()"><text>重试</text></view>
    </view>

    <view v-else-if="latest" class="log-latest">
      <view class="log-line1">
        <text class="log-type">{{ latest.businessTypeName || '业务' }}</text>
        <text class="log-action" :class="actionClass(latest.action)">
          {{ latest.actionName || latest.action || '操作' }}
        </text>
        <text class="log-time">{{ shortTime(latest.createTime) }}</text>
      </view>
      <text class="log-summary">{{ latest.businessSummary || latest.businessId || '—' }}</text>
      <text class="log-node">{{ latest.nodeName || '—' }} · {{ latest.operatorName || '—' }}</text>
    </view>

    <view v-else class="log-state">
      <text>暂无操作记录，处理业务后会在这里留下记录</text>
    </view>

    <view v-if="!loading && !error" class="log-stats">
      <text class="log-week">
        本周已记录 <text class="log-num">{{ weekCount === null ? '—' : weekCount }}</text> 条
      </text>
      <text class="log-total">累计 {{ totalCount }} 条</text>
    </view>
  </view>
</template>

<script setup>
import { onMounted } from 'vue'
import { useWorkLogSummary } from '@/hooks/useWorkLogSummary'
import { track } from '@/utils/track'

/**
 * 工作台「工作业务日志」单卡片：最近一条摘要 + 本周/累计统计，点击进入日志列表。
 * 日志由审批/业务动作自动生成，系统没有手工「记录日志」入口，改为单卡片
 * 不会丢失任何写入能力；列表页与详情页保持原样。
 */
const { latest, totalCount, weekCount, loading, error, load } = useWorkLogSummary()

onMounted(load)

function goAll() {
  track('worklog_card_click', { total: totalCount.value })
  uni.navigateTo({ url: '/pages/employee/work-log' })
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
  margin: 24rpx 24rpx 0;
  background: #fff;
  border-radius: 20rpx;
  padding: 24rpx;
  box-shadow: 0 2rpx 16rpx rgba(0, 0, 0, .04);
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.work-log-card:active { transform: scale(.995); }
.log-head { display: flex; align-items: center; justify-content: space-between; }
.log-title { font-size: 28rpx; font-weight: 700; color: #1D2129; }
.log-all { font-size: 23rpx; color: #FF6B35; }
.log-latest { display: flex; flex-direction: column; gap: 6rpx; }
.log-line1 { display: flex; align-items: center; gap: 10rpx; }
.log-type { font-size: 22rpx; color: #4E5969; font-weight: 600; }
.log-action { font-size: 20rpx; padding: 1rpx 8rpx; border-radius: 4rpx; background: #E8F8E8; color: #0B7A42; }
.log-action.stop { background: #FFF1F0; color: #C62828; }
.log-action.initiate { background: #E8F3FF; color: #2F6FEB; }
.log-time { margin-left: auto; font-size: 20rpx; color: #A9AEB8; }
.log-summary {
  font-size: 26rpx;
  color: #1D2129;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.log-node { font-size: 21rpx; color: #86909C; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.log-stats {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 16rpx;
  border-top: 1rpx solid #F2F3F5;
  font-size: 22rpx;
  color: #86909C;
}
.log-week { color: #4E5969; }
.log-num { font-size: 28rpx; font-weight: 700; color: #FF6B35; padding: 0 2rpx; }
.log-total { color: #86909C; }
.log-state {
  padding: 24rpx 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14rpx;
  text-align: center;
  font-size: 23rpx;
  color: #A9AEB8;
}
.log-error { color: #F53F3F; }
.log-retry {
  padding: 10rpx 36rpx;
  border: 1rpx solid #FF6B35;
  border-radius: 30rpx;
  font-size: 23rpx;
  color: #FF6B35;
}
</style>
