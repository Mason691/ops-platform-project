<template>
  <view class="page">
    <!-- Month Selector -->
    <view class="month-bar">
      <view class="month-arrow" :class="{ disabled: monthIdx === 0 }" @click="prevMonth">
        <text>◀</text>
      </view>
      <text class="month-label">{{ formatMonth(currentMonth) }}</text>
      <view class="month-arrow" :class="{ disabled: monthIdx >= months.length - 1 }" @click="nextMonth">
        <text>▶</text>
      </view>
    </view>

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
    <view class="empty" v-else-if="!loading && categories.length === 0">
      <text class="empty-icon">📋</text>
      <text class="empty-text">当前月份暂无业务数据</text>
    </view>

    <!-- Data -->
    <template v-else>
      <!-- Stats Bar -->
      <view class="stats-row">
        <view class="stat-card">
          <text class="stat-label">总销售额</text>
          <text class="stat-val">{{ formatMoney(stats.totalSale) }}</text>
          <text class="stat-mom" v-if="stats.momSaleChange != null" :class="{ up: stats.momSaleChange > 0, down: stats.momSaleChange < 0 }">
            环比 {{ stats.momSaleChange > 0 ? '↑' : '↓' }}{{ Math.abs(stats.momSaleChange) }}%
          </text>
        </view>
        <view class="stat-card">
          <text class="stat-label">总提成</text>
          <text class="stat-val">{{ formatMoney(stats.totalCommission) }}</text>
          <text class="stat-mom" v-if="stats.momCommissionChange != null" :class="{ up: stats.momCommissionChange > 0, down: stats.momCommissionChange < 0 }">
            环比 {{ stats.momCommissionChange > 0 ? '↑' : '↓' }}{{ Math.abs(stats.momCommissionChange) }}%
          </text>
        </view>
      </view>
      <view class="order-count-bar">
        <text class="oc-label">订单数</text>
        <text class="oc-val">{{ stats.orderCount || 0 }}</text>
        <text class="stat-mom" v-if="stats.momOrderChange != null" :class="{ up: stats.momOrderChange > 0, down: stats.momOrderChange < 0 }">
          环比 {{ stats.momOrderChange > 0 ? '↑' : '↓' }}{{ Math.abs(stats.momOrderChange) }}%
        </text>
        <text class="stat-mom" v-else style="color:#ccc"> -- </text>
      </view>

      <!-- Category Cards -->
      <view class="category-list">
        <view class="cat-card" v-for="cat in categories" :key="cat.category">
          <view class="cat-header" @click="toggleCategory(cat)">
            <view class="cat-info">
              <text class="cat-name">{{ cat.categoryName || cat.category }}</text>
              <text class="cat-summary">{{ formatMoney(cat.totalSale) }} / {{ formatMoney(cat.totalCommission) }} / {{ cat.orderCount }}单</text>
            </view>
            <text class="cat-expand">{{ expandedCats[cat.category] ? '▲' : '▼' }}</text>
          </view>
          <!-- Detail Records -->
          <view class="cat-detail" v-if="expandedCats[cat.category]">
            <view class="detail-loading" v-if="detailLoading[cat.category]">
              <text>加载中...</text>
            </view>
            <template v-else>
              <view class="detail-row" v-for="(d, di) in (catDetails[cat.category] || [])" :key="di">
                <view class="detail-info">
                  <text class="detail-product">{{ d.brand || '-' }} {{ d.model || '' }}</text>
                  <text class="detail-customer" v-if="d.customer">客户: {{ d.customer }}</text>
                  <text class="detail-date">{{ d.saleDate || '-' }}</text>
                </view>
                <view class="detail-money">
                  <text class="detail-sale">{{ formatMoney(d.salePrice) }}</text>
                  <text class="detail-commission">提成 {{ formatMoney(d.commissionAmount) }}</text>
                </view>
              </view>
              <view class="detail-empty" v-if="!catDetails[cat.category] || catDetails[cat.category].length === 0">
                <text>暂无明细数据</text>
              </view>
            </template>
          </view>
        </view>
      </view>

      <!-- Monthly Total -->
      <view class="total-bar">
        <text class="total-label">本月合计</text>
        <text class="total-val">{{ formatMoney(stats.totalSale) }} / {{ formatMoney(stats.totalCommission) }}</text>
      </view>
    </template>

    <!-- Custom Bottom Bar -->
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

