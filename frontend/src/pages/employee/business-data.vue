<template>
  <view class="page">
    <view class="page-head-wrap"><EmployeePageHead title="业务数据" /></view>
    <!-- Loading -->
    <view class="loading-wrap" v-if="loading">
      <text class="loading-text">加载中...</text>
    </view>

    <!-- Error -->
    <view class="error-wrap" v-else-if="errorMsg">
      <text class="error-text">{{ errorMsg }}</text>
      <view class="retry-btn" @click="loadData"><text>重试</text></view>
    </view>

    <!-- Empty -->
    <view class="empty" v-else-if="ordersLoaded && !orders.length">
      <text class="empty-icon">📋</text>
      <text class="empty-text">暂无业务数据</text>
      <view class="add-btn empty-add" @click="goAddBusiness"><text>＋ 新增业务</text></view>
    </view>

    <!-- Data -->
    <template v-else>
      <!-- All-time Totals -->
      <view class="alltime-section">
        <view class="section-head">
          <text class="section-title">📊 总计</text>
          <view class="add-btn" @click="goAddBusiness"><text>＋ 新增业务</text></view>
        </view>
        <!-- Batch selector -->
        <view class="batch-row">
          <picker mode="selector" :range="batchList" range-key="label"
                  :value="selectedBatchIndex" @change="onBatchChange">
            <view class="batch-picker">
              <text>{{ selectedBatchLabel }}</text>
              <text class="batch-arrow">▼</text>
            </view>
          </picker>
          <view class="check-btn" v-if="selectedBatch" @click="doBatchCheck">
            <text>✅ 经办人核对</text>
          </view>
        </view>
        <view class="alltime-cards">
          <view class="at-card">
            <text class="at-label">总销售额</text>
            <text class="at-val sale">{{ formatMoney(allTime.totalSale) }}</text>
          </view>
          <view class="at-card">
            <text class="at-label">总提成</text>
            <text class="at-val commission">{{ formatMoney(allTime.totalCommission) }}</text>
          </view>
          <view class="at-card">
            <text class="at-label">总订单数</text>
            <text class="at-val count">{{ allTime.orderCount || 0 }}</text>
          </view>
        </view>
      </view>

      <!-- Order list: 订单主行 + 展开明细 + 流程进度 -->
      <view class="orders-section">
        <view class="section-head">
          <text class="section-title">📦 业务订单</text>
          <text class="order-total">共 {{ orderTotal }} 单</text>
        </view>
        <BusinessOrderList v-if="orders.length" :orders="orders"
                           :status-options="statusOptions"
                           :loading="orderLoadingMore" :has-more="hasMore"
                           @load-more="loadMore" />
        <view v-else-if="orderLoading" class="orders-loading">
          <text>加载订单...</text>
        </view>
      </view>
    </template>

    <!-- 底部导航与员工工作台主页保持一致（AI助手/主页/待办/我的） -->
    <EmployeeTabBar active-key="home" :allow-active-click="true" @switch="onTabSwitch" />
  </view>
</template>

<script setup>
import EmployeePageHead from '@/components/EmployeePageHead.vue'
import EmployeeTabBar from '@/components/EmployeeTabBar.vue'
import { ref, reactive, computed, onMounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import BusinessOrderList from '@/components/BusinessOrderList.vue'

const loading = ref(true)
const ready = ref(false)
const errorMsg = ref('')
const allTime = reactive({ totalSale: 0, totalCommission: 0, orderCount: 0 })
const orders = ref([])
const statusOptions = ref([])
const orderTotal = ref(0)
const orderPage = ref(1)
const orderLoading = ref(false)
const orderLoadingMore = ref(false)
const ordersLoaded = ref(false)
const PAGE_SIZE = 10
const hasMore = computed(() => orders.value.length < orderTotal.value)
const batchList = reactive([{ label: '全部批次', value: '' }])
const selectedBatch = ref('')
const selectedBatchLabel = ref('全部批次')
const selectedBatchIndex = computed(() => {
  const index = batchList.findIndex(item => item.value === selectedBatch.value)
  return index >= 0 ? index : 0
})

async function loadBatches() {
  try {
    const data = await api.employeeBatches()
    batchList.length = 1 // keep "全部批次"
    if (Array.isArray(data)) {
      data.forEach(b => batchList.push({ label: b, value: b }))
    }
  } catch (e) {
    console.error('[bd] loadBatches error:', e)
  }
}

function onBatchChange(e) {
  const idx = e.detail.value
  const item = batchList[idx]
  selectedBatch.value = item.value
  selectedBatchLabel.value = item.label
  loadData()
}

async function doBatchCheck() {
  if (!selectedBatch.value) return
  const r = await new Promise((resolve) => {
    uni.showModal({
      title: '确认核对',
      content: '将批次 ' + selectedBatch.value + ' 下本人的已计算数据标记为已核对，确认？',
      success: (res) => resolve(res.confirm)
    })
  })
  if (!r) return
  try {
    const res = await api.employeeBatchCheck({ batchNo: selectedBatch.value })
    uni.showToast({ title: res.message || '核对成功', icon: 'success' })
    loadData()
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '核对失败', icon: 'none' })
  }
}

onMounted(() => { loadBatches();
  // Auth guard
  const empToken = uni.getStorageSync('ops_employee_token')
  if (!empToken) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  ready.value = true
  applyPendingBatch()
  loadData()
})

