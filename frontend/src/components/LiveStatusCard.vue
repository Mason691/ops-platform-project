<template>
  <view class="lsc-card">
    <!-- ═══ Cover area with status overlay ═══ -->
    <view class="lsc-cover-wrap">
      <image
        :src="coverSrc"
        mode="aspectFill"
        class="lsc-cover"
        @error="coverSrc = ''"
      />
      <!-- Pulsing live indicator overlay -->
      <view v-if="displayStatus === 'live'" class="lsc-live-overlay">
        <view class="lsc-live-ring">
          <view class="lsc-live-ring-inner"></view>
        </view>
      </view>
      <!-- Status badge -->
      <view class="lsc-badge" :class="displayStatus">
        <view v-if="displayStatus === 'live'" class="lsc-badge-dot"></view>
        <text>{{ badgeText }}</text>
      </view>
    </view>

    <!-- ═══ Info area ═══ -->
    <view class="lsc-info">
      <text class="lsc-title">{{ liveRoom.liveTitle || '直播间' }}</text>

      <!-- Description (new) -->
      <text v-if="liveRoom.description" class="lsc-desc">{{ liveRoom.description }}</text>

      <!-- ═ Upcoming: countdown + remind ═ -->
      <view v-if="displayStatus === 'upcoming'" class="lsc-state">
        <view class="lsc-countdown">
          <view class="lsc-cd-item" v-if="cd.d > 0">
            <text class="lsc-cd-num">{{ cd.d }}</text>
            <text class="lsc-cd-label">天</text>
          </view>
          <view class="lsc-cd-item">
            <text class="lsc-cd-num">{{ pad(cd.h) }}</text>
            <text class="lsc-cd-label">时</text>
          </view>
          <view class="lsc-cd-item">
            <text class="lsc-cd-num">{{ pad(cd.m) }}</text>
            <text class="lsc-cd-label">分</text>
          </view>
          <view class="lsc-cd-item">
            <text class="lsc-cd-num">{{ pad(cd.s) }}</text>
            <text class="lsc-cd-label">秒</text>
          </view>
        </view>
        <text class="lsc-hint">预计 {{ fmtTime(liveRoom.startTime) }} 开播</text>

        <!-- Remind button -->
        <view class="lsc-remind-wrap">
          <view
            v-if="reminded"
            class="lsc-remind-btn reminded"
          >
            <text>✓ 已设置提醒</text>
          </view>
          <view
            v-else
            class="lsc-remind-btn"
            :class="{ loading: reminding }"
            @click="doRemind"
          >
            <text>{{ reminding ? '设置中...' : '🔔 开播提醒' }}</text>
          </view>
          <text class="lsc-remind-hint">开播前 5 分钟将通过微信提醒你</text>
        </view>
      </view>

      <!-- ═ Live: viewer count + watch button ═ -->
      <view v-else-if="displayStatus === 'live'" class="lsc-state">
        <text class="lsc-viewers" v-if="(liveStatus.viewerCount || 0) > 0">
          🔥 {{ liveStatus.viewerCount }} 人正在观看
        </text>
        <text v-else class="lsc-viewers lsc-viewers-placeholder">
          🔴 正在直播
        </text>
        <view class="lsc-watch-btn" @click="openLive">
          <text>📺 点击观看直播</text>
        </view>
        <text class="lsc-hint">将在微信视频号中打开</text>
      </view>

      <!-- ═ Ended: replay or finished ═ -->
      <view v-else class="lsc-state">
        <text class="lsc-ended-text">直播已结束</text>
        <view
          v-if="liveStatus.hasReplay"
          class="lsc-watch-btn replay"
          @click="openReplay"
        >
          <text>▶ 观看回放</text>
        </view>
        <view v-else class="lsc-watch-btn ended" disabled>
          <text>暂无回放</text>
        </view>
        <!-- Link to live square -->
        <text class="lsc-more-link" @click="goLiveSquare">更多直播 ›</text>
      </view>
    </view>

    <!-- ═══ Non-WeChat guide overlay with QR code ═══ -->
    <view class="lsc-mask" v-if="showGuide" @click="showGuide = false">
      <view class="lsc-guide" @click.stop>
        <text class="lsc-guide-icon">📱</text>
        <text class="lsc-guide-title">请在微信客户端打开</text>
        <text class="lsc-guide-desc">直播功能需在微信中打开，将为您跳转视频号直播间</text>
        <!-- QR code via Google Charts -->
        <image
          v-if="qrUrl"
          :src="qrUrl"
          class="lsc-qr"
          mode="aspectFit"
          @error="qrError = true"
        />
        <text class="lsc-guide-desc">请使用微信扫一扫观看直播</text>
        <view class="lsc-guide-btn" @click="showGuide = false">知道了</view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { BASE_URL } from '@/config'
import { assetUrl } from '@/utils/image.js'
import { openChannelsLive } from '@/utils/jssdk.js'

const props = defineProps({
  liveRoom: { type: Object, required: true },
  liveStatus: { type: Object, required: true },
  activityTitle: { type: String, default: '' }
})

