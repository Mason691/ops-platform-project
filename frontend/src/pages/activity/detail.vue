<template>
  <view class="page" v-if="act">
    <!-- ═══ Cover — video or image ═══ -->
    <view class="cover-area">
      <view v-if="videoSrc && !videoFailed" class="cover-video-wrap">
        <video
          :key="videoSrc"
          :src="videoSrc"
          class="cover-video"
          controls
          playsinline
          webkit-playsinline
          x5-video-player-type="h5"
          x5-video-player-fullscreen="true"
          x5-video-orientation="portrait"
          show-center-play-btn
          enable-progress-gesture
          :poster="assetUrl(act.videoCoverUrl || act.coverUrl || act.coverImage)"
          @error="onVideoError"
          @loadeddata="onVideoLoaded"
        />
      </view>
      <view v-else-if="videoFailed" class="cover-fallback" @click="retryVideo">
        <image
          :src="assetUrl(act.coverUrl || act.coverImage)"
          class="cover-img"
          mode="aspectFill"
          @error="onImgError"
        />
        <view class="fallback-overlay">
          <text class="fallback-icon">▶</text>
          <text class="fallback-text">{{ videoErrorInfo || '视频暂时无法播放' }}</text>
          <text class="fallback-hint">点击重试</text>
        </view>
      </view>
      <image
        v-else
        :src="assetUrl(act.coverUrl || act.coverImage) || '/static/gas-default.png'"
        class="cover-img"
        mode="aspectFill"
      />
      <!-- Status badge -->
      <view class="hero-badge" :class="badgeClass">{{ statusText }}</view>
    </view>

    <!-- ═══ Live room — prominent below cover for live_reserve ═══ -->
    <view v-if="liveRoom && liveStatus" class="live-section section">
      <LiveStatusCard :liveRoom="liveRoom" :liveStatus="liveStatus" :activityTitle="act?.title" />
      <!-- 视频号主页入口 -->
      <view v-if="liveRoom?.finderUsername" class="channel-home-entry" @click="openChannelHome">
        <text class="channel-home-icon">🏠</text>
        <text class="channel-home-text">前往视频号主页</text>
        <text class="channel-home-arrow">›</text>
      </view>
    </view>

    <!-- ═══ Info ═══ -->
    <view class="section">
      <text class="title">{{ act.title }}</text>

      <view class="tags-row">
        <view class="t-tag cat">{{ categoryLabel(act.category) }}</view>
        <view v-if="act.pointsReward > 0" class="t-tag points">🎁 +{{ act.pointsReward }} 积分</view>
      </view>

      <view class="meta-list">
        <view class="meta-item">
          <text class="m-label">活动时间</text>
          <text class="m-value">{{ fmtFull(act.startTime) }} ~ {{ fmtFull(act.endTime) }}</text>
        </view>
        <view v-if="act.location" class="meta-item">
          <text class="m-label">活动地点</text>
          <text class="m-value">{{ act.location }}</text>
        </view>
        <view v-if="act.maxParticipants" class="meta-item">
          <text class="m-label">参与人数</text>
          <text class="m-value">限 {{ act.maxParticipants }} 人</text>
        </view>
      </view>
    </view>

    <!-- ═══ Detail images gallery ═══ -->
    <view v-if="detailImages.length" class="section">
      <text class="sec-title">活动图片</text>
      <scroll-view scroll-x class="img-gallery" :show-scrollbar="false">
        <image
          v-for="(url, i) in detailImages"
          :key="i"
          :src="assetUrl(url)"
          class="gallery-img"
          mode="aspectFill"
          @click="previewImages(i)"
        />
      </scroll-view>
    </view>

    <!-- ═══ Associated products ═══ -->
    <view v-if="products.length" class="section">
      <text class="sec-title">关联商品</text>
      <scroll-view scroll-x class="prod-scroll" :show-scrollbar="false">
        <view v-for="p in products" :key="p.product_id" class="prod-card">
          <image :src="assetUrl(p.cover_image || p.coverImage)" class="prod-img" mode="aspectFill" @click="goProduct(p)" @error="onImgError"/>
          <text class="prod-name">{{ p.name }}</text>
          <view class="prod-price">
            <text v-if="(p.points_price || p.points || 0) > 0" class="p-points">{{ p.points_price || p.points }}积分</text>
            <text v-else class="p-points p-placeholder">0积分</text>
            <text v-if="(p.cash_price || 0) > 0" class="p-cash">¥{{ p.cash_price }}</text>
            <text v-else class="p-cash p-placeholder">¥0</text>
          </view>
          <view class="prod-buy-btn" @click.stop="goProduct(p)">立即抢购</view>
        </view>
      </scroll-view>
    </view>

    <!-- ═══ Detail text ═══ -->
    <view v-if="act.detail" class="section">
      <text class="sec-title">活动详情</text>
      <view class="detail-text"><rich-text :nodes="act.detail" /></view>
    </view>

    <view style="height:140rpx" />

    <!-- ═══ Bottom bar ═══ -->
    <view class="bottom-bar">
      <view class="share-btn" @click="doShare">
        <text class="share-icon">↗</text>
        <text>分享</text>
      </view>
      <view class="action-btn" :class="btnClass" @click="handleAction">
        {{ btnText }}
      </view>
    </view>

    <!-- ═══ Non-WeChat guide overlay ═══ -->
    <view class="guide-mask" v-if="showGuide" @click="showGuide = false">
      <view class="guide-card" @click.stop>
        <text class="guide-icon">📱</text>
        <text class="guide-title">请在微信客户端打开</text>
        <text class="guide-desc">请使用微信扫描二维码，在微信中打开视频号直播间</text>
        <image
          v-if="qrUrl"
          :src="qrUrl"
          class="guide-qr"
          mode="aspectFit"
          @error="qrError = true"
        />
        <text class="guide-desc-sm">扫描二维码进入直播广场，选择正在直播的直播间观看</text>
        <view class="guide-btn" @click="showGuide = false">知道了</view>
      </view>
    </view>
  </view>

  <!-- Loading / Error -->
  <view v-else class="empty-page">
    <text>{{ loadErr || '加载中...' }}</text>
  </view>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { BASE_URL } from '@/config'
