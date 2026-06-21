<template>
  <view class="page" v-if="p">
    <swiper class="swiper" indicator-dots><swiper-item v-for="(img,i) in images" :key="i"><image :src="assetUrl(img)" class="swiper-img" mode="aspectFill" @error="onImgError"/></swiper-item></swiper>
    <view class="info">
      <text class="merchant">{{p.merchantName||''}}</text>
      <text class="name">{{p.name}}</text>
      <!-- 积分兑换价格 -->
      <view class="price-row" v-if="p.points > 0">
        <view class="price-exchange">
          <text class="points-num">{{p.points}}</text><text class="points-label">积分</text>
          <text class="plus">+</text>
          <text class="cash-num">¥{{fmtCash(p.cashPrice)}}</text>
        </view>
        <text class="price-original">原价 ¥{{fmtCash(p.salePrice)}}</text>
        <view class="save-badge"><text>省 ¥{{fmtCash(p.salePrice - p.cashPrice)}}</text></view>
      </view>
      <!-- 无积分兑换 -->
      <view class="price-row" v-else>
        <text class="price-single">¥{{fmtCash(p.salePrice||p.cashPrice||0)}}</text>
      </view>
      <view class="desc"><text class="desc-title">商品详情</text><text>{{p.description||'暂无描述'}}</text></view>
    </view>
    <view class="bottom-bar">
      <view class="bar-info" v-if="p.points > 0">
        <text class="bar-points">{{p.points}}积分</text>
        <text v-if="p.cashPrice > 0"> + ¥{{fmtCash(p.cashPrice)}}</text>
      </view>
      <view class="bar-info" v-else>
        <text>¥{{fmtCash(p.salePrice||p.cashPrice||0)}}</text>
      </view>
      <view class="buy-btn" @click="doExchange">立即兑换</view>
      <view v-if="p.miniappConfig && p.miniappConfig.appId" class="jump-btn" @click="jumpToMiniapp">进入小程序</view>
    </view>
    <!-- 确认弹窗 -->
    <view class="mask" v-if="showConfirm" @click="showConfirm=false"><view class="confirm" @click.stop>
      <text class="confirm-title">确认兑换</text>
      <text class="confirm-desc">消耗 {{p.points||0}} 积分 + ¥{{fmtCash(p.cashPrice||0)}} 兑换「{{p.name}}」</text>
      <view class="confirm-btns"><view class="cancel" @click="showConfirm=false">取消</view><view class="ok" @click="confirmExchange">确认兑换</view></view>
    </view></view>
    <!-- 非微信引导 -->
    <view class="mask" v-if="showGuide" @click="showGuide=false"><view class="guide" @click.stop>
      <text class="guide-icon">📱</text><text class="guide-title">请使用微信客户端打开</text>
      <text class="guide-desc">当前浏览器不支持小程序跳转，请复制链接在微信中打开</text>
      <view class="guide-btn" @click="showGuide=false">知道了</view>
    </view></view>
  </view>
