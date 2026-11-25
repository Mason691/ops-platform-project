<template>
  <view class="page">
    <EmployeePageHead title="工作日志" />
    <view class="filter-card">
      <view class="filter-row">
        <picker mode="selector" :range="businessTypeLabels" :value="businessTypeIndex"
                @change="onBusinessType">
          <view class="filter-pill">{{ businessTypeLabel }}</view>
        </picker>
        <picker mode="selector" :range="actionLabels" :value="actionIndex" @change="onAction">
          <view class="filter-pill">{{ actionLabel }}</view>
        </picker>
      </view>
      <view class="filter-row">
        <picker mode="date" :value="filters.startDate" @change="onStart">
          <view class="filter-pill">{{ filters.startDate || '开始日期' }}</view>
        </picker>
        <text class="filter-sep">至</text>
        <picker mode="date" :value="filters.endDate" @change="onEnd">
          <view class="filter-pill">{{ filters.endDate || '结束日期' }}</view>
        </picker>
        <text class="filter-reset" @click="resetFilters">重置</text>
      </view>
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-error">{{ errorMsg }}</text>
      <view class="retry" @click="load(true)"><text>重试</text></view>
    </view>
    <view v-else-if="!logs.length" class="state"><text>暂无操作记录</text></view>
    <view v-else class="log-list">
      <view v-for="log in logs" :key="log.key" class="log-card" @click="openDetail(log)">
        <view class="log-line1">
          <text class="log-type">{{ log.businessTypeName || '业务' }}</text>
          <text class="log-status" :class="statusClass(log)">{{ log.instanceStatusName || '—' }}</text>
          <text v-if="log.instanceDeleted || log.deleted" class="log-deleted">已删除</text>
          <text class="log-action" :class="actionClass(log)">{{ log.actionName || log.action }}</text>
          <text class="log-time">{{ formatTime(log.createTime) }}</text>
        </view>
        <text class="log-summary">{{ log.businessSummary || log.businessId || '—' }}</text>
        <view class="log-line2">
          <text class="log-node">{{ log.nodeName || '—' }}</text>
          <text class="log-operator">{{ log.operatorName || '—' }}</text>
        </view>
        <text v-if="log.opinion" class="log-opinion">{{ log.opinion }}</text>
      </view>
    </view>

    <view v-if="logs.length && hasMore && !loading" class="load-more" @click="loadMore">
      <text>{{ loadingMore ? '加载中...' : '加载更多' }}</text>
    </view>
    <view v-else-if="logs.length && !hasMore" class="list-end"><text>没有更多了</text></view>
  </view>
</template>

<script setup>
import EmployeePageHead from '@/components/EmployeePageHead.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { api } from '@/api'

/**
 * 工作业务日志列表：当前登录员工发起/处理的全部流程操作。
 * 后端按 userId 强过滤（操作人或实例发起人），前端只做展示与筛选。
 */
const PAGE_SIZE = 20
const logs = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)
const loadingMore = ref(false)
const errorMsg = ref('')
const businessTypeOptions = ref([
  { value: '', label: '全部类型' },
  { value: 'OPS_BUSINESS_ORDER', label: '业务数据订单' },
  { value: 'OPS_BUSINESS_ORDER_VOID', label: '业务订单作废' },
  { value: 'PUR_ORDER', label: '采购订单' }
])
const actionOptions = ref([
  { value: '', label: '全部动作' },
  { value: 'INITIATE', label: '发起' },
  { value: 'APPROVE', label: '通过' },
  { value: 'REJECT', label: '驳回' },
  { value: 'MANUAL_ADVANCE', label: '运营推进' },
  { value: 'TERMINATE', label: '终止' }
])
const filters = reactive({ businessType: '', action: '', startDate: '', endDate: '' })

const businessTypeLabels = computed(() => businessTypeOptions.value.map(item => item.label))
const actionLabels = computed(() => actionOptions.value.map(item => item.label))
const businessTypeIndex = computed(() => Math.max(0,
  businessTypeOptions.value.findIndex(item => item.value === filters.businessType)))
const actionIndex = computed(() => Math.max(0,
  actionOptions.value.findIndex(item => item.value === filters.action)))
const businessTypeLabel = computed(() => businessTypeOptions.value[businessTypeIndex.value]?.label || '全部类型')
const actionLabel = computed(() => actionOptions.value[actionIndex.value]?.label || '全部动作')
const hasMore = computed(() => logs.value.length < total.value)

onMounted(() => load(true))
onReachBottom(loadMore)
onPullDownRefresh(async () => {
  await load(true)
  uni.stopPullDownRefresh()
})

