<template>
  <view class="page">
    <view class="top-bar"><input class="search-input" v-model="keyword" placeholder="搜索商品" @confirm="load(true)"/></view>
    <view class="filters"><view v-for="c in cats" :key="c" class="cat" :class="{on:cat===c}" @click="cat=c;load(true)">{{c}}</view></view>
    <view class="product-grid">
      <view v-for="p in products" :key="p.id" class="p-card" @click="onProductClick(p)">
        <image :src="p.coverImage||'/static/gas-default.png'" class="p-img" mode="aspectFill"/>
        <text class="p-name">{{p.name}}</text>
        <view class="p-price"><text class="points">{{p.pointsPrice}}积分</text><text v-if="p.cashPrice>0" class="cash">+¥{{p.cashPrice}}</text></view>
      </view>
    </view>
    <view class="no-more" v-if="noMore">— 没有更多了 —</view>

    <!-- 非微信环境引导蒙层 -->
    <view class="mask" v-if="showGuide" @click="showGuide=false">
      <view class="guide" @click.stop>
        <text class="guide-icon">📱</text>
        <text class="guide-title">请使用微信客户端打开</text>
        <text class="guide-desc">当前浏览器不支持小程序跳转，请复制链接在微信中打开</text>
        <view class="guide-btn" @click="showGuide=false">知道了</view>
      </view>
    </view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index'
const keyword=ref(''),cat=ref('全部'),cats=ref(['全部','家用电器','粮油食品','日用百货','燃气设备']),products=ref([]),page=ref(1),noMore=ref(false),showGuide=ref(false)
onMounted(()=>load(true))
async function load(reset){
  if(reset){products.value=[];page.value=1;noMore.value=false}
  try{const r=await api.productPage({pageNum:page.value,pageSize:20,name:keyword.value||undefined});const recs=r.records||[];if(recs.length<20)noMore.value=true;products.value=reset?recs:[...products.value,...recs];page.value++}catch{}
}

function isWechat(){return /micromessenger/i.test(navigator.userAgent)}

function onProductClick(p){
  // 微信环境：优先跳转小程序
  if(isWechat() && p.miniappConfig && p.miniappConfig.appId){
    jumpToMiniProgram(p.miniappConfig)
    return
  }
  // 非微信环境或无配置：进入详情页
  goDetail(p.id)
}

function jumpToMiniProgram(cfg){
  try{
    // #ifdef H5
    if(typeof wx !== 'undefined' && wx.miniProgram){
      wx.miniProgram.navigateTo({
        appId: cfg.appId,
        path: cfg.path,
        envVersion: cfg.envVersion || 'release',
        success: () => {},
        fail: () => { uni.showToast({title:'跳转小程序失败，请重试',icon:'none'}) }
      })
      return
    }
    // #endif
    showGuide.value = true
  }catch(e){ showGuide.value = true }
}

function goDetail(id){uni.navigateTo({url:'/pages/product/detail?id='+id})}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5}.top-bar{padding:16rpx 24rpx;background:#fff}.search-input{background:#f5f5f5;border-radius:32rpx;padding:16rpx 24rpx;font-size:28rpx}.filters{display:flex;gap:16rpx;padding:16rpx 24rpx;background:#fff}.cat{padding:12rpx 24rpx;border-radius:24rpx;font-size:26rpx;color:#666;background:#f5f5f5}.cat.on{background:#FF6B35;color:#fff}.product-grid{display:grid;grid-template-columns:1fr 1fr;gap:16rpx;padding:16rpx 24rpx}.p-card{background:#fff;border-radius:16rpx;overflow:hidden}.p-img{width:100%;height:260rpx}.p-name{font-size:26rpx;color:#333;padding:12rpx 16rpx 4rpx;display:block}.p-price{display:flex;align-items:baseline;gap:8rpx;padding:0 16rpx 16rpx}.points{font-size:30rpx;color:#FF6B35;font-weight:600}.cash{font-size:24rpx;color:#999}.no-more{text-align:center;padding:32rpx;color:#999;font-size:26rpx}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}.guide{background:#fff;border-radius:24rpx;padding:48rpx 40rpx;width:80%;text-align:center}.guide-icon{font-size:64rpx;display:block;margin-bottom:20rpx}.guide-title{font-size:34rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}.guide-desc{font-size:26rpx;color:#999;display:block;margin-bottom:32rpx;line-height:1.5}.guide-btn{background:#FF6B35;color:#fff;padding:20rpx 48rpx;border-radius:48rpx;font-size:28rpx;display:inline-block}
</style>
