<template>
  <view class="page">
    <view class="header" @click="goLogin" v-if="!isLogin"><image src="/static/gas-avatar.png" class="avatar"/><text class="nick">点击登录</text><text class="phone">登录后享积分权益</text></view>
    <view class="header" v-else><image :src="user.avatar||'/static/gas-avatar.png'" class="avatar"/><text class="nick">{{user.nickname||'燃气用户'}}</text><text class="phone">{{user.phone||''}}</text></view>
    <view class="points-card" v-if="isLogin"><text class="points-label">我的积分</text><text class="points-val">{{user.pointsBalance||0}}</text><text class="points-unit">积分</text></view>
    <view class="menu">
      <view class="menu-item" @click="goPage('/pages/points/log')"><text>📊 积分明细</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goPage('/pages/order/list')"><text>📦 兑换记录</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goPage('/pages/coupon/list')"><text>🎫 我的优惠券</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goPage('/pages/address/list')"><text>📍 收货地址</text><text class="arrow">›</text></view>
    </view>
    <view class="logout" v-if="isLogin" @click="doLogout"><text>退出登录</text></view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index';import {onShow} from '@dcloudio/uni-app'
const isLogin=ref(false),user=ref({})
async function loadUser(){try{user.value=await api.getMe();isLogin.value=true}catch{isLogin.value=false}}
onMounted(loadUser);onShow(loadUser)
function goLogin(){uni.navigateTo({url:'/pages/auth/login'})}
function goPage(url){if(!isLogin.value){goLogin();return};uni.navigateTo({url})}
function doLogout(){uni.clearStorageSync();isLogin.value=false;uni.showToast({title:'已退出',icon:'none'})}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5}.header{display:flex;align-items:center;gap:24rpx;padding:48rpx 32rpx;background:linear-gradient(135deg,#FF6B35,#FF8C5A)}.avatar{width:100rpx;height:100rpx;border-radius:50%;border:3rpx solid rgba(255,255,255,.4)}.nick{font-size:36rpx;color:#fff;font-weight:600}.phone{font-size:24rpx;color:rgba(255,255,255,.7)}.points-card{background:#fff;margin:-40rpx 24rpx 24rpx;border-radius:20rpx;padding:32rpx;text-align:center;box-shadow:0 4rpx 20rpx rgba(255,107,53,.15)}.points-label{font-size:24rpx;color:#999;display:block}.points-val{font-size:64rpx;color:#FF6B35;font-weight:700}.points-unit{font-size:28rpx;color:#FF6B35;margin-left:8rpx}.menu{background:#fff;margin:16rpx 24rpx;border-radius:16rpx;overflow:hidden}.menu-item{display:flex;justify-content:space-between;align-items:center;padding:32rpx;border-bottom:1rpx solid #f0f0f0;font-size:30rpx;color:#333}.menu-item:last-child{border-bottom:none}.arrow{color:#ccc;font-size:32rpx}.logout{margin:32rpx 24rpx;text-align:center;padding:24rpx;color:#999;font-size:28rpx;background:#fff;border-radius:16rpx}
</style>
