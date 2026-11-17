<template>
  <view class="page">
    <!-- Brand Header -->
    <view class="brand-bar">
      <view class="brand-left">
        <text class="brand-name">金地美家</text>
        <text class="brand-sub">员工工作台</text>
      </view>
      <OrgSwitcher :corp-name="company.corpName" :org-count="orgs.length" @open="openSheet" />
    </view>

    <view v-if="noPermissionTip" class="org-empty-card">
      <text class="empty-title">当前组织暂无可用功能</text>
      <text class="empty-sub">请联系管理员为该组织角色配置菜单权限</text>
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
    <view v-if="!noPermissionTip" class="menu-section">
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
        <view class="menu-card" @click="goVerifiedBusiness">
          <view class="mc-icon mc-icon-data"><text>🧾</text></view>
          <view class="mc-body">
            <text class="mc-title">已核对业务</text>
            <text class="mc-desc">财务核对订单发起采购申请</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
        <view class="menu-card" @click="goBalancePending">
          <view class="mc-icon mc-icon-pay">
            <text>💰</text>
            <text v-if="balancePendingCount" class="mc-badge">{{ balancePendingCount > 99 ? '99+' : balancePendingCount }}</text>
          </view>
          <view class="mc-body">
            <text class="mc-title">货款补录</text>
            <text class="mc-desc">定金订单收齐余款，解锁派工</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
        <view class="menu-card" @click="goMaterialRequisition">
          <view class="mc-icon mc-icon-data"><text>🧰</text></view>
          <view class="mc-body">
            <text class="mc-title">物资领用</text>
            <text class="mc-desc">申请领用物料，部长审批</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
        <view class="menu-card" @click="goMaterialWriteOff2">
          <view class="mc-icon mc-icon-pending"><text>✅</text></view>
          <view class="mc-body">
            <text class="mc-title">物资核销</text>
            <text class="mc-desc">核销使用与退回，回写领用余量</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
        <view class="menu-card" @click="goMaterialPick">
          <view class="mc-icon mc-icon-dispatch"><text>📦</text></view>
          <view class="mc-body">
            <text class="mc-title">物料领取</text>
            <text class="mc-desc">派工施工领取物料，财务审批</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
        <view class="menu-card" @click="goMaterialWriteOff">
          <view class="mc-icon mc-icon-dispatch"><text>♻️</text></view>
          <view class="mc-body">
            <text class="mc-title">物料核销</text>
            <text class="mc-desc">剩余物料退回与消耗核销</text>
          </view>
          <text class="mc-arrow">›</text>
        </view>
        <view class="menu-card" @click="goTodo">
          <view class="mc-icon mc-icon-pending"><text>📋</text></view>
          <view class="mc-body">
            <text class="mc-title">待办任务</text>
            <text class="mc-desc">审核、核对、派工与施工任务</text>
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

    <OrgSwitchSheet
      v-model:visible="sheetVisible"
      :orgs="orgs"
      :current-corp-id="Number(company.corpId) || 0"
      :loading="switching"
      @select="confirmSwitch"
    />

    <!-- Bottom Tab Bar -->
    <EmployeeTabBar active-key="home" @switch="onTabSwitch" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api } from '@/api/index.js'
import EmployeeTabBar from '@/components/EmployeeTabBar.vue'
import OrgSwitcher from '@/components/OrgSwitcher.vue'
import OrgSwitchSheet from '@/components/OrgSwitchSheet.vue'
import { useOrgSwitch } from '@/hooks/useOrgSwitch'
import { getActiveCompany, getEmployeeInfo } from '@/utils/employeeSession'

const company      = ref({})
const employeeName = ref('')
const roleName     = ref('员工')
const noPermissionTip = ref(uni.getStorageSync('ops_employee_no_permission') === '1')
const currentYear  = new Date().getFullYear().toString()

const yearlyStats = ref({
  orderCount: '0',
  totalCommission: '¥0',
  totalSale: '¥0'
})
const balancePendingCount = ref(0)

const {
  orgs, sheetVisible, switching, loadOrgs, openSheet, confirmSwitch
} = useOrgSwitch({ onChanged: onOrgChanged })

const avatarChar = computed(() => (employeeName.value || '员').charAt(0))

onShow(async () => {
  await loadDashboard()
  loadBalancePending()
  await loadOrgs()
})

