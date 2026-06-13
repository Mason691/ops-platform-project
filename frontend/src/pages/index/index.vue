<template>
  <view class="page">
    <!-- 顶部 -->
    <view class="top-bar">
      <view class="search-box" @click="search"><text class="search-icon">🔍</text><text class="search-placeholder">搜商品、活动</text></view>
      <view class="user-btn" @click="goLogin" v-if="!isLogin"><text>登录</text></view>
      <view class="user-btn" v-else @click="goMine"><text>{{points||0}}积分</text></view>
    </view>
    <scroll-view scroll-y class="content">
      <!-- 轮播 -->
      <swiper class="banner" indicator-dots autoplay circular><swiper-item v-for="b in banners" :key="b"><image :src="b" class="banner-img" mode="aspectFill"/></swiper-item></swiper>
      <!-- 优惠券弹窗入口 -->
      <view class="coupon-entry" @click="showCoupons=true"><text>🎫</text><text>新人专享积分券，点击领取 ›</text></view>
      <!-- 直播入口 -->
      <view class="live-entry" v-if="liveRoom" @click="openLive(liveRoom)">
        <view class="live-badge"><view class="live-dot"></view><text>LIVE</text></view>
        <view class="live-info">
          <text class="live-title">{{ liveRoom.liveTitle }}</text>
          <text class="live-sub">点击进入视频号直播间 ›</text>
        </view>
        <image v-if="liveRoom.coverUrl" :src="liveRoom.coverUrl" class="live-cover" mode="aspectFill"/>
      </view>
      <view class="more-activity" @click="goActivityList"><text>更多活动 ›</text></view>
      <!-- 视频信息流 -->
      <view class="section-title"><text>📹 精彩视频</text></view>
      <view v-for="(p,i) in products" :key="'v'+i" class="video-card">
        <view class="video-placeholder" @click="goProduct(p.id)"><text class="play-icon">▶</text></view>
        <view class="video-info"><text class="v-title">{{p.name}}</text><text class="v-points">🔥 {{p.pointsPrice}}积分兑换</text></view>
      </view>
      <!-- 热门推荐 -->
      <view class="section-title"><text>🏆 热门推荐</text></view>
      <view class="product-grid">
        <view v-for="p in products" :key="p.id" class="p-card" @click="goProduct(p.id)">
          <image :src="p.coverImage||'/static/gas-default.png'" class="p-img" mode="aspectFill"/>
          <text class="p-name">{{p.name}}</text>
          <view class="p-bottom"><text class="p-points">{{p.pointsPrice}}积分</text><text class="p-cash" v-if="p.cashPrice>0">+¥{{p.cashPrice}}</text></view>
        </view>
      </view>
    </scroll-view>
    <!-- AI 助手浮动按钮 -->
    <view class="ai-float-btn" @click="goAiChat">
      <text class="ai-btn-icon">🤖</text>
    </view>
    <!-- 优惠券弹窗 -->
    <view class="mask" v-if="showCoupons" @click="showCoupons=false">
      <view class="coupon-modal" @click.stop>
        <text class="modal-title">🎉 限时优惠券</text>
        <view v-for="c in coupons" :key="c.id" class="coupon-item"><text class="c-name">{{c.name}}</text><text class="c-value">{{c.value}}积分</text><view class="c-draw" @click="doDraw(c.id)">立即领取</view></view>
        <view class="modal-close" @click="showCoupons=false">关闭</view>
      </view>
    </view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index'
