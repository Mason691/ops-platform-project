<template>
  <view class="attachment-uploader">
    <view class="attachment-grid">
      <view v-for="(file, index) in modelValue" :key="file.id || file.url || index"
            class="attachment-item">
        <image v-if="isImage(file)" class="attachment-img" :src="assetUrl(file.url)"
               mode="aspectFill" @click="preview(file)" />
        <view v-else class="attachment-file" @click="preview(file)">
          <text class="file-badge">PDF</text>
          <text class="file-name">{{ file.fileName || file.name || '合同附件' }}</text>
        </view>
        <view class="attachment-del" @click.stop="remove(index)">×</view>
      </view>
      <view v-if="modelValue.length < max" class="attachment-add"
            :class="{ disabled: uploading }" @click="add">
        <text class="add-icon">{{ uploading ? progress + '%' : '＋' }}</text>
        <text class="add-text">{{ uploading ? '上传中' : addText }}</text>
        <view v-if="uploading" class="upload-progress">
          <view class="upload-progress-fill" :style="{ width: progress + '%' }"></view>
        </view>
      </view>
    </view>
    <text class="attachment-hint">{{ hintText }}</text>
    <text v-if="error || uploadError" class="ferr">{{ error || uploadError }}</text>
  </view>
</template>

<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { BASE_URL } from '@/config'
import { api } from '@/api/index'
import { assetUrl } from '@/utils/image'
import { compressImageBlob } from '@/utils/imageCompress'

/**
 * 业务附件上传：图片（拍照/相册）与 PDF（合同附件），统一走后端
 * /api/ops/employee/business-data/attachments?fileType=RECEIPT|PAYMENT|CONTRACT。
 * 收据图片原图上传（不压缩，保证红字 OCR 清晰度）；支付凭证/合同图片在 H5 端压缩后上传，
 * 服务端仍会二次压缩兜底；返回 {id,url,fileName,...} 供提交引用。
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  fileType: { type: String, required: true },
  max: { type: Number, default: 3 },
  accept: { type: String, default: 'image' },
  maxSizeMB: { type: Number, default: 10 },
  addText: { type: String, default: '上传' },
  hintText: { type: String, default: '' },
  error: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'uploaded'])

const uploading = ref(false)
const progress = ref(0)
const uploadError = ref('')
let disposed = false
const activeXhrs = new Set()

onBeforeUnmount(() => {
  disposed = true
  activeXhrs.forEach(xhr => { try { xhr.abort() } catch (e) { /* ended */ } })
  activeXhrs.clear()
})

function isImage(file) {
  if (!file) return false
  const type = String(file.contentType || '')
  if (type.startsWith('image/')) return true
  return /\.(jpe?g|png)$/i.test(file.fileName || file.name || file.url || '')
}

function preview(file) {
  if (!file || !file.url) return
  if (isImage(file)) {
    const urls = props.modelValue.filter(isImage).map(item => assetUrl(item.url))
    uni.previewImage({ urls, current: assetUrl(file.url) })
    return
  }
  // #ifdef H5
  if (typeof window !== 'undefined') window.open(assetUrl(file.url), '_blank')
  // #endif
  // #ifndef H5
  uni.downloadFile({
    url: assetUrl(file.url),
    success: res => {
      if (res.statusCode === 200) {
        uni.openDocument({ filePath: res.tempFilePath, fileType: 'pdf', showMenu: true })
      } else {
        uni.showToast({ title: '文件下载失败', icon: 'none' })
      }
    },
    fail: () => uni.showToast({ title: '文件下载失败', icon: 'none' })
  })
  // #endif
}

function remove(index) {
  if (uploading.value) return
  const removed = props.modelValue[index]
  const next = [...props.modelValue]
  next.splice(index, 1)
  emit('update:modelValue', next)
  // 尽力删除未绑定附件（服务端也会每日清理）；删除失败不阻塞表单
  if (removed && removed.id) {
    api.employeeDeleteBusinessAttachment(removed.id).catch(() => {})
  }
}

function add() {
  if (uploading.value) return
  if (props.modelValue.length >= props.max) {
    uni.showToast({ title: `最多上传${props.max}个`, icon: 'none' })
    return
  }
  if (props.accept !== 'image-pdf') {
    chooseImages()
    return
  }
  uni.showActionSheet({
    itemList: ['拍照', '从相册选择', '选择 PDF'],
    success: res => {
      if (res.tapIndex === 2) choosePdf()
      else chooseImages()
    }
  })
}

