<template>
  <view class="page">
    <!-- Header -->
    <view class="header">
      <text class="company-name">{{ company.corpName || '员工工作台' }}</text>
      <text class="role-badge">{{ company.roleName || company.roleType || '员工' }}</text>
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

const company = ref({})
const companies = ref([])

const hasMultiple = computed(() => companies.value.length > 1)

onMounted(() => {
  const active = uni.getStorageSync('ops_employee_active_company')
  if (active) {
    try { company.value = JSON.parse(active) } catch(e) { company.value = {} }
  }
  const cached = uni.getStorageSync('ops_employee_companies')
  if (cached) {
    try { companies.value = JSON.parse(cached) } catch(e) { companies.value = [] }
  }
  if (!company.value.corpId) {
    uni.showToast({ title: '未选择公司', icon: 'none' })
    setTimeout(() => { uni.navigateTo({ url: '/pages/employee/company-select' }) }, 1000)
  }
})

function switchCompany() {
  uni.navigateTo({ url: '/pages/employee/company-select' })
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
.header { background: linear-gradient(135deg, #FF6B35, #FF8C5A); padding: 60rpx 32rpx 48rpx; text-align: center; }
.company-name { font-size: 40rpx; color: #fff; font-weight: 700; display: block; }
.role-badge { display: inline-block; margin-top: 12rpx; font-size: 24rpx; color: #FF6B35; background: #fff; padding: 6rpx 24rpx; border-radius: 20rpx; }
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
