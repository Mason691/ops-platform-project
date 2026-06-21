<template>
  <view class="live-page">
    <view class="live-header">
      <text class="live-title">🔥 直播广场</text>
      <text class="live-sub">点击进入视频号直播间，观看商品演示与优惠活动</text>
    </view>
    <view class="live-grid" v-if="rooms.length > 0">
      <view class="live-card" v-for="room in rooms" :key="room.id" @click="openLive(room)">
        <view class="card-cover">
          <image :src="assetUrl(room.coverUrl)" class="cover-img" mode="aspectFill" @error="onImgError" />
          <view class="live-badge" v-if="room.status === 1">
            <view class="badge-dot"></view>
            <text>直播中</text>
          </view>
          <view class="live-badge upcoming" v-else>
            <text>即将开始</text>
          </view>
        </view>
        <view class="card-info">
          <text class="card-title">{{ room.liveTitle }}</text>
          <text class="card-time" v-if="room.startTime">{{ fmtTime(room.startTime) }}</text>
        </view>
      </view>
    </view>
    <view class="empty" v-else>暂无直播，敬请期待 🎬</view>

    <!-- WeChat guide for non-WeChat browsers -->
    <view class="mask" v-if="showGuide" @click="showGuide=false">
      <view class="guide" @click.stop>
        <text class="guide-icon">📱</text>
        <text class="guide-title">请使用微信客户端打开</text>
        <text class="guide-desc">直播功能需在微信中打开，将为您跳转视频号直播间</text>
        <view class="guide-btn" @click="showGuide=false">知道了</view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { assetUrl, onImgError } from '@/utils/image.js'

const rooms = ref([])
const showGuide = ref(false)

function isWechat(){ return /micromessenger/i.test(navigator.userAgent) }

function fmtTime(t){
  if(!t) return ''
  const d = new Date(t)
  const pad = n => String(n).padStart(2,'0')
  return `${d.getMonth()+1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  try {
    const resp = await new Promise((resolve, reject) => {
      uni.request({
        url: '/api/ops/live-rooms/active',
        data: { limit: 20 },
        success(r) { if (r.data?.code === 200) resolve(r.data.data); else reject(r.data) },
        fail: reject
      })
    })
    rooms.value = resp || []
  } catch (_) {}
})

function openLive(room) {
  if (!isWechat()) { showGuide.value = true; return }
  if (!room.finderUsername) {
    uni.showToast({ title: '直播信息不完整，请联系管理员', icon: 'none' })
    return
  }

  // uni-app shadows global wx — reach through window.wx
  var realWx = window.wx || window.WX
            || (typeof wx !== 'undefined' && wx.openChannelsLive ? wx : null)

  if (realWx && typeof realWx.openChannelsLive === 'function') {
    realWx.openChannelsLive({
      finderUserName: room.finderUsername,
      success: function(){},
      fail: function(err) {
        console.warn('[Live] fail:', err)
        uni.showToast({ title: '请在微信中搜索视频号观看直播', icon: 'none', duration: 3000 })
      }
    })
  } else {
    uni.showToast({ title: '请在微信中搜索视频号 "'+room.finderUsername+'" 观看直播', icon: 'none', duration: 3000 })
  }
}
</script>

<style scoped>
.live-page{min-height:100vh;background:#f5f5f5;padding-bottom:40rpx}
.live-header{padding:32rpx 24rpx 24rpx;background:linear-gradient(135deg,#FF6B35,#ff4444);text-align:center}
.live-title{font-size:40rpx;color:#fff;font-weight:700;display:block}
.live-sub{font-size:24rpx;color:rgba(255,255,255,.8);margin-top:8rpx;display:block}
.live-grid{padding:24rpx;display:grid;grid-template-columns:1fr 1fr;gap:20rpx}
.live-card{background:#fff;border-radius:16rpx;overflow:hidden;box-shadow:0 2rpx 12rpx rgba(0,0,0,.06)}
.card-cover{position:relative}
.cover-img{width:100%;height:260rpx;display:block}
.live-badge{position:absolute;top:12rpx;left:12rpx;display:flex;align-items:center;gap:6rpx;padding:6rpx 16rpx;background:rgba(255,0,0,.85);border-radius:8rpx;font-size:22rpx;color:#fff}
.live-badge.upcoming{background:rgba(255,107,53,.85)}
.badge-dot{width:10rpx;height:10rpx;background:#fff;border-radius:50%;animation:pulse 1s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}
.card-info{padding:16rpx 20rpx}
.card-title{font-size:28rpx;color:#333;font-weight:600;display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.card-time{font-size:22rpx;color:#999;margin-top:6rpx;display:block}
.empty{text-align:center;padding:120rpx 0;color:#999;font-size:28rpx}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}
.guide{background:#fff;border-radius:24rpx;padding:48rpx 40rpx;width:80%;text-align:center}
.guide-icon{font-size:64rpx;display:block;margin-bottom:20rpx}
.guide-title{font-size:34rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}
.guide-desc{font-size:26rpx;color:#999;display:block;margin-bottom:32rpx;line-height:1.5}
.guide-btn{background:#FF6B35;color:#fff;padding:20rpx 48rpx;border-radius:48rpx;font-size:28rpx;display:inline-block}
</style>
