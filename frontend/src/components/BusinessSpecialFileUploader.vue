<template>
  <view class="special-file">
    <view v-if="file" class="file-card">
      <view class="file-main">
        <text class="file-icon">{{ isPdf ? 'PDF' : 'IMG' }}</text>
        <view class="file-info">
          <text class="file-name">{{ file.name || '特批文件' }}</text>
          <text class="file-sub">已上传，可预览或重新上传</text>
        </view>
      </view>
      <view class="file-actions">
        <text class="file-action" @click="preview">预览</text>
        <text class="file-action danger" @click="remove">删除</text>
      </view>
    </view>
    <view v-else class="upload-box" :class="{ disabled: uploading }" @click="choose">
      <text class="upload-icon">{{ uploading ? percent + '%' : '＋' }}</text>
      <text class="upload-text">{{ uploading ? '上传中' : '上传特批文件' }}</text>
      <text class="upload-hint">支持 PDF / JPG / PNG，单个不超过 10MB</text>
    </view>
    <text v-if="error" class="ferr">{{ error }}</text>
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { BASE_URL } from '@/config'
import { assetUrl } from '@/utils/image'

/**
 * 低价特批文件上传：PDF/JPG/PNG ≤10MB。
 * H5 用原生 file input + XHR（带员工 JWT）；小程序用 chooseMessageFile + uploadFile。
 * 上传成功返回 {id,url,name}，由订单提交引用 fileId。
 */
