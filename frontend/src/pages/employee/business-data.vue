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
    </view>

    <!-- Data -->
    <template v-else>
      <!-- All-time Totals -->
      <view class="alltime-section">
        <text class="section-title">📊 总计</text>
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
      <view class="monthly-section">
        <text class="section-title">📅 月度明细</text>

        <view class="month-card" v-for="m in months" :key="m.month">
          <view class="month-header" @click="toggleMonth(m.month)">
            <view class="month-info">
              <text class="month-label">{{ m.monthLabel || m.month }}</text>
              <text class="month-summary">
                {{ formatMoney(m.totalSale) }} / {{ formatMoney(m.totalCommission) }} / {{ m.orderCount }}单
              </text>
            </view>
            <text class="month-expand">{{ expanded[m.month] ? '▲' : '▼' }}</text>
          </view>

          <!-- Detail Records -->
          <view class="month-detail" v-if="expanded[m.month]">
            <view class="detail-row" v-for="(d, di) in (m.details || [])" :key="di">
              <view class="detail-info">
                <text class="detail-product">{{ d.brand || '-' }} {{ d.model || '' }}</text>
                <text class="detail-meta">
                  <text class="detail-cat">{{ d.categoryName || d.category || '-' }}</text>
                  <text v-if="d.customer" class="detail-customer"> · 客户: {{ d.customer }}</text>
                </text>
                <text class="detail-date">{{ d.saleDate || '-' }}</text>
              </view>
              <view class="detail-money">
                <text class="detail-sale">{{ formatMoney(d.salePrice) }}</text>
                <text class="detail-commission">提成 {{ formatMoney(d.commissionAmount) }}</text>
              </view>
            </view>
          </view>
        </view>
      </view>
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
      <view class="bar-item" @click="goBackMember">
        <text class="bar-icon">👤</text>
        <text class="bar-label">返回会员端</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '@/api/index'

const loading = ref(true)
const errorMsg = ref('')
const allTime = reactive({ totalSale: 0, totalCommission: 0, orderCount: 0 })
const months = ref([])
const expanded = reactive({})

onMounted(() => { loadData() })

async function loadData() {
  loading.value = true; errorMsg.value = ''
  try {
    const data = await api.employeeOverview()
    if (data?.allTime) Object.assign(allTime, data.allTime)
    months.value = Array.isArray(data?.months) ? data.months : []
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败'
  } finally { loading.value = false }
}

function toggleMonth(key) {
  if (expanded[key]) {
    delete expanded[key]
  } else {
    expanded[key] = true
  }
}

function formatMoney(val) {
  const n = Number(val) || 0
  return '¥' + n.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function goAiChat() { uni.navigateTo({ url: '/pages/employee/ai-chat' }) }
function goBackMember() { uni.switchTab({ url: '/pages/index/index' }) }
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

/* Section title */
.section-title {
  font-size: 28rpx; color: #999; font-weight: 600;
  padding: 24rpx 24rpx 16rpx; display: block;
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

/* Monthly Section */
.monthly-section { padding: 0 24rpx; }
.month-card {
  background: #fff; border-radius: 16rpx; margin-bottom: 16rpx;
  overflow: hidden; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04);
}
.month-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 24rpx;
}
.month-info { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.month-label { font-size: 30rpx; font-weight: 600; color: #333; }
.month-summary { font-size: 24rpx; color: #999; }
.month-expand { font-size: 24rpx; color: #ccc; padding: 8rpx; }

.month-detail { border-top: 1rpx solid #f0f0f0; }
.detail-row {
  display: flex; justify-content: space-between; align-items: flex-start;
  padding: 20rpx 24rpx; border-bottom: 1rpx solid #f8f8f8;
}
.detail-row:last-child { border-bottom: none; }
.detail-info { display: flex; flex-direction: column; gap: 4rpx; flex: 1; }
.detail-product { font-size: 28rpx; color: #333; font-weight: 500; }
.detail-meta { font-size: 22rpx; color: #999; }
.detail-cat { color: #FF6B35; }
.detail-customer { color: #999; }
.detail-date { font-size: 22rpx; color: #bbb; }
.detail-money { display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; }
.detail-sale { font-size: 28rpx; color: #FF6B35; font-weight: 600; }
.detail-commission { font-size: 22rpx; color: #4caf50; }

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
</style>