function chooseImages() {
  const remain = props.max - props.modelValue.length
  if (remain <= 0) return
  uni.chooseImage({
    count: remain,
    // 收据需原图识别红字，必须取原图；其他图片可先由系统压缩再本地二次压缩
    sizeType: props.fileType === 'RECEIPT' ? ['original'] : ['compressed'],
    sourceType: ['album', 'camera'],
    success: async res => {
      const paths = res.tempFilePaths || []
      const files = res.tempFiles || []
      await uploadBatch(paths.map((path, index) => {
        const file = files[index]
        const isBlob = typeof Blob !== 'undefined' && file instanceof Blob
        const rawName = (file && file.name) || ''
        return {
          path,
          file: isBlob ? file : null,
          name: /\.[a-z0-9]+$/i.test(rawName)
            ? rawName : `image_${Date.now()}_${index}.jpg`,
          size: file && file.size
        }
      }))
    },
    fail: e => {
      if (!String(e.errMsg || '').includes('cancel')) {
        uni.showToast({ title: '选择图片失败', icon: 'none' })
      }
    }
  })
}

function choosePdf() {
  const remain = props.max - props.modelValue.length
  if (remain <= 0) return
  // #ifdef H5
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.pdf'
  input.onchange = event => {
    const selected = event.target && event.target.files && event.target.files[0]
    if (selected) uploadBatch([{ file: selected, name: selected.name, size: selected.size }])
  }
  input.click()
  // #endif
  // #ifndef H5
  uni.chooseMessageFile({
    count: remain,
    type: 'file',
    extension: ['pdf'],
    success: res => {
      const files = (res.tempFiles || []).map(file => ({
        path: file.path || file.tempFilePath,
        name: file.name || 'contract.pdf',
        size: file.size
      }))
      uploadBatch(files)
    },
    fail: e => {
      if (!String(e.errMsg || '').includes('cancel')) {
        uni.showToast({ title: '选择文件失败', icon: 'none' })
      }
    }
  })
  // #endif
}

/** 串行上传，全部完成后一次性回写，避免父级校验在中间态卸载组件。 */
async function uploadBatch(items) {
  if (!items.length) return
  const next = [...props.modelValue]
  let uploaded = 0
  uploadError.value = ''
  uploading.value = true
  try {
    for (const item of items) {
      progress.value = 0
      if (!validateFile(item.name)) continue
      // 粗筛：避免把超大原图读进内存做压缩；最终限额在压缩后校验
      if (Number(item.size) > 50 * 1024 * 1024) {
        failUpload('文件过大，请选择50MB以内的文件')
        continue
      }
      const record = await uploadOne(item)
      if (record) {
        next.push(record)
        uploaded += 1
        emit('uploaded', record)
      }
    }
  } catch (e) {
    if (!disposed) {
      uploadError.value = e.message || '上传失败，请重试'
      uni.showToast({ title: uploadError.value, icon: 'none' })
    }
  } finally {
    uploading.value = false
    progress.value = 0
    if (!disposed && uploaded) emit('update:modelValue', next)
    if (!disposed && uploaded === items.length) {
      uni.showToast({ title: '上传成功', icon: 'success' })
    }
  }
}

async function uploadOne(item) {
  // #ifdef H5
  const blob = item.file || await fetch(item.path).then(r => r.blob())
  const isPdf = /\.pdf$/i.test(item.name || '') || blob.type === 'application/pdf'
  // 收据原图不压缩；支付凭证/合同图片压缩后上传
  const payload = isPdf || props.fileType === 'RECEIPT'
    ? blob : await compressImageBlob(blob)
  const name = isPdf
    ? (item.name || 'contract.pdf')
    : (props.fileType === 'RECEIPT'
      ? (item.name || 'receipt.jpg')
      : (item.name || 'image.jpg').replace(/\.[^.]+$/, '.jpg'))
  // 收据用原图、其他图片用压缩产物：限额校验必须放在压缩之后
  if (payload.size > props.maxSizeMB * 1024 * 1024) {
    throw new Error(`单个文件不能超过${props.maxSizeMB}MB`)
  }
  return uploadBlob(payload, name)
  // #endif
  // #ifndef H5
  if (Number(item.size) > props.maxSizeMB * 1024 * 1024) {
    throw new Error(`单个文件不能超过${props.maxSizeMB}MB`)
  }
  return uploadMp(item.path, item.name)
  // #endif
}

