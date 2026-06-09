<template>
  <view class="page">
    <view v-if="list.length===0" class="empty">暂无积分记录</view>
    <view v-for="l in list" :key="l.id" class="card"><view class="left"><text class="remark">{{l.remark||'积分变动'}}</text><text class="time">{{l.createTime}}</text></view>
      <text class="amount" :class="l.changeType">{{l.changeType==='GAIN'?'+':'-'}}{{l.amount}}</text>
    </view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index'
const list=ref([])
onMounted(async()=>{try{const r=await api.pointsLog({pageNum:1,pageSize:100});list.value=r.records||[]}catch{}})
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding:16rpx}.empty{text-align:center;padding:120rpx;color:#999}.card{display:flex;justify-content:space-between;align-items:center;background:#fff;border-radius:12rpx;padding:24rpx;margin-bottom:8rpx}.remark{font-size:28rpx;color:#333;display:block}.time{font-size:24rpx;color:#999;margin-top:4rpx}.amount{font-size:34rpx;font-weight:700}.amount.GAIN,.amount.REFUND{color:#4caf50}.amount.CONSUME{color:#FF6B35}
</style>
