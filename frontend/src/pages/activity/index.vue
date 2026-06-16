<template>
  <view class="page">
    <!-- ── Filter tabs ── -->
    <scroll-view scroll-x class="filter-bar" :show-scrollbar="false">
      <view
        v-for="c in categories"
        :key="c.value"
        class="filter-tag"
        :class="{ active: currentCat === c.value }"
        @click="switchCat(c.value)"
      >{{ c.label }}</view>
    </scroll-view>

    <!-- ── Activity cards ── -->
    <view class="card-list">
      <view v-for="a in list" :key="a.id" class="card" @click="goDetail(a.id)">
        <!-- Cover -->
        <view class="card-cover">
          <image
            :src="assetUrl(a.coverUrl || a.coverImage) || '/static/gas-default.png'"
            mode="aspectFill"
            class="cover-img"
          />
          <view class="status-badge" :class="statusClass(a)">
            {{ statusLabel(a) }}
          </view>
        </view>

        <!-- Info -->
        <view class="card-body">
          <text class="card-title">{{ a.title }}</text>

          <view class="card-tags">
            <view :class="['tag', a.type === 'live_reserve' ? 'tag-live' : 'tag-normal']">
              {{ a.type === 'live_reserve' ? '📺 直播预约' : '活动' }}
            </view>
            <view class="tag cat-tag">{{ categoryLabel(a.category) }}</view>
            <view v-if="a.pointsReward > 0" class="tag points-tag">
              🎁 +{{ a.pointsReward }} 积分
            </view>
          </view>

          <view class="card-meta">
            <view class="meta-row">
              <text class="meta-icon">🕐</text>
              <text class="meta-text">{{ fmtTime(a.startTime) }} ~ {{ fmtEndTime(a.endTime) }}</text>
            </view>
            <view v-if="a.location" class="meta-row">
              <text class="meta-icon">📍</text>
              <text class="meta-text">{{ a.location }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- ── States ── -->
    <view v-if="loading" class="state-tip">加载中...</view>
    <view v-else-if="!list.length" class="state-tip empty">
      <text class="empty-icon">🎉</text>
      <text class="empty-text">暂无活动，敬请期待更多精彩内容</text>
    </view>
    <view v-else-if="noMore && list.length" class="state-tip">— 已经到底啦 —</view>
  </view>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { BASE_URL } from '@/config'

/** Resolve asset URL — prepend BASE_URL for mini-program, keep relative for H5 */
function assetUrl(path) {
  if (!path) return ''
  if (/^https?:\/\//.test(path) || /^data:/.test(path)) return path
  return BASE_URL + path
}

const list = ref([])
const loading = ref(false)
const pageNum = ref(1)
const noMore = ref(false)
const currentCat = ref('')
const categories = [
  { label: '全部', value: '' },
  { label: '直播预约', value: 'live_reserve' },
  { label: '活动展示', value: 'activity' }
]
// Map old category values to display
const TYPE_MAP = { live_reserve: '直播预约', activity: '活动' }

// ── Helpers ──
function fmtTime(t) {
  if (!t) return ''
  // backend returns "yyyy-MM-dd HH:mm:ss" or similar
  return t.substring(0, 16).replace(' ', ' ')
}
function fmtEndTime(t) {
  if (!t) return ''
  return t.substring(5, 16).replace(' ', ' ')
}
function categoryLabel(v) {
  const m = { OFFLINE: '线下', FAMILY: '亲子', POINTS: '积分' }
  return m[v] || v || '其他'
}

function statusLabel(a) {
  if (a.type === 'live_reserve') {
    const start = a.startTime ? new Date(a.startTime.replace(/-/g, '/')) : null
    if (start && new Date() < start) return '预约中'
    return '直播中'
  }
  const now = new Date()
  const start = a.startTime ? new Date(a.startTime.replace(/-/g, '/')) : null
  const end = a.endTime ? new Date(a.endTime.replace(/-/g, '/')) : null
  if (!start || !end) return '进行中'
  if (now < start) return '即将开始'
  if (now > end) return '已结束'
  if (a.status === 0) return '已结束'
  return '进行中'
}
function statusClass(a) {
  const s = statusLabel(a)
  if (s === '进行中') return 'status-live'
  if (s === '即将开始') return 'status-upcoming'
  return 'status-ended'
}

// ── Load ──
async function load(reset) {
  if (loading.value) return
  if (reset) {
    list.value = []
    pageNum.value = 1
    noMore.value = false
  }
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: 10 }
    if (currentCat.value) params.type = currentCat.value
    const r = await api('/api/ops/activity/page', 'GET', params)
    const records = r.records || []
    if (records.length < 10) noMore.value = true
    list.value = reset ? records : [...list.value, ...records]
    pageNum.value++
  } catch (e) {
    // silently handled
  } finally {
    loading.value = false
  }
}