const months = ref([])
const currentMonth = ref('')
const monthIdx = ref(0)
const loading = ref(true)
const errorMsg = ref('')
const categories = ref([])
const stats = reactive({ totalSale: 0, totalCommission: 0, orderCount: 0, momSaleChange: null, momCommissionChange: null, momOrderChange: null })
const expandedCats = reactive({})
const catDetails = reactive({})
const detailLoading = reactive({})

onMounted(() => { loadData() })

async function loadData() {
  loading.value = true; errorMsg.value = ''
  try {
    const m = await api.employeeMonths()
    months.value = Array.isArray(m) ? m : (m && m.months) || []
    if (months.value.length === 0) {
      loading.value = false; return
    }
    currentMonth.value = months.value[0]
    monthIdx.value = 0
    await Promise.all([fetchStats(), fetchSummary()])
  } catch(e) {
    errorMsg.value = (e && e.msg) || '加载失败'
  } finally { loading.value = false }
}

async function fetchStats() {
  try {
    const s = await api.employeeStats(currentMonth.value)
    if (s) Object.assign(stats, s)
  } catch(e) {
    console.warn('[Employee] fetchStats failed:', e?.msg || e)
  }
}

async function fetchSummary() {
  try {
    const list = await api.employeeSummary(currentMonth.value)
    categories.value = Array.isArray(list) ? list : []
  } catch { categories.value = [] }
}

async function switchMonth(delta) {
  const idx = monthIdx.value + delta
  if (idx < 0 || idx >= months.value.length) return
  monthIdx.value = idx
  currentMonth.value = months.value[idx]
  // Reset expanded state
  Object.keys(expandedCats).forEach(k => delete expandedCats[k])
  Object.keys(catDetails).forEach(k => delete catDetails[k])
  // Refetch
  await Promise.all([fetchStats(), fetchSummary()])
}

function prevMonth() { switchMonth(-1) }
function nextMonth() { switchMonth(1) }

async function toggleCategory(cat) {
  const key = cat.category
  if (expandedCats[key]) {
    delete expandedCats[key]
    return
  }
  expandedCats[key] = true
  if (catDetails[key]) return // already loaded
  detailLoading[key] = true
  try {
    const list = await api.employeeDetail(currentMonth.value, key)
    catDetails[key] = Array.isArray(list) ? list : []
  } catch(e) {
    catDetails[key] = []
  } finally { delete detailLoading[key] }
}