const showGuide = ref(false)
const coverSrc = ref('')
const reminded = ref(false)
const reminding = ref(false)

// Countdown state
const cd = ref({ d: 0, h: 0, m: 0, s: 0 })
let countdownTimer = null

const displayStatus = computed(() => props.liveStatus?.displayStatus || 'upcoming')

const badgeText = computed(() => {
  switch (displayStatus.value) {
    case 'live': return '直播中'
    case 'ended': return '已结束'
    default: return '预告'
  }
})

const qrError = ref(false)
// QR code URL for non-WeChat fallback (always use prod URL for scannability)
const qrUrl = computed(() => {
  if (qrError.value) return ''
  return 'https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=' +
    encodeURIComponent('https://boss.jdzn99.com/ops/#/pages/live/index')
})

onMounted(() => {
  coverSrc.value = assetUrl(props.liveRoom?.coverUrl) || ''
  if (displayStatus.value === 'upcoming') {
    startCountdown()
  }
  // Check if already reminded (localStorage per liveRoomId)
  if (props.liveRoom?.id) {
    reminded.value = uni.getStorageSync('lr_remind_' + props.liveRoom.id) === '1'
  }
})

onBeforeUnmount(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})

// ── Countdown ──

function startCountdown() {
  let remaining = props.liveStatus?.countdownMs || 0
  if (remaining <= 0) return
  const tick = () => {
    cd.value = msToDHMS(remaining)
    if (remaining > 0) remaining -= 1000
    else { cd.value = { d: 0, h: 0, m: 0, s: 0 }; clearInterval(countdownTimer) }
  }
  tick()
  countdownTimer = setInterval(tick, 1000)
}

