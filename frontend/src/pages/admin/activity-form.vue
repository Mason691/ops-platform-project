<template>
  <view class="page">
    <!-- ═══ Header ═══ -->
    <view class="af-header">
      <text class="af-title">{{ isEdit ? '编辑活动' : '新建活动' }}</text>
    </view>

    <!-- ═══ Cover Image ═══ -->
    <view class="af-section">
      <text class="af-label">封面图 <text class="af-required">*</text></text>
      <!-- H5 隐藏的原生 file input，避免 uni.chooseImage 兼容问题 -->
      <!-- #ifdef H5 -->
      <input
        ref="fileInputRef"
        type="file"
        accept="image/*"
        style="display:none"
        @change="onFilePicked"
      />
      <!-- #endif -->
      <view class="af-cover" @click="chooseCover">
        <image
          v-if="coverUrl"
          :src="coverUrl"
          class="af-cover-img"
          mode="aspectFill"
          @error="coverUrl = ''"
        />
        <view v-else class="af-cover-empty">
          <text class="af-empty-icon">📷</text>
          <text class="af-empty-text">点击选择封面</text>
        </view>
        <view class="af-cover-badge" v-if="coverUrl">点击更换</view>
      </view>
      <!-- 测试用：直接用已有图片 URL 打开裁剪器 -->
      <view class="af-test-row" v-if="!coverUrl">
        <text class="af-test-label">快速测试：</text>
        <button class="af-test-btn" @click="testCropperWithUrl('https://a.15891589.xyz/uploads/ops/img/20260621/546aaaff.jpg')">测试图片1</button>
        <button class="af-test-btn" @click="testCropperWithUrl('https://a.15891589.xyz/uploads/ops/img/20260621/607065ab.jpg')">测试图片2</button>
      </view>
    </view>

    <!-- ═══ Basic Info ═══ -->
    <view class="af-section">
      <text class="af-label">活动标题 <text class="af-required">*</text></text>
      <input class="af-input" v-model="form.title" placeholder="请输入活动标题" maxlength="50" />

      <text class="af-label">活动分类</text>
      <view class="af-tags">
        <view
          v-for="c in categories"
          :key="c.value"
          :class="['af-tag', { active: form.category === c.value }]"
          @click="form.category = c.value"
        >{{ c.label }}</view>
      </view>

      <text class="af-label">活动类型</text>
      <view class="af-tags">
        <view
          v-for="t in types"
          :key="t.value"
          :class="['af-tag', { active: form.type === t.value }]"
          @click="form.type = t.value"
        >{{ t.label }}</view>
      </view>

      <text class="af-label">活动地点</text>
      <input class="af-input" v-model="form.location" placeholder="线下活动请填写地址；线上可留空" maxlength="100" />

      <text class="af-label">活动时间</text>
      <view class="af-row">
        <input class="af-input af-input--half" v-model="form.startTime" placeholder="开始时间 2026-01-01 09:00" />
        <text class="af-row-label">~</text>
        <input class="af-input af-input--half" v-model="form.endTime" placeholder="结束时间 2026-01-01 18:00" />
      </view>

      <text class="af-label">参与人数上限</text>
      <input class="af-input af-input--short" v-model="form.maxParticipants" type="number" placeholder="不限" />

      <text class="af-label">积分奖励</text>
      <input class="af-input af-input--short" v-model="form.pointsReward" type="number" placeholder="0" />
    </view>

    <!-- ═══ Video ═══ -->
    <view class="af-section">
      <text class="af-section-title">📹 视频（可选）</text>
      <text class="af-label">视频文件</text>
      <view class="af-upload-row">
        <button class="af-btn af-btn--outline" @click="chooseVideo">{{ form.videoUrl ? '更换视频' : '上传视频' }}</button>
        <text v-if="form.videoUrl" class="af-upload-status">已上传</text>
      </view>
      <text class="af-hint">支持 mp4/mov/avi/webm，最大 50MB</text>
    </view>

    <!-- ═══ Detail Gallery ═══ -->
    <view class="af-section">
      <text class="af-section-title">🖼 活动详情图（可选，可多张）</text>
      <scroll-view scroll-x class="af-gallery" :show-scrollbar="false">
        <view
          v-for="(url, i) in form.detailImages"
          :key="i"
          class="af-gallery-item"
          @click="previewDetailImage(i)"
        >
          <image :src="url" class="af-gallery-img" mode="aspectFill" />
          <view class="af-gallery-del" @click.stop="removeDetailImage(i)">✕</view>
        </view>
        <view class="af-gallery-add" @click="chooseDetailImage">
          <text class="af-add-icon">+</text>
          <text class="af-add-text">添加</text>
        </view>
      </scroll-view>
      <text class="af-hint">建议上传活动宣传海报、日程安排等图片</text>
    </view>

    <!-- ═══ Activity Detail Text ═══ -->
    <view class="af-section">
      <text class="af-label">活动详情文字</text>
      <textarea class="af-textarea" v-model="form.detail" placeholder="输入活动详情介绍（支持富文本）" />
    </view>

    <!-- ═══ Submit ═══ -->
    <view class="af-submit-bar">
      <button class="af-btn af-btn--cancel" @click="goBack" v-if="isEdit">取消</button>
      <button
        class="af-btn af-btn--primary"
        :class="{ 'af-btn--loading': submitting }"
        :disabled="submitting"
        @click="handleSubmit"
      >
        {{ submitting ? '提交中...' : isEdit ? '保存修改' : '创建活动' }}
      </button>
    </view>

    <!-- ═══ Inline Image Cropper ═══ -->
    <!-- #ifdef H5 -->
    <view class="co-overlay" v-if="cropperVisible" @click.stop.prevent="() => {}" @touchmove.prevent.stop>
      <view class="co-dialog">
        <view class="co-header">
          <view class="co-header-left">
            <view class="co-header-icon">✂️</view>
            <text class="co-title">裁剪封面图</text>
            <view class="co-badge">800 × 800</view>
          </view>
          <view class="co-close-btn" @click="cropperVisible = false">✕</view>
        </view>
        <view class="co-body">
          <view class="co-main">
            <view class="co-crop-container">
              <img ref="cropperImageRef" :src="cropperImageUrl" alt="封面原图" crossorigin="anonymous" @load="initCropper" @error="cropperVisible = false" />
            </view>
          </view>
          <view class="co-preview">
            <view class="co-preview-header"><text class="co-preview-title">实时预览</text></view>
            <view class="co-preview-cards">
              <view class="co-preview-item">
                <view class="co-preview-box co-preview-box--round"><view class="co-preview-inner" id="previewRound"></view></view>
                <text class="co-preview-label">圆形</text>
              </view>
              <view class="co-preview-item">
                <view class="co-preview-box co-preview-box--square"><view class="co-preview-inner" id="previewSquare"></view></view>
                <text class="co-preview-label">方形</text>
              </view>
            </view>
          </view>
        </view>
        <view class="co-footer">
          <view class="co-toolbar">
            <button class="co-tool-btn co-tool-btn--icon" @click="zoomOut" :disabled="zoomLevel <= 0.5">−</button>
            <view class="co-zoom-display"><text class="co-zoom-value">{{ Math.round(zoomLevel * 100) }}</text><text class="co-zoom-unit">%</text></view>
            <button class="co-tool-btn co-tool-btn--icon" @click="zoomIn" :disabled="zoomLevel >= 3">+</button>
            <view class="co-tool-divider"></view>
            <button class="co-tool-btn co-tool-btn--text" @click="rotateLeft">↺ 左转</button>
            <button class="co-tool-btn co-tool-btn--text" @click="rotateRight">↻ 右转</button>
            <view class="co-tool-divider"></view>
            <button class="co-tool-btn co-tool-btn--text" @click="resetCrop">重置</button>
          </view>
          <view class="co-actions">
            <button class="co-btn co-btn--cancel" @click="cropperVisible = false">取消</button>
            <button class="co-btn co-btn--confirm" @click="handleCropperConfirm">确认裁剪</button>
          </view>
        </view>
      </view>
    </view>
    <!-- #endif -->

    <view style="height: 120rpx"></view>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, watch, onBeforeUnmount } from 'vue'