import { assetUrl, onImgError } from '@/utils/image.js'
import LiveStatusCard from '@/components/LiveStatusCard.vue'
import { openChannelsLive, openChannelsProfile } from '@/utils/jssdk.js'

const act = ref(null), liveRoom = ref(null), liveStatus = ref(null), products = ref([])
const loadErr = ref('')
const booked = ref(false)
const videoFailed = ref(false)
const videoErrorInfo = ref('')
const showGuide = ref(false)
let pollTimer = null, pollInterval = 30000
let lastDisplayStatus = null

onMounted(async () => {
  const pages = getCurrentPages()
  const id = pages[pages.length - 1]?.options?.id
  if (!id) { loadErr.value = '缺少活动ID'; return }
  await fetchDetail(id)
  checkBooked(Number(id))
  // Start polling if live or upcoming within 10min
  if (liveStatus.value) {
    lastDisplayStatus = liveStatus.value.displayStatus
    schedulePolling(id)
  }
})

// Pause polling when page hidden, resume on show
let pageHidden = false
uni.$on?.('onHide', () => { pageHidden = true })
uni.$on?.('onShow', async () => {
  if (!pageHidden) return
  pageHidden = false
  const pages = getCurrentPages()
  const id = pages[pages.length - 1]?.options?.id
  if (!id) return
  await fetchDetail(id)
  schedulePolling(id)
})

onBeforeUnmount(() => {
  stopPolling()
})

async function fetchDetail(activityId) {
  try {
    const res = await req('/api/ops/activity/' + activityId)
    act.value = res.activity
    liveRoom.value = res.liveRoom || null
    products.value = res.products || []
    // Detect state transition
    if (res.liveStatus) {
      const prev = lastDisplayStatus
      const cur = res.liveStatus.displayStatus
      if (prev && prev !== cur) {
        if (prev === 'upcoming' && cur === 'live') {
          uni.showToast({ title: '直播已开始！🎉', icon: 'success', duration: 2500 })
        } else if (prev === 'live' && cur === 'ended') {
          uni.showToast({ title: '直播已结束', icon: 'none', duration: 2000 })
        }
      }
      liveStatus.value = res.liveStatus
      lastDisplayStatus = cur
    }
  } catch (e) {
    loadErr.value = '加载失败，请返回重试'
    console.error(e)
  }
}

