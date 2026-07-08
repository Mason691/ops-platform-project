<template>
  <view class="page">
    <!-- Brand Header -->
    <view class="brand-bar">
      <text class="brand-name">金地燃气</text>
      <text class="brand-sub">员工工作台</text>
    </view>

    <!-- Profile Card -->
    <view class="profile-card">
      <view class="pc-left">
        <view class="pc-avatar">
          <text class="pc-avatar-text">{{ avatarChar }}</text>
        </view>
        <view class="pc-info">
          <text class="pc-name">{{ employeeName || '员工' }}</text>
          <view class="pc-meta">
            <text class="pc-company">{{ company.corpName || '—' }}</text>
            <text class="pc-dot">·</text>
            <text class="pc-role">{{ roleName }}</text>
          </view>
        </view>
      </view>
      <view class="pc-right" @click="goProfile">
        <text class="pc-arrow">›</text>
      </view>
    </view>

    <!-- Yearly Stats -->
    <view class="stats-row">
      <view class="stat-item">
        <text class="stat-val">{{ yearlyStats.orderCount }}</text>
        <text class="stat-label">全年订单</text>
      </view>
      <view class="stat-divider" />
      <view class="stat-item">
        <text class="stat-val">{{ yearlyStats.totalCommission }}</text>
        <text class="stat-label">全年提成</text>
      </view>
      <view class="stat-divider" />
      <view class="stat-item">
        <text class="stat-val">{{ yearlyStats.totalSale }}</text>
        <text class="stat-label">全年销售额</text>
      </view>
    </view>

    <!-- Menu -->
    <view class="menu-section">
      <text class="section-title">常用功能</text>
      <view class="menu-grid">
        <view class="menu-card" @click="goBusinessData">
          <view class="mc-icon mc-icon-data"><text>📊</text></view>
          <view class="mc-body">
            <text class="mc-title">业务数据</text>
            <text class="mc-desc">查看提成与业绩明细</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
        <view class="menu-card" @click="goAiChat">
          <view class="mc-icon mc-icon-ai"><text>🤖</text></view>
          <view class="mc-body">
            <text class="mc-title">AI 助手</text>
            <text class="mc-desc">智能问答，随时解答</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
        <view class="menu-card" @click="goProfile">
          <view class="mc-icon mc-icon-me"><text>👤</text></view>
          <view class="mc-body">
            <text class="mc-title">个人中心</text>
            <text class="mc-desc">账户信息与密码管理</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
      </view>
    </view>

    <!-- Bottom Tab Bar -->
    <EmployeeTabBar active-key="home" @switch="onTabSwitch" />
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/index.js'
import EmployeeTabBar from '@/components/EmployeeTabBar.vue'

const company      = ref({})
const employeeName = ref('')
const roleName     = ref('员工')
const currentYear  = new Date().getFullYear().toString()

const yearlyStats = ref({
  orderCount: '0',
  totalCommission: '¥0',
  totalSale: '¥0'
})

const avatarChar = computed(() => (employeeName.value || '员').charAt(0))

onMounted(async () => {
  const empToken = uni.getStorageSync('ops_employee_token')
  if (!empToken) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }

  // ── 员工信息 ──
  const info = uni.getStorageSync('ops_employee_info')
  if (info) {
    try {
      const p = JSON.parse(info)
      employeeName.value = p.name || ''
      roleName.value = p.role || '员工'
    } catch (_) {}
  }

  // ── 公司 ──
  const active = uni.getStorageSync('ops_employee_active_company')
  if (active) {
    try { company.value = JSON.parse(active) } catch (_) {}
  }
  if (!company.value.corpId && info) {
    try {
      const p = JSON.parse(info)
      company.value = { corpId: p.corpId, corpName: '', roleName: p.role || '员工' }
      uni.setStorageSync('ops_employee_active_company', JSON.stringify(company.value))
    } catch (_) {}
  }

  // ── 全年统计 ──
  try {
    const data = await api.employeeOverview()
    const months = Array.isArray(data?.months) ? data.months : []
    // Sum only current-year months
    let yearOrders = 0, yearCommission = 0, yearSale = 0
    for (const m of months) {
      if (m.month && m.month.startsWith(currentYear)) {
        yearOrders += (typeof m.orderCount === 'number' ? m.orderCount : 0)
        yearCommission += (typeof m.totalCommission === 'string' ? parseFloat(m.totalCommission) :
                           typeof m.totalCommission === 'number' ? m.totalCommission : 0)
        yearSale += (typeof m.totalSale === 'string' ? parseFloat(m.totalSale) :
                     typeof m.totalSale === 'number' ? m.totalSale : 0)
      }
    }
    yearlyStats.value = {
      orderCount: String(yearOrders),
      totalCommission: '¥' + yearCommission.toFixed(2),
      totalSale: '¥' + (yearSale / 10000).toFixed(2) + '万'
    }
  } catch (_) {
    // keep defaults showing 0
  }
})