import { api } from '@/api/index'
import Cropper from 'cropperjs'
import 'cropperjs/dist/cropper.css'

// ==================== State ====================
const isEdit = ref(false)
const editId = ref(null)
const submitting = ref(false)

const categories = [
  { label: '线下活动', value: 'OFFLINE' },
  { label: '亲子活动', value: 'FAMILY' },
  { label: '积分活动', value: 'POINTS' },
]
const types = [
  { label: '普通活动', value: 'activity' },
  { label: '直播预约', value: 'live_reserve' },
]

const form = reactive({
  title: '',
  category: 'OFFLINE',
  type: 'activity',
  location: '',
  startTime: '',
  endTime: '',
  maxParticipants: null,
  pointsReward: 0,
  detail: '',
  coverImage: '',
  coverUrl: '',
  videoUrl: '',
  videoCoverUrl: '',
  images: '[]',
  detailImages: [],
})

const coverUrl = ref('')

// ==================== Cropper ====================
const cropperVisible = ref(false)
const cropperImageUrl = ref('')
const cropperImageRef = ref(null)
const fileInputRef = ref(null)
let cropperInstance = null
let cropperInitialized = false
const zoomLevel = ref(1)

// ═══ Cropper Core ═══
function updatePreview() {
  if (!cropperInstance || !cropperInitialized) return
  try {
    const rc = cropperInstance.getCroppedCanvas({ width: 150, height: 150 })
    const sc = cropperInstance.getCroppedCanvas({ width: 150, height: 150 })
    const rEl = document.getElementById('previewRound')
    const sEl = document.getElementById('previewSquare')
    if (rEl) { rEl.innerHTML = ''; if (rc) rEl.appendChild(rc) }
    if (sEl) { sEl.innerHTML = ''; if (sc) sEl.appendChild(sc) }
  } catch {}
}

