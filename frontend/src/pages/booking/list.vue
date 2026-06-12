<template>
  <view class="page">
    <view class="list" v-if="bookings.length>0">
      <view class="item" v-for="b in bookings" :key="b.id">
        <text class="item-title">{{b.activityTitle}}</text>
        <text class="item-time" v-if="b.activityTime">{{b.activityTime}}</text>
        <view class="item-status">
          <text :class="['tag', b.status===0?'active':'done']">{{ b.status===0?'待参加':b.status===1?'已参加':'已取消' }}</text>
        </view>
      </view>
    </view>
    <view class="empty" v-else>暂无预约记录</view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue'
const bookings=ref([])
onMounted(async()=>{
  try{
    const data=await request()
    bookings.value=data||[]
  }catch{}
})
function request(){
  return new Promise((resolve,reject)=>{
    const token=uni.getStorageSync('ops_token')||''
    uni.request({
      url:'/api/ops/my-bookings',
      header:token?{'Authorization':'Bearer '+token,'Content-Type':'application/json'}:{'Content-Type':'application/json'},
      success(r){if(r.data?.code===200)resolve(r.data.data);else reject(r.data)},
      fail:reject
    })
  })
}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding:24rpx}.list{display:flex;flex-direction:column;gap:16rpx}.item{background:#fff;border-radius:12rpx;padding:24rpx}.item-title{font-size:30rpx;color:#333;font-weight:600;display:block}.item-time{font-size:24rpx;color:#999;margin-top:8rpx;display:block}.item-status{margin-top:12rpx}.tag{padding:4rpx 16rpx;border-radius:8rpx;font-size:24rpx}.tag.active{color:#FF6B35;background:#FFF3EE}.tag.done{color:#999;background:#f5f5f5}.empty{text-align:center;padding:120rpx 0;color:#999;font-size:28rpx}
</style>
</template>
