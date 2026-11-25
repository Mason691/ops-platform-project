<template>
  <view v-if="visible" class="overlay">
    <view class="sheet">
      <view class="sheet-head">
        <text class="title">上门打卡</text>
        <text class="close" @click="close">×</text>
      </view>

      <scroll-view scroll-y class="sheet-body">
        <view class="location-card">
          <view class="loc-row">
            <text class="loc-label">当前定位</text>
            <text class="loc-status" :class="locationStatus.toLowerCase()">
              {{ locationLabel }}
            </text>
          </view>
          <text v-if="locating" class="loc-text">定位中...</text>
          <text v-else-if="locationStatus === 'SUCCESS'" class="loc-text">
            {{ lng.toFixed(6) }}, {{ lat.toFixed(6) }}
          </text>
          <text v-else class="loc-text warn">
            定位失败不影响打卡，请手动填写上门地址
          </text>
          <input v-if="locationStatus !== 'SUCCESS'" v-model="address" class="input"
                 maxlength="120" placeholder="请输入上门地址（必填）" />
          <text class="relocate" @click="locate">重新定位</text>
        </view>

        <view class="field">
          <text class="label">现场照片 <text class="hint">（建议至少 1 张）</text></text>
          <BusinessAttachmentUploader v-model="photos" file-type="CONSTRUCT" :max="3"
                                      add-text="现场照片"
                                      hint-text="拍摄门牌/施工现场，作为到场凭证" />
        </view>

        <view class="field">
          <text class="label">备注</text>
          <textarea v-model="remark" class="textarea" maxlength="200"
                    placeholder="选填，例如：客户不在家、门锁无法打开等" />
        </view>
      </scroll-view>

      <view class="sheet-foot">
        <button class="btn ghost" :disabled="submitting" @click="close">取消</button>
        <button class="btn primary" :disabled="submitting" @click="submit">
          {{ submitting ? '提交中...' : '确认打卡' }}
        </button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import BusinessAttachmentUploader from '@/components/BusinessAttachmentUploader.vue'
import { getCurrentLocation } from '@/utils/location'

const props = defineProps({
  visible: { type: Boolean, default: false },
  orderId: { type: [Number, String], default: '' },
  submitting: { type: Boolean, default: false }
})
const emit = defineEmits(['update:visible', 'submit'])

const locating = ref(false)
const locationStatus = ref('FAILED')
const lng = ref(0)
const lat = ref(0)
const address = ref('')
const remark = ref('')
const photos = ref([])

const locationLabel = computed(() => ({
  SUCCESS: '定位成功',
  FAILED: '定位失败',
  MANUAL: '手动填写'
}[locationStatus.value] || '定位失败'))

watch(() => props.visible, open => {
  if (open) {
    photos.value = []
    remark.value = ''
    address.value = ''
    locate()
  }
})

async function locate() {
  locating.value = true
  try {
    const result = await getCurrentLocation()
    if (result && result.status === 'SUCCESS') {
      locationStatus.value = 'SUCCESS'
      lng.value = Number(result.lng) || 0
      lat.value = Number(result.lat) || 0
    } else {
      locationStatus.value = address.value ? 'MANUAL' : 'FAILED'
    }
  } finally {
    locating.value = false
  }
}

function close() {
  emit('update:visible', false)
}

function submit() {
  if (locating.value) return toast('定位中，请稍候')
  const status = locationStatus.value === 'SUCCESS' ? 'SUCCESS' : 'FAILED'
  const manualAddress = String(address.value || '').trim()
  if (status !== 'SUCCESS' && !manualAddress) {
    return toast('请填写上门地址')
  }
  const payload = {
    locationStatus: status === 'SUCCESS' ? 'SUCCESS' : 'FAILED',
    lng: status === 'SUCCESS' ? lng.value : null,
    lat: status === 'SUCCESS' ? lat.value : null,
    address: manualAddress || null,
    attachmentIds: (photos.value || []).map(file => file.id).filter(Boolean),
    remark: String(remark.value || '').trim() || null
  }
  if (!payload.attachmentIds.length) {
    uni.showModal({
      title: '未上传现场照片',
      content: '现场照片是到场凭证，确定不拍照直接打卡吗？',
      confirmText: '继续打卡',
      success: res => { if (res.confirm) emit('submit', payload) }
    })
    return
  }
  emit('submit', payload)
}

function toast(title) {
  uni.showToast({ title, icon: 'none' })
}
</script>

<style scoped>
.overlay {
  position: fixed; inset: 0; z-index: 900; background: rgba(0, 0, 0, .45);
  display: flex; align-items: flex-end; justify-content: center;
}
.sheet {
  width: 100%; max-width: 560px; max-height: 88vh; background: #fff;
  border-radius: 24rpx 24rpx 0 0; display: flex; flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom);
}
.sheet-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 26rpx 30rpx; border-bottom: 1rpx solid #F2F3F5;
}
.title { font-size: 32rpx; font-weight: 700; color: #1D2129; }
.close { font-size: 44rpx; color: #A9AEB8; line-height: 1; padding: 0 8rpx; }
.sheet-body { flex: 1; min-height: 0; padding: 24rpx 30rpx; box-sizing: border-box; }
.location-card { background: #F7F8FA; border-radius: 12rpx; padding: 20rpx; margin-bottom: 24rpx; }
.loc-row { display: flex; align-items: center; justify-content: space-between; }
.loc-label { font-size: 25rpx; color: #4E5969; font-weight: 600; }
.loc-status { font-size: 22rpx; padding: 2rpx 10rpx; border-radius: 6rpx; background: #FFF1F0; color: #C62828; }
.loc-status.success { background: #E8F8E8; color: #0B7A42; }
.loc-text { display: block; margin-top: 8rpx; font-size: 22rpx; color: #86909C; word-break: break-all; }
.loc-text.warn { color: #B26A00; }
.relocate { display: inline-block; margin-top: 10rpx; font-size: 23rpx; color: #2F6FEB; }
.input {
  margin-top: 14rpx; width: 100%; box-sizing: border-box; background: #fff;
  border: 1rpx solid #E5E6EB; border-radius: 10rpx; padding: 16rpx;
  font-size: 26rpx; color: #1D2129;
}
.field { margin-bottom: 24rpx; }
.label { display: block; font-size: 26rpx; color: #1D2129; font-weight: 600; margin-bottom: 12rpx; }
.hint { font-size: 21rpx; color: #A9AEB8; font-weight: 400; }
.textarea {
  width: 100%; box-sizing: border-box; background: #F7F8FA;
  /* App.vue 全局 uni-textarea{height:auto!important} 会压塌宿主高度，
     内层绝对定位 textarea 高度为 0；显式 !important 撑开才能聚焦录入。 */
  height: 120rpx !important;
  min-height: 120rpx !important;
  border-radius: 10rpx; padding: 16rpx; font-size: 26rpx; color: #1D2129;
}
.sheet-foot {
  display: flex; gap: 16rpx; padding: 20rpx 30rpx 26rpx;
  border-top: 1rpx solid #F2F3F5;
}
.btn {
  flex: 1; height: 84rpx; line-height: 84rpx; border-radius: 42rpx;
  font-size: 28rpx; text-align: center; border: none;
}
.btn.primary { background: #FF6B35; color: #fff; }
.btn.primary[disabled] { opacity: .55; }
.btn.ghost { background: #F2F3F5; color: #4E5969; }
</style>