function destroyCropper() {
  if (cropperInstance) {
    try { cropperInstance.destroy() } catch {}
    cropperInstance = null
    cropperInitialized = false
    zoomLevel.value = 1
  }
}

function initCropper() {
  destroyCropper()
  const img = cropperImageRef.value
  if (!img) return
  nextTick(() => {
    try {
      cropperInstance = new Cropper(img, {
        aspectRatio: 1, viewMode: 1, autoCropArea: 1,
        cropBoxMovable: false, cropBoxResizable: false, dragMode: 'move',
        minCanvasWidth: 800, minCanvasHeight: 800,
        guides: true, background: true, modal: true, center: true, highlight: false,
        movable: true, zoomable: true, zoomOnWheel: true, zoomOnTouch: true,
        scalable: true, rotatable: true, toggleDragModeOnDblclick: false,
        responsive: true, restore: false,
        ready() {
          const cd = cropperInstance.getContainerData()
          const id = cropperInstance.getImageData()
          if (id.naturalWidth > 0 && id.naturalHeight > 0) {
            const fz = Math.max(cd.width / id.naturalWidth, cd.height / id.naturalHeight)
            cropperInstance.zoomTo(fz)
            zoomLevel.value = Math.round(fz * 100) / 100
          } else { cropperInstance.zoomTo(2); zoomLevel.value = 2 }
          updatePreview()
        },
        crop() { updatePreview() },
        zoom(e) { zoomLevel.value = e.detail.ratio },
      })
      cropperInitialized = true
    } catch (e) { console.error('Cropper init:', e) }
  })
}

function zoomIn() { if (cropperInstance) cropperInstance.zoom(0.1) }
function zoomOut() { if (cropperInstance) cropperInstance.zoom(-0.1) }
function rotateLeft() { if (cropperInstance) cropperInstance.rotate(-90) }
function rotateRight() { if (cropperInstance) cropperInstance.rotate(90) }
function resetCrop() {
  if (!cropperInstance) return
  cropperInstance.reset()
  const cd = cropperInstance.getContainerData()
  const id = cropperInstance.getImageData()
  if (id.naturalWidth > 0 && id.naturalHeight > 0) {
    const fz = Math.max(cd.width / id.naturalWidth, cd.height / id.naturalHeight)
    cropperInstance.zoomTo(fz)
    zoomLevel.value = Math.round(fz * 100) / 100
  } else { cropperInstance.zoomTo(2) }
}

