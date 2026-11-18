<template>
  <view class="evidence">
    <view class="grid">
      <view v-for="(file, index) in modelValue" :key="file.id || file.url || index" class="item">
        <image v-if="isImage(file)" class="thumb" :src="assetUrl(file.url)" mode="aspectFill"
               @click="previewImage(file)" />
        <view v-else class="video-thumb" @click="previewVideo(file)">
          <text class="play">▶</text>
          <text class="video-label">短视频</text>
          <text v-if="file.durationSeconds" class="duration">{{ file.durationSeconds }}s</text>
        </view>
        <view class="remove" @click.stop="remove(index)">×</view>
      </view>

      <view v-if="imageCount < maxImages" class="add" :class="{ disabled: uploading }" @click="chooseImages">
        <text class="add-icon">{{ uploading ? progress + '%' : '＋' }}</text>
        <text class="add-text">{{ uploading ? '上传中' : '图片' }}</text>
      </view>
      <view v-if="videoCount < maxVideos" class="add" :class="{ disabled: uploading }" @click="chooseVideoAction">
        <text class="add-icon">🎬</text>
        <text class="add-text">{{ uploading ? '上传中' : '视频' }}</text>
      </view>
    </view>

    <text class="hint">
      验收通过/不通过均必须上传至少 1 张图片或 1 段短视频；图片 JPG/PNG ≤10MB（最多9张），
      视频 MP4/MOV ≤30 秒且 ≤50MB。
    </text>
    <text v-if="error" class="error">{{ error }}</text>

    <view v-if="videoVisible" class="video-mask" @click="videoVisible = false">
      <video class="video-player" :src="videoUrl" controls autoplay @click.stop />
      <text class="video-close" @click.stop="videoVisible = false">×</text>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { api } from '@/api/index'
import { BASE_URL } from '@/config'
import { assetUrl } from '@/utils/image'
import { useVideoRecorder } from '@/hooks/useVideoRecorder'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  maxImages: { type: Number, default: 9 },
  maxVideos: { type: Number, default: 1 }
})
const emit = defineEmits(['update:modelValue'])

const { chooseVideo } = useVideoRecorder()
const uploading = ref(false)
const progress = ref(0)
const error = ref('')
const videoVisible = ref(false)
const videoUrl = ref('')

const imageCount = computed(() => props.modelValue.filter(isImage).length)
const videoCount = computed(() => props.modelValue.filter(file => !isImage(file)).length)

function isImage(file) {
  return String(file?.contentType || '').startsWith('image/')
    || /\.(jpe?g|png)(\?|$)/i.test(file?.url || '')
}

function previewImage(file) {
  const urls = props.modelValue.filter(isImage).map(item => assetUrl(item.url))
  uni.previewImage({ urls, current: assetUrl(file.url) })
}

function previewVideo(file) {
  videoUrl.value = assetUrl(file.url)
  videoVisible.value = true
}

function remove(index) {
  if (uploading.value) return
  const removed = props.modelValue[index]
  const next = [...props.modelValue]
  next.splice(index, 1)
  emit('update:modelValue', next)
  if (removed?.id) api.employeeDeleteBusinessAttachment(removed.id).catch(() => {})
}

function chooseImages() {
  if (uploading.value) return
  const remain = props.maxImages - imageCount.value
  if (remain <= 0) return
  uni.chooseImage({
    count: remain,
    sizeType: ['compressed'],
    sourceType: ['album', 'camera'],
    success: async res => {
      const files = res.tempFiles || []
      const paths = res.tempFilePaths || []
      await uploadItems(paths.map((path, index) => ({
        path,
        file: typeof Blob !== 'undefined' && files[index] instanceof Blob ? files[index] : null,
        name: `acceptance_${Date.now()}_${index}.jpg`,
        size: Number(files[index]?.size || 0)
      })))
    },
    fail: e => {
      if (!String(e.errMsg || '').includes('cancel')) error.value = '选择图片失败'
    }
  })
}

function chooseVideoAction() {
  if (uploading.value) return
  uni.showActionSheet({
    itemList: ['录制视频', '从相册选择视频'],
    success: async res => {
      const sourceType = res.tapIndex === 0 ? ['camera'] : ['album']
      try {
        const item = await chooseVideo(sourceType)
        await uploadItems([item])
      } catch (e) {
        if (!e.cancelled) error.value = e.message || '选择视频失败'
      }
    }
  })
}

async function uploadItems(items) {
  if (!items.length) return
  error.value = ''
  uploading.value = true
  const next = [...props.modelValue]
  let uploaded = 0
  try {
    for (const item of items) {
      progress.value = 0
      if (item.size > 50 * 1024 * 1024) {
        error.value = '文件不能超过 50MB'
        continue
      }
      const record = await uploadOne(item)
      if (record) {
        next.push(record)
        uploaded += 1
      }
    }
  } catch (e) {
    error.value = e.message || '上传失败，请重试'
  } finally {
    uploading.value = false
    progress.value = 0
    if (uploaded) emit('update:modelValue', next)
    if (uploaded) uni.showToast({ title: '上传成功', icon: 'success' })
  }
}

