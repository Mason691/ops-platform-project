<template>
  <view class="page">
    <!-- ═══ Header ═══ -->
    <view class="af-header">
      <text class="af-title">{{ isEdit ? '编辑活动' : '新建活动' }}</text>
    </view>

    <!-- ═══ Cover Image ═══ -->
    <view class="af-section">
      <text class="af-label">封面图 <text class="af-required">*</text></text>
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

    <!-- ═══ Image Cropper Modal ═══ -->
    <ImageCropperModal
      :visible="cropperVisible"
      :imageUrl="cropperImageUrl"
      @close="cropperVisible = false"
      @confirm="onCropperConfirm"
      @error="onCropperError"
    />

    <view style="height: 120rpx"></view>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '@/api/index'
import ImageCropperModal from '@/components/ImageCropperModal.vue'

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

async function chooseCover() {
  try {
    const res = await new Promise((resolve, reject) => {
      uni.chooseImage({
        count: 1,
        sizeType: ['original', 'compressed'],
        sourceType: ['album', 'camera'],
        success: resolve,
        fail: reject,
      })
    })
    const path = res.tempFilePaths[0]
    // H5: convert blob path to data URL for cropper
    let dataUrl = path
    // #ifdef H5
    try {
      const blob = await fetch(path).then(r => r.blob())
      dataUrl = await new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result)
        reader.onerror = reject
        reader.readAsDataURL(blob)
      })
    } catch { /* fall through — path may already be a data URL */ }
    // #endif
    cropperImageUrl.value = dataUrl
    cropperVisible.value = true
  } catch (e) {
    if (!String(e.errMsg || '').includes('cancel')) {
      uni.showToast({ title: '选择图片失败', icon: 'none' })
    }
  }
}

async function onCropperConfirm(blob) {
  cropperVisible.value = false
  uni.showLoading({ title: '上传封面...' })
  try {
    const uploadedUrl = await uploadFile(blob, 'cover.jpg')
    form.coverImage = uploadedUrl
    form.coverUrl = uploadedUrl
    coverUrl.value = uploadedUrl
    uni.hideLoading()
    uni.showToast({ title: '封面上传成功', icon: 'success' })
  } catch (e) {
    uni.hideLoading()
    uni.showToast({ title: '封面上传失败', icon: 'none' })
    console.error('[activity-form] cover upload failed:', e)
  }
}

function onCropperError(e) {
  console.error('[activity-form] cropper error:', e)
  uni.showToast({ title: '图片处理失败', icon: 'none' })
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