async function handleCropperConfirm() {
  if (!cropperInstance || !cropperInitialized) { uni.showToast({ title: '裁剪未就绪', icon: 'none' }); return }
  try {
    const canvas = cropperInstance.getCroppedCanvas({ width: 800, height: 800, imageSmoothingEnabled: true, imageSmoothingQuality: 'high' })
    if (!canvas) { uni.showToast({ title: '生成图片失败', icon: 'none' }); return }
    canvas.toBlob(async (blob) => {
      if (!blob) { uni.showToast({ title: '导出失败', icon: 'none' }); return }
      cropperVisible.value = false
      uni.showLoading({ title: '上传封面...' })
      try {
        const url = await uploadFile(blob, 'cover.jpg')
        form.coverImage = url; form.coverUrl = url; coverUrl.value = url
        uni.hideLoading(); uni.showToast({ title: '上传成功', icon: 'success' })
      } catch { uni.hideLoading(); uni.showToast({ title: '上传失败', icon: 'none' }) }
    }, 'image/jpeg', 0.92)
  } catch (e) { console.error('Crop failed:', e); uni.showToast({ title: '裁剪失败', icon: 'none' }) }
}

// Watchers + lifecycle for cropper
watch(cropperImageUrl, (n, o) => { if (n !== o && cropperInstance && cropperInitialized) cropperInstance.replace(n) })
onBeforeUnmount(() => destroyCropper())

// Test: open cropper with known URL
function testCropperWithUrl(url) {
  cropperImageUrl.value = url; cropperVisible.value = true
}

// File picker — H5 native input
function onFilePicked(e) {
  // #ifdef H5
  const file = e.target?.files?.[0] || e.detail?.target?.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = (ev) => { cropperImageUrl.value = ev.target.result; cropperVisible.value = true }
  reader.readAsDataURL(file)
  if (fileInputRef.value) fileInputRef.value.value = ''
  // #endif
}

async function chooseCover() {
  // #ifdef H5
  if (fileInputRef.value) { fileInputRef.value.click(); return }
  // #endif
  try {
    const res = await new Promise((resolve, reject) => {
      uni.chooseImage({ count: 1, sizeType: ['original', 'compressed'], sourceType: ['album', 'camera'], success: resolve, fail: reject })
    })
    const path = res.tempFilePaths[0]
    let dataUrl = path
    // #ifdef H5
    try {
      const blob = await fetch(path).then(r => r.blob())
      dataUrl = await new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(r.result); r.onerror = rej; r.readAsDataURL(blob) })
    } catch {}
    // #endif
    cropperImageUrl.value = dataUrl; cropperVisible.value = true
  } catch (e) {
    if (!String(e.errMsg || '').includes('cancel')) { uni.showToast({ title: '选择图片失败', icon: 'none' }) }
  }
}

// ==================== Upload Helper ====================
async function uploadFile(blob, filename) {
  const token = uni.getStorageSync('ops_token') || ''
  const formData = new FormData()
  formData.append('file', blob, filename)
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/ops/files/upload')
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    xhr.onload = () => {
      try {
        const d = JSON.parse(xhr.responseText)
        if (d.ok && d.data?.url) resolve(d.data.url)
        else reject(d)
      } catch { reject(xhr) }
    }
    xhr.onerror = () => reject(new Error('Upload failed'))
    xhr.send(formData)
  })
}

