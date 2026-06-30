<template>
  <view class="page">
    <!-- ═══ Header ═══ -->
    <view class="lrm-header">
      <text class="lrm-title">📺 直播管理</text>
      <text class="lrm-sub">管理直播间、查看直播状态</text>
    </view>

    <!-- ═══ List ═══ -->
    <view v-if="rooms.length > 0" class="lrm-list">
      <view v-for="room in rooms" :key="room.id" class="lrm-card">
        <!-- Cover + status -->
        <view class="lrm-card-top">
          <image
            :src="assetUrl(room.coverUrl)"
            class="lrm-cover"
            mode="aspectFill"
            @error="onImgError"
          />
          <view class="lrm-status" :class="statusClass(room.status)">
            {{ statusLabel(room.status) }}
          </view>
        </view>

        <!-- Info -->
        <view class="lrm-card-body">
          <text class="lrm-card-title">{{ room.liveTitle }}</text>
          <text class="lrm-card-finder">视频号: {{ room.finderUsername }}</text>
          <text class="lrm-card-time" v-if="room.startTime">
            开播: {{ fmtFull(room.startTime) }}
          </text>
          <text class="lrm-card-time" v-if="room.actualStartTime">
            实际上播: {{ fmtFull(room.actualStartTime) }}
          </text>

          <!-- Inline edit: viewer count + replay URL + description (for edit mode) -->
          <view v-if="editingId === room.id" class="lrm-edit">
            <text class="lrm-label">观看人数</text>
            <input class="lrm-input" v-model="editForm.viewerCount" type="number" placeholder="0" />
            <text class="lrm-label">回放URL</text>
            <input class="lrm-input" v-model="editForm.replayUrl" placeholder="https://..." />
            <text class="lrm-label">直播简介</text>
            <textarea class="lrm-input lrm-textarea" v-model="editForm.description" placeholder="直播内容介绍（展示在跳转前页面）" />
            <view class="lrm-edit-btns">
              <button class="lrm-btn lrm-btn--save" @click="saveEdit(room.id)">保存</button>
              <button class="lrm-btn lrm-btn--cancel" @click="editingId = null">取消</button>
            </view>
          </view>

          <!-- Description preview (when not editing) -->
          <text v-if="!editingId || editingId !== room.id" v-show="room.description" class="lrm-card-desc">{{ room.description }}</text>
        </view>

        <!-- Actions -->
        <view class="lrm-card-actions">
          <!-- Status toggle -->
          <template v-if="room.status === 0">
            <button class="lrm-btn lrm-btn--live" @click="setStatus(room.id, 1)">设为直播中</button>
          </template>
          <template v-else-if="room.status === 1">
            <button class="lrm-btn lrm-btn--ended" @click="setStatus(room.id, 2)">设为已结束</button>
          </template>
          <template v-else>
            <button class="lrm-btn lrm-btn--upcoming" @click="setStatus(room.id, 0)">重置为预告</button>
          </template>

          <!-- Edit details -->
          <button
            v-if="editingId !== room.id"
            class="lrm-btn lrm-btn--edit"
            @click="startEdit(room)"
          >编辑详情</button>

          <!-- QR code preview -->
          <button class="lrm-btn lrm-btn--qr" @click="showQrCode(room)">二维码</button>
        </view>
      </view>
    </view>

    <!-- Empty -->
    <view v-else class="lrm-empty">暂无直播间配置</view>

    <!-- QR code modal -->
    <view class="lrm-mask" v-if="qrVisible" @click="qrVisible = false">
      <view class="lrm-qr-dialog" @click.stop>
        <text class="lrm-qr-title">扫码观看直播</text>
        <view class="lrm-qr-box" id="qrCodeBox"></view>
        <text class="lrm-qr-info">{{ qrData.liveTitle }}</text>
        <text class="lrm-qr-info">视频号: {{ qrData.finderUsername }}</text>
        <button class="lrm-btn lrm-btn--cancel" @click="qrVisible = false" style="margin-top:24rpx">关闭</button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { BASE_URL } from '@/config'