function schedulePolling(activityId) {
  if (!liveStatus.value) return
  const ds = liveStatus.value.displayStatus
  if (ds === 'live') {
    pollInterval = 15000 // 15s during live
  } else if (ds === 'upcoming') {
    // Only poll if within 10 minutes of start time
    if (liveStatus.value.countdownMs && liveStatus.value.countdownMs > 600_000) return
    pollInterval = 30000
  } else {
    return // ended — no polling
  }
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const res = await req('/api/ops/activity/' + activityId)
      if (res.liveStatus) {
        const prev = liveStatus.value?.displayStatus
        liveStatus.value = res.liveStatus
        const cur = res.liveStatus.displayStatus
        if (prev === 'upcoming' && cur === 'live') {
          uni.showToast({ title: '直播已开始！🎉', icon: 'success', duration: 2500 })
          // Switch to faster polling interval
          stopPolling()
          schedulePolling(activityId)
        }
        if (cur !== 'live' && cur !== 'upcoming') {
          stopPolling()
        }
      }
    } catch (_) {}
  }, pollInterval)
}

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function checkBooked(activityId) {
  const token = uni.getStorageSync('ops_token')
  if (!token) return
  try {
    const bookings = await req('/api/ops/my-bookings')
    booked.value = (bookings || []).some(b => b.activityId === activityId && b.status === 0)
  } catch (_) {}
}

// ── Derived ──
const videoSrc = computed(() => {
  if (videoFailed.value) return ''
  const raw = act.value?.detailVideoUrl || act.value?.videoUrl
  if (!raw || raw === 'null' || raw === 'None') return ''
  // Trim whitespace and wrapping quotes sometimes present in DB data
  let url = String(raw).trim()
  if ((url.startsWith("'") && url.endsWith("'")) || (url.startsWith('"') && url.endsWith('"')))
    url = url.slice(1, -1)
  // Resolve to absolute HTTPS URL — iOS WebKit <video> requires absolute URLs
  url = assetUrl(url)
  if (url && !/^https?:\/\//.test(url)) {
    const origin = typeof location !== 'undefined' ? location.origin : 'https://test.jdzn99.com'
    url = origin + url
  }
  return url
})
const detailImages = computed(() => {
  if (!act.value) return []
  const di = act.value.detailImages
  if (typeof di === 'string') {
    try { return JSON.parse(di) } catch (_) { return [] }
  }
  return Array.isArray(di) ? di : []
})

const qrError = ref(false)
/** QR code for non-WeChat fallback — 扫码进入直播广场 (always use prod URL for scannability) */
const qrUrl = computed(() => {
  if (qrError.value) return ''
  return 'https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=' +
    encodeURIComponent('https://boss.jdzn99.com/ops/#/pages/live/index')
})

const now = computed(() => new Date())
const statusText = computed(() => {
  if (!act.value) return ''
  const s = act.value.startTime ? new Date(act.value.startTime.replace(/-/g, '/')) : null
  const e = act.value.endTime ? new Date(act.value.endTime.replace(/-/g, '/')) : null
  if (!s || !e) return '进行中'
  if (now.value < s) return '即将开始'
  if (now.value > e || act.value.status === 0) return '已结束'
  return '进行中'
})
const badgeClass = computed(() => {
  if (statusText.value === '进行中') return 'badge-live'
  if (statusText.value === '即将开始') return 'badge-upcoming'
  return 'badge-ended'
})
const btnText = computed(() => {
  if (act.value?.type === 'live_reserve') {
    if (liveStatus.value?.displayStatus === 'live') return '📺 观看直播'
    if (liveStatus.value?.displayStatus === 'ended') return '直播已结束'
    if (booked.value) return '已预约 ✓'
    return '预约参加'
  }
  if (booked.value) return '已预约 ✓'
  if (statusText.value === '即将开始') return '预约参加'
  if (statusText.value === '进行中') return '立即参与'
  return '已结束'
})
const btnClass = computed(() => {
  if (act.value?.type === 'live_reserve') {
    if (liveStatus.value?.displayStatus === 'live') return 'btn-live'
    if (liveStatus.value?.displayStatus === 'ended') return 'btn-disabled'
    if (booked.value) return 'btn-done'
    return 'btn-active'
  }
  if (booked.value) return 'btn-done'
  if (statusText.value === '已结束') return 'btn-disabled'
  return 'btn-active'
})