function switchCat(v) {
  if (currentCat.value === v) return
  currentCat.value = v
  load(true)
}

// ── Scroll load more ──
let lastScrollY = 0
function onPageScroll(e) {
  // Approximate: if within 100px of bottom, load more
}

// We use onReachBottom from pages.json or manual
function goDetail(id) {
  uni.navigateTo({ url: '/pages/activity/detail?id=' + id })
}

import { onReachBottom } from '@dcloudio/uni-app'
onReachBottom(() => {
  if (!noMore.value && !loading.value) load(false)
})

onMounted(() => load(true))

// ── Generic request helper ──
function api(url, method, data) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE_URL + url,
      method: method || 'GET',
      data: data || {},
      header: { 'Content-Type': 'application/json' },
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
.page {
  min-height: 100vh;
  background: #f5f5f5;
  padding-bottom: 48rpx;
}

/* ── Filter bar ── */
.filter-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  background: #fff;
  white-space: nowrap;
  padding: 20rpx 24rpx;
  border-bottom: 1rpx solid #eee;
}
.filter-tag {
  display: inline-block;
  padding: 12rpx 28rpx;
  margin-right: 16rpx;
  border-radius: 32rpx;
  font-size: 26rpx;
  color: #666;
  background: #f5f5f5;
  transition: all .2s;
}
.filter-tag.active {
  background: #FF6B35;
  color: #fff;
}

/* ── Cards ── */
.card-list {
  padding: 20rpx 24rpx;
}
.card {
  background: #fff;
  border-radius: 16rpx;
  overflow: hidden;
  margin-bottom: 24rpx;
  box-shadow: 0 2rpx 16rpx rgba(0,0,0,.04);
}

/* Cover */
.card-cover {
  position: relative;
  width: 100%;
  height: 340rpx;
}
.cover-img {
  width: 100%;
  height: 100%;
}
.status-badge {
  position: absolute;
  top: 16rpx;
  left: 16rpx;
  padding: 6rpx 20rpx;
  border-radius: 8rpx;
  font-size: 22rpx;
  color: #fff;
}
.status-live { background: #FF6B35; }
.status-upcoming { background: #4CAF50; }
.status-ended { background: #999; }

/* Body */
.card-body { padding: 20rpx 24rpx 24rpx; }
.card-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #333;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  line-height: 1.4;
}

.card-tags {
  display: flex;
  gap: 12rpx;
  margin-top: 16rpx;
}
.tag {
  padding: 4rpx 16rpx;
  border-radius: 6rpx;
  font-size: 22rpx;
}
.tag-live {
  background: #FFF3E0;
  color: #E65100;
}
.tag-normal {
  background: #E8F5E9;
  color: #2E7D32;
}
.cat-tag {
  background: #EEF2FF;
  color: #6366F1;
}
.points-tag {
  background: #FFF3EE;
  color: #FF6B35;
}

.card-meta {
  margin-top: 18rpx;
}
.meta-row {
  display: flex;
  align-items: center;
  margin-bottom: 8rpx;
}
.meta-icon {
  font-size: 24rpx;
  margin-right: 8rpx;
}
.meta-text {
  font-size: 24rpx;
  color: #888;
}

/* ── States ── */
.state-tip {
  text-align: center;
  padding: 48rpx;
  color: #999;
  font-size: 26rpx;
}
.empty { padding: 120rpx 48rpx; }
.empty-icon { font-size: 64rpx; display: block; margin-bottom: 20rpx; }
.empty-text { color: #999; }
</style>