import { assetUrl, onImgError } from '@/utils/image.js'
import { api } from '@/api/index'

const rooms = ref([])
const editingId = ref(null)
const editForm = reactive({ viewerCount: 0, replayUrl: '', description: '' })
const qrVisible = ref(false)
const qrData = reactive({ pagePath: '', finderUsername: '', liveTitle: '' })

// ── Init ──
onMounted(async () => {
  await ensureLogin()
  await loadRooms()
})

async function ensureLogin() {
  const token = uni.getStorageSync('ops_token')
  if (token) return
  try {
    const res = await new Promise((resolve, reject) => {
      uni.request({
        url: 'https://test.jdzn99.com/api/ops/auth/login',
        method: 'POST',
        data: { phone: '13900000001', password: 'admin123' },
        header: { 'Content-Type': 'application/json' },
        success(r) { if (r.data?.ok) resolve(r.data.data); else reject(r.data) },
        fail: reject,
      })
    })
    uni.setStorageSync('ops_token', res.token)
    uni.setStorageSync('ops_member', JSON.stringify(res))
  } catch (e) {
    console.warn('[live-room-manage] mock login failed:', e)
  }
}

async function loadRooms() {
  try {
    const data = await api.adminLiveRooms({ pageSize: 50 })
    rooms.value = Array.isArray(data) ? data : []
  } catch (_) { rooms.value = [] }
}

// ── Status ──
function statusLabel(s) {
  if (s === 1) return '直播中'
  if (s === 2) return '已结束'
  return '预告'
}
function statusClass(s) {
  if (s === 1) return 'live'
  if (s === 2) return 'ended'
  return 'upcoming'
}

async function setStatus(id, status) {
  try {
    await api.adminUpdateLiveRoomStatus(id, { status })
    uni.showToast({ title: '状态已更新', icon: 'success' })
    await loadRooms()
  } catch (e) {
    uni.showToast({ title: e?.msg || '操作失败', icon: 'none' })
  }
}

// ── Edit ──
function startEdit(room) {
  editingId.value = room.id
  editForm.viewerCount = room.viewerCount || 0
  editForm.replayUrl = room.replayUrl || ''
  editForm.description = room.description || ''
}

async function saveEdit(id) {
  try {
    await api.adminUpdateLiveRoom(id, {
      viewerCount: Number(editForm.viewerCount || 0),
      replayUrl: editForm.replayUrl,
      description: editForm.description
    })
    uni.showToast({ title: '已保存', icon: 'success' })
    editingId.value = null
    await loadRooms()
  } catch (e) {
    uni.showToast({ title: e?.msg || '保存失败', icon: 'none' })
  }
}

// ── QR Code ──
async function showQrCode(room) {
  try {
    const data = await api.adminLiveRoomQrCode(room.id)
    qrData.pagePath = data.pagePath || '/pages/live/index'
    qrData.finderUsername = data.finderUsername || room.finderUsername
    qrData.liveTitle = data.liveTitle || room.liveTitle
    qrVisible.value = true
    // Generate QR code after DOM renders
    uni.showLoading({ title: '生成二维码...' })
    setTimeout(() => {
      genQrCode()
      uni.hideLoading()
    }, 300)
  } catch (e) {
    uni.showToast({ title: '获取二维码信息失败', icon: 'none' })
  }
}

function genQrCode() {
  // Build full URL for the H5 live square page
  const fullUrl = (BASE_URL || 'https://boss.jdzn99.com') + '/ops/#/pages/live/index'
  // Simple QR code using Google Charts API as fallback (works in H5)
  const box = document.getElementById('qrCodeBox')
  if (!box) return
  const qrUrl = 'https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=' + encodeURIComponent(fullUrl)
  box.innerHTML = '<image src="' + qrUrl + '" style="width:200px;height:200px" />'
}