// 从「新增业务」返回时刷新批次与明细；
// 成功页「查看订单」通过本地标记传入批次，回到列表后直接定位该批次。
onShow(() => {
  if (!ready.value) return
  applyPendingBatch()
  loadBatches()
  loadData()
})

/** 消费成功页写入的批次定位标记；60 秒内有效，避免陈旧标记误选。 */
function applyPendingBatch() {
  const pendingBatch = uni.getStorageSync('ops_employee_view_batch')
  if (pendingBatch && pendingBatch.batchNo
    && Date.now() - Number(pendingBatch.ts || 0) < 60000) {
    selectedBatch.value = pendingBatch.batchNo
    selectedBatchLabel.value = pendingBatch.batchNo
  }
  if (pendingBatch) uni.removeStorageSync('ops_employee_view_batch')
}

async function loadData() {
  loading.value = true; errorMsg.value = ''
  try {
    await loadTotals()
    await loadOrders(true)
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败'
  } finally { loading.value = false }
}

async function loadTotals() {
  const params = selectedBatch.value ? { batchNo: selectedBatch.value } : {}
  const data = await api.employeeOverview(params)
  if (data?.allTime) Object.assign(allTime, data.allTime)
}

async function loadOrders(reset) {
  if (reset) {
    orderPage.value = 1
    orderLoading.value = true
    ordersLoaded.value = false
  } else {
    orderLoadingMore.value = true
  }
  try {
    const params = { pageNum: orderPage.value, pageSize: PAGE_SIZE }
    if (selectedBatch.value) params.batchNo = selectedBatch.value
    const data = await api.employeeOrders(params)
    const records = Array.isArray(data?.records) ? data.records : []
    orders.value = reset ? records : orders.value.concat(records)
    orderTotal.value = Number(data?.total) || 0
    statusOptions.value = Array.isArray(data?.bizStatusOptions) ? data.bizStatusOptions : []
    ordersLoaded.value = true
  } finally {
    orderLoading.value = false
    orderLoadingMore.value = false
  }
}

function loadMore() {
  if (!hasMore.value || orderLoadingMore.value) return
  orderPage.value += 1
  loadOrders(false).catch(e => {
    uni.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
  })
}

function formatMoney(val) {
  const n = Number(val) || 0
  return '¥' + n.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function onTabSwitch(key) {
  const routes = {
    ai: '/pages/employee/ai-chat',
    home: '/pages/employee/dashboard',
    todo: '/pages/employee/flow-todo',
    profile: '/pages/employee/profile'
  }
  if (routes[key]) uni.redirectTo({ url: routes[key] })
}
function goAddBusiness() { uni.navigateTo({ url: '/pages/employee/business-data-new' }) }
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding-bottom: 130rpx; }
.page-head-wrap { margin: 16rpx 24rpx 0; }

/* Loading / Error / Empty */
.loading-wrap, .error-wrap, .empty { text-align: center; padding: 200rpx 32rpx; }
.loading-text { font-size: 28rpx; color: #999; }
.error-text { font-size: 28rpx; color: #e53935; display: block; margin-bottom: 24rpx; }
.retry-btn { display: inline-block; padding: 16rpx 48rpx; background: #FF6B35; color: #fff; border-radius: 32rpx; font-size: 28rpx; }
.empty-icon { font-size: 80rpx; display: block; margin-bottom: 16rpx; }
.empty-text { font-size: 28rpx; color: #999; }
.empty-add { display: inline-block; margin-top: 24rpx; }

/* Section title */
.section-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 24rpx 24rpx 16rpx;
}
.section-title {
  font-size: 28rpx; color: #999; font-weight: 600;
}
.add-btn {
  padding: 10rpx 24rpx; background: #FF6B35; color: #fff;
  border-radius: 28rpx; font-size: 24rpx; font-weight: 600;
}

/* All-time Totals */
.alltime-section { padding: 8rpx 0 0; }
.alltime-cards { display: flex; gap: 16rpx; padding: 0 24rpx; }
.at-card {
  flex: 1; background: #fff; border-radius: 16rpx;
  padding: 24rpx 16rpx; text-align: center;
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04);
}
.at-label { font-size: 24rpx; color: #999; display: block; margin-bottom: 8rpx; }
.at-val { font-size: 36rpx; font-weight: 700; display: block; }
.at-val.sale { color: #FF6B35; }
.at-val.commission { color: #4caf50; }
.at-val.count { color: #333; }

.batch-row { display:flex; align-items:center; justify-content:space-between; margin-bottom:16rpx; gap:12rpx; }
.batch-picker { display:flex; align-items:center; padding:12rpx 20rpx; background:#f5f5f5; border-radius:8rpx; font-size:26rpx; color:#333; flex:1; }
.batch-arrow { font-size:20rpx; color:#999; margin-left:8rpx; }
.check-btn { padding:12rpx 20rpx; background:#07C160; color:#fff; border-radius:8rpx; font-size:26rpx; white-space:nowrap; }

/* 业务订单列表（订单主行 + 展开明细 + 流程进度） */
.orders-section { padding: 0 24rpx; }
.orders-section .section-head { padding-left: 0; padding-right: 0; }
.order-total { font-size: 23rpx; color: #86909C; }
.orders-loading {
  text-align: center; padding: 40rpx; font-size: 24rpx; color: #86909C;
  background: #fff; border-radius: 16rpx;
}
</style>