function goBusinessData() { uni.navigateTo({ url: '/pages/employee/business-data' }) }
function goAiChat()       { uni.redirectTo({ url: '/pages/employee/ai-chat' }) }
function goProfile()      { uni.redirectTo({ url: '/pages/employee/profile' }) }

function onTabSwitch(key) {
  const routes = { ai: '/pages/employee/ai-chat', home: '', profile: '/pages/employee/profile' }
  if (routes[key]) uni.redirectTo({ url: routes[key] })
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #F5F6FA;
  padding-bottom: 130rpx;
}

.brand-bar { padding: 48rpx 36rpx 0; display: flex; align-items: baseline; gap: 16rpx; }
.brand-name { font-size: 34rpx; font-weight: 700; color: #1D2129; }
.brand-sub { font-size: 24rpx; color: #86909C; }

.profile-card {
  margin: 24rpx 24rpx 0; background: #fff; border-radius: 20rpx; padding: 32rpx 28rpx;
  display: flex; align-items: center; justify-content: space-between;
  box-shadow: 0 2rpx 16rpx rgba(0,0,0,.04);
}
.pc-left { display: flex; align-items: center; gap: 20rpx; }
.pc-avatar {
  width: 88rpx; height: 88rpx; border-radius: 50%;
  background: linear-gradient(135deg, #FF8C5A, #FF6B35);
  display: flex; align-items: center; justify-content: center;
}
.pc-avatar-text { font-size: 36rpx; color: #fff; font-weight: 600; }
.pc-info { display: flex; flex-direction: column; gap: 6rpx; }
.pc-name { font-size: 32rpx; font-weight: 700; color: #1D2129; }
.pc-meta { display: flex; align-items: center; gap: 8rpx; }
.pc-company { font-size: 24rpx; color: #4E5969; }
.pc-dot { color: #C9CDD4; font-size: 24rpx; }
.pc-role { font-size: 24rpx; color: #FF6B35; font-weight: 500; }
.pc-right { padding: 8rpx; }
.pc-arrow { font-size: 40rpx; color: #C9CDD4; font-weight: 300; }

.stats-row {
  margin: 20rpx 24rpx 0; background: #fff; border-radius: 20rpx; padding: 28rpx 16rpx;
  display: flex; align-items: center; box-shadow: 0 2rpx 16rpx rgba(0,0,0,.04);
}
.stat-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 6rpx; }
.stat-val { font-size: 30rpx; font-weight: 700; color: #1D2129; }
.stat-label { font-size: 22rpx; color: #86909C; }
.stat-divider { width: 1rpx; height: 48rpx; background: #E5E6EB; }

.menu-section { margin: 28rpx 24rpx 0; }
.section-title { font-size: 26rpx; color: #86909C; margin-bottom: 16rpx; display: block; padding-left: 4rpx; }
.menu-grid { display: flex; flex-direction: column; gap: 12rpx; }
.menu-card {
  background: #fff; border-radius: 16rpx; padding: 28rpx 24rpx;
  display: flex; align-items: center; gap: 20rpx;
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,.03);
}
.menu-card:active { transform: scale(.985); box-shadow: 0 1rpx 6rpx rgba(0,0,0,.06); }
.mc-icon { width: 80rpx; height: 80rpx; border-radius: 18rpx; display: flex; align-items: center; justify-content: center; font-size: 40rpx; flex-shrink: 0; }
.mc-icon-data { background: #FFF3E8; }
.mc-icon-ai   { background: #E8F3FF; }
.mc-icon-me   { background: #F0F1F5; }
.mc-body { flex: 1; display: flex; flex-direction: column; gap: 4rpx; }
.mc-title { font-size: 28rpx; font-weight: 600; color: #1D2129; }
.mc-desc { font-size: 22rpx; color: #86909C; }
.mc-arrow { font-size: 36rpx; color: #C9CDD4; font-weight: 300; flex-shrink: 0; }
</style>