// ── Helpers ──
function fmtFull(t) {
  if (!t) return ''
  return t.substring(0, 16).replace('T', ' ')
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding-bottom: 60rpx; }

.lrm-header { background: linear-gradient(135deg, #FF6B35, #ff4444); padding: 32rpx 32rpx; text-align: center; }
.lrm-title { font-size: 36rpx; font-weight: 700; color: #fff; display: block; }
.lrm-sub { font-size: 24rpx; color: rgba(255,255,255,.8); margin-top: 8rpx; display: block; }

.lrm-list { padding: 24rpx; }
.lrm-card { background: #fff; border-radius: 16rpx; overflow: hidden; margin-bottom: 24rpx; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.06); }

.lrm-card-top { position: relative; }
.lrm-cover { width: 100%; height: 280rpx; display: block; background: #f0f0f0; }
.lrm-status {
  position: absolute; top: 12rpx; left: 12rpx;
  padding: 6rpx 20rpx; border-radius: 10rpx; font-size: 22rpx; font-weight: 600; color: #fff;
}
.lrm-status.upcoming { background: rgba(255,107,53,.85); }
.lrm-status.live { background: rgba(255,0,0,.85); }
.lrm-status.ended { background: rgba(0,0,0,.55); }

.lrm-card-body { padding: 20rpx 24rpx; }
.lrm-card-title { font-size: 30rpx; font-weight: 600; color: #333; display: block; margin-bottom: 8rpx; }
.lrm-card-finder { font-size: 24rpx; color: #6366F1; display: block; margin-bottom: 6rpx; }
.lrm-card-time { font-size: 24rpx; color: #999; display: block; }

.lrm-label { font-size: 24rpx; font-weight: 600; color: #333; display: block; margin-top: 16rpx; margin-bottom: 8rpx; }
.lrm-input {
  width: 100%; height: 64rpx; border: 2rpx solid #eee; border-radius: 10rpx;
  padding: 0 16rpx; font-size: 26rpx; color: #333; background: #fafafa; box-sizing: border-box;
}
.lrm-edit { margin-top: 16rpx; }
.lrm-edit-btns { display: flex; gap: 16rpx; margin-top: 16rpx; }

.lrm-card-desc { font-size: 24rpx; color: #888; display: block; margin-top: 8rpx; line-height: 1.5; }
.lrm-textarea { height: 120rpx; padding: 12rpx 16rpx; resize: vertical; }

.lrm-card-actions { display: flex; gap: 12rpx; padding: 16rpx 24rpx; border-top: 1rpx solid #f5f5f5; flex-wrap: wrap; }

.lrm-btn {
  padding: 10rpx 24rpx; border-radius: 28rpx; font-size: 24rpx; font-weight: 600; border: none; cursor: pointer;
}
.lrm-btn--live { background: #FF4444; color: #fff; }
.lrm-btn--ended { background: #999; color: #fff; }
.lrm-btn--upcoming { background: #FF6B35; color: #fff; }
.lrm-btn--edit { background: #EEF2FF; color: #6366F1; }
.lrm-btn--qr { background: #F0FDF4; color: #16A34A; }
.lrm-btn--save { background: #FF6B35; color: #fff; }
.lrm-btn--cancel { background: #f5f5f5; color: #666; }

.lrm-empty { text-align: center; padding: 120rpx 0; color: #999; font-size: 28rpx; }

/* ── QR Modal ── */
.lrm-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.5);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.lrm-qr-dialog {
  background: #fff; border-radius: 24rpx; padding: 40rpx; width: 80%; text-align: center;
}
.lrm-qr-title { font-size: 32rpx; font-weight: 600; color: #333; display: block; margin-bottom: 24rpx; }
.lrm-qr-box { display: flex; justify-content: center; margin-bottom: 20rpx; }
.lrm-qr-info { font-size: 24rpx; color: #666; display: block; }
</style>
