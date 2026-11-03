<template>
  <view class="voucher-uploader">
    <view class="voucher-grid">
      <view v-for="(url, index) in modelValue" :key="url + index" class="voucher-item">
        <image class="voucher-img" :src="assetUrl(url)" mode="aspectFill" @click="preview(index)" />
        <view class="voucher-del" @click.stop="remove(index)">×</view>
      </view>
      <view v-if="modelValue.length < max" class="voucher-add" :class="{ disabled: uploading }" @click="choose">
        <text class="add-icon">{{ uploading ? '…' : '＋' }}</text>
        <text class="add-text">{{ uploading ? '上传中' : '上传凭证' }}</text>
      </view>
    </view>
    <text class="voucher-hint">最多 {{ max }} 张，支持拍照或相册，图片自动压缩</text>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { BASE_URL } from '@/config'
import { assetUrl } from '@/utils/image'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  max: { type: Number, default: 3 }
})
const emit = defineEmits(['update:modelValue'])
const uploading = ref(false)
const displayUrls = computed(() => props.modelValue.map(assetUrl))

function choose() {
  if (uploading.value) return
  const remain = props.max - props.modelValue.length
  if (remain <= 0) {
    uni.showToast({ title: `最多上传${props.max}张`, icon: 'none' })
    return
  }
  uni.chooseImage({
    count: remain,
    sizeType: ['compressed'],
    sourceType: ['album', 'camera'],
    success: async (res) => {
      uploading.value = true
      try {
        const next = [...props.modelValue]
        for (const path of res.tempFilePaths) {
          next.push(await uploadOne(path))
          emit('update:modelValue', [...next])
        }
        uni.showToast({ title: '上传成功', icon: 'success' })
      } catch (e) {
        uni.showToast({ title: e.message || '上传失败', icon: 'none' })
      } finally {
        uploading.value = false
      }
    },
    fail: (e) => {
      if (!String(e.errMsg || '').includes('cancel')) {
        uni.showToast({ title: '选择图片失败', icon: 'none' })
      }
    }
  })
}

function preview(index) {
  uni.previewImage({ urls: displayUrls.value, current: displayUrls.value[index] })
}

function remove(index) {
  if (uploading.value) return
  const next = [...props.modelValue]
  next.splice(index, 1)
  emit('update:modelValue', next)
}

async function uploadOne(path) {
  // #ifdef H5
  const blob = await fetch(path).then(r => r.blob())
  return await uploadBlob(await compressImage(blob))
  // #endif
  // #ifndef H5
  return await uploadFile(path)
  // #endif
}

function compressImage(blob) {
  // #ifdef H5
  if (!blob) return Promise.resolve(blob)
  // 统一重编码为 JPEG：既压缩体积，也规避 iOS HEIC 等浏览器不可预览的格式
  return new Promise((resolve) => {
    const objectUrl = URL.createObjectURL(blob)
    const img = new Image()
    img.onload = () => {
      const scale = Math.min(1, 1600 / Math.max(img.width, img.height))
      const canvas = document.createElement('canvas')
      canvas.width = Math.round(img.width * scale)
      canvas.height = Math.round(img.height * scale)
      const ctx = canvas.getContext('2d')
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, canvas.width, canvas.height)
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
      canvas.toBlob((out) => {
        URL.revokeObjectURL(objectUrl)
        resolve(out && out.size < blob.size ? out : blob)
      }, 'image/jpeg', 0.82)
    }
    img.onerror = () => { URL.revokeObjectURL(objectUrl); resolve(blob) }
    img.src = objectUrl
  })
  // #endif
  // #ifndef H5
  return Promise.resolve(blob)
  // #endif
}

function uploadBlob(blob) {
  // #ifdef H5
  const token = uni.getStorageSync('ops_employee_token') || ''
  const formData = new FormData()
  formData.append('file', blob, 'voucher_' + Date.now() + '.jpg')
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', BASE_URL + '/api/ops/files/upload')
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    xhr.onload = () => {
      try {
        const body = JSON.parse(xhr.responseText)
        if (body.code === 200 && body.data && body.data.url) resolve(body.data.url)
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

function uploadFile(filePath) {
  const token = uni.getStorageSync('ops_employee_token') || ''
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: BASE_URL + '/api/ops/files/upload',
      filePath,
      name: 'file',
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: (res) => {
        try {
          const body = JSON.parse(res.data)
          if (body.code === 200 && body.data && body.data.url) resolve(body.data.url)
          else reject(new Error(body.msg || '上传失败'))
        } catch (e) {
          reject(new Error('上传失败'))
        }
      },
      fail: reject
    })
  })
}
</script>

<style scoped>
.voucher-uploader { width: 100%; }
.voucher-grid { display: flex; flex-wrap: wrap; gap: 16rpx; }
.voucher-item {
  position: relative; width: 180rpx; height: 180rpx;
  border-radius: 12rpx; overflow: hidden; background: #f5f5f5;
}
.voucher-img { width: 100%; height: 100%; display: block; }
.voucher-del {
  position: absolute; top: 0; right: 0; width: 44rpx; height: 44rpx;
  line-height: 40rpx; text-align: center; color: #fff; font-size: 34rpx;
  background: rgba(0, 0, 0, .55); border-bottom-left-radius: 12rpx;
}
.voucher-add {
  width: 180rpx; height: 180rpx; border-radius: 12rpx;
  border: 2rpx dashed #d0d0d0; background: #fafafa;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8rpx; box-sizing: border-box;
}
.voucher-add.disabled { opacity: .6; }
.add-icon { font-size: 48rpx; color: #bbb; line-height: 1; }
.add-text { font-size: 22rpx; color: #999; }
.voucher-hint { display: block; margin-top: 12rpx; font-size: 22rpx; color: #999; line-height: 1.5; }
</style>