const isLogin=ref(false),points=ref(0),products=ref([]),banners=ref(['/static/gas-banner1.png','/static/gas-banner2.png']),coupons=ref([]),showCoupons=ref(false),liveRoom=ref(null)
onMounted(async()=>{
  const u=uni.getStorageSync('gas_user');if(u){isLogin.value=true;try{const m=await api.getMe();points.value=m.pointsBalance||0}catch{isLogin.value=false}}
  try{const r=await api.productPage({pageNum:1,pageSize:10});products.value=r.records||[]}catch{}
  try{coupons.value=await api.couponList()||[]}catch{}
  try{const lr=await api.activeLiveRooms();liveRoom.value=(lr||[])[0]||null}catch{}
  // 首次进入弹优惠券
  if(!uni.getStorageSync('gas_coupon_shown')&&coupons.value.length){showCoupons.value=true;uni.setStorageSync('gas_coupon_shown','1')}
})
async function doDraw(cid){try{await api.drawCoupon(cid);uni.showToast({title:'领取成功',icon:'success'})}catch(e){uni.showToast({title:e.msg,icon:'none'})}}
function goLogin(){uni.navigateTo({url:'/pages/auth/login'})}
function goMine(){uni.switchTab({url:'/pages/mine/index'})}
function goProduct(id){uni.navigateTo({url:'/pages/product/detail?id='+id})}
function goAiChat(){uni.navigateTo({url:'/pages/ai/chat'})}
function goActivityList(){uni.navigateTo({url:'/pages/activity/index'})}
function search(){uni.showToast({title:'搜索功能开发中',icon:'none'})}
function openLive(room){
  if(!/micromessenger/i.test(navigator.userAgent)){
    uni.showToast({title:'请在微信中打开直播间',icon:'none'});return
  }
  if(!room.finderUsername){uni.showToast({title:'直播信息不完整',icon:'none'});return}

  // uni-app's module named 'wx' shadows window.wx. Must reach through window.
  var realWx = window.wx       // WeChat WebView global
            || window.WX        // some builds use WX
            || (typeof wx !== 'undefined' && wx.openChannelsLive ? wx : null)
  console.log('[Live] realWx:', typeof realWx, 'openChannelsLive:', typeof (realWx && realWx.openChannelsLive))

  if (realWx && typeof realWx.openChannelsLive === 'function'){
    realWx.openChannelsLive({
      finderUserName: room.finderUsername,
      success: function(){ console.log('[Live] openChannelsLive success') },
      fail: function(err){
        console.warn('[Live] openChannelsLive fail:', err)
        uni.showToast({title:'请在微信中搜索视频号 "'+room.finderUsername+'" 观看',icon:'none'})
      }
    })
  } else {
    // openChannelsLive = undefined: 测试号(wxe0eb0deb7d251505)不支持视频号API。
    // 正式环境需: 1)认证公众号(wx095b91409f8ecbfe) 2)在mp.weixin.qq.com关联视频号sphaxIEW2Etr8EL
    console.warn('[Live] openChannelsLive=undefined — 测试号限制, 请在微信中搜索视频号观看')
    uni.showToast({title:'请在微信中搜索视频号观看',icon:'none',duration:2000})
  }
}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5}.content{padding:0 24rpx 120rpx}.top-bar{display:flex;align-items:center;gap:16rpx;padding:16rpx 24rpx;background:#FF6B35}.search-box{flex:1;background:rgba(255,255,255,.2);border-radius:32rpx;padding:16rpx 24rpx;display:flex;align-items:center;gap:12rpx}.search-icon{font-size:28rpx}.search-placeholder{font-size:26rpx;color:rgba(255,255,255,.7)}.user-btn{color:#fff;font-size:26rpx;white-space:nowrap}.banner{height:320rpx;margin:24rpx 0}.banner-img{width:100%;height:100%;border-radius:16rpx}.more-activity{text-align:center;padding:16rpx;font-size:26rpx;color:#FF6B35}.coupon-entry{background:linear-gradient(90deg,#FFF3EE,#FFE8D6);padding:20rpx 24rpx;border-radius:12rpx;margin-bottom:24rpx;display:flex;align-items:center;gap:16rpx;font-size:26rpx;color:#FF6B35}.section-title{font-size:32rpx;font-weight:600;color:#333;margin:32rpx 0 16rpx}.video-card{background:#fff;border-radius:16rpx;overflow:hidden;margin-bottom:16rpx}.video-placeholder{height:400rpx;background:#333;display:flex;align-items:center;justify-content:center}.play-icon{font-size:60rpx;color:rgba(255,255,255,.8)}.video-info{padding:16rpx 20rpx}.v-title{font-size:28rpx;color:#333;display:block}.v-points{font-size:26rpx;color:#FF6B35;font-weight:600}.product-grid{display:grid;grid-template-columns:1fr 1fr;gap:16rpx}.p-card{background:#fff;border-radius:16rpx;overflow:hidden}.p-img{width:100%;height:260rpx}.p-name{font-size:26rpx;color:#333;padding:12rpx 16rpx 4rpx;display:block}.p-bottom{display:flex;align-items:baseline;gap:8rpx;padding:0 16rpx 16rpx}.p-points{font-size:30rpx;color:#FF6B35;font-weight:600}.p-cash{font-size:24rpx;color:#999}
.live-entry{display:flex;align-items:center;gap:20rpx;padding:24rpx;margin-bottom:24rpx;background:linear-gradient(135deg,#ff2d2d,#ff4444);border-radius:16rpx;position:relative;overflow:hidden}.live-badge{display:flex;align-items:center;gap:8rpx;padding:8rpx 16rpx;background:rgba(255,255,255,.2);border-radius:8rpx;font-size:22rpx;color:#fff;font-weight:700;flex-shrink:0}.live-dot{width:12rpx;height:12rpx;background:#fff;border-radius:50%;animation:ld-pulse .8s infinite}@keyframes ld-pulse{0%,100%{opacity:1}50%{opacity:.2}}.live-info{flex:1;min-width:0}.live-title{font-size:28rpx;color:#fff;font-weight:600;display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.live-sub{font-size:22rpx;color:rgba(255,255,255,.8);margin-top:4rpx;display:block}.live-cover{width:120rpx;height:80rpx;border-radius:8rpx;flex-shrink:0}
.ai-float-btn{position:fixed;right:24rpx;bottom:160rpx;width:100rpx;height:100rpx;background:#FF6B35;border-radius:50%;display:flex;align-items:center;justify-content:center;box-shadow:0 4rpx 16rpx rgba(255,107,53,.4);z-index:99}.ai-btn-icon{font-size:48rpx}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}.coupon-modal{background:#fff;border-radius:24rpx;padding:40rpx;width:80%}.modal-title{font-size:34rpx;font-weight:600;text-align:center;display:block;margin-bottom:24rpx}.coupon-item{display:flex;align-items:center;gap:16rpx;padding:20rpx;background:#FFF3EE;border-radius:12rpx;margin-bottom:16rpx}.c-name{font-size:26rpx;color:#333;flex:1}.c-value{font-size:32rpx;color:#FF6B35;font-weight:600}.c-draw{background:#FF6B35;color:#fff;padding:12rpx 24rpx;border-radius:24rpx;font-size:24rpx}.modal-close{text-align:center;padding:16rpx;color:#999;font-size:28rpx;margin-top:16rpx}
</style>
