<template>
  <view v-if="visible" class="cc-mask" @click="close">
    <view class="cc-sheet" @click.stop>
      <view class="sheet-head">
        <text class="sheet-title">新建小区</text>
        <text class="sheet-close" @click="close">×</text>
      </view>

      <view class="sheet-body">
        <text class="label">小区名称 <text class="req">*</text></text>
        <input class="input" :value="name" maxlength="200"
               placeholder="请输入小区名称" @input="onInput" />

        <view class="district-row">
          <text class="district-label">归属区县</text>
          <text class="district-value">{{ districtText }}</text>
        </view>

        <CommunityLocationPanel
          :address="detailAddress" :district-name="districtName"
          :duplicate-prefix="duplicatePrefix" :full-address-preview="fullAddressPreview"
          :locating="locating" :location-status="locationStatus"
          :location-source-text="sourceText" :location-failure="locationFailure"
          :geocode-loading="geocodeLoading" :geocode-result="geocodeResult"
          :geocode-error="geocodeError" :geocode-stale="geocodeStale"
          :low-accuracy="lowAccuracy" :geocode-level="geocodeLevel"
          :coords-text="coordsText" :mismatch-text="mismatchText"
          @update:address="onAddressInput" @use-location="useCurrentLocation"
          @strip-prefix="stripPrefix" />

        <text v-if="error" class="ferr">{{ error }}</text>
        <text v-if="!districtConfigured" class="ferr">
          当前组织未配置所在区县，请联系管理员完善组织信息后再新建小区
        </text>
        <text v-else-if="geocodeError && !hasCoordinates && !geocodeLoading"
              class="save-anyway" @click="saveWithoutCoords">
          仍要保存（无坐标）
        </text>
        <text v-else class="hint">小区归属仍以组织所在区县为准，地址仅用于记录位置</text>
      </view>

      <view class="sheet-foot">
        <view class="btn ghost" @click="close"><text>取消</text></view>
        <view class="btn primary"
              :class="{ disabled: submitting || !districtConfigured || locating || geocodeLoading }"
              @click="submit">
          <text>{{ primaryText }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '@/api/index'
import CommunityLocationPanel from '@/components/CommunityLocationPanel.vue'
import { useCommunityGeocode } from '@/hooks/useCommunityGeocode'
import { regionPrefix } from '@/utils/address'

/**
 * 员工制单新建小区：详细地址手动输入为主（只填区县之后部分），
 * 确认时后端拼接完整地址并地理编码得到 GCJ-02 坐标；
 * 也可点击「使用当前位置自动填充」。编码失败允许确认后无坐标保存。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  initialName: { type: String, default: '' },
  districtName: { type: String, default: '' },
  districtConfigured: { type: Boolean, default: true },
  regionId: { type: [String, Number], default: null }
})
const emit = defineEmits(['update:visible', 'created'])

const name = ref('')
const error = ref('')
const submitting = ref(false)

const {
  detailAddress, locationStatus, locating, sourceText, locationFailure,
  geocodeLoading, geocodeResult, geocodeError, geocodeStale, lowAccuracy,
  geocodeLevel, hasCoordinates, coordsText, districtName, duplicatePrefix,
  fullAddressPreview, mismatch, mismatchText,
  onAddressInput, stripPrefix, geocode, useCurrentLocation, reset, payload
} = useCommunityGeocode({
  regionId: computed(() => props.regionId),
  regionName: computed(() => props.districtName)
})

const districtText = computed(() => {
  if (!props.districtConfigured) return '未配置'
  return (regionPrefix(props.districtName) || '组织所在区县') + '（已自动确定）'
})
const primaryText = computed(() => {
  if (geocodeLoading.value) return '正在获取坐标...'
  if (submitting.value) return '创建中...'
  return hasCoordinates.value ? '创建并选择' : '确认地址并获取坐标'
})

watch(() => props.visible, value => {
  if (!value) return
  name.value = props.initialName || ''
  error.value = ''
  reset()
}, { immediate: true })

function onInput(event) {
  name.value = String(event.detail.value ?? '')
  error.value = ''
}

function validate() {
  if (!props.districtConfigured) return '当前组织未配置所在区县，请联系管理员完善组织信息'
  const value = String(name.value || '').trim()
  if (!value) return '请输入小区名称'
  if (value.length > 200) return '小区名称不能超过200个字符'
  if (!String(detailAddress.value || '').trim()) {
    return '请填写详细地址（区县之后的部分，如 XX路XX号）'
  }
  return ''
}

async function submit() {
  if (submitting.value || locating.value || geocodeLoading.value) return
  const nameValue = String(name.value || '').trim()
  const invalid = validate()
  if (invalid) {
    error.value = invalid
    return
  }
  submitting.value = true
  try {
    if (!hasCoordinates.value) {
      // 第一步：编码并展示完整地址/坐标预览；用户确认后再点一次创建。
      const ok = await geocode()
      if (!ok) return
      return
    }
    if (!(await confirmMismatchIfNeeded())) return
    await doSubmit(nameValue)
  } finally {
    submitting.value = false
  }
}

async function saveWithoutCoords() {
  if (submitting.value) return
  const invalid = validate()
  if (invalid) {
    error.value = invalid
    return
  }
  const confirmed = await confirmModal('未获取到坐标，仍要保存该小区吗？', '仍要保存')
  if (!confirmed) return
  submitting.value = true
  try {
    await doSubmit(String(name.value || '').trim())
  } finally {
    submitting.value = false
  }
}

function confirmMismatchIfNeeded() {
  if (!mismatch.value) return Promise.resolve(true)
  return confirmModal(mismatchText.value, '继续创建')
}

function confirmModal(content, confirmText) {
  return new Promise(resolve => {
    uni.showModal({
      title: '请确认',
      content,
      confirmText,
      cancelText: '返回修改',
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
}

async function doSubmit(nameValue) {
  try {
    const created = await api.employeeCreateCommunity({ name: nameValue, ...payload() })
    const title = created && created.existed
      ? '该小区已存在，已为您选中'
      : (created && created.longitude != null
        ? '小区创建成功（已记录位置）' : '小区创建成功（未获取坐标）')
    uni.showToast({ title, icon: 'none' })
    emit('created', created || {})
    close()
  } catch (e) {
    error.value = (e && e.msg) || '新建小区失败，请重试'
  }
}

function close() {
  emit('update:visible', false)
}
</script>

<style scoped>
.cc-mask {
  position: fixed; inset: 0; z-index: 210;
  background: rgba(0, 0, 0, .42);
  display: flex; align-items: flex-end;
}
.cc-sheet {
  width: 100%; background: #fff; border-radius: 24rpx 24rpx 0 0;
  display: flex; flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom);
  max-height: 90vh;
}
.sheet-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 26rpx 28rpx 18rpx; border-bottom: 1rpx solid #f2f2f2;
}
.sheet-title { font-size: 31rpx; font-weight: 700; color: #1D2129; }
.sheet-close { font-size: 44rpx; color: #999; line-height: 1; padding: 0 8rpx; }
.sheet-body { padding: 24rpx 28rpx 8rpx; overflow-y: auto; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 12rpx; }
.req { color: #e53935; }
.input {
  width: 100%; height: 84rpx !important; min-height: 84rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 0 20rpx;
  font-size: 29rpx; color: #1D2129; box-sizing: border-box;
}
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 10rpx; }
.save-anyway {
  display: block; font-size: 23rpx; color: #FF6B35; margin-top: 10rpx;
  font-weight: 600; padding: 4rpx 0;
}
.district-row {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 18rpx; padding: 18rpx 20rpx; background: #f7f7f7; border-radius: 12rpx;
}
.district-label { font-size: 25rpx; color: #666; }
.district-value { font-size: 26rpx; color: #1D2129; font-weight: 600; }
.hint { display: block; font-size: 21rpx; color: #999; margin-top: 10rpx; line-height: 1.5; }
.sheet-foot {
  display: flex; gap: 18rpx; padding: 20rpx 28rpx 22rpx;
  border-top: 1rpx solid #f2f2f2;
}
.btn {
  flex: 1; height: 82rpx; line-height: 82rpx; text-align: center;
  border-radius: 41rpx; font-size: 28rpx; font-weight: 600;
}
.btn.ghost { background: #fff; color: #FF6B35; border: 2rpx solid #FF6B35; flex: 0 0 34%; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn.primary.disabled { opacity: .6; }
</style>
