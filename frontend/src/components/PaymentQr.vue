<template>
  <view class="payment-qr">
    <view v-if="loading" class="qr-state"><text>收款码加载中...</text></view>
    <view v-else-if="error" class="qr-error">
      <text class="qr-error-text">{{ error }}</text>
      <view class="qr-retry" @click="load"><text>重试</text></view>
    </view>
    <image v-else-if="src" class="qr-image" :src="src" mode="widthFix"
           @click="preview" @error="onImageError" />
    <view v-else class="qr-state"><text>收款码暂不可用，请联系工作人员</text></view>
    <text v-if="src && !loading && !error" class="qr-hint">请使用微信扫描上方收款码完成支付</text>
  </view>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { BASE_URL } from '@/config'

/**
 * 收款码展示：通过员工鉴权接口取图；后端对 jar 内置图片做 ECDSA 验签，
 * 校验失败返回 503 + 统一文案，这里直接展示错误文案（不暴露文件路径）。
 * 图片资源不在 static 目录，前端无法绕过接口直连静态文件。
 */
const loading = ref(true)
const error = ref('')
const src = ref('')
let disposed = false
let objectUrl = ''

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  revoke()
})

async function load() {
  loading.value = true
  error.value = ''
  src.value = ''
  revoke()
  const token = uni.getStorageSync('ops_employee_token') || ''
  if (!token) {
    loading.value = false
    error.value = '登录已过期，请重新登录'
    return
  }
  // #ifdef H5
  try {
    const res = await fetch(BASE_URL + '/api/ops/employee/business-data/payment-qr', {
      headers: { Authorization: 'Bearer ' + token },
      cache: 'no-store'
    })
    const contentType = res.headers.get('content-type') || ''
    if (!res.ok || contentType.includes('application/json')) {
      const body = await res.json().catch(() => ({}))
      if (!disposed) error.value = body.msg || '收款码暂不可用，请联系工作人员'
      return
    }
    const blob = await res.blob()
    if (disposed) return
    objectUrl = URL.createObjectURL(blob)
    src.value = objectUrl
  } catch (e) {
    if (!disposed) error.value = '收款码暂不可用，请联系工作人员'
  } finally {
    if (!disposed) loading.value = false
  }
  // #endif
  // #ifndef H5
  uni.downloadFile({
    url: BASE_URL + '/api/ops/employee/business-data/payment-qr',
    header: { Authorization: 'Bearer ' + token },
    success: (res) => {
      if (disposed) return
      if (res.statusCode === 200 && res.tempFilePath) {
        src.value = res.tempFilePath
      } else {
        error.value = '收款码暂不可用，请联系工作人员'
      }
    },
    fail: () => { if (!disposed) error.value = '收款码暂不可用，请联系工作人员' },
    complete: () => { if (!disposed) loading.value = false }
  })
  // #endif
}

function preview() {
  if (!src.value) return
  uni.previewImage({ urls: [src.value], current: src.value })
}

/** 图片解码/渲染失败（哈希校验通过但浏览器无法展示）时也给出重试入口，不静默空白。 */
function onImageError() {
  revoke()
  src.value = ''
  error.value = '收款码暂不可用，请联系工作人员'
}

function revoke() {
  // #ifdef H5
  if (objectUrl && typeof URL !== 'undefined') URL.revokeObjectURL(objectUrl)
  // #endif
  objectUrl = ''
}
</script>

<style scoped>
.payment-qr {
  display: flex; flex-direction: column; align-items: center;
  background: #fff; border-radius: 14rpx; padding: 24rpx; gap: 12rpx;
}
.qr-image { width: 360rpx; height: 360rpx; border-radius: 12rpx; background: #f7f7f7; }
.qr-state { padding: 40rpx 0; font-size: 25rpx; color: #999; }
.qr-error { display: flex; flex-direction: column; align-items: center; gap: 14rpx; padding: 24rpx 0; }
.qr-error-text { font-size: 24rpx; color: #e53935; text-align: center; line-height: 1.5; }
.qr-retry {
  padding: 10rpx 28rpx; border-radius: 28rpx; background: #FF6B35;
  color: #fff; font-size: 24rpx;
}
.qr-hint { font-size: 22rpx; color: #999; text-align: center; }
</style>