async function load(reset) {
  if (reset) {
    pageNum.value = 1
    loading.value = true
  } else {
    loadingMore.value = true
  }
  errorMsg.value = ''
  try {
    const params = { pageNum: pageNum.value, pageSize: PAGE_SIZE }
    if (filters.businessType) params.businessType = filters.businessType
    if (filters.action) params.action = filters.action
    if (filters.startDate) params.startDate = filters.startDate
    if (filters.endDate) params.endDate = filters.endDate
    const data = await api.workflowMyLogs(params)
    const records = Array.isArray(data && data.records) ? data.records : []
    logs.value = reset ? records : logs.value.concat(records)
    total.value = Number(data && data.total) || 0
    if (Array.isArray(data && data.businessTypeOptions) && data.businessTypeOptions.length) {
      businessTypeOptions.value = [{ value: '', label: '全部类型' }].concat(data.businessTypeOptions)
    }
    if (Array.isArray(data && data.actionOptions) && data.actionOptions.length) {
      actionOptions.value = [{ value: '', label: '全部动作' }].concat(data.actionOptions)
    }
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败，请重试'
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function loadMore() {
  if (!hasMore.value || loading.value || loadingMore.value) return
  pageNum.value += 1
  load(false)
}

function onBusinessType(event) {
  filters.businessType = businessTypeOptions.value[Number(event.detail.value)]?.value || ''
  load(true)
}

function onAction(event) {
  filters.action = actionOptions.value[Number(event.detail.value)]?.value || ''
  load(true)
}

function onStart(event) {
  filters.startDate = event.detail.value
  load(true)
}

function onEnd(event) {
  filters.endDate = event.detail.value
  load(true)
}

function resetFilters() {
  filters.businessType = ''
  filters.action = ''
  filters.startDate = ''
  filters.endDate = ''
  load(true)
}

function openDetail(log) {
  if (!log || !log.key) return
  uni.navigateTo({
    url: '/pages/employee/work-log-detail?key=' + encodeURIComponent(log.key)
  })
}

function statusClass(log) {
  if (log.instanceStatus === 'COMPLETED') return 'done'
  if (log.instanceStatus === 'REJECTED' || log.instanceStatus === 'TERMINATED') return 'stop'
  return 'running'
}

function actionClass(log) {
  if (log.action === 'REJECT' || log.action === 'TERMINATE') return 'stop'
  if (log.action === 'INITIATE') return 'initiate'
  return 'pass'
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 20rpx 24rpx 40rpx; box-sizing: border-box; }
.filter-card {
  background: #fff; border-radius: 16rpx; padding: 18rpx;
  display: flex; flex-direction: column; gap: 14rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .03); margin-bottom: 18rpx;
}
.filter-row { display: flex; align-items: center; gap: 12rpx; flex-wrap: wrap; }
.filter-pill {
  padding: 10rpx 18rpx; background: #F5F6FA; border-radius: 24rpx;
  font-size: 23rpx; color: #4E5969; max-width: 220rpx;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.filter-sep { font-size: 21rpx; color: #A9AEB8; }
.filter-reset { margin-left: auto; font-size: 23rpx; color: #FF6B35; padding: 6rpx 10rpx; }
.log-list { display: flex; flex-direction: column; gap: 14rpx; }
.log-card {
  background: #fff; border-radius: 16rpx; padding: 22rpx 24rpx;
  display: flex; flex-direction: column; gap: 8rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .03);
}
.log-line1 { display: flex; align-items: center; gap: 10rpx; flex-wrap: wrap; }
.log-type { font-size: 23rpx; font-weight: 600; color: #1D2129; }
.log-status, .log-action, .log-deleted {
  font-size: 19rpx; padding: 1rpx 8rpx; border-radius: 4rpx;
}
.log-status.running { background: #E8F3FF; color: #2F6FEB; }
.log-status.done { background: #E8F8E8; color: #0B7A42; }
.log-status.stop, .log-deleted { background: #FFF1F0; color: #C62828; }
.log-action.pass { background: #E8F8E8; color: #0B7A42; }
.log-action.stop { background: #FFF1F0; color: #C62828; }
.log-action.initiate { background: #E8F3FF; color: #2F6FEB; }
.log-time { margin-left: auto; font-size: 20rpx; color: #A9AEB8; }
.log-summary {
  font-size: 25rpx; color: #1D2129;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.log-line2 { display: flex; align-items: center; gap: 14rpx; }
.log-node { font-size: 21rpx; color: #4E5969; }
.log-operator { font-size: 21rpx; color: #86909C; }
.log-opinion {
  font-size: 21rpx; color: #86909C; line-height: 1.5;
  background: #F7F9FC; border-radius: 8rpx; padding: 10rpx 12rpx;
}
.load-more, .list-end {
  text-align: center; padding: 26rpx; font-size: 23rpx; color: #86909C;
}
.state { text-align: center; padding: 160rpx 32rpx; font-size: 25rpx; color: #86909C; }
.state-error { display: block; color: #C62828; margin-bottom: 20rpx; line-height: 1.5; }
.retry {
  display: inline-block; padding: 12rpx 36rpx; background: #FF6B35;
  color: #fff; border-radius: 28rpx; font-size: 24rpx;
}
</style>