// ── Actions ──
function handleAction() {
  // Live reserve — all states handled via liveStatus
  if (act.value?.type === 'live_reserve') {
    const ds = liveStatus.value?.displayStatus
    if (ds === 'ended') {
      uni.showToast({ title: '直播已结束', icon: 'none' })
      return
    }
    if (ds === 'live') {
      openLiveRoom()
      return
    }
    // upcoming — book (skip if already booked)
    if (booked.value) return
    const token = uni.getStorageSync('ops_token')
    if (!token) { uni.navigateTo({ url: '/pages/auth/login' }); return }
    doBook()
    return
  }
  if (statusText.value === '已结束' || booked.value) return
  const token = uni.getStorageSync('ops_token')
  if (!token) { uni.navigateTo({ url: '/pages/auth/login' }); return }
  doBook()
}

function openLiveRoom() {
  if (!liveRoom.value?.finderUsername && !liveRoom.value?.jumpUrl) {
    uni.showToast({ title: '直播信息不完整', icon: 'none' })
    return
  }
  if (liveStatus.value?.displayStatus === 'ended') {
    uni.showToast({ title: '直播已结束', icon: 'none' })
    return
  }
  if (!/micromessenger/i.test(navigator.userAgent)) {
    qrError.value = false
    showGuide.value = true
    return
  }

  if (liveRoom.value?.jumpUrl) { window.location.href = liveRoom.value.jumpUrl; return }
  openChannelsLive(liveRoom.value.finderUsername)
}

function openChannelHome() {
  if (!liveRoom.value?.finderUsername && !liveRoom.value?.jumpUrl) {
    uni.showToast({ title: '视频号信息不完整', icon: 'none' })
    return
  }
  if (!/micromessenger/i.test(navigator.userAgent)) {
    qrError.value = false
    showGuide.value = true
    return
  }

  if (liveRoom.value?.jumpUrl) { window.location.href = liveRoom.value.jumpUrl; return }
  openChannelsProfile(liveRoom.value.finderUsername)
}

async function doBook() {
  try {
    await req('/api/ops/activity/' + act.value.id + '/book', 'POST')
    booked.value = true
    uni.showToast({ title: '预约成功！', icon: 'success', duration: 2000 })
  } catch (e) {
    uni.showToast({ title: e?.msg || '预约失败', icon: 'none' })
  }
}

function retryVideo() {
  videoFailed.value = false
  videoErrorInfo.value = ''
}

function onVideoLoaded() {
  console.log('[activity-detail] 视频加载成功:', videoSrc.value)
}

function onVideoError(e) {
  // Collect ALL possible error info — uni-app H5 uses standard DOM events
  const target = e.target || (e.detail && e.detail.target)
  const mediaErr = target?.error
  const code = mediaErr?.code || e.detail?.error?.code || e.detail?.code || e.detail?.errCode
  const codes = {1:'MEDIA_ERR_ABORTED', 2:'MEDIA_ERR_NETWORK', 3:'MEDIA_ERR_DECODE', 4:'MEDIA_ERR_SRC_NOT_SUPPORTED'}
  const msg = codes[code] || mediaErr?.message || e.detail?.errMsg || e.detail?.error?.message || '未知错误'

  // Log everything for debugging
  console.error('[activity-detail] 视频加载失败:', {
    videoUrl: videoSrc.value,
    errorCode: code,
    errorMsg: msg,
    mediaError: mediaErr ? {code: mediaErr.code, message: mediaErr.message} : null,
    eventKeys: Object.keys(e),
    detailKeys: e.detail ? Object.keys(e.detail) : null,
    platform: uni.getSystemInfoSync?.()?.platform || 'unknown'
  })
  videoFailed.value = true
  videoErrorInfo.value = msg
}

function doShare() {
  // Use JSSDK if available
  if (typeof wx !== 'undefined' && wx.ready && typeof wx.updateAppMessageShareData === 'function') {
    wx.ready(function() {
      wx.updateAppMessageShareData({
        title: act.value?.title || '精彩活动',
        desc: (act.value?.detail || '').substring(0, 50),
        link: location.href,
        imgUrl: act.value?.coverUrl || act.value?.coverImage || ''
      })
    })
    uni.showToast({ title: '已设置分享信息', icon: 'success' })
  } else {
    // Fallback: show tip
    uni.showToast({ title: '点击右上角 ··· 分享给朋友', icon: 'none', duration: 2500 })
  }
}

