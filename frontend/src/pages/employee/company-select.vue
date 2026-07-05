<template>
  <view class="page">
    <view class="header">
      <text class="header-title">选择公司</text>
      <text class="header-sub">请选择要进入的公司</text>
    </view>
    <view class="list">
      <view class="company-card" v-for="(c, i) in companies" :key="i" @click="selectCompany(c)">
        <view class="card-left">
          <text class="company-name">{{ c.corpName || '未知公司' }}</text>
          <text class="company-role">{{ c.roleName || c.roleType || '员工' }}</text>
        </view>
        <text class="arrow">›</text>
      </view>
    </view>
    <view class="empty" v-if="!companies || companies.length === 0">
      <text class="empty-text">暂无可关联的公司</text>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/index.js'

const companies = ref([])
const loading = ref(true)

onMounted(async () => {
  // Auth guard
  const empToken = uni.getStorageSync('ops_employee_token')
  if (!empToken) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  // 先从 localStorage 即时展示（登录时已存入）
  const cached = uni.getStorageSync('ops_employee_companies')
  if (cached) {
    try { companies.value = JSON.parse(cached) } catch(e) { companies.value = [] }
  }
  // 再从平台拉取最新数据（与会员端"我是员工"同源，角色解析更精确）
  try {
    const resp = await api.employeeIdentity()
    if (resp.companies && resp.companies.length > 0) {
      companies.value = resp.companies
      uni.setStorageSync('ops_employee_companies', JSON.stringify(resp.companies))
    }
  } catch (e) {
    // API 失败时用 localStorage 中的数据降级
    console.warn('获取组织列表失败，使用缓存数据', e)
  } finally {
    loading.value = false
  }
})

function selectCompany(c) {
  uni.setStorageSync('ops_employee_active_company', JSON.stringify(c))
  uni.navigateTo({ url: '/pages/employee/dashboard' })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; }
.header { background: linear-gradient(135deg, #FF6B35, #FF8C5A); padding: 60rpx 32rpx 48rpx; }
.header-title { font-size: 40rpx; color: #fff; font-weight: 700; display: block; }
.header-sub { font-size: 24rpx; color: rgba(255,255,255,.7); margin-top: 8rpx; display: block; }
.list { margin: 24rpx; }
.company-card {
  background: #fff; border-radius: 16rpx; padding: 32rpx;
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 16rpx; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04);
}
.card-left { display: flex; flex-direction: column; gap: 8rpx; }
.company-name { font-size: 32rpx; color: #333; font-weight: 600; }
.company-role { font-size: 24rpx; color: #999; background: #f5f5f5; padding: 4rpx 16rpx; border-radius: 8rpx; display: inline-block; width: fit-content; }
.arrow { font-size: 36rpx; color: #ccc; }
.empty { text-align: center; padding: 200rpx 0; }
.empty-text { font-size: 28rpx; color: #999; }
</style>