async function uploadOne(item) {
  let blob = item.file
  if (!blob) {
    try {
      blob = await fetch(item.path).then(res => res.blob())
    } catch (e) {
      // 微信内部分机型 tempFilePath 为本地路径，fetch 不可读，交给 uni.uploadFile
      return uploadByUni(item.path)
    }
  }
  if (!blob) throw new Error('读取视频失败')
  if (isVideoBlob(blob) && blob.size > 50 * 1024 * 1024) throw new Error('视频不能超过 50MB')
  if (!isVideoBlob(blob) && blob.size > 10 * 1024 * 1024) throw new Error('图片不能超过 10MB')
  return uploadBlob(blob, item.name)
}

function uploadByUni(filePath) {
  const token = uni.getStorageSync('ops_employee_token') || ''
  return new Promise((resolve, reject) => {
    const task = uni.uploadFile({
      url: BASE_URL + '/api/ops/employee/business-data/attachments?fileType=ACCEPTANCE',
      filePath,
      name: 'file',
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: res => {
        try {
          const body = JSON.parse(res.data)
          if (body.code === 200 && body.data?.id) resolve(mapRecord(body.data))
          else reject(new Error(body.msg || '上传失败'))
        } catch (e) {
          reject(new Error('上传失败'))
        }
      },
      fail: () => reject(new Error('上传失败，请重试'))
    })
    if (task && typeof task.onProgressUpdate === 'function') {
      task.onProgressUpdate(res => {
        progress.value = Math.min(99, Number(res.progress) || 0)
      })
    }
  })
}

function isVideoBlob(blob) {
  return String(blob.type || '').startsWith('video/')
}

function uploadBlob(blob, name) {
  const token = uni.getStorageSync('ops_employee_token') || ''
  const formData = new FormData()
  formData.append('file', blob, name)
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', BASE_URL + '/api/ops/employee/business-data/attachments?fileType=ACCEPTANCE')
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    xhr.upload.onprogress = event => {
      if (event.lengthComputable && event.total > 0) {
        progress.value = Math.min(99, Math.round((event.loaded * 100) / event.total))
      }
    }
    xhr.onload = () => {
      try {
        const body = JSON.parse(xhr.responseText)
        if (body.code === 200 && body.data?.id) resolve(mapRecord(body.data))
        else reject(new Error(body.msg || '上传失败'))
      } catch (e) {
        reject(new Error('上传失败'))
      }
    }
    xhr.onerror = () => reject(new Error('网络错误，请重试'))
    xhr.send(formData)
  })
}

function mapRecord(data) {
  return {
    id: data.id,
    url: data.fileUrl,
    fileName: data.fileName || '',
    fileSize: data.fileSize,
    contentType: data.contentType || '',
    durationSeconds: data.durationSeconds,
    fileType: data.fileType
  }
}
</script>

<style scoped>
.evidence { width: 100%; }
.grid { display: flex; flex-wrap: wrap; gap: 16rpx; }
.item { position: relative; width: 184rpx; height: 184rpx; border-radius: 12rpx;
  overflow: hidden; background: #1f2937; }
.thumb { width: 100%; height: 100%; }
.video-thumb { width: 100%; height: 100%; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 4rpx; color: #fff; }
.play { font-size: 48rpx; line-height: 1; }
.video-label { font-size: 22rpx; }
.duration { font-size: 20rpx; color: #d1d5db; }
.remove { position: absolute; top: 4rpx; right: 6rpx; width: 36rpx; height: 36rpx;
  line-height: 32rpx; text-align: center; border-radius: 50%; background: rgba(0,0,0,.65);
  color: #fff; font-size: 28rpx; }
.add { width: 184rpx; height: 184rpx; border: 1rpx dashed #c8c9cc; border-radius: 12rpx;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8rpx; color: #909399; font-size: 22rpx; background: #f7f8fa; }
.add.disabled { opacity: .6; }
.add-icon { font-size: 40rpx; line-height: 1; }
.hint { display: block; margin-top: 14rpx; color: #909399; font-size: 22rpx; line-height: 1.6; }
.error { display: block; margin-top: 8rpx; color: #f56c6c; font-size: 24rpx; }
.video-mask { position: fixed; inset: 0; z-index: 999; background: rgba(0,0,0,.88);
  display: flex; align-items: center; justify-content: center; }
.video-player { width: 92vw; max-height: 70vh; }
.video-close { position: absolute; top: 80rpx; right: 40rpx; color: #fff; font-size: 64rpx; }
</style>
