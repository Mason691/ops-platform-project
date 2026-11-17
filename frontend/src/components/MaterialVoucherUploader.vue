<template>
  <view class="voucher-uploader">
    <view class="grid">
      <view v-for="(item, index) in items" :key="item.url + index" class="file-item">
        <image v-if="isImage(item.url)" class="thumb" :src="assetUrl(item.url)" mode="aspectFill"
               @click="preview(index)" />
        <view v-else class="pdf-thumb" @click="preview(index)">
          <text class="pdf-icon">PDF</text>
        </view>
        <view class="file-del" @click.stop="remove(index)">×</view>
      </view>
      <view v-if="items.length < max" class="add" :class="{ disabled: uploading }" @click="choose">
        <text class="add-icon">{{ uploading ? progress + '%' : '＋' }}</text>
        <text class="add-text">{{ uploading ? '上传中' : '上传凭证' }}</text>
      </view>
    </view>
    <text v-if="error" class="ferr">{{ error }}</text>
    <text v-else class="hint">已上传 {{ items.length }}/{{ max }} 个；重复上传前请先删除旧文件</text>
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { BASE_URL } from '@/config'
import { assetUrl } from '@/utils/image'

/**
 * 物资使用凭证上传：JPG/PNG/WEBP/PDF，单个 ≤10MB，最多 5 个。
 * H5 用 file input + XHR（带员工 JWT）；全部上传完成后只回写一次，避免中途
 * 触发确认页校验导致上传组件被卸载。
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  max: { type: Number, default: 5 },
  error: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const uploading = ref(false)
const progress = ref(0)
let disposed = false
const activeXhrs = new Set()

const items = computed(() =>
  (props.modelValue || []).map(url => ({ url })))

onBeforeUnmount(() => {
  disposed = true
  activeXhrs.forEach(xhr => { try { xhr.abort() } catch (e) { /* 已结束 */ } })
  activeXhrs.clear()
})

function isImage(url) {
  return /\.(jpe?g|png|webp)$/i.test(String(url || ''))
}

function choose() {
  if (uploading.value) return
  const remain = props.max - items.value.length
  if (remain <= 0) {
    uni.showToast({ title: `最多上传${props.max}个凭证`, icon: 'none' })
    return
  }
  // #ifdef H5
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.jpg,.jpeg,.png,.webp,.pdf'
  input.multiple = true
  input.onchange = (event) => {
    const files = Array.from(event.target.files || []).slice(0, remain)
    if (files.length) uploadH5(files)
  }
  input.click()
  // #endif
  // #ifndef H5
  uni.chooseMessageFile({
    count: remain,
    type: 'file',
    extension: ['jpg', 'jpeg', 'png', 'webp', 'pdf'],
    success: (res) => uploadMp((res.tempFiles || []).slice(0, remain)),
    fail: (e) => {
      if (!String(e.errMsg || '').includes('cancel')) {
        uni.showToast({ title: '选择文件失败', icon: 'none' })
      }
    }
  })
  // #endif
}

function validate(name, size) {
  if (!/\.(jpe?g|png|webp|pdf)$/i.test(String(name || ''))) {
    uni.showToast({ title: '仅支持 JPG/PNG/WEBP/PDF', icon: 'none' })
    return false
  }
  if (size > 10 * 1024 * 1024) {
    uni.showToast({ title: '单个凭证不能超过10MB', icon: 'none' })
    return false
  }
  return true
}

async function uploadH5(files) {
  // #ifdef H5
  const valid = files.filter(f => validate(f.name, f.size))
  if (!valid.length) return
  uploading.value = true
  progress.value = 0
  const next = [...(props.modelValue || [])]
  let done = 0
  try {
    for (const file of valid) {
      next.push(await uploadOneH5(file, done, valid.length))
      done += 1
      progress.value = Math.round((done / valid.length) * 100)
    }
  } catch (e) {
    if (!disposed) uni.showToast({ title: e.message || '上传失败', icon: 'none' })
  } finally {
    uploading.value = false
    progress.value = 0
    if (!disposed && next.length > (props.modelValue || []).length) {
      emit('update:modelValue', next)
    }
  }
  // #endif
}