function msToDHMS(ms) {
  const totalSec = Math.floor(ms / 1000)
  const d = Math.floor(totalSec / 86400)
  const h = Math.floor((totalSec % 86400) / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  return { d, h, m, s }
}

function pad(n) { return String(n).padStart(2, '0') }

function fmtTime(t) {
  if (!t) return ''
  const d = new Date(t)
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// ── Remind me ──

async function doRemind() {
  if (reminding.value || reminded.value) return
  reminding.value = true

  try {
    // Attempt WeChat subscribe message
    if (typeof wx !== 'undefined' && typeof wx.requestSubscribeMessage === 'function') {
      try {
        await new Promise((resolve, reject) => {
          wx.requestSubscribeMessage({
            tmplIds: [], // Template IDs configured in WeChat official account
            success: resolve,
            fail: reject
          })
        })
      } catch (_) {
        // User declined subscribe — still save remind on server
      }
    }

    // Save remind record via API
    const token = uni.getStorageSync('ops_token') || ''
    await new Promise((resolve, reject) => {
      uni.request({
        url: '/api/ops/live-rooms/' + props.liveRoom.id + '/remind',
        method: 'POST',
        header: token ? { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' },
        data: {},
        success(r) {
          if (r.data && r.data.code === 200) resolve(r.data.data)
          else reject(r.data)
        },
        fail: reject
      })
    })

    reminded.value = true
    uni.setStorageSync('lr_remind_' + props.liveRoom.id, '1')
    uni.showToast({ title: '已设置开播提醒', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e?.msg || '设置失败，请重试', icon: 'none' })
  } finally {
    reminding.value = false
  }
}

// ── Open live (wx.openChannelsLive) ──

function isWechat() { return /micromessenger/i.test(navigator.userAgent) }

function openLive() {
  if (!isWechat()) { qrError.value = false; showGuide.value = true; return }
  if (props.liveRoom?.liveLink) { window.location.href = props.liveRoom.liveLink; return }
  if (props.liveRoom?.finderUsername) { openChannelsLive(props.liveRoom.finderUsername); return }
  if (props.liveRoom?.jumpUrl) { window.location.href = props.liveRoom.jumpUrl; return }
  uni.showToast({ title: '直播链接未配置', icon: 'none' })
}

function openReplay() {
  const url = props.liveRoom?.replayUrl
  if (!url) return
  // #ifdef H5
  window.open(url, '_blank')
  // #endif
  // #ifdef MP-WEIXIN
  uni.navigateTo({ url: '/pages/webview/index?url=' + encodeURIComponent(url) })
  // #endif
}

function goLiveSquare() {
  uni.navigateTo({ url: '/pages/live/index' })
}
</script>

<style scoped>
.lsc-card {
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  box-shadow: 0 4rpx 24rpx rgba(0,0,0,.08);
}

/* ── Cover ── */
.lsc-cover-wrap {
  position: relative;
  width: 100%;
  height: 380rpx;
}
.lsc-cover {
  width: 100%;
  height: 100%;
  display: block;
  background: linear-gradient(135deg, #f0f0f0, #e8e8e8);
}

/* Live overlay glow */
.lsc-live-overlay {
  position: absolute;
  top: 20rpx;
  right: 20rpx;
}
.lsc-live-ring {
  width: 60rpx; height: 60rpx;
  border-radius: 50%;
  background: rgba(255, 0, 0, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
}
.lsc-live-ring-inner {
  width: 16rpx; height: 16rpx;
  border-radius: 50%;
  background: #ff0000;
  animation: lsc-ring-pulse 1.5s ease-in-out infinite;
}
@keyframes lsc-ring-pulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(3); opacity: 0.3; }
}

/* Status badge */
.lsc-badge {
  position: absolute;
  top: 16rpx;
  left: 16rpx;
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 8rpx 20rpx;
  border-radius: 10rpx;
  font-size: 24rpx;
  font-weight: 600;
  color: #fff;
}
.lsc-badge.upcoming { background: rgba(255, 107, 53, 0.9); }
.lsc-badge.live { background: rgba(255, 0, 0, 0.88); }
.lsc-badge.ended { background: rgba(0, 0, 0, 0.6); }

.lsc-badge-dot {
  width: 12rpx; height: 12rpx;
  background: #fff;
  border-radius: 50%;
  animation: lsc-pulse 1s infinite;
}
@keyframes lsc-pulse { 0%,100%{opacity:1} 50%{opacity:.3} }

/* ── Info ── */
.lsc-info { padding: 28rpx 28rpx 32rpx; }
.lsc-title {
  font-size: 34rpx;
  font-weight: 700;
  color: #222;
  display: block;
  margin-bottom: 12rpx;
  line-height: 1.4;
}

/* Description */
.lsc-desc {
  font-size: 26rpx;
  color: #666;
  line-height: 1.6;
  margin-bottom: 24rpx;
  display: block;
  white-space: pre-line;
}

.lsc-state { display: flex; flex-direction: column; align-items: center; gap: 16rpx; }

/* Countdown */
.lsc-countdown { display: flex; gap: 12rpx; }
.lsc-cd-item {
  display: flex; flex-direction: column; align-items: center;
  background: #FFF5F0; border-radius: 12rpx; padding: 14rpx 18rpx; min-width: 84rpx;
}
.lsc-cd-num { font-size: 38rpx; font-weight: 700; color: #FF6B35; line-height: 1.2; }
.lsc-cd-label { font-size: 20rpx; color: #999; margin-top: 4rpx; }

.lsc-hint { font-size: 24rpx; color: #999; }

/* Remind button */
.lsc-remind-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  margin-top: 8rpx;
}
.lsc-remind-btn {
  padding: 18rpx 48rpx;
  border-radius: 48rpx;
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  color: #fff;
  font-size: 28rpx;
  font-weight: 600;
  cursor: pointer;
}
.lsc-remind-btn.reminded {
  background: #e0e0e0;
  color: #999;
}
.lsc-remind-btn.loading {
  opacity: 0.7;
}
.lsc-remind-hint {
  font-size: 22rpx;
  color: #bbb;
}

/* Viewers */
.lsc-viewers { font-size: 28rpx; color: #FF6B35; font-weight: 600; }
.lsc-viewers-placeholder { color: #ff0000; }

/* Watch button */
.lsc-watch-btn {
  display: flex; align-items: center; justify-content: center;
  width: 100%; padding: 26rpx 0;
  background: linear-gradient(135deg, #FF6B35, #ff4444);
  border-radius: 48rpx;
  font-size: 32rpx; font-weight: 600; color: #fff;
  cursor: pointer;
  box-shadow: 0 8rpx 20rpx rgba(255, 107, 53, 0.3);
}
.lsc-watch-btn.replay { background: linear-gradient(135deg, #07C160, #06AD56); box-shadow: 0 8rpx 20rpx rgba(7, 193, 96, 0.3); }
.lsc-watch-btn.ended { background: #e0e0e0; color: #999; box-shadow: none; }

.lsc-ended-text { font-size: 28rpx; color: #999; }

/* More link */
.lsc-more-link {
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #FF6B35;
  cursor: pointer;
}

/* ── Non-WeChat guide overlay ── */
.lsc-mask {
  position: fixed; inset: 0;
  background: rgba(0,0,0,.5);
  display: flex; align-items: center; justify-content: center;
  z-index: 999;
}
.lsc-guide {
  background: #fff; border-radius: 24rpx;
  padding: 48rpx 40rpx; width: 84%; max-width: 560rpx; text-align: center;
}
.lsc-guide-icon { font-size: 64rpx; display: block; margin-bottom: 20rpx; }
.lsc-guide-title { font-size: 34rpx; font-weight: 600; color: #333; display: block; margin-bottom: 12rpx; }
.lsc-guide-desc { font-size: 26rpx; color: #999; display: block; margin-bottom: 24rpx; line-height: 1.5; }
.lsc-qr {
  width: 200rpx; height: 200rpx;
  margin: 0 auto 20rpx;
  display: block;
  border: 2rpx solid #eee;
  border-radius: 12rpx;
}
.lsc-guide-btn {
  background: #FF6B35; color: #fff;
  padding: 20rpx 48rpx; border-radius: 48rpx;
  font-size: 28rpx; display: inline-block;
  cursor: pointer;
}
</style>
