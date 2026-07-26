<template>
  <view class="page">
    <!-- 直播横幅 -->
    <view class="live-banner" v-if="liveRoom" @click="openLive(liveRoom)">
      <view class="lb-badge"><view class="lb-dot"></view><text>LIVE</text></view>
      <text class="lb-title">{{ liveRoom.liveTitle }}</text>
      <text class="lb-arrow">›</text>
    </view>
    <!-- 分类标签栏 -->
    <view class="cat-tabs">
      <view
        v-for="c in categories" :key="c.id"
        class="cat-tab"
        :class="{ active: activeCatId === c.id }"
        @click="switchCat(c.id)"
      >{{ c.name }}</view>
    </view>

    <!-- 商品网格 -->
    <view class="product-grid" v-if="products.length > 0">
      <view v-for="p in products" :key="p.id" class="p-card" @click="onProductClick(p)">
        <image :src="assetUrl(p.coverImage)" class="p-img" mode="aspectFill" @error="onImgError"/>
        <view v-if="p.miniappConfig" class="p-tag">小程序</view>
        <view class="p-body">
          <text class="p-name">{{p.name}}</text>
          <view class="p-price" v-if="p.points > 0">
            <view class="price-main">
              <text class="points-num">{{p.points}}</text><text class="points-unit">积分</text>
              <text class="price-plus">+</text>
              <text class="cash-num">¥{{fmtCash(p.cashPrice)}}</text>
            </view>
            <text class="price-original">¥{{fmtCash(p.salePrice)}}</text>
          </view>
          <view class="p-price" v-else>
            <view class="price-main">
              <text class="cash-num plain">¥{{fmtCash(p.salePrice||p.cashPrice)}}</text>
            </view>
          </view>
          <view class="points-badge" v-if="p.points > 0"><text>积分兑</text></view>
        </view>
      </view>
    </view>
    <view class="empty" v-else-if="!loading">暂无商品</view>

    <!-- 非微信引导 -->
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
import { assetUrl, onImgError } from '@/utils/image.js'
import { openChannelsLive } from '@/utils/jssdk.js'


const categories=ref([]),activeCatId=ref(null),products=ref([]),loading=ref(false),showGuide=ref(false),liveRoom=ref(null)
onMounted(async()=>{
  try{const lr=await api.activeLiveRooms();liveRoom.value=(lr||[])[0]||null}catch{}
  try{const cats=await api.productCategories();categories.value=cats||[];if(categories.value.length>0){activeCatId.value=categories.value[0].id;loadProducts()}}catch{}
})
async function switchCat(id){activeCatId.value=id;loadProducts()}
async function loadProducts(){
  loading.value=true
  try{
    const r=await api.productPage({pageNum:1,pageSize:50,categoryId:activeCatId.value})
    products.value=r.records||[]
  }catch{}finally{loading.value=false}
}
function fmtCash(v){if(v==null)return'0.00';const n=Number(v);return n.toFixed(n===Math.floor(n)?0:2)}
function isWechat(){return /micromessenger/i.test(navigator.userAgent)}
function onProductClick(p){
  if(isWechat() && p.miniappConfig && p.miniappConfig.appId){jumpToMiniProgram(p.miniappConfig);return}
  uni.navigateTo({url:'/pages/product/detail?id='+p.id})
}
function jumpToMiniProgram(cfg){
  try{
    // #ifdef H5
    var realWx = window.wx || (typeof wx !== 'undefined' && wx.miniProgram ? wx : null)
    if(realWx && realWx.miniProgram && typeof realWx.miniProgram.navigateTo === 'function'){
      realWx.miniProgram.navigateTo({appId:cfg.appId,path:cfg.path,envVersion:cfg.envVersion||'release',success:()=>{},fail:()=>{uni.showToast({title:'跳转小程序失败，请重试',icon:'none'})}})
      return
    }
    // #endif
    showGuide.value=true
  }catch(e){showGuide.value=true}
}
function openLive(room){
  if(!isWechat()){uni.showToast({title:'请在微信中打开直播间',icon:'none'});return}
  if(room.finderUsername){ openChannelsLive(room.finderUsername); return }
  if(room.jumpUrl){ window.location.href = room.jumpUrl; return }
  uni.showToast({title:'直播链接未配置',icon:'none'})
}
</script>
<style scoped>
.live-banner{display:flex;align-items:center;gap:16rpx;padding:18rpx 24rpx;margin:16rpx 24rpx 0;background:linear-gradient(135deg,#ff2d2d,#ff4444);border-radius:12rpx}.lb-badge{display:flex;align-items:center;gap:6rpx;padding:4rpx 12rpx;background:rgba(255,255,255,.2);border-radius:6rpx;font-size:20rpx;color:#fff;font-weight:700;flex-shrink:0}.lb-dot{width:10rpx;height:10rpx;background:#fff;border-radius:50%;animation:lpulse .8s infinite}@keyframes lpulse{0%,100%{opacity:1}50%{opacity:.2}}.lb-title{flex:1;font-size:26rpx;color:#fff;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.lb-arrow{color:rgba(255,255,255,.7);font-size:32rpx}

.page{min-height:100vh;background:#f5f5f5}
.cat-tabs{display:flex;background:#fff;padding:12rpx 16rpx;gap:8rpx;overflow-x:auto;white-space:nowrap;-webkit-overflow-scrolling:touch}
.cat-tab{flex-shrink:0;padding:14rpx 28rpx;border-radius:32rpx;font-size:26rpx;color:#666;background:#f5f5f5;transition:all .2s}
.cat-tab.active{background:#FF6B35;color:#fff;font-weight:600}
.product-grid{display:grid;grid-template-columns:1fr 1fr;gap:16rpx;padding:16rpx 24rpx}
.p-card{background:#fff;border-radius:16rpx;overflow:hidden;position:relative}
.p-img{width:100%;height:280rpx;display:block}
.p-tag{position:absolute;top:8rpx;right:8rpx;background:#07c160;color:#fff;font-size:20rpx;padding:4rpx 12rpx;border-radius:8rpx;z-index:2}
.p-body{padding:12rpx 16rpx 16rpx}
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
.empty{text-align:center;padding:120rpx 0;color:#999;font-size:28rpx}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}.guide{background:#fff;border-radius:24rpx;padding:48rpx 40rpx;width:80%;text-align:center}.guide-icon{font-size:64rpx;display:block;margin-bottom:20rpx}.guide-title{font-size:34rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}.guide-desc{font-size:26rpx;color:#999;display:block;margin-bottom:32rpx;line-height:1.5}.guide-btn{background:#FF6B35;color:#fff;padding:20rpx 48rpx;border-radius:48rpx;font-size:28rpx;display:inline-block}
</style>
