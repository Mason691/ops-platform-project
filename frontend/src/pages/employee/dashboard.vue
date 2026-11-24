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
            <text class="pc-role">{{ roleText }}</text>
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

    <!-- Primary action + todo -->
    <AddBusinessButton ref="addBusinessRef" :enabled="!noPermissionTip" />
    <WorkTodoCard ref="todoCardRef" :addable="!noPermissionTip" @add-business="openAddBusiness" />

    <!-- Menu -->
    <view v-if="!noPermissionTip" class="menu-section">
      <text class="section-title">常用功能</text>
      <view class="menu-grid">
        <view v-for="card in visibleCards" :key="card.code" class="menu-card" @click="openCard(card)">
          <text v-if="card.badge" class="mc-badge">{{ card.badge > 99 ? '99+' : card.badge }}</text>
          <view class="mc-icon" :class="card.tone"><text>{{ card.icon }}</text></view>
          <view class="mc-body">
            <text class="mc-title">{{ card.name }}</text>
            <text class="mc-desc">{{ card.desc }}</text>
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
import WorkTodoCard from '@/components/WorkTodoCard.vue'
import AddBusinessButton from '@/components/AddBusinessButton.vue'
import { useOrgSwitch } from '@/hooks/useOrgSwitch'
import {
  getActiveCompany, getCompanyRoleText, getEmployeeInfo
} from '@/utils/employeeSession'

const company      = ref({})
const employeeName = ref('')
// 展示当前组织全部角色，避免只显示第一个角色
const roleText     = computed(() => getCompanyRoleText(company.value))
const noPermissionTip = ref(uni.getStorageSync('ops_employee_no_permission') === '1')
// 常用功能卡片由底座按 userType + 权限下发；前端不硬编码“向上采购申请”等敏感卡片
const cards = ref([])
const visibleCards = computed(() => (cards.value || [])
  .filter(card => card.visible !== false))
const currentYear  = new Date().getFullYear().toString()

/** 后端配置接口失败时的兜底卡片（不含仅内部可见的“向上采购申请”）。 */
const FALLBACK_CARDS = [
  { code: 'business-data', name: '业务数据', desc: '查看提成与业绩明细', icon: '📊', tone: 'mc-icon-data', path: '/pages/employee/business-data' },
  { code: 'balance-pending', name: '货款补录', desc: '定金订单收齐余款，解锁派工', icon: '💰', tone: 'mc-icon-pay', path: '/pages/employee/balance-pending' },
  { code: 'material-requisition', name: '物资领用', desc: '申请领用物料，部长审批', icon: '🧰', tone: 'mc-icon-data', path: '/pages/employee/material-requisition-records' },
  { code: 'material-write-off-records', name: '物资核销', desc: '核销使用与退回，回写领用余量', icon: '✅', tone: 'mc-icon-pending', path: '/pages/employee/material-write-off-records' },
  { code: 'material-pick', name: '物料领用', desc: '派工施工领用物料，部长审批', icon: '📦', tone: 'mc-icon-dispatch', path: '/pages/employee/material-pick' },
  { code: 'material-write-off', name: '物料核销', desc: '剩余物料退回与消耗核销', icon: '♻️', tone: 'mc-icon-dispatch', path: '/pages/employee/material-write-off' },
  { code: 'work-log', name: '工作业务日志', desc: '查看我发起与处理的流程操作', icon: '📝', tone: 'mc-icon-data', path: '/pages/employee/work-log' },
  { code: 'ai-chat', name: 'AI 助手', desc: '智能问答，随时解答', icon: '🤖', tone: 'mc-icon-ai', path: '/pages/employee/ai-chat' },
  { code: 'profile', name: '个人中心', desc: '账户信息与密码管理', icon: '👤', tone: 'mc-icon-me', path: '/pages/employee/profile' }
]

const yearlyStats = ref({
  orderCount: '0',
  totalCommission: '¥0',
  totalSale: '¥0'
})
/** 待办数量徽标由 WorkTodoCard 自行加载并展示 */
const todoCardRef = ref(null)
const addBusinessRef = ref(null)
let dashboardShown = false
const {
  orgs, sheetVisible, switching, loadOrgs, openSheet, confirmSwitch
} = useOrgSwitch({ onChanged: onOrgChanged })

const avatarChar = computed(() => (employeeName.value || '员').charAt(0))

onShow(async () => {
  await loadDashboard()
  await loadOrgs()
  await loadCards()
  addBusinessRef.value?.reload()
  // 卡片自身在 mounted 已加载一次；后续回到工作台（含查看全部返回）再刷新
  if (dashboardShown) {
    todoCardRef.value?.reload({ silent: true })
  }
  dashboardShown = true
})