function uploadBlob(blob, name) {
  // #ifdef H5
  const token = uni.getStorageSync('ops_employee_token') || ''
  const formData = new FormData()
  formData.append('file', blob, name)
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    activeXhrs.add(xhr)
    xhr.open('POST', BASE_URL + '/api/ops/employee/business-data/attachments?fileType='
      + encodeURIComponent(props.fileType))
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    xhr.upload.onprogress = event => {
      if (event.lengthComputable && event.total > 0) {
        progress.value = Math.min(99, Math.round((event.loaded * 100) / event.total))
      }
    }
    xhr.onloadend = () => activeXhrs.delete(xhr)
    xhr.onabort = () => {
      activeXhrs.delete(xhr)
      reject(new Error('上传已取消'))
    }
    xhr.onload = () => {
      try {
        const body = JSON.parse(xhr.responseText)
        if (body.code === 200 && body.data && body.data.id) resolve(mapRecord(body.data))
        else reject(new Error(body.msg || '上传失败'))
      } catch (e) {
        reject(new Error('上传失败'))
      }
    }
    xhr.onerror = () => reject(new Error('网络错误，请重试'))
    xhr.send(formData)
  })
  // #endif
  // #ifndef H5
  return Promise.reject(new Error('当前端不支持该上传方式'))
  // #endif
}

function uploadMp(filePath, fileName) {
  // #ifndef H5
  const token = uni.getStorageSync('ops_employee_token') || ''
  return new Promise((resolve, reject) => {
    const task = uni.uploadFile({
      url: BASE_URL + '/api/ops/employee/business-data/attachments',
      filePath,
      name: 'file',
      formData: { fileType: props.fileType },
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: res => {
        try {
          const body = JSON.parse(res.data)
          if (body.code === 200 && body.data && body.data.id) resolve(mapRecord(body.data))
          else reject(new Error(body.msg || '上传失败'))
        } catch (e) {
          reject(new Error('上传失败'))
        }
      },
      fail: reject
    })
    if (task && typeof task.onProgressUpdate === 'function') {
      task.onProgressUpdate(res => {
        progress.value = Math.min(99, Number(res.progress) || 0)
      })
    }
  })
  // #endif
  // #ifdef H5
  return Promise.reject(new Error('当前端不支持该上传方式'))
  // #endif
}

function mapRecord(data) {
  return {
    id: data.id,
    url: data.fileUrl,
    fileName: data.fileName || '',
    name: data.fileName || '',
    fileSize: data.fileSize,
    size: data.fileSize,
    contentType: data.contentType || '',
    fileType: data.fileType || props.fileType
  }
}

function validateFile(name) {
  const fileName = String(name || '')
  const isPdf = /\.pdf$/i.test(fileName)
  const isImage = /\.(jpe?g|png)$/i.test(fileName)
  if (props.accept === 'image-pdf') {
    if (!isPdf && !isImage) {
      failUpload('仅支持 JPG、PNG、PDF')
      return false
    }
  } else if (!isImage) {
    failUpload('仅支持 JPG、PNG 图片')
    return false
  }
  return true
}

function failUpload(message) {
  uploadError.value = message
  uni.showToast({ title: message, icon: 'none' })
}
</script>

<style scoped>
.attachment-uploader { width: 100%; }
.attachment-grid { display: flex; flex-wrap: wrap; gap: 16rpx; }
.attachment-item {
  position: relative; width: 180rpx; height: 180rpx;
  border-radius: 12rpx; overflow: hidden; background: #f5f5f5;
}
.attachment-img { width: 100%; height: 100%; display: block; }
.attachment-file {
  width: 100%; height: 100%; box-sizing: border-box; padding: 16rpx;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8rpx; background: #FFF7F2;
}
.file-badge {
  font-size: 26rpx; font-weight: 700; color: #C2410C;
  border: 2rpx solid #FFB99A; border-radius: 8rpx; padding: 2rpx 10rpx;
}
.file-name {
  width: 100%; font-size: 20rpx; color: #666; text-align: center;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.attachment-del {
  position: absolute; top: 0; right: 0; width: 44rpx; height: 44rpx;
  line-height: 40rpx; text-align: center; color: #fff; font-size: 34rpx;
  background: rgba(0, 0, 0, .55); border-bottom-left-radius: 12rpx;
}
.attachment-add {
  width: 180rpx; height: 180rpx; border-radius: 12rpx;
  border: 2rpx dashed #d0d0d0; background: #fafafa;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8rpx; box-sizing: border-box;
}
.attachment-add.disabled { opacity: .6; }
.upload-progress {
  width: 120rpx; height: 6rpx; background: #eee; border-radius: 3rpx; overflow: hidden;
}
.upload-progress-fill {
  height: 100%; background: #FF6B35; border-radius: 3rpx;
  transition: width .2s ease;
}
.add-icon { font-size: 48rpx; color: #bbb; line-height: 1; }
.add-text { font-size: 22rpx; color: #999; }
.attachment-hint { display: block; margin-top: 12rpx; font-size: 22rpx; color: #999; line-height: 1.5; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
</style>
