<template>
  <view class="page" v-if="a">
    <image :src="a.coverImage||'/static/gas-default.png'" class="cover" mode="aspectFill"/>
    <view class="info"><text class="title">{{a.title}}</text><text class="time">🕐 {{a.startTime}} ~ {{a.endTime}}</text><text v-if="a.location" class="loc">📍 {{a.location}}</text>
      <view class="reward" v-if="a.pointsReward">🎁 参加可获得 <text class="r-points">{{a.pointsReward}} 积分</text></view>
      <view class="detail"><rich-text :nodes="a.detail||'暂无详情'"/></view>
    </view>
    <view class="bottom-bar"><view class="join-btn" @click="join">我要报名</view></view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index'
const a=ref(null)
onMounted(async()=>{const id=(getCurrentPages().pop())?.options?.id;if(id)try{a.value=await api.activityDetail(Number(id))}catch{}})
function join(){uni.showToast({title:'请添加客服微信报名',icon:'none'})}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding-bottom:120rpx}.cover{width:100%;height:400rpx}.info{background:#fff;padding:32rpx;margin-top:-24rpx;border-radius:24rpx 24rpx 0 0}.title{font-size:34rpx;color:#333;font-weight:600;display:block}.time,.loc{font-size:26rpx;color:#999;display:block;margin-top:12rpx}.reward{margin-top:20rpx;background:#FFF3EE;padding:16rpx 24rpx;border-radius:12rpx;font-size:28rpx}.r-points{color:#FF6B35;font-weight:600}.detail{margin-top:24rpx;font-size:28rpx;color:#666;line-height:1.8}.bottom-bar{position:fixed;bottom:0;left:0;right:0;padding:16rpx 32rpx calc(env(safe-area-inset-bottom)+16rpx);background:#fff;border-top:1rpx solid #eee}.join-btn{background:#FF6B35;color:#fff;text-align:center;padding:24rpx;border-radius:48rpx;font-size:32rpx;font-weight:600}
</style>