// ==================== Video ====================
async function chooseVideo() {
  try {
    const res = await new Promise((resolve, reject) => {
      uni.chooseVideo({
        sourceType: ['album', 'camera'],
        maxDuration: 300,
        success: resolve,
        fail: reject,
      })
    })
    const path = res.tempFilePath
    uni.showLoading({ title: '上传视频...' })
    // #ifdef H5
    const blob = await fetch(path).then(r => r.blob())
    const uploaded = await uploadFile(blob, 'video.' + (blob.type === 'video/webm' ? 'webm' : 'mp4'))
    uni.hideLoading()
    form.videoUrl = uploaded
    if (res.thumbTempFilePath) {
      form.videoCoverUrl = await uploadVideoCover(res.thumbTempFilePath)
    }
    uni.showToast({ title: '视频上传成功', icon: 'success' })
    // #endif
  } catch (e) {
    uni.hideLoading()
    if (!String(e.errMsg || '').includes('cancel')) {
      uni.showToast({ title: '视频上传失败', icon: 'none' })
    }
  }
}

async function uploadVideoCover(path) {
  try {
    const blob = await fetch(path).then(r => r.blob())
    return await uploadFile(blob, 'video_cover.jpg')
  } catch { return '' }
}

// ==================== Detail Images ====================
async function chooseDetailImage() {
  try {
    const res = await new Promise((resolve, reject) => {
      uni.chooseImage({
        count: 3,
        sizeType: ['compressed'],
        sourceType: ['album'],
        success: resolve,
        fail: reject,
      })
    })
    uni.showLoading({ title: '上传中...' })
    for (const path of res.tempFilePaths) {
      let blob
      // #ifdef H5
      const r = await fetch(path)
      blob = await r.blob()
      // #endif
      const ext = (path.split('.').pop() || 'jpg').toLowerCase()
      const url = await uploadFile(blob, 'detail.' + ext)
      form.detailImages.push(url)
    }
    uni.hideLoading()
    uni.showToast({ title: '已添加 ' + res.tempFilePaths.length + ' 张', icon: 'success' })
  } catch (e) {
    uni.hideLoading()
    if (!String(e.errMsg || '').includes('cancel')) {
      uni.showToast({ title: '上传失败', icon: 'none' })
    }
  }
}

function removeDetailImage(i) {
  form.detailImages.splice(i, 1)
}

function previewDetailImage(i) {
  uni.previewImage({
    urls: form.detailImages,
    current: form.detailImages[i],
  })
}