function formatMonth(ym) {
  if (!ym) return ''
  const parts = ym.split('-')
  return parts[0] + '年' + parseInt(parts[1]) + '月'
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

/* Month Bar */
.month-bar { display: flex; align-items: center; justify-content: center; gap: 40rpx; padding: 24rpx; background: #fff; border-bottom: 1rpx solid #f0f0f0; }
.month-label { font-size: 32rpx; font-weight: 700; color: #333; min-width: 200rpx; text-align: center; }
.month-arrow { padding: 12rpx 20rpx; }
.month-arrow text { font-size: 28rpx; color: #FF6B35; }
.month-arrow.disabled text { color: #ccc; }

/* Loading / Error / Empty */
.loading-wrap, .error-wrap, .empty { text-align: center; padding: 200rpx 32rpx; }
.loading-text { font-size: 28rpx; color: #999; }
.error-text { font-size: 28rpx; color: #e53935; display: block; margin-bottom: 24rpx; }
.retry-btn { display: inline-block; padding: 16rpx 48rpx; background: #FF6B35; color: #fff; border-radius: 32rpx; font-size: 28rpx; }
.empty-icon { font-size: 80rpx; display: block; margin-bottom: 16rpx; }
.empty-text { font-size: 28rpx; color: #999; }

/* Stats */
.stats-row { display: grid; grid-template-columns: 1fr 1fr; gap: 20rpx; padding: 24rpx; }
.stat-card { background: #fff; border-radius: 16rpx; padding: 24rpx; text-align: center; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04); }
.stat-label { font-size: 24rpx; color: #999; display: block; }
.stat-val { font-size: 40rpx; font-weight: 700; color: #FF6B35; display: block; margin: 8rpx 0; }
.stat-mom { font-size: 22rpx; display: inline-block; }
.stat-mom.up { color: #e53935; }
.stat-mom.down { color: #4caf50; }
.stat-mom:not(.up):not(.down) { color: #999; }

.order-count-bar { background: #fff; margin: 0 24rpx 20rpx; border-radius: 16rpx; padding: 24rpx; display: flex; align-items: center; gap: 16rpx; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04); }
.oc-label { font-size: 24rpx; color: #999; }
.oc-val { font-size: 36rpx; font-weight: 700; color: #333; flex: 1; }

/* Category Cards */
.category-list { padding: 0 24rpx; }
.cat-card { background: #fff; border-radius: 16rpx; margin-bottom: 16rpx; overflow: hidden; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04); }
.cat-header { display: flex; justify-content: space-between; align-items: center; padding: 24rpx; }
.cat-info { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.cat-name { font-size: 30rpx; font-weight: 600; color: #333; }
.cat-summary { font-size: 24rpx; color: #999; }
.cat-expand { font-size: 24rpx; color: #ccc; padding: 8rpx; }

.cat-detail { border-top: 1rpx solid #f0f0f0; }
.detail-loading { padding: 24rpx; text-align: center; font-size: 24rpx; color: #999; }
.detail-row { display: flex; justify-content: space-between; align-items: flex-start; padding: 20rpx 24rpx; border-bottom: 1rpx solid #f8f8f8; }
.detail-row:last-child { border-bottom: none; }
.detail-info { display: flex; flex-direction: column; gap: 4rpx; flex: 1; }
.detail-product { font-size: 28rpx; color: #333; font-weight: 500; }
.detail-customer { font-size: 22rpx; color: #999; }
.detail-date { font-size: 22rpx; color: #bbb; }
.detail-money { display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; }
.detail-sale { font-size: 28rpx; color: #FF6B35; font-weight: 600; }
.detail-commission { font-size: 22rpx; color: #999; }
.detail-empty { padding: 24rpx; text-align: center; font-size: 24rpx; color: #999; }

/* Monthly Total */
.total-bar { margin: 20rpx 24rpx 32rpx; background: linear-gradient(135deg, #FFF7F0, #FFF0E8); border-radius: 16rpx; padding: 24rpx; display: flex; justify-content: space-between; align-items: center; }
.total-label { font-size: 28rpx; color: #FF6B35; font-weight: 600; }
.total-val { font-size: 28rpx; color: #FF6B35; }

/* Bottom Bar */
.bottom-bar { position: fixed; bottom: 0; left: 0; right: 0; height: 110rpx; background: #fff; display: flex; align-items: center; justify-content: space-around; border-top: 1rpx solid #f0f0f0; padding-bottom: env(safe-area-inset-bottom); z-index: 100; }
.bar-item { display: flex; flex-direction: column; align-items: center; gap: 4rpx; padding: 8rpx 24rpx; }
.bar-icon { font-size: 40rpx; }
.bar-label { font-size: 22rpx; color: #999; }
.bar-item.active .bar-label { color: #FF6B35; font-weight: 600; }
</style>