const props = defineProps({
  modelValue: { type: Object, default: null },
  error: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const uploading = ref(false)
const percent = ref(0)
const file = computed(() => props.modelValue || null)
const isPdf = computed(() => /\.pdf$/i.test((file.value && file.value.name) || ''))
let disposed = false
let activeXhr = null

onBeforeUnmount(() => {
  disposed = true
  if (activeXhr) {
    try { activeXhr.abort() } catch (e) { /* ignore */ }
  }
})

function choose() {
  if (uploading.value) return
  // #ifdef H5
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.pdf,image/jpeg,image/png'
  input.onchange = (event) => {
    const selected = event.target && event.target.files && event.target.files[0]
    if (selected) uploadH5(selected)
  }
  input.click()
  // #endif
  // #ifndef H5
  uni.chooseMessageFile({
    count: 1,
    type: 'file',
    extension: ['pdf', 'jpg', 'jpeg', 'png'],
    success: (res) => {
      const selected = res.tempFiles && res.tempFiles[0]
      if (selected) uploadMp(selected.path || selected.tempFilePath, selected.name)
    },
    fail: (e) => {
      if (!String(e.errMsg || '').includes('cancel')) {
        uni.showToast({ title: '选择文件失败', icon: 'none' })
      }
    }
  })
  // #endif
}

function validate(fileName, size) {
  const name = String(fileName || '')
  if (!/\.(pdf|jpe?g|png)$/i.test(name)) {
    uni.showToast({ title: '仅支持 PDF、JPG、PNG', icon: 'none' })
    return false
  }
  if (size > 10 * 1024 * 1024) {
    uni.showToast({ title: '特批文件不能超过10MB', icon: 'none' })
    return false
  }
  return true
}

function uploadH5(selected) {
  // #ifdef H5
  if (!validate(selected.name, selected.size)) return
  const token = uni.getStorageSync('ops_employee_token') || ''
  const formData = new FormData()
  formData.append('file', selected, selected.name)
  uploading.value = true
  percent.value = 0
  const xhr = new XMLHttpRequest()
  activeXhr = xhr
  xhr.open('POST', BASE_URL + '/api/ops/employee/business-data/special-file')
  if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
  xhr.upload.onprogress = (event) => {
    if (event.lengthComputable && event.total > 0) {
      percent.value = Math.min(99, Math.round((event.loaded * 100) / event.total))
    }
  }
  xhr.onload = () => {
    activeXhr = null
    uploading.value = false
    percent.value = 0
    if (disposed) return
    try {
      const body = JSON.parse(xhr.responseText)
      if (body.code === 200 && body.data && body.data.id) {
        emit('update:modelValue', {
          id: body.data.id,
          url: body.data.fileUrl,
          name: body.data.fileName || selected.name
        })
      } else {
        uni.showToast({ title: body.msg || '上传失败', icon: 'none' })
      }
    } catch (e) {
      uni.showToast({ title: '上传失败，请重试', icon: 'none' })
    }
  }
  xhr.onerror = () => {
    activeXhr = null
    uploading.value = false
    percent.value = 0
    if (!disposed) uni.showToast({ title: '网络错误，请重试', icon: 'none' })
  }
  xhr.send(formData)
  // #endif
}

function uploadMp(filePath, fileName) {
  // #ifndef H5
  if (!validate(fileName, 0)) return
  const token = uni.getStorageSync('ops_employee_token') || ''
  uploading.value = true
  const task = uni.uploadFile({
    url: BASE_URL + '/api/ops/employee/business-data/special-file',
    filePath,
    name: 'file',
    header: token ? { Authorization: 'Bearer ' + token } : {},
    success: (res) => {
      if (disposed) return
      try {
        const body = JSON.parse(res.data)
        if (body.code === 200 && body.data && body.data.id) {
          emit('update:modelValue', {
            id: body.data.id,
            url: body.data.fileUrl,
            name: body.data.fileName || fileName
          })
        } else {
          uni.showToast({ title: body.msg || '上传失败', icon: 'none' })
        }
      } catch (e) {
        uni.showToast({ title: '上传失败，请重试', icon: 'none' })
      }
    },
    fail: () => {
      if (!disposed) uni.showToast({ title: '上传失败，请重试', icon: 'none' })
    },
    complete: () => { uploading.value = false }
  })
  if (task && typeof task.onProgressUpdate === 'function') {
    task.onProgressUpdate((res) => { percent.value = Math.min(99, Number(res.progress) || 0) })
  }
  // #endif
}

function preview() {
  if (!file.value) return
  const url = assetUrl(file.value.url)
  if (isPdf.value) {
    // #ifdef H5
    window.open(url, '_blank')
    // #endif
    // #ifndef H5
    uni.openDocument({ filePath: url, showMenu: true })
    // #endif
    return
  }
  uni.previewImage({ urls: [url], current: url })
}

function remove() {
  if (uploading.value) return
  emit('update:modelValue', null)
}
</script>

<style scoped>
.special-file { width: 100%; }
.file-card {
  display: flex; align-items: center; justify-content: space-between;
  background: #fff; border: 2rpx solid #FFD9C8; border-radius: 12rpx;
  padding: 18rpx 20rpx;
}
.file-main { display: flex; align-items: center; flex: 1; min-width: 0; }
.file-icon {
  width: 64rpx; height: 64rpx; line-height: 64rpx; text-align: center;
  border-radius: 10rpx; background: #FFF0EB; color: #FF6B35;
  font-size: 22rpx; font-weight: 700; margin-right: 14rpx; flex-shrink: 0;
}
.file-info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.file-name {
  font-size: 25rpx; color: #333; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.file-sub { font-size: 21rpx; color: #999; margin-top: 4rpx; }
.file-actions { display: flex; gap: 16rpx; margin-left: 12rpx; }
.file-action { font-size: 23rpx; color: #FF6B35; }
.file-action.danger { color: #e53935; }
.upload-box {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  min-height: 150rpx; border: 2rpx dashed #FFB08E; border-radius: 12rpx;
  background: #FFFDFC; gap: 6rpx; padding: 16rpx;
}
.upload-box.disabled { opacity: .65; }
.upload-icon { font-size: 34rpx; color: #FF6B35; font-weight: 700; }
.upload-text { font-size: 25rpx; color: #C2410C; }
.upload-hint { font-size: 21rpx; color: #999; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
</style>