// ==================== Submit ====================
async function handleSubmit() {
  if (!form.title.trim()) { uni.showToast({ title: '请输入活动标题', icon: 'none' }); return }
  if (!form.coverImage) { uni.showToast({ title: '请上传封面图', icon: 'none' }); return }

  submitting.value = true
  const payload = {
    title: form.title.trim(),
    category: form.category,
    type: form.type,
    location: form.location.trim(),
    startTime: form.startTime.trim(),
    endTime: form.endTime.trim(),
    maxParticipants: form.maxParticipants ? Number(form.maxParticipants) : null,
    pointsReward: Number(form.pointsReward || 0),
    detail: form.detail.trim(),
    coverImage: form.coverImage,
    coverUrl: form.coverUrl,
    videoUrl: form.videoUrl,
    videoCoverUrl: form.videoCoverUrl,
    images: JSON.stringify(form.detailImages),
    detailImages: JSON.stringify(form.detailImages),
    productIds: [],
  }

  try {
    if (isEdit.value) {
      await api.adminUpdateActivity(editId.value, payload)
      uni.showToast({ title: '活动已更新', icon: 'success' })
    } else {
      await api.adminCreateActivity(payload)
      uni.showToast({ title: '活动创建成功', icon: 'success' })
    }
    setTimeout(() => uni.navigateBack(), 1200)
  } catch (e) {
    console.error('[activity-form] submit failed:', e)
    uni.showToast({ title: e?.msg || '提交失败，请重试', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function goBack() {
  uni.navigateBack()
}

// ==================== Mock Login ====================
async function ensureLogin() {
  const token = uni.getStorageSync('ops_token')
  if (token) return true
  try {
    const res = await new Promise((resolve, reject) => {
      uni.request({
        url: 'https://a.15891589.xyz/api/ops/auth/login',
        method: 'POST',
        data: { phone: '13900000001', password: 'admin123' },
        header: { 'Content-Type': 'application/json' },
        success(r) { if (r.data?.ok) resolve(r.data.data); else reject(r.data) },
        fail: reject,
      })
    })
    uni.setStorageSync('ops_token', res.token)
    uni.setStorageSync('ops_member', JSON.stringify(res))
    return true
  } catch (e) {
    console.warn('[activity-form] mock login failed:', e)
    return false
  }
}

// ==================== Init (Edit mode) ====================
onMounted(async () => {
  await ensureLogin()
  const pages = getCurrentPages()
  const opts = pages[pages.length - 1]?.options || {}
  const id = opts.id
  if (id) {
    isEdit.value = true
    editId.value = Number(id)
    uni.showLoading({ title: '加载活动...' })
    try {
      const detail = await api.adminActivityDetail(id)
      const a = detail.activity
      form.title = a.title || ''
      form.category = a.category || 'OFFLINE'
      form.type = a.type || 'activity'
      form.location = a.location || ''
      form.startTime = a.startTime ? a.startTime.substring(0, 16) : ''
      form.endTime = a.endTime ? a.endTime.substring(0, 16) : ''
      form.maxParticipants = a.maxParticipants || null
      form.pointsReward = a.pointsReward || 0
      form.detail = a.detail || ''
      form.coverImage = a.coverImage || ''
      form.coverUrl = a.coverUrl || a.coverImage || ''
      form.videoUrl = a.videoUrl || ''
      form.videoCoverUrl = a.videoCoverUrl || ''
      form.images = a.images || '[]'
      let di = a.detailImages
      if (typeof di === 'string') { try { di = JSON.parse(di) } catch { di = [] } }
      form.detailImages = Array.isArray(di) ? di : []
      coverUrl.value = form.coverUrl ? (form.coverUrl.startsWith('/') ? 'https://a.15891589.xyz' + form.coverUrl : form.coverUrl) : ''
    } catch (e) {
      console.error('[activity-form] load failed:', e)
      uni.showToast({ title: '加载活动失败', icon: 'none' })
    } finally {
      uni.hideLoading()
    }
  }
})
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; }

/* Header */
.af-header {
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  padding: 32rpx 32rpx;
  display: flex; align-items: center; justify-content: center;
}
.af-title { font-size: 32rpx; font-weight: 700; color: #fff; }

/* Sections */
.af-section {
  background: #fff; margin: 20rpx 24rpx; padding: 28rpx;
  border-radius: 16rpx;
}
.af-section-title { font-size: 28rpx; font-weight: 600; color: #333; display: block; margin-bottom: 16rpx; }

/* Labels */
.af-label { font-size: 26rpx; font-weight: 600; color: #333; display: block; margin-bottom: 12rpx; margin-top: 16rpx; }
.af-label:first-child { margin-top: 0; }
.af-required { color: #FF6B35; }
.af-hint { font-size: 22rpx; color: #999; display: block; margin-top: 8rpx; }

/* Inputs */
.af-input {
  width: 100%; height: 80rpx;
  border: 2rpx solid #eee; border-radius: 12rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333;
  background: #fafafa; box-sizing: border-box;
}
.af-input:focus { border-color: #FF6B35; background: #fff; }
.af-input--short { width: 240rpx; }
.af-input--half { flex: 1; }
.af-row { display: flex; align-items: center; gap: 16rpx; }
.af-row-label { font-size: 28rpx; color: #999; }
.af-textarea {
  width: 100%; min-height: 200rpx;
  border: 2rpx solid #eee; border-radius: 12rpx;
  padding: 20rpx; font-size: 28rpx; color: #333;
  background: #fafafa; box-sizing: border-box;
}
.af-textarea:focus { border-color: #FF6B35; background: #fff; }

/* Tags */
.af-tags { display: flex; gap: 16rpx; flex-wrap: wrap; }
.af-tag {
  padding: 12rpx 28rpx; border-radius: 32rpx; font-size: 26rpx; color: #666;
  background: #f5f5f5; transition: all .2s;
}
.af-tag.active { background: #FF6B35; color: #fff; font-weight: 600; }

/* Cover */
.af-cover {
  width: 320rpx; height: 320rpx; border-radius: 16rpx;
  border: 3rpx dashed #ddd; overflow: hidden;
  position: relative; cursor: pointer;
}
.af-cover-img { width: 100%; height: 100%; }
.af-cover-empty {
  width: 100%; height: 100%; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 12rpx;
}
.af-empty-icon { font-size: 48rpx; }
.af-empty-text { font-size: 24rpx; color: #999; }
.af-cover-badge {
  position: absolute; bottom: 0; left: 0; right: 0;
  text-align: center; padding: 8rpx; background: rgba(0,0,0,.4);
  color: #fff; font-size: 22rpx;
}

/* Test buttons */
.af-test-row {
  display: flex; align-items: center; gap: 12rpx; margin-top: 16rpx;
}
.af-test-label { font-size: 22rpx; color: #999; }
.af-test-btn {
  padding: 8rpx 20rpx; border-radius: 20rpx;
  background: #EEF2FF; color: #6366F1; border: 1px solid #d4d8f0;
  font-size: 22rpx; font-weight: 550; cursor: pointer;
}
.af-test-btn:hover { background: #dde3ff; }
.af-test-btn:active { transform: scale(0.97); }


/* ═══ Cropper Overlay & Dialog ═══ */
.co-overlay { position: fixed; inset: 0; z-index: 9999; background: rgba(0,0,0,0.5); backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px); display: flex; align-items: center; justify-content: center; }
.co-dialog { background: #fff; border-radius: 16px; box-shadow: 0 24px 64px rgba(0,0,0,0.16); width: 1060px; max-width: 96vw; max-height: 95vh; display: flex; flex-direction: column; overflow: hidden; }
.co-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 24px; border-bottom: 1px solid #f0f0f0; background: #fafbfc; }
.co-header-left { display: flex; align-items: center; gap: 10px; }
.co-header-icon { width: 36px; height: 36px; border-radius: 10px; background: linear-gradient(135deg, #fff3ee, #ffe8dc); color: #FF6B35; display: flex; align-items: center; justify-content: center; font-size: 18px; }
.co-title { font-size: 17px; font-weight: 650; color: #1a1a2e; }
.co-badge { font-size: 11px; font-weight: 600; color: #FF6B35; background: #fff5f0; padding: 3px 10px; border-radius: 20px; }
.co-close-btn { width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: #94a3b8; font-size: 20px; font-weight: 600; }
.co-body { flex: 1; display: flex; gap: 24px; padding: 24px; background: #f8f9fb; overflow: hidden; }
.co-main { flex: none; display: flex; align-items: center; justify-content: center; }
.co-crop-container { width: 800px; height: 800px; background: #fff; border-radius: 12px; box-shadow: 0 4px 16px rgba(0,0,0,0.06); overflow: hidden; }
.co-crop-container img { display: block; }
.co-preview { flex: none; width: 180px; display: flex; flex-direction: column; align-items: center; gap: 20px; padding-top: 4px; }
.co-preview-title { font-size: 12px; font-weight: 650; color: #64748b; text-transform: uppercase; }
.co-preview-cards { display: flex; flex-direction: column; align-items: center; gap: 20px; }
.co-preview-item { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.co-preview-box { width: 130px; height: 130px; background: #fff; box-shadow: 0 4px 12px rgba(0,0,0,0.06); overflow: hidden; }
.co-preview-box--round { border-radius: 50%; }
.co-preview-box--square { border-radius: 12px; }
.co-preview-inner { width: 100%; height: 100%; }
.co-preview-inner canvas { width: 100%; height: 100%; }
.co-preview-label { font-size: 11px; font-weight: 550; color: #94a3b8; }
.co-footer { display: flex; align-items: center; justify-content: space-between; padding: 14px 24px; border-top: 1px solid #f0f0f0; background: #fff; }
.co-toolbar { display: flex; align-items: center; gap: 10px; }
.co-tool-divider { width: 1px; height: 22px; background: #e5e7eb; }
.co-zoom-display { display: flex; align-items: baseline; min-width: 52px; padding: 2px 6px; background: #f8fafc; border-radius: 6px; }
.co-zoom-value { font-size: 14px; font-weight: 700; color: #1e293b; }
.co-zoom-unit { font-size: 10px; font-weight: 600; color: #94a3b8; margin-left: 1px; }
.co-tool-btn { display: inline-flex; align-items: center; justify-content: center; gap: 4px; border: 1px solid #e2e8f0; background: #fff; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 550; color: #475569; padding: 6px 10px; outline: none; }
.co-tool-btn:disabled { opacity: 0.3; pointer-events: none; }
.co-tool-btn--icon { width: 34px; height: 34px; padding: 0; }
.co-actions { display: flex; gap: 10px; }
.co-btn { display: inline-flex; align-items: center; gap: 6px; padding: 10px 24px; font-size: 14px; font-weight: 600; border-radius: 10px; border: none; cursor: pointer; outline: none; }
.co-btn--cancel { background: #f1f5f9; color: #475569; }
.co-btn--confirm { background: linear-gradient(135deg, #FF6B35, #e85d2c); color: #fff; }
/* Cropper overrides */
:deep(.cropper-crop-box) { outline: 2px dashed rgba(255,255,255,0.85) !important; }
:deep(.cropper-point) { display: none !important; }
:deep(.cropper-face) { opacity: 0 !important; }
@media (max-width: 1100px) { .co-dialog { width: 97vw; } .co-body { flex-direction: column; align-items: center; } .co-crop-container { width: 80vw; height: 80vw; max-width: 500px; max-height: 500px; } .co-preview { flex-direction: row; gap: 12px; } .co-preview-cards { flex-direction: row; } }
@media (max-width: 640px) { .co-crop-container { width: 88vw; height: 88vw; max-width: 340px; max-height: 340px; } .co-preview-box { width: 80px; height: 80px; } .co-btn { padding: 8px 16px; font-size: 13px; } }
@media (max-width: 400px) { .co-crop-container { width: 92vw; height: 92vw; max-width: 280px; max-height: 280px; } }
/* Upload */
.af-upload-row { display: flex; align-items: center; gap: 16rpx; }
.af-upload-status { font-size: 24rpx; color: #4CAF50; }

/* Gallery */
.af-gallery { white-space: nowrap; padding: 8rpx 0; }
.af-gallery-item { display: inline-block; width: 160rpx; height: 160rpx; margin-right: 16rpx; border-radius: 12rpx; overflow: hidden; position: relative; }
.af-gallery-img { width: 100%; height: 100%; }
.af-gallery-del {
  position: absolute; top: 6rpx; right: 6rpx;
  width: 40rpx; height: 40rpx; border-radius: 50%;
  background: rgba(0,0,0,.6); color: #fff; font-size: 24rpx;
  display: flex; align-items: center; justify-content: center;
}
.af-gallery-add {
  display: inline-flex; flex-direction: column; align-items: center; justify-content: center;
  width: 160rpx; height: 160rpx; border: 3rpx dashed #ddd; border-radius: 12rpx;
  gap: 8rpx;
}
.af-add-icon { font-size: 48rpx; color: #ccc; line-height: 1; }
.af-add-text { font-size: 22rpx; color: #999; }

/* Buttons */
.af-btn { display: inline-flex; align-items: center; justify-content: center; height: 76rpx; border-radius: 38rpx; font-size: 28rpx; font-weight: 600; border: none; }
.af-btn--primary { flex: 1; background: linear-gradient(135deg, #FF6B35, #e85d2c); color: #fff; }
.af-btn--cancel { width: 180rpx; background: #f5f5f5; color: #666; margin-right: 16rpx; }
.af-btn--outline { background: #fff; border: 2rpx solid #FF6B35; color: #FF6B35; padding: 0 24rpx; }
.af-btn--loading { opacity: 0.6; pointer-events: none; }
.af-submit-bar {
  display: flex; padding: 24rpx 32rpx;
  background: #fff; border-top: 1rpx solid #eee;
  position: fixed; bottom: 0; left: 0; right: 0;
  padding-bottom: calc(env(safe-area-inset-bottom) + 24rpx);
}
</style>
