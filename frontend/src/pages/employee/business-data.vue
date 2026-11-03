<template>
  <view class="page">
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
    <view class="empty" v-else-if="!loading && months.length === 0">
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
          <picker mode="selector" :range="batchList" range-key="label" @change="onBatchChange">
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

      <!-- Monthly Breakdown -->
      <BusinessMonthList :months="months" />
    </template>

    <!-- Bottom Bar -->
    <view class="bottom-bar">
      <view class="bar-item active">
        <text class="bar-icon">📊</text>
        <text class="bar-label">业务数据</text>
      </view>
      <view class="bar-item" @click="goAiChat">
        <text class="bar-icon">🤖</text>
        <text class="bar-label">AI助手</text>
      </view>
      <view class="bar-item" @click="goProfile">
        <text class="bar-icon">👤</text>
        <text class="bar-label">我的</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import BusinessMonthList from '@/components/BusinessMonthList.vue'

const loading = ref(true)
const ready = ref(false)
const errorMsg = ref('')
const allTime = reactive({ totalSale: 0, totalCommission: 0, orderCount: 0 })
const months = ref([])
const batchList = reactive([{ label: '全部批次', value: '' }])
const selectedBatch = ref('')
const selectedBatchLabel = ref('全部批次')

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
  loadData()
})

// 从「新增业务」返回时刷新批次与明细
onShow(() => {
  if (!ready.value) return
  loadBatches()
  loadData()
})

async function loadData() {
  loading.value = true; errorMsg.value = ''
  try {
    const params = selectedBatch.value ? { batchNo: selectedBatch.value } : {}
    const data = await api.employeeOverview(params)
    if (data?.allTime) Object.assign(allTime, data.allTime)
    months.value = Array.isArray(data?.months) ? data.months : []
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败'
  } finally { loading.value = false }
}

function formatMoney(val) {
  const n = Number(val) || 0
  return '¥' + n.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function goAiChat() { uni.navigateTo({ url: '/pages/employee/ai-chat' }) }
function goProfile() { uni.navigateTo({ url: '/pages/employee/profile' }) }
function goAddBusiness() { uni.navigateTo({ url: '/pages/employee/business-data-new' }) }
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding-bottom: 130rpx; }

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

/* Bottom Bar */
.bottom-bar {
  position: fixed; bottom: 0; left: 0; right: 0; height: 110rpx;
  background: #fff; display: flex; align-items: center; justify-content: space-around;
  border-top: 1rpx solid #f0f0f0; padding-bottom: env(safe-area-inset-bottom); z-index: 100;
}
.bar-item { display: flex; flex-direction: column; align-items: center; gap: 4rpx; padding: 8rpx 24rpx; }
.bar-icon { font-size: 40rpx; }
.bar-label { font-size: 22rpx; color: #999; }
.bar-item.active .bar-label { color: #FF6B35; font-weight: 600; }
.batch-row { display:flex; align-items:center; justify-content:space-between; margin-bottom:16rpx; gap:12rpx; }
.batch-picker { display:flex; align-items:center; padding:12rpx 20rpx; background:#f5f5f5; border-radius:8rpx; font-size:26rpx; color:#333; flex:1; }
.batch-arrow { font-size:20rpx; color:#999; margin-left:8rpx; }
.check-btn { padding:12rpx 20rpx; background:#07C160; color:#fff; border-radius:8rpx; font-size:26rpx; white-space:nowrap; }
</style>
