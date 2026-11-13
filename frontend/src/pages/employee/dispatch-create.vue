<template>
  <view class="page">
    <view class="search-bar">
      <input
        v-model="keyword"
        class="search-input"
        placeholder="订单号 / 客户 / 手机 / 收据号"
        confirm-type="search"
        @confirm="search"
      />
      <view class="search-btn" @click="search">搜索</view>
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
      <text class="state-icon">🧾</text>
      <text class="state-text">暂无可派工订单</text>
      <text class="state-hint">仅展示可管辖组织内已完成财务核对、且未产生销售出库单的订单</text>
    </view>

    <view v-else class="list">
      <view v-for="o in records" :key="o.id" class="card" @click="openForm(o.id)">
        <view class="card-top">
          <text class="order-no">{{ o.orderNo || ('订单 #' + o.id) }}</text>
          <text class="tag">已核对</text>
        </view>
        <view class="row">
          <text class="label">客户</text>
          <text class="value">{{ o.customer || '—' }} {{ o.customerPhone || '' }}</text>
        </view>
        <view class="row">
          <text class="label">地址</text>
          <text class="value">{{ addressText(o) }}</text>
        </view>
        <view class="row">
          <text class="label">公司</text>
          <text class="value">{{ o.company || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">经办人</text>
          <text class="value">{{ o.agentNames || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">批次/收据</text>
          <text class="value">{{ o.batchNo || '—' }} / {{ o.receiptNo || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">合计</text>
          <text class="value strong">{{ money(o.totalAmount) }} · {{ o.productCount || 0 }} 项商品</text>
        </view>
        <view class="card-foot">
          <text class="hint">核对 {{ o.financeCheckUserName || '—' }} {{ o.financeCheckTime || '' }}</text>
          <text class="go">发起派工 ›</text>
        </view>
      </view>
      <view v-if="hasMore" class="more" @click="loadMore">{{ loadingMore ? '加载中...' : '加载更多' }}</view>
      <view v-else class="no-more">没有更多了</view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { api } from '@/api/index'

const keyword = ref('')
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

onShow(() => {
  if (!uni.getStorageSync('ops_employee_token')) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
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
  if (reset) pageNum.value = 1
  try {
    const data = await api.employeeDispatchableOrders({
      pageNum: pageNum.value,
      pageSize,
      keyword: keyword.value.trim() || undefined
    })
    if (seq !== requestSeq) return
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

function search() {
  load(true)
}

function loadMore() {
  if (loadingMore.value) return
  pageNum.value += 1
  load(false)
}

function openForm(orderId) {
  uni.navigateTo({ url: '/pages/employee/dispatch-form?orderId=' + orderId })
}

function addressText(o) {
  const text = [o.communityName, o.detailedAddress].filter(Boolean).join(' ')
  return text || '—'
}

function money(value) {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  return Number.isFinite(n) ? '¥' + n.toFixed(2) : String(value)
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding-bottom: 40rpx; }

.search-bar {
  display: flex; align-items: center; gap: 16rpx;
  padding: 20rpx 24rpx; background: #fff; position: sticky; top: 0; z-index: 10;
}
.search-input {
  flex: 1; height: 72rpx; padding: 0 24rpx; border-radius: 36rpx;
  background: #F5F6FA; font-size: 26rpx; box-sizing: border-box;
}
.search-btn {
  padding: 16rpx 28rpx; border-radius: 36rpx; background: #FF6B35;
  color: #fff; font-size: 26rpx; font-weight: 600;
}

.state { padding: 120rpx 48rpx; display: flex; flex-direction: column; align-items: center; gap: 18rpx; }
.state-icon { font-size: 72rpx; }
.state-text { font-size: 28rpx; color: #4E5969; text-align: center; }
.state-hint { font-size: 22rpx; color: #86909C; text-align: center; line-height: 1.6; }
.spinner {
  width: 40rpx; height: 40rpx; border-radius: 50%;
  border: 4rpx solid #FFD8C7; border-top-color: #FF6B35;
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.retry-btn { margin-top: 8rpx; padding: 14rpx 40rpx; border-radius: 32rpx; background: #FF6B35; color: #fff; font-size: 26rpx; }

.list { padding: 20rpx 24rpx 0; display: flex; flex-direction: column; gap: 16rpx; }
.card { background: #fff; border-radius: 20rpx; padding: 28rpx 24rpx; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.03); }
.card:active { transform: scale(.99); }
.card-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16rpx; }
.order-no { font-size: 30rpx; font-weight: 700; color: #1D2129; }
.tag { font-size: 22rpx; color: #00B42A; background: #E8F8E8; padding: 4rpx 14rpx; border-radius: 8rpx; }
.row { display: flex; align-items: flex-start; gap: 16rpx; padding: 8rpx 0; }
.label { width: 96rpx; flex-shrink: 0; font-size: 24rpx; color: #86909C; }
.value { flex: 1; font-size: 26rpx; color: #1D2129; line-height: 1.5; }
.value.strong { font-weight: 600; }
.card-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 16rpx; padding-top: 16rpx; border-top: 1rpx solid #F2F3F5; }
.hint { font-size: 22rpx; color: #86909C; }
.go { font-size: 26rpx; color: #FF6B35; font-weight: 600; }
.more, .no-more { text-align: center; padding: 24rpx 0; font-size: 24rpx; color: #86909C; }
</style>