function uploadOneH5(file, index, total) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('ops_employee_token') || ''
    const formData = new FormData()
    formData.append('file', file, file.name)
    const xhr = new XMLHttpRequest()
    activeXhrs.add(xhr)
    xhr.open('POST', BASE_URL + '/api/ops/employee/material/voucher')
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        const base = Math.round((index / total) * 100)
        progress.value = Math.min(99, base + Math.round((event.loaded * 100) / event.total / total))
      }
    }
    xhr.onload = () => {
      activeXhrs.delete(xhr)
      try {
        const body = JSON.parse(xhr.responseText)
        if (body.code === 200 && body.data && body.data.url) resolve(body.data.url)
        else reject(new Error(body.msg || '上传失败'))
      } catch (e) {
        reject(new Error('上传失败，请重试'))
      }
    }
    xhr.onerror = () => {
      activeXhrs.delete(xhr)
      reject(new Error('网络错误，请重试'))
    }
    xhr.send(formData)
  })
}

async function uploadMp(files) {
  // #ifndef H5
  const valid = files.filter(f => validate(f.name || '', Number(f.size) || 0))
  if (!valid.length) return
  uploading.value = true
  const next = [...(props.modelValue || [])]
  try {
    for (const file of valid) {
      const url = await uploadOneMp(file.path || file.tempFilePath)
      next.push(url)
    }
  } catch (e) {
    uni.showToast({ title: e.message || '上传失败', icon: 'none' })
  } finally {
    uploading.value = false
    if (!disposed && next.length > (props.modelValue || []).length) {
      emit('update:modelValue', next)
    }
  }
  // #endif
}

function uploadOneMp(filePath) {
  // #ifndef H5
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('ops_employee_token') || ''
    uni.uploadFile({
      url: BASE_URL + '/api/ops/employee/material/voucher',
      filePath,
      name: 'file',
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: (res) => {
        try {
          const body = JSON.parse(res.data)
          if (body.code === 200 && body.data && body.data.url) resolve(body.data.url)
          else reject(new Error(body.msg || '上传失败'))
        } catch (e) {
          reject(new Error('上传失败，请重试'))
        }
      },
      fail: () => reject(new Error('上传失败，请重试'))
    })
  })
  // #endif
}

function preview(index) {
  const url = assetUrl(items.value[index].url)
  if (isImage(url)) {
    uni.previewImage({ urls: items.value.map(item => assetUrl(item.url)), current: url })
    return
  }
  // #ifdef H5
  if (typeof window !== 'undefined') window.open(url, '_blank')
  // #endif
  // #ifndef H5
  uni.openDocument({ filePath: url, showMenu: true })
  // #endif
}

function remove(index) {
  if (uploading.value) return
  const next = [...(props.modelValue || [])]
  next.splice(index, 1)
  emit('update:modelValue', next)
}
</script>

<style scoped>
.voucher-uploader { display: flex; flex-direction: column; gap: 12rpx; }
.grid { display: flex; flex-wrap: wrap; gap: 14rpx; }
.file-item { position: relative; width: 150rpx; height: 150rpx; border-radius: 10rpx; overflow: hidden; background: #f5f5f5; }
.thumb { width: 100%; height: 100%; }
.pdf-thumb {
  width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;
  background: #FFF1F0;
}
.pdf-icon { font-size: 26rpx; color: #e53935; font-weight: 700; }
.file-del {
  position: absolute; right: 0; top: 0; width: 40rpx; height: 40rpx; line-height: 36rpx;
  text-align: center; border-radius: 0 0 0 10rpx; background: rgba(0, 0, 0, .5);
  color: #fff; font-size: 28rpx;
}
.add {
  width: 150rpx; height: 150rpx; border: 2rpx dashed #FFB99A; border-radius: 10rpx;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 6rpx; background: #FFFDFC;
}
.add.disabled { opacity: .6; }
.add-icon { font-size: 40rpx; color: #FF6B35; line-height: 1; }
.add-text { font-size: 22rpx; color: #FF6B35; }
.ferr { display: block; font-size: 22rpx; color: #e53935; }
.hint { display: block; font-size: 21rpx; color: #999; line-height: 1.5; }
</style>
