<template>
  <view class="page" v-if="o">
    <view class="card"><text class="l">订单号</text><text class="v">{{o.orderNo}}</text></view>
    <view class="card"><text class="l">商品</text><text class="v">{{o.productName}}</text></view>
    <view class="card"><text class="l">消耗积分</text><text class="v price">{{o.pointsSpent}} 积分</text></view>
    <view class="card"><text class="l">状态</text><text class="v status">{{o.status==='PENDING'?'待支付':o.status==='PAID'?'已完成':'已取消'}}</text></view>
    <view class="card" v-if="o.status==='PAID'"><text class="l">第三方单号</text><text class="v">{{o.thirdPartyOrderNo||'—'}}</text></view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index'
const o=ref(null)
onMounted(async()=>{const no=(getCurrentPages().pop())?.options?.no;if(no)try{o.value=await api.orderDetail(no)}catch{}})
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding:16rpx}.card{background:#fff;border-radius:12rpx;padding:24rpx;margin-bottom:12rpx}.l{font-size:24rpx;color:#999;display:block}.v{font-size:30rpx;color:#333;margin-top:4rpx}.price{color:#FF6B35;font-weight:600}.status{color:#4caf50}
</style>