function goProduct(p) {
  if (!p.miniapp_app_id) {
    uni.showToast({ title: '暂不支持小程序跳转', icon: 'none' })
    return
  }
  const url = (p.miniapp_path || '/pages/index/index').replace('{productId}', p.product_id)
  // Use window.wx explicitly — uni-app shadows bare `wx` in H5 mode
  const realWx = window.wx || (typeof wx !== 'undefined' ? wx : null)
  if (realWx && realWx.miniProgram && typeof realWx.miniProgram.navigateTo === 'function') {
    realWx.miniProgram.navigateTo({
      url: url,
      appId: p.miniapp_app_id,
      success: () => {},
      fail: (err) => { console.error('[goProduct] 跳转失败:', err); uni.showToast({ title: '跳转失败', icon: 'none' }) }
    })
  } else if (typeof WeixinJSBridge !== 'undefined' && WeixinJSBridge.invoke) {
    WeixinJSBridge.invoke('navigateToMiniProgram', { appId: p.miniapp_app_id, path: url }, function(res) {
      if (res.err_msg && res.err_msg.indexOf('ok') === -1) {
        uni.showToast({ title: '跳转失败', icon: 'none' })
      }
    })
  } else {
    uni.showToast({ title: '请在小程序中打开', icon: 'none' })
  }
}

function previewImages(index) {
  const urls = detailImages.value.map(u => assetUrl(u))
  uni.previewImage({
    urls,
    current: urls[index] || urls[0]
  })
}

// ── Helpers ──
function fmtFull(t) {
  if (!t) return ''
  return t.substring(0, 16).replace(' ', ' ')
}
function categoryLabel(v) {
  const m = { OFFLINE: '线下', FAMILY: '亲子', POINTS: '积分' }
  return m[v] || v || '其他'
}

function req(url, method, data) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('ops_token') || ''
    const header = token
      ? { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' }
      : { 'Content-Type': 'application/json' }
    uni.request({
      url, method: method || 'GET', data: data || {}, header,
      success(r) {
        if (r.data && r.data.code === 200) resolve(r.data.data)
        else reject(r.data)
      },
      fail: reject
    })
  })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding-bottom: 140rpx; }