function refreshCompany() {
  noPermissionTip.value = uni.getStorageSync('ops_employee_no_permission') === '1'
  const info = getEmployeeInfo()
  const active = getActiveCompany()
  employeeName.value = info.name || ''
  if (active.corpId) {
    company.value = active
    return
  }
  // 兜底：active_company 丢失时用员工信息恢复
  if (info.corpId) {
    company.value = {
      corpId: info.corpId, corpName: '', roleName: info.role || '员工'
    }
    uni.setStorageSync('ops_employee_active_company', JSON.stringify(company.value))
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

async function loadCards() {
  try {
    const list = await api.workbenchCards()
    cards.value = Array.isArray(list) ? list : []
  } catch (_) {
    // 配置接口不可用时保留基础功能入口，且兜底列表不包含仅内部可见卡片
    cards.value = FALLBACK_CARDS
  }
  await loadPickBadge()
  await loadBalanceBadge()
  await loadUpwardPurchaseBadge()
}

/** 向上采购申请角标：财务核对后待发起的商品记录数；无权限/接口失败不阻塞工作台。 */
async function loadUpwardPurchaseBadge() {
  const card = (cards.value || []).find(item => item.code === 'verified-business'
    || String(item.path || '').includes('verified-business'))
  // 无权限时平台下发 visible=false 或不返回该卡片：不再请求角标，避免 403 提示
  if (!card || card.visible === false) return
  try {
    const result = await api.upwardPurchasePendingCount()
    card.badge = Number(result && result.count) || 0
  } catch (_) {
    // 角标失败不影响常用功能展示
  }
}

/** 货款补录角标：定金支付且尾款未结清数量；结清后接口返回 0，角标自动消失。 */
async function loadBalanceBadge() {
  try {
    const result = await api.depositPendingCount()
    const count = Number(result && result.count) || 0
    const card = (cards.value || []).find(item => item.code === 'balance-pending'
      || String(item.path || '').includes('balance-pending'))
    if (card) card.badge = count
  } catch (_) {
    // 角标失败不影响常用功能展示
  }
}

/**
 * 物料领用草稿角标：只统计当前登录人的 DRAFT，不含审批中/已通过。
 * 无权限或接口失败不阻塞工作台（角标是提醒，不是功能前提）。
 */
async function loadPickBadge() {
  try {
    const count = Number(await api.scmMaterialPickDraftCount()) || 0
    let card = (cards.value || []).find(item => item.code === 'material-pick'
      || String(item.path || '').includes('material-pick'))
    if (!card && count > 0) {
      card = {
        code: 'material-pick-drafts', name: '物料领用单',
        desc: '补充仓库/批次并提交审批', icon: '📦', tone: 'mc-icon-dispatch',
        path: '/pages/employee/material-pick-drafts'
      }
      cards.value = [...cards.value, card]
    }
    if (card) card.badge = count
  } catch (_) {
    // 角标失败不影响常用功能展示
  }
}

async function onOrgChanged(data) {
  noPermissionTip.value = !(data.permissions || []).length
  // refreshCompany 会从会话缓存重新读取权限，保证切组织后卡片立即按新角色渲染
  refreshCompany()
  await loadStats()
  await loadCards()
  addBusinessRef.value?.reload()
  todoCardRef.value?.reload()
}

function openCard(card) {
  if (!card) return
  // 物料领用统一进“我的领用单”（含派工自动生成的草稿），新建入口在列表页内
  if (card.code === 'material-pick' || card.code === 'material-pick-drafts') {
    uni.navigateTo({ url: '/pages/employee/material-pick-drafts' })
    return
  }
  if (!card.path) return
  if (card.code === 'ai-chat' || card.code === 'profile') {
    uni.redirectTo({ url: card.path })
    return
  }
  uni.navigateTo({ url: card.path })
}
function goProfile()      { uni.redirectTo({ url: '/pages/employee/profile' }) }
function openAddBusiness() { addBusinessRef.value?.open() }

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
.pc-left { display: flex; align-items: center; gap: 20rpx; flex: 1; min-width: 0; }
.pc-avatar {
  width: 88rpx; height: 88rpx; border-radius: 50%;
  background: linear-gradient(135deg, #FF8C5A, #FF6B35);
  display: flex; align-items: center; justify-content: center;
}
.pc-avatar-text { font-size: 36rpx; color: #fff; font-weight: 600; }
.pc-info { display: flex; flex-direction: column; gap: 6rpx; flex: 1; min-width: 0; }
.pc-name { font-size: 32rpx; font-weight: 700; color: #1D2129; }
.pc-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8rpx; }
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
  position: relative;
  background: #fff; border-radius: 16rpx; padding: 28rpx 24rpx;
  display: flex; align-items: center; gap: 20rpx;
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,.03);
}
.mc-badge {
  position: absolute; top: 10rpx; right: 16rpx;
  min-width: 32rpx; height: 32rpx; padding: 0 8rpx;
  border-radius: 16rpx; background: #F53F3F; color: #fff;
  font-size: 20rpx; line-height: 32rpx; text-align: center;
}
.menu-card:active { transform: scale(.985); box-shadow: 0 1rpx 6rpx rgba(0,0,0,.06); }
.mc-icon { position: relative; width: 80rpx; height: 80rpx; border-radius: 18rpx; display: flex; align-items: center; justify-content: center; font-size: 40rpx; flex-shrink: 0; }
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
