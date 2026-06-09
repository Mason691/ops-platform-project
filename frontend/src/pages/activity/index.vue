<template>
  <view class="page">
    <view class="filters"><view v-for="c in cats" :key="c" class="cat" :class="{on:cat===c}" @click="cat=c;load(true)">{{c}}</view></view>
    <view v-for="a in list" :key="a.id" class="card" @click="goDetail(a.id)">
      <image :src="a.coverImage||'/static/gas-default.png'" class="cover" mode="aspectFill"/>
      <view class="info"><text class="title">{{a.title}}</text><text class="time">🕐 {{a.startTime}} ~ {{a.endTime}}</text><text class="location" v-if="a.location">📍 {{a.location}}</text>
        <view class="reward" v-if="a.pointsReward>0"><text>🎁 参加即送 {{a.pointsReward}} 积分</text></view>
      </view>
    </view>
    <view class="no-more" v-if="noMore">— 没有更多了 —</view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index'
const cat=ref('全部'),cats=ref(['全部','OFFLINE','FAMILY','OTHER']),list=ref([]),page=ref(1),noMore=ref(false)
onMounted(()=>load(true))
async function load(reset){
  if(reset){list.value=[];page.value=1;noMore.value=false}
  try{const r=await api.activityPage({pageNum:page.value,pageSize:10,category:cat.value==='全部'?undefined:cat.value});const recs=r.records||[];if(recs.length<10)noMore.value=true;list.value=reset?recs:[...list.value,...recs];page.value++}catch{}
}
function goDetail(id){uni.navigateTo({url:'/pages/activity/detail?id='+id})}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5}.filters{display:flex;gap:16rpx;padding:16rpx 24rpx;background:#fff}.cat{padding:12rpx 24rpx;border-radius:24rpx;font-size:26rpx;color:#666;background:#f5f5f5}.cat.on{background:#FF6B35;color:#fff}.card{background:#fff;border-radius:16rpx;overflow:hidden;margin:16rpx 24rpx;display:flex}.cover{width:200rpx;height:200rpx;flex-shrink:0}.info{flex:1;padding:20rpx}.title{font-size:28rpx;color:#333;font-weight:600;display:block}.time,.location{font-size:24rpx;color:#999;display:block;margin-top:8rpx}.reward{margin-top:12rpx;background:#FFF3EE;padding:8rpx 16rpx;border-radius:8rpx;display:inline-block}.reward text{font-size:24rpx;color:#FF6B35}.no-more{text-align:center;padding:32rpx;color:#999;font-size:26rpx}
</style>