function refreshCompany() {
  noPermissionTip.value = uni.getStorageSync('ops_employee_no_permission') === '1'
  const info = getEmployeeInfo()
  const active = getActiveCompany()
  employeeName.value = info.name || ''
  if (active.corpId) {
    company.value = active
    roleName.value = active.roleName || info.role || '员工'
    return
  }
  // 兜底：active_company 丢失时用员工信息恢复
  if (info.corpId) {
    company.value = {
      corpId: info.corpId, corpName: '', roleName: info.role || '员工'
    }
    uni.setStorageSync('ops_employee_active_company', JSON.stringify(company.value))
    roleName.value = company.value.roleName
  }
}

async function loadDashboard() {
  if (!uni.getStorageSync('ops_employee_token')) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  refreshCompany()
  await loadStats()
}

async function loadStats() {
  // 先归零，避免切换组织后短暂显示上一个组织的数据
  yearlyStats.value = { orderCount: '0', totalCommission: '¥0', totalSale: '¥0' }
  try {
    const data = await api.employeeOverview()
    const months = Array.isArray(data?.months) ? data.months : []
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
    // 保留归零后的占位，避免残留旧组织数据
  }
}

async function onOrgChanged(data) {
  noPermissionTip.value = !(data.permissions || []).length
  refreshCompany()
  await loadStats()
}

function goBusinessData() { uni.navigateTo({ url: '/pages/employee/business-data' }) }
function goBalancePending() { uni.navigateTo({ url: '/pages/employee/balance-pending' }) }

async function loadBalancePending() {
  if (!uni.getStorageSync('ops_employee_token')) {
    balancePendingCount.value = 0
    return
  }
  try {
    const list = await api.depositPending()
    balancePendingCount.value = Array.isArray(list) ? list.length : 0
  } catch (e) {
    balancePendingCount.value = 0
  }
}
function goVerifiedBusiness() { uni.navigateTo({ url: '/pages/employee/verified-business' }) }
function goMaterialRequisition() { uni.navigateTo({ url: '/pages/employee/material-requisition-records' }) }
function goMaterialWriteOff2()   { uni.navigateTo({ url: '/pages/employee/material-write-off-records' }) }
function goMaterialPick()     { uni.navigateTo({ url: '/pages/employee/material-pick' }) }
function goMaterialWriteOff() { uni.navigateTo({ url: '/pages/employee/material-write-off' }) }
function goTodo()         { uni.redirectTo({ url: '/pages/employee/flow-todo' }) }
function goAiChat()       { uni.redirectTo({ url: '/pages/employee/ai-chat' }) }
function goProfile()      { uni.redirectTo({ url: '/pages/employee/profile' }) }

function onTabSwitch(key) {
  const routes = { ai: '/pages/employee/ai-chat', home: '', todo: '/pages/employee/flow-todo', profile: '/pages/employee/profile' }
  if (routes[key]) uni.redirectTo({ url: routes[key] })
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #F5F6FA;
  padding-bottom: 130rpx;
}

.brand-bar { padding: 48rpx 36rpx 0; display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.brand-left { display: flex; align-items: baseline; gap: 16rpx; min-width: 0; }
.org-empty-card { margin: 24rpx; padding: 40rpx 32rpx; background: #fff; border-radius: 20rpx; text-align: center; box-shadow: 0 2rpx 16rpx rgba(0,0,0,.04); }
.empty-title { display: block; font-size: 30rpx; font-weight: 600; color: #1D2129; }
.empty-sub { display: block; margin-top: 12rpx; font-size: 24rpx; color: #86909C; }
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
.mc-icon { position: relative; width: 80rpx; height: 80rpx; border-radius: 18rpx; display: flex; align-items: center; justify-content: center; font-size: 40rpx; flex-shrink: 0; }
.mc-badge {
  position: absolute; top: -10rpx; right: -12rpx; min-width: 30rpx; height: 30rpx; line-height: 30rpx;
  padding: 0 8rpx; border-radius: 15rpx; background: #F53F3F; color: #fff;
  font-size: 19rpx; text-align: center; font-weight: 600; box-sizing: border-box;
}
.mc-icon-data { background: #FFF3E8; }
.mc-icon-pay  { background: #FFF1E8; }
.mc-icon-pending { background: #E8F8E8; }
.mc-icon-dispatch { background: #FFF0EB; }
.mc-icon-ai   { background: #E8F3FF; }
.mc-icon-me   { background: #F0F1F5; }
.mc-body { flex: 1; display: flex; flex-direction: column; gap: 4rpx; }
.mc-title { font-size: 28rpx; font-weight: 600; color: #1D2129; }
.mc-desc { font-size: 22rpx; color: #86909C; }
.mc-arrow { font-size: 36rpx; color: #C9CDD4; font-weight: 300; flex-shrink: 0; }
</style>