</template>
<script setup>
import {ref,computed,onMounted} from 'vue';import {api} from '@/api/index'
import { assetUrl, onImgError } from '@/utils/image.js'
const id=ref(0),p=ref(null),showConfirm=ref(false),showGuide=ref(false)
const images=computed(()=>{if(!p.value)return[];try{return JSON.parse(p.value.images)}catch{return[p.value.coverImage]}})
function fmtCash(v){if(v==null)return'0.00';const n=Number(v);return n.toFixed(n===Math.floor(n)?0:2)}
onMounted(async()=>{const opts=(getCurrentPages().pop())?.options||{};if(opts.id){id.value=Number(opts.id);try{p.value=await api.productDetail(id.value)}catch{}}})
function isWechat(){return /micromessenger/i.test(navigator.userAgent)}
function jumpToMiniapp(){
  const cfg = p.value?.miniappConfig
  if(!cfg||!cfg.appId){showGuide.value=true;return}
  try{
    // #ifdef H5
    var realWx = window.wx || (typeof wx !== 'undefined' && wx.miniProgram ? wx : null)
    if(realWx && realWx.miniProgram && typeof realWx.miniProgram.navigateTo === 'function'){realWx.miniProgram.navigateTo({appId:cfg.appId,path:cfg.path,envVersion:cfg.envVersion||'release',success:()=>{},fail:()=>{uni.showToast({title:'跳转小程序失败，请重试',icon:'none'})}});return}
    // #endif
    showGuide.value=true
  }catch(e){showGuide.value=true}
}
function doExchange(){if(!uni.getStorageSync('gas_token')){uni.navigateTo({url:'/pages/auth/login'});return};showConfirm.value=true}
async function confirmExchange(){
  try{
    const r=await api.createOrder({productId:p.value.id});uni.showToast({title:'兑换成功！',icon:'success',duration:1500});
    // #ifdef MP-WEIXIN
    const cfg=p.value?.miniappConfig;setTimeout(()=>{wx.navigateToMiniProgram({appId:cfg?.appId||'',path:cfg?.path||'pages/index/index',extraData:{orderNo:r.orderNo},success:()=>{},fail:()=>uni.showToast({title:'请手动打开小程序',icon:'none'})})},1500)
    // #endif
    // #ifdef H5
    jumpToMiniapp()
    // #endif
  }catch(e){uni.showToast({title:e.msg||'兑换失败',icon:'none'})}
}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding-bottom:120rpx}.swiper{height:500rpx}.swiper-img{width:100%;height:100%}.info{background:#fff;border-radius:24rpx 24rpx 0 0;padding:32rpx;margin-top:-24rpx;position:relative}.merchant{font-size:24rpx;color:#999;display:block;margin-bottom:8rpx}.name{font-size:34rpx;color:#333;font-weight:600;display:block;margin-bottom:16rpx}
.price-row{display:flex;flex-direction:column;gap:8rpx;margin:20rpx 0;padding:20rpx;background:#fff9f5;border-radius:12rpx;border:2rpx solid #ffe8d6}
.price-exchange{display:flex;align-items:baseline;gap:6rpx}
.points-num{font-size:48rpx;color:#FF6B35;font-weight:700}
.points-label{font-size:28rpx;color:#FF6B35;font-weight:600}
.plus{font-size:28rpx;color:#999;margin:0 4rpx}
.cash-num{font-size:36rpx;color:#333;font-weight:700}
.price-original{font-size:26rpx;color:#c0c4cc;text-decoration:line-through}
.save-badge{display:inline-flex;align-self:flex-start;background:#FF6B35;color:#fff;font-size:22rpx;padding:4rpx 14rpx;border-radius:6rpx;margin-top:4rpx}
.price-single{font-size:48rpx;color:#FF6B35;font-weight:700}
.desc{margin-top:32rpx}.desc-title{font-size:28rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}
.bottom-bar{position:fixed;bottom:0;left:0;right:0;display:flex;align-items:center;justify-content:space-between;gap:12rpx;background:#fff;padding:16rpx 32rpx calc(env(safe-area-inset-bottom)+16rpx);border-top:1rpx solid #eee}
.bar-info{font-size:28rpx;color:#FF6B35;font-weight:600}.bar-points{font-size:32rpx}
.buy-btn{background:#FF6B35;color:#fff;padding:20rpx 32rpx;border-radius:48rpx;font-size:28rpx;font-weight:600;white-space:nowrap}
.jump-btn{background:#409eff;color:#fff;padding:20rpx 32rpx;border-radius:48rpx;font-size:28rpx;font-weight:600;white-space:nowrap}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}.confirm{background:#fff;border-radius:24rpx;padding:40rpx;width:80%}.confirm-title{font-size:34rpx;font-weight:600;text-align:center;display:block}.confirm-desc{font-size:28rpx;color:#666;text-align:center;margin:24rpx 0;display:block}.confirm-btns{display:flex;gap:20rpx}.cancel{flex:1;text-align:center;padding:24rpx;border:2rpx solid #ddd;border-radius:48rpx;font-size:28rpx;color:#666}.ok{flex:1;text-align:center;padding:24rpx;background:#FF6B35;color:#fff;border-radius:48rpx;font-size:28rpx;font-weight:600}
.guide{background:#fff;border-radius:24rpx;padding:48rpx 40rpx;width:80%;text-align:center}.guide-icon{font-size:64rpx;display:block;margin-bottom:20rpx}.guide-title{font-size:34rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}.guide-desc{font-size:26rpx;color:#999;display:block;margin-bottom:32rpx;line-height:1.5}.guide-btn{background:#FF6B35;color:#fff;padding:20rpx 48rpx;border-radius:48rpx;font-size:28rpx;display:inline-block}
</style>
