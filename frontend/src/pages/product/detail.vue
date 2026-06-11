<template>
  <view class="page" v-if="p">
    <swiper class="swiper" indicator-dots><swiper-item v-for="(img,i) in images" :key="i"><image :src="img" class="swiper-img" mode="aspectFill"/></swiper-item></swiper>
    <view class="info"><text class="name">{{p.name}}</text>
      <view class="price-row"><text class="points">{{p.pointsPrice}} 积分</text><text v-if="p.cashPrice>0" class="cash">+ ¥{{p.cashPrice}}</text></view>
      <view class="desc"><text class="desc-title">商品详情</text><text>{{p.description||'暂无描述'}}</text></view>
    </view>
    <view class="bottom-bar">
      <text class="total">需消耗 {{p.pointsPrice}} 积分</text>
      <view class="buy-btn" @click="doExchange">立即兑换</view>
      <view v-if="p.miniappConfig && p.miniappConfig.appId" class="jump-btn" @click="jumpToMiniapp">进入小程序</view>
    </view>
    <!-- 确认弹窗 -->
    <view class="mask" v-if="showConfirm" @click="showConfirm=false"><view class="confirm" @click.stop>
      <text class="confirm-title">确认兑换</text><text class="confirm-desc">消耗 {{p.pointsPrice}} 积分兑换「{{p.name}}」</text>
      <view class="confirm-btns"><view class="cancel" @click="showConfirm=false">取消</view><view class="ok" @click="confirmExchange">确认兑换</view></view>
    </view></view>
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
import {ref,computed,onMounted} from 'vue';import {api} from '@/api/index'
const id=ref(0),p=ref(null),showConfirm=ref(false),showGuide=ref(false)
const images=computed(()=>{if(!p.value)return[];try{return JSON.parse(p.value.images)}catch{return[p.value.coverImage]}})
onMounted(async()=>{
  const opts=(getCurrentPages().pop())?.options||{};
  if(opts.id){id.value=Number(opts.id);try{p.value=await api.productDetail(id.value)}catch{}}
})

function isWechat(){return /micromessenger/i.test(navigator.userAgent)}

function jumpToMiniapp(){
  const cfg = p.value?.miniappConfig
  if(!cfg || !cfg.appId){ showGuide.value = true; return }
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

function doExchange(){if(!uni.getStorageSync('gas_token')){uni.navigateTo({url:'/pages/auth/login'});return};showConfirm.value=true}
async function confirmExchange(){
  try{
    const r=await api.createOrder({productId:p.value.id});
    uni.showToast({title:'兑换成功！',icon:'success',duration:1500});
    // 兑换成功后跳转小程序
    // #ifdef MP-WEIXIN
    const cfg = p.value?.miniappConfig
    setTimeout(()=>{wx.navigateToMiniProgram({appId: cfg?.appId || '', path: cfg?.path || 'pages/index/index', extraData:{orderNo:r.orderNo}, success:()=>{}, fail:()=>uni.showToast({title:'请手动打开小程序',icon:'none'})})},1500)
    // #endif
    // #ifdef H5
    jumpToMiniapp()
    // #endif
  }catch(e){uni.showToast({title:e.msg||'兑换失败',icon:'none'})}
}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding-bottom:120rpx}.swiper{height:500rpx}.swiper-img{width:100%;height:100%}.info{background:#fff;border-radius:24rpx 24rpx 0 0;padding:32rpx;margin-top:-24rpx;position:relative}.name{font-size:34rpx;color:#333;font-weight:600;display:block}.price-row{display:flex;align-items:baseline;gap:12rpx;margin:20rpx 0}.points{font-size:40rpx;color:#FF6B35;font-weight:700}.cash{font-size:28rpx;color:#999}.desc{margin-top:32rpx}.desc-title{font-size:28rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}.bottom-bar{position:fixed;bottom:0;left:0;right:0;display:flex;align-items:center;justify-content:space-between;gap:16rpx;background:#fff;padding:16rpx 32rpx calc(env(safe-area-inset-bottom)+16rpx);border-top:1rpx solid #eee}.total{font-size:28rpx;color:#FF6B35;font-weight:600}.buy-btn{background:#FF6B35;color:#fff;padding:20rpx 32rpx;border-radius:48rpx;font-size:28rpx;font-weight:600}.jump-btn{background:#409eff;color:#fff;padding:20rpx 32rpx;border-radius:48rpx;font-size:28rpx;font-weight:600}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}.confirm{background:#fff;border-radius:24rpx;padding:40rpx;width:80%}.confirm-title{font-size:34rpx;font-weight:600;text-align:center;display:block}.confirm-desc{font-size:28rpx;color:#666;text-align:center;margin:24rpx 0;display:block}.confirm-btns{display:flex;gap:20rpx}.cancel{flex:1;text-align:center;padding:24rpx;border:2rpx solid #ddd;border-radius:48rpx;font-size:28rpx;color:#666}.ok{flex:1;text-align:center;padding:24rpx;background:#FF6B35;color:#fff;border-radius:48rpx;font-size:28rpx;font-weight:600}
.guide{background:#fff;border-radius:24rpx;padding:48rpx 40rpx;width:80%;text-align:center}.guide-icon{font-size:64rpx;display:block;margin-bottom:20rpx}.guide-title{font-size:34rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}.guide-desc{font-size:26rpx;color:#999;display:block;margin-bottom:32rpx;line-height:1.5}.guide-btn{background:#FF6B35;color:#fff;padding:20rpx 48rpx;border-radius:48rpx;font-size:28rpx;display:inline-block}
</style>
</template>
