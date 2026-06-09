<template>
  <view class="page">
    <view v-if="list.length===0" class="empty">暂无优惠券，去首页领取吧</view>
    <view v-for="c in list" :key="c.id" class="card" :class="{used:c.status!=='UNUSED'}"><view class="left"><text class="val">{{c.value}}积分</text><text class="name">优惠券</text></view>
      <view class="right"><text class="status">{{c.status==='UNUSED'?'可使用':c.status==='USED'?'已使用':'已过期'}}</text><text class="expire">有效期至{{c.expireTime}}</text></view>
    </view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index'
const list=ref([])
onMounted(async()=>{try{list.value=await api.myCoupons()||[]}catch{}})
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding:16rpx}.empty{text-align:center;padding:120rpx;color:#999}.card{display:flex;background:#fff;border-radius:16rpx;overflow:hidden;margin-bottom:12rpx}.card.used{opacity:.5}.left{width:200rpx;background:#FFF3EE;padding:32rpx;text-align:center}.val{font-size:40rpx;color:#FF6B35;font-weight:700;display:block}.name{font-size:24rpx;color:#FF6B35}.right{flex:1;padding:24rpx}.status{font-size:28rpx;color:#333;font-weight:500;display:block}.expire{font-size:24rpx;color:#999;margin-top:8rpx}
</style>
