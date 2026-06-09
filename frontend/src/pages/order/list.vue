<template>
  <view class="page">
    <view v-if="list.length===0" class="empty">暂无兑换记录</view>
    <view v-for="o in list" :key="o.id" class="card" @click="goDetail(o.orderNo)"><text class="name">{{o.productName}}</text>
      <view class="row"><text class="points">-{{o.pointsSpent}}积分</text><text class="status" :class="o.status">{{statusText(o.status)}}</text></view>
      <text class="time">{{o.createTime}}</text>
    </view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index'
const list=ref([])
onMounted(async()=>{try{const r=await api.myOrders({pageNum:1,pageSize:50});list.value=r.records||[]}catch{}})
function statusText(s){return s==='PENDING'?'待支付':s==='PAID'?'已完成':s==='CANCELLED'?'已取消':s}
function goDetail(no){uni.navigateTo({url:'/pages/order/detail?no='+no})}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding:16rpx}.empty{text-align:center;padding:120rpx;color:#999;font-size:28rpx}.card{background:#fff;border-radius:16rpx;padding:24rpx;margin-bottom:12rpx}.name{font-size:28rpx;color:#333;font-weight:500}.row{display:flex;justify-content:space-between;margin-top:12rpx}.points{font-size:30rpx;color:#FF6B35;font-weight:600}.status{font-size:24rpx;padding:4rpx 16rpx;border-radius:8rpx}.status.PENDING{color:#ff9800}.status.PAID{color:#4caf50}.status.CANCELLED{color:#999}.time{font-size:24rpx;color:#999;margin-top:8rpx}
</style>
