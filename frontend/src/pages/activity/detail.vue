<template>
  <view class="page" v-if="act">
    <!-- ═══ Cover — video or image ═══ -->
    <view class="cover-area">
      <view v-if="videoSrc" class="cover-video-wrap">
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
          :poster="act.coverUrl || act.coverImage"
          @error="onVideoError"
        />
      </view>
      <image
        v-else
        :src="act.coverUrl || act.coverImage || '/static/gas-default.png'"
        class="cover-img"
        mode="aspectFill"
      />
      <!-- Status badge -->
      <view class="hero-badge" :class="badgeClass">{{ statusText }}</view>
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
          :src="url"
          class="gallery-img"
          mode="aspectFill"
          @click="previewImages(i)"
        />
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
  </view>

  <!-- Loading / Error -->
  <view v-else class="empty-page">
    <text>{{ loadErr || '加载中...' }}</text>
  </view>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'

const act = ref(null)
const loadErr = ref('')
const booked = ref(false)
const videoFailed = ref(false)

onMounted(async () => {
  const pages = getCurrentPages()
  const id = pages[pages.length - 1]?.options?.id
  if (!id) { loadErr.value = '缺少活动ID'; return }
  try {
    act.value = await req('/api/ops/activity/' + id)
    checkBooked(Number(id))
  } catch (e) {
    loadErr.value = '加载失败，请返回重试'
    console.error(e)
  }
})

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
  if (!raw) return ''
  // Trim whitespace and wrapping quotes sometimes present in DB data
  let url = raw.trim()
  if ((url.startsWith("'") && url.endsWith("'")) || (url.startsWith('"') && url.endsWith('"')))
    url = url.slice(1, -1)
  // H5 environment: relative paths are fine; uni-app handles them
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
  if (booked.value) return '已预约 ✓'
  if (statusText.value === '即将开始') return '预约参加'
  if (statusText.value === '进行中') return '立即参与'
  return '已结束'
})
const btnClass = computed(() => {
  if (booked.value) return 'btn-done'
  if (statusText.value === '已结束') return 'btn-disabled'
  return 'btn-active'
})

// ── Actions ──
function handleAction() {
  if (statusText.value === '已结束' || booked.value) return
  const token = uni.getStorageSync('ops_token')
  if (!token) { uni.navigateTo({ url: '/pages/auth/login' }); return }
  doBook()
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

function onVideoError(e) {
  console.error('[activity-detail] 视频加载失败:', e.detail?.errMsg || e)
  videoFailed.value = true
  uni.showToast({ title: '视频暂无法播放', icon: 'none', duration: 2000 })
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

function previewImages(index) {
  uni.previewImage({
    urls: detailImages.value,
    current: detailImages.value[index] || detailImages.value[0]
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
.btn-done { background: #ccc; }
.btn-disabled { background: #bbb; color: #fff; }

.empty-page { display: flex; align-items: center; justify-content: center; height: 100vh; color: #999; font-size: 28rpx; }
</style>
