<template>
  <view class="page">
    <!-- Header -->
    <view class="header">
      <view class="logout-icon" @click="doLogout">
        <text>🚪</text>
      </view>
      <text class="employee-name">{{ employeeName }}</text>
      <view class="header-row">
        <text class="company-name">{{ company.corpName || '员工工作台' }}</text>
        <text class="role-badge">{{ company.roleName || company.roleType || '员工' }}</text>
      </view>
      <view class="switch-btn" v-if="hasMultiple" @click="switchCompany">
        <text>切换公司</text>
      </view>
    </view>

    <!-- Function Cards -->
    <view class="cards">
      <view class="card" @click="goBusinessData">
        <text class="card-icon">📊</text>
        <text class="card-title">业务数据</text>
        <text class="card-desc">查看个人增值业务数据</text>
      </view>
      <view class="card" @click="goAiChat">
        <text class="card-icon">🤖</text>
        <text class="card-title">AI助手</text>
        <text class="card-desc">智能客服，随时解答</text>
      </view>
    </view>

    <!-- Custom Bottom Bar -->
    <view class="bottom-bar">
      <view class="bar-item active" @click="goBusinessData">
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
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/index.js'

const company = ref({})
const companies = ref([])
const employeeName = ref('')

const hasMultiple = computed(() => companies.value.length > 1)

onMounted(async () => {
  // Auth guard: redirect to login if not authenticated as employee
  const empToken = uni.getStorageSync('ops_employee_token')
  if (!empToken) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  // 读取员工姓名
  const info = uni.getStorageSync('ops_employee_info')
  if (info) {
    try { employeeName.value = JSON.parse(info).name || '' } catch(e) {}
  }
  // 先从 localStorage 即时展示
  const active = uni.getStorageSync('ops_employee_active_company')
  if (active) {
    try { company.value = JSON.parse(active) } catch(e) { company.value = {} }
  }
  const cached = uni.getStorageSync('ops_employee_companies')
  if (cached) {
    try { companies.value = JSON.parse(cached) } catch(e) { companies.value = [] }
  }
  // 从平台拉取最新组织数据（与 company-select 同源）
  try {
    const resp = await api.employeeIdentity()
    if (resp.companies && resp.companies.length > 0) {
      companies.value = resp.companies
      uni.setStorageSync('ops_employee_companies', JSON.stringify(resp.companies))
      // 单组织登录时尚未设置 active，用第一个
      if (!company.value.corpId) {
        company.value = resp.companies[0]
        uni.setStorageSync('ops_employee_active_company', JSON.stringify(resp.companies[0]))
      }
    }
  } catch (e) { /* 降级使用缓存数据 */ }
  if (!company.value.corpId) {
    uni.showToast({ title: '未选择公司', icon: 'none' })
    setTimeout(() => { uni.navigateTo({ url: '/pages/employee/company-select' }) }, 1000)
  }
})

function switchCompany() {
  uni.navigateTo({ url: '/pages/employee/company-select' })
}
function doLogout() {
  uni.showModal({
    title: '确认退出',
    content: '退出后需要重新登录，确定要退出吗？',
    success(res) {
      if (res.confirm) {
        uni.removeStorageSync('ops_employee_token')
        uni.removeStorageSync('ops_employee_info')
        uni.removeStorageSync('ops_employee_companies')
        uni.removeStorageSync('ops_employee_active_company')
        uni.redirectTo({ url: '/pages/employee/login' })
      }
    }
  })
}
function goBusinessData() {
  uni.navigateTo({ url: '/pages/employee/business-data' })
}
function goAiChat() {
  uni.navigateTo({ url: '/pages/employee/ai-chat' })
}
function goBackMember() {
  uni.switchTab({ url: '/pages/index/index' })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding-bottom: 120rpx; }
.header { background: linear-gradient(135deg, #FF6B35, #FF8C5A); padding: 60rpx 32rpx 48rpx; text-align: center; position: relative; }
.logout-icon { position: absolute; top: 24rpx; right: 24rpx; font-size: 40rpx; padding: 8rpx; }
.employee-name { font-size: 36rpx; color: #fff; font-weight: 700; display: block; margin-top: 24rpx; margin-bottom: 16rpx; }
.header-row { display: flex; align-items: center; justify-content: center; gap: 16rpx; flex-wrap: wrap; }
.company-name { font-size: 28rpx; color: rgba(255,255,255,.9); font-weight: 600; }
.role-badge { display: inline-block; font-size: 22rpx; color: #fff; background: rgba(255,255,255,.2); padding: 4rpx 18rpx; border-radius: 20rpx; }
.switch-btn { margin-top: 20rpx; }
.switch-btn text { font-size: 24rpx; color: rgba(255,255,255,.8); padding: 8rpx 24rpx; border: 1rpx solid rgba(255,255,255,.4); border-radius: 32rpx; }

.cards { display: grid; grid-template-columns: 1fr 1fr; gap: 20rpx; padding: 32rpx 24rpx; }
.card { background: #fff; border-radius: 16rpx; padding: 40rpx 24rpx; text-align: center; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04); display: flex; flex-direction: column; align-items: center; gap: 12rpx; }
.card-icon { font-size: 56rpx; }
.card-title { font-size: 30rpx; color: #333; font-weight: 600; }
.card-desc { font-size: 22rpx; color: #999; }

.bottom-bar { position: fixed; bottom: 0; left: 0; right: 0; height: 110rpx; background: #fff; display: flex; align-items: center; justify-content: space-around; border-top: 1rpx solid #f0f0f0; padding-bottom: env(safe-area-inset-bottom); z-index: 100; }
.bar-item { display: flex; flex-direction: column; align-items: center; gap: 4rpx; padding: 8rpx 24rpx; }
.bar-icon { font-size: 40rpx; }
.bar-label { font-size: 22rpx; color: #999; }
.bar-item.active .bar-label { color: #FF6B35; font-weight: 600; }
</style>
