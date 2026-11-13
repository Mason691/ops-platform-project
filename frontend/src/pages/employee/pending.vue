<template>
  <view class="page">
    <!-- 状态页签 -->
    <view class="tabs">
      <view
        v-for="tab in tabs"
        :key="tab.key"
        class="tab"
        :class="{ active: activeStatus === tab.key }"
        @click="switchTab(tab.key)"
      >
        <text>{{ tab.label }}</text>
      </view>
    </view>

    <view v-if="loading" class="state">
      <view class="spinner"></view>
      <text class="state-text">加载中...</text>
    </view>

    <view v-else-if="errorMsg" class="state">
      <text class="state-icon">⚠️</text>
      <text class="state-text">{{ errorMsg }}</text>
      <view class="retry-btn" @click="load(true)">重新加载</view>
    </view>

    <view v-else-if="!records.length" class="state">
      <text class="state-icon">📋</text>
      <text class="state-text">暂无{{ currentTabLabel }}工单</text>
      <text class="state-hint">下拉刷新试试</text>
    </view>

    <view v-else class="list">
      <view v-for="d in records" :key="d.id" class="card" @click="openDetail(d.id)">
        <view class="card-top">
          <text class="type-tag">{{ typeName(d.dispatchType) }}</text>
          <text class="status" :class="statusClass(d.status)">{{ statusName(d.status) }}</text>
        </view>
        <text class="dispatch-no">{{ d.dispatchNo }}</text>
        <view class="row">
          <text class="label">客户</text>
          <text class="value">{{ d.customerName || '—' }} {{ d.customerPhone || '' }}</text>
        </view>
        <view class="row">
          <text class="label">地址</text>
          <text class="value">{{ addressText(d) }}</text>
        </view>
        <view class="row">
          <text class="label">期望上门</text>
          <text class="value strong">{{ d.expectTime || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">派发时间</text>
          <text class="value">{{ d.dispatchTime || '—' }}</text>
        </view>
      </view>
      <view v-if="hasMore" class="more" @click="loadMore">{{ loadingMore ? '加载中...' : '加载更多' }}</view>
      <view v-else class="no-more">没有更多了</view>
    </view>

    <EmployeeTabBar active-key="pending" @switch="onTabSwitch" />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import EmployeeTabBar from '@/components/EmployeeTabBar.vue'

const tabs = [
  { key: 'PENDING', label: '待接单' },
  { key: 'ACCEPTED', label: '已接单' },
  { key: 'PROCESSING', label: '处理中' },
  { key: 'COMPLETED', label: '已完成' }
]

const activeStatus = ref('PENDING')
const records = ref([])
const loading = ref(false)
const loadingMore = ref(false)
const errorMsg = ref('')
const pageNum = ref(1)
const pageSize = 20
const total = ref(0)
let loadedOnce = false
let requestSeq = 0

const hasMore = computed(() => records.value.length < total.value)
const currentTabLabel = computed(() => {
  const tab = tabs.find(t => t.key === activeStatus.value)
  return tab ? tab.label : ''
})

onShow(() => {
  if (!uni.getStorageSync('ops_employee_token')) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  // 首次进入显示加载态；从详情返回时静默刷新，避免列表闪烁
  load(true, loadedOnce)
  loadedOnce = true
})

onPullDownRefresh(async () => {
  await load(true, true)
  uni.stopPullDownRefresh()
})

async function load(reset, silent = false) {
  const seq = ++requestSeq
  if (!silent && reset) loading.value = true
  else if (!reset) loadingMore.value = true
  errorMsg.value = ''
  if (reset) {
    pageNum.value = 1
  }
  try {
    const params = { pageNum: pageNum.value, pageSize }
    const data = activeStatus.value === 'PENDING'
      ? await api.employeePendingDispatches(params)
      : await api.employeeMyDispatches({ ...params, status: activeStatus.value })
    if (seq !== requestSeq) return // 丢弃切换页签/下拉刷新期间的旧响应
    const list = data.records || []
    records.value = reset ? list : records.value.concat(list)
    total.value = data.total || 0
  } catch (e) {
    if (seq !== requestSeq) return
    errorMsg.value = (e && e.msg) || '加载失败，请稍后重试'
    if (e && e.code === 401) {
      uni.redirectTo({ url: '/pages/employee/login' })
    }
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

function switchTab(key) {
  if (activeStatus.value === key) return
  activeStatus.value = key
  records.value = []
  total.value = 0
  load(true)
}

function loadMore() {
  if (loading.value || loadingMore.value || !hasMore.value) return
  pageNum.value += 1
  load(false)
}

function openDetail(id) {
  uni.navigateTo({ url: '/pages/employee/work-order-detail?id=' + id })
}

function onTabSwitch(key) {
  const routes = { ai: '/pages/employee/ai-chat', home: '/pages/employee/dashboard', profile: '/pages/employee/profile' }
  if (routes[key]) uni.redirectTo({ url: routes[key] })
}

function typeName(type) {
  return type === 'VISIT' ? '入户随访' : '施工'
}

function statusName(status) {
  return {
    PENDING: '待接单',
    ACCEPTING: '接单处理中',
    ACCEPTED: '已接单',
    PROCESSING: '处理中',
    COMPLETED: '已完成',
    REJECTED: '已退回',
    CANCELLED: '已取消'
  }[status] || status
}

function statusClass(status) {
  return 'status-' + String(status || '').toLowerCase()
}

function addressText(d) {
  const text = [d.communityName, d.detailedAddress].filter(Boolean).join(' ')
  return text || '—'
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f5f5;
  padding-bottom: 150rpx;
}

.tabs {
  display: flex;
  background: #fff;
  padding: 0 12rpx;
  border-bottom: 1rpx solid #f0f0f0;
  position: sticky;
  top: 0;
  z-index: 10;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 26rpx 0 22rpx;
  font-size: 27rpx;
  color: #4E5969;
  position: relative;
}
.tab.active {
  color: #FF6B35;
  font-weight: 600;
}
.tab.active::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 8rpx;
  width: 44rpx;
  height: 6rpx;
  margin-left: -22rpx;
  border-radius: 3rpx;
  background: #FF6B35;
}

.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 120rpx 40rpx;
}
.state-icon { font-size: 64rpx; margin-bottom: 16rpx; }
.state-text { font-size: 28rpx; color: #86909C; }
.state-hint { font-size: 24rpx; color: #C9CDD4; margin-top: 10rpx; }
.retry-btn {
  margin-top: 28rpx;
  padding: 14rpx 40rpx;
  border-radius: 30rpx;
  background: #FF6B35;
  color: #fff;
  font-size: 26rpx;
}
.spinner {
  width: 44rpx;
  height: 44rpx;
  border: 4rpx solid #eee;
  border-top-color: #FF6B35;
  border-radius: 50%;
  animation: spin .8s linear infinite;
  margin-bottom: 16rpx;
}
@keyframes spin { to { transform: rotate(360deg); } }

.list { padding: 20rpx 24rpx; }
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 26rpx 24rpx;
  margin-bottom: 20rpx;
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04);
}
.card:active { transform: scale(.99); }
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10rpx;
}
.type-tag {
  font-size: 22rpx;
  color: #FF6B35;
  background: #FFF3E8;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
}
.status { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; }
.status-pending { background: #FFF3E8; color: #E85D2C; }
.status-accepting { background: #F0F1F5; color: #4E5969; }
.status-accepted { background: #E6F0FF; color: #409EFF; }
.status-processing { background: #F3E8FF; color: #8B5CF6; }
.status-completed { background: #E8F8E8; color: #07C160; }
.status-rejected { background: #FFECEC; color: #e53935; }
.status-cancelled { background: #F5F5F5; color: #999; }

.dispatch-no {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: #1D2129;
  margin-bottom: 14rpx;
}
.row {
  display: flex;
  align-items: flex-start;
  margin-top: 8rpx;
  font-size: 25rpx;
}
.label { width: 132rpx; color: #86909C; flex-shrink: 0; }
.value { flex: 1; color: #4E5969; word-break: break-all; }
.value.strong { color: #1D2129; font-weight: 500; }

.more, .no-more {
  text-align: center;
  font-size: 25rpx;
  color: #86909C;
  padding: 24rpx 0;
}
.more { color: #FF6B35; }
</style>