/* Cover */
.cover-area { position: relative; width: 100%; height: 500rpx; background: #000; }
.cover-img { width: 100%; height: 100%; }
.cover-video-wrap { width: 100%; height: 100%; }
.cover-video { width: 100%; height: 100%; }
.cover-fallback { position: relative; width: 100%; height: 100%; }
.fallback-overlay { position: absolute; inset: 0; background: rgba(0,0,0,.55); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12rpx; }
.fallback-icon { font-size: 56rpx; color: rgba(255,255,255,.9); }
.fallback-text { font-size: 26rpx; color: rgba(255,255,255,.85); }
.fallback-hint { font-size: 22rpx; color: rgba(255,255,255,.55); }
.hero-badge {
  position: absolute; top: 20rpx; left: 20rpx;
  padding: 8rpx 24rpx; border-radius: 12rpx; font-size: 24rpx; color: #fff;
}
.badge-live { background: #FF6B35; }
.badge-upcoming { background: #4CAF50; }
.badge-ended { background: #999; }

/* Sections */
.section {
  background: #fff;
  margin: 20rpx 24rpx;
  padding: 28rpx;
  border-radius: 16rpx;
}
.title { font-size: 34rpx; font-weight: 600; color: #222; line-height: 1.4; }
.sec-title { font-size: 30rpx; font-weight: 600; color: #333; margin-bottom: 20rpx; display: block; }

/* Tags */
.tags-row { display: flex; gap: 12rpx; margin-top: 20rpx; }
.t-tag { padding: 6rpx 20rpx; border-radius: 8rpx; font-size: 24rpx; }
.t-tag.cat { background: #EEF2FF; color: #6366F1; }
.t-tag.points { background: #FFF3EE; color: #FF6B35; }

/* Meta */
.meta-list { margin-top: 24rpx; }
.meta-item { display: flex; justify-content: space-between; padding: 16rpx 0; border-bottom: 1rpx solid #f5f5f5; }
.m-label { font-size: 26rpx; color: #999; }
.m-value { font-size: 26rpx; color: #333; text-align: right; flex: 1; }

/* Image gallery */
.img-gallery { white-space: nowrap; }
.gallery-img { display: inline-block; width: 200rpx; height: 200rpx; margin-right: 16rpx; border-radius: 12rpx; }

/* Detail text */
.detail-text { font-size: 28rpx; color: #666; line-height: 1.8; }

/* Bottom bar */
.bottom-bar {
  position: fixed; bottom: 0; left: 0; right: 0;
  display: flex; gap: 16rpx; align-items: center;
  padding: 16rpx 32rpx calc(env(safe-area-inset-bottom) + 16rpx);
  background: #fff; border-top: 1rpx solid #eee;
}
.share-btn {
  width: 120rpx; height: 80rpx; border-radius: 40rpx;
  border: 2rpx solid #ddd; display: flex; flex-direction: column;
  align-items: center; justify-content: center; font-size: 22rpx; color: #666;
}
.share-icon { font-size: 28rpx; }
.action-btn {
  flex: 1; height: 80rpx; border-radius: 40rpx;
  display: flex; align-items: center; justify-content: center;
  font-size: 30rpx; font-weight: 600; color: #fff;
}
.btn-active { background: #FF6B35; }
.btn-live { background: linear-gradient(135deg, #FF6B35, #ff4444); }
.btn-done { background: #ccc; }
.btn-disabled { background: #bbb; color: #fff; }

.empty-page { display: flex; align-items: center; justify-content: center; height: 100vh; color: #999; font-size: 28rpx; }

/* ── Product scroll ── */
.prod-scroll { white-space: nowrap; }
.prod-card { display: inline-block; width: 200rpx; margin-right: 20rpx; background: #f9f9f9; border-radius: 12rpx; overflow: hidden; }
.prod-img { width: 200rpx; height: 200rpx; }
.prod-name { display: block; padding: 8rpx 12rpx; font-size: 24rpx; color: #333; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.prod-price { display: flex; gap: 8rpx; padding: 0 12rpx 12rpx; align-items: baseline; }
.p-points { font-size: 24rpx; color: #FF6B35; font-weight: 600; }
.p-cash { font-size: 22rpx; color: #999; }
.p-placeholder { visibility: hidden; }
.prod-buy-btn {
  margin: 8rpx 12rpx 12rpx;
  padding: 8rpx 0;
  background: #FF6B35;
  color: #fff;
  font-size: 22rpx;
  text-align: center;
  border-radius: 8rpx;
}

/* ── Live section highlight ── */
.live-section { background: linear-gradient(135deg, #fff5f0, #fff); border: 2rpx solid rgba(255, 107, 53, 0.15); padding: 0; }

/* ── Channel homepage entry ── */
.channel-home-entry {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  padding: 24rpx 28rpx 32rpx;
  border-top: 1rpx solid rgba(255, 107, 53, 0.12);
  cursor: pointer;
}
.channel-home-icon { font-size: 28rpx; }
.channel-home-text { font-size: 28rpx; color: #FF6B35; font-weight: 500; }
.channel-home-arrow { font-size: 32rpx; color: #FF6B35; margin-left: 4rpx; }

/* ── Non-WeChat guide overlay ── */
.guide-mask {
  position: fixed; inset: 0;
  background: rgba(0,0,0,.5);
  display: flex; align-items: center; justify-content: center;
  z-index: 999;
}
.guide-card {
  background: #fff; border-radius: 24rpx;
  padding: 48rpx 40rpx; width: 84%; max-width: 560rpx; text-align: center;
}
.guide-icon { font-size: 64rpx; display: block; margin-bottom: 20rpx; }
.guide-title { font-size: 34rpx; font-weight: 600; color: #333; display: block; margin-bottom: 12rpx; }
.guide-desc { font-size: 26rpx; color: #999; display: block; margin-bottom: 16rpx; line-height: 1.5; }
.guide-desc-sm { font-size: 22rpx; color: #bbb; display: block; margin-bottom: 16rpx; line-height: 1.4; }
.guide-qr {
  width: 200rpx; height: 200rpx;
  margin: 0 auto 20rpx; display: block;
  border: 2rpx solid #eee; border-radius: 12rpx;
}
.guide-btn {
  background: #FF6B35; color: #fff;
  padding: 20rpx 48rpx; border-radius: 48rpx;
  font-size: 28rpx; display: inline-block;
}
</style>
