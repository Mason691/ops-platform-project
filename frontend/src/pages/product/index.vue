<template>
  <view class="page">
    <view class="top-bar"><input class="search-input" v-model="keyword" placeholder="搜索商品" @confirm="load(true)"/></view>
    <view class="product-grid">
      <view v-for="p in products" :key="p.id" class="p-card" @click="onProductClick(p)">
        <image :src="p.coverImage||'/static/gas-default.png'" class="p-img" mode="aspectFill"/>
        <view v-if="p.miniappConfig" class="p-tag">小程序</view>
        <view class="p-body">
          <text class="p-merchant">{{p.merchantName||''}}</text>
          <text class="p-name">{{p.name}}</text>
          <!-- 积分兑换价格 -->
          <view class="p-price" v-if="p.points > 0">
            <view class="price-main">
              <text class="points-num">{{p.points}}</text><text class="points-unit">积分</text>
              <text class="price-plus">+</text>
              <text class="cash-num">¥{{fmtCash(p.cashPrice)}}</text>
            </view>
            <text class="price-original">¥{{fmtCash(p.salePrice)}}</text>
          </view>
          <!-- 无积分兑换：显示销售价 -->
          <view class="p-price" v-else>
            <view class="price-main">
              <text class="cash-num plain">¥{{fmtCash(p.salePrice||p.cashPrice)}}</text>
            </view>
          </view>
          <!-- 积分兑换标签 -->
          <view class="points-badge" v-if="p.points > 0">
            <text>积分兑</text>
          </view>
        </view>
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
const keyword=ref(''),products=ref([]),page=ref(1),noMore=ref(false),showGuide=ref(false)
onMounted(()=>load(true))
async function load(reset){
  if(reset){products.value=[];page.value=1;noMore.value=false}
  try{const r=await api.productPage({pageNum:page.value,pageSize:20,name:keyword.value||undefined});const recs=r.records||[];if(recs.length<20)noMore.value=true;products.value=reset?recs:[...products.value,...recs];page.value++}catch{}
}
function fmtCash(v){if(v==null)return'0.00';const n=Number(v);return n.toFixed(n===Math.floor(n)?0:2)}
function isWechat(){return /micromessenger/i.test(navigator.userAgent)}
function onProductClick(p){
  if(isWechat() && p.miniappConfig && p.miniappConfig.appId){jumpToMiniProgram(p.miniappConfig);return}
  goDetail(p.id)
}
function jumpToMiniProgram(cfg){
  try{
    // #ifdef H5
    if(typeof wx !== 'undefined' && wx.miniProgram){
      wx.miniProgram.navigateTo({appId:cfg.appId,path:cfg.path,envVersion:cfg.envVersion||'release',success:()=>{},fail:()=>{uni.showToast({title:'跳转小程序失败，请重试',icon:'none'})}})
      return
    }
    // #endif
    showGuide.value=true
  }catch(e){showGuide.value=true}
}
function goDetail(id){uni.navigateTo({url:'/pages/product/detail?id='+id})}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5}.top-bar{padding:16rpx 24rpx;background:#fff}.search-input{background:#f5f5f5;border-radius:32rpx;padding:16rpx 24rpx;font-size:28rpx}
.product-grid{display:grid;grid-template-columns:1fr 1fr;gap:16rpx;padding:16rpx 24rpx}
.p-card{background:#fff;border-radius:16rpx;overflow:hidden;position:relative}
.p-img{width:100%;height:280rpx;display:block}
.p-tag{position:absolute;top:8rpx;right:8rpx;background:#07c160;color:#fff;font-size:20rpx;padding:4rpx 12rpx;border-radius:8rpx;z-index:2}
.p-body{padding:12rpx 16rpx 16rpx}
.p-merchant{font-size:20rpx;color:#999;display:block;margin-bottom:4rpx}
.p-name{font-size:26rpx;color:#333;display:block;margin-bottom:8rpx;line-height:1.4;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.p-price{display:flex;flex-direction:column;gap:4rpx}
.price-main{display:flex;align-items:baseline;gap:4rpx}
.points-num{font-size:36rpx;color:#FF6B35;font-weight:700}
.points-unit{font-size:24rpx;color:#FF6B35;font-weight:600}
.price-plus{font-size:22rpx;color:#999;margin:0 2rpx}
.cash-num{font-size:28rpx;color:#333;font-weight:600}
.cash-num.plain{font-size:32rpx;color:#FF6B35}
.price-original{font-size:22rpx;color:#c0c4cc;text-decoration:line-through}
.points-badge{position:absolute;bottom:8rpx;right:8rpx;background:linear-gradient(135deg,#FF6B35,#f56c6c);color:#fff;font-size:18rpx;padding:2rpx 10rpx;border-radius:6rpx}
.no-more{text-align:center;padding:32rpx;color:#999;font-size:26rpx}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}.guide{background:#fff;border-radius:24rpx;padding:48rpx 40rpx;width:80%;text-align:center}.guide-icon{font-size:64rpx;display:block;margin-bottom:20rpx}.guide-title{font-size:34rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}.guide-desc{font-size:26rpx;color:#999;display:block;margin-bottom:32rpx;line-height:1.5}.guide-btn{background:#FF6B35;color:#fff;padding:20rpx 48rpx;border-radius:48rpx;font-size:28rpx;display:inline-block}
</style>
