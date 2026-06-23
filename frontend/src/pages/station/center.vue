<template>
  <view class="page">
    <view class="nav-bar"><view class="back" @click="goBack">← 返回</view><text class="title">站长中心</text></view>

    <view class="header-card" v-if="info">
      <view class="avatar-text">{{ info.name ? info.name.charAt(0) : '站' }}</view>
      <view class="info-block">
        <text class="name">{{ info.name || '--' }}</text>
        <text class="phone">{{ info.phone || '--' }}</text>
        <text class="area" v-if="info.area">{{ info.area }}</text>
      </view>
      <view class="status-badge" :class="info.status === 1 ? 'active' : 'inactive'">
        {{ info.status === 1 ? '正常' : '已停用' }}
      </view>
    </view>

    <view class="stats-row" v-if="info">
      <view class="stat-item">
        <text class="stat-val">{{ info.memberCount || 0 }}</text>
        <text class="stat-label">下级会员</text>
      </view>
      <view class="stat-item">
        <text class="stat-val">¥{{ formatMoney(info.totalSales) }}</text>
        <text class="stat-label">累计销售额</text>
      </view>
    </view>

    <view class="card tips-card">
      <text class="tips-title">📌 站长须知</text>
      <text class="tips-text">1. 推广二维码/链接可在微信中分享，用户通过您的链接进入商城即计入您的下级会员。</text>
      <text class="tips-text">2. 下级会员购买商品后，订单将计入您的推广业绩。</text>
      <text class="tips-text">3. 请遵守平台规则，诚信推广。</text>
    </view>

    <view v-if="!info" class="card empty-card">
      <text class="empty-icon">📭</text>
      <text class="empty-text">您还不是站长</text>
      <button class="apply-btn" @click="goApply">申请成为站长</button>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/index'

const info = ref(null)

onMounted(async () => {
  try {
    info.value = await api.stationInfo()
  } catch {
    info.value = null
  }
})

function formatMoney(val) {
  if (!val) return '0.00'
  return Number(val).toFixed(2)
}

function goApply() {
  uni.navigateTo({ url: '/pages/station/apply' })
}
function goBack() {
  uni.navigateBack()
}
</script>

<style scoped>
.page{min-height:100vh;background:#f5f5f5}
.nav-bar{display:flex;align-items:center;padding:20rpx 32rpx;background:#fff;border-bottom:1rpx solid #eee}
.back{font-size:28rpx;color:#FF6B35}
.title{flex:1;text-align:center;font-size:32rpx;font-weight:600;margin-right:80rpx}
.header-card{display:flex;align-items:center;gap:24rpx;margin:24rpx;padding:32rpx;background:#fff;border-radius:16rpx}
.avatar-text{width:88rpx;height:88rpx;border-radius:50%;background:linear-gradient(135deg,#FF6B35,#FF8C5A);color:#fff;display:flex;align-items:center;justify-content:center;font-size:40rpx;font-weight:700;flex-shrink:0}
.info-block{flex:1}
.name{display:block;font-size:34rpx;font-weight:600;color:#333}
.phone{display:block;font-size:26rpx;color:#999;margin-top:4rpx}
.area{display:block;font-size:24rpx;color:#bbb;margin-top:2rpx}
.status-badge{font-size:22rpx;padding:4rpx 16rpx;border-radius:20rpx}
.status-badge.active{background:#e8f5e9;color:#4caf50}
.status-badge.inactive{background:#ffebee;color:#f44336}
.stats-row{display:flex;margin:0 24rpx;gap:24rpx}
.stat-item{flex:1;background:#fff;border-radius:16rpx;padding:32rpx;text-align:center}
.stat-val{display:block;font-size:40rpx;font-weight:700;color:#FF6B35}
.stat-label{display:block;font-size:24rpx;color:#999;margin-top:8rpx}
.card{background:#fff;margin:24rpx;border-radius:16rpx;padding:32rpx}
.tips-title{display:block;font-size:30rpx;font-weight:600;color:#333;margin-bottom:16rpx}
.tips-text{display:block;font-size:26rpx;color:#666;line-height:1.8}
.empty-card{text-align:center}
.empty-icon{font-size:80rpx;display:block;margin-bottom:16rpx}
.empty-text{display:block;font-size:30rpx;color:#999;margin-bottom:24rpx}
.apply-btn{background:linear-gradient(135deg,#FF6B35,#FF8C5A);color:#fff;border:none;border-radius:8rpx;font-size:28rpx;padding:20rpx 48rpx}
</style>
