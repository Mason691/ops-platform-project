<script setup>
/**
 * 新建小区地址面板：手动输入为主，定位为辅。
 * 只负责展示与事件；状态机/地理编码在 useCommunityGeocode。
 */
const props = defineProps({
  address: { type: String, default: '' },
  districtName: { type: String, default: '' },
  duplicatePrefix: { type: Boolean, default: false },
  fullAddressPreview: { type: String, default: '' },
  locating: { type: Boolean, default: false },
  locationStatus: { type: String, default: 'idle' },
  locationSourceText: { type: String, default: '' },
  locationFailure: { type: String, default: '' },
  geocodeLoading: { type: Boolean, default: false },
  geocodeResult: { type: Object, default: null },
  geocodeError: { type: String, default: '' },
  geocodeStale: { type: Boolean, default: false },
  lowAccuracy: { type: Boolean, default: false },
  geocodeLevel: { type: String, default: '' },
  coordsText: { type: String, default: '—' },
  mismatchText: { type: String, default: '' }
})
defineEmits(['update:address', 'use-location', 'strip-prefix'])
</script>

<template>
  <view class="loc-panel">
    <view class="label-row">
      <text class="label">详细地址 <text class="req">*</text></text>
      <text class="district-tag">{{ districtName || '组织区县' }}</text>
    </view>
    <textarea class="address-input" :value="address" maxlength="120" :disabled="locating"
              placeholder="请输入区县之后的地址，如：XX路XX号 / XX小区X栋"
              @input="$emit('update:address', $event.detail.value)" />
    <text class="hint">所属区县：{{ districtName || '组织所在区县' }}（已自动确定，无需填写省市区）</text>

    <view v-if="duplicatePrefix" class="warn-row">
      <text class="warn-text">无需填写省市区，请只填区县之后的地址</text>
      <text class="warn-action" @click="$emit('strip-prefix')">一键去除</text>
    </view>

    <view class="loc-row">
      <view class="loc-btn" :class="{ disabled: locating }" @click="$emit('use-location')">
        <text>{{ locating ? '正在定位…'
          : (locationStatus === 'success' || locationStatus === 'partial'
            ? '重新定位' : '使用当前位置自动填充') }}</text>
      </view>
      <text v-if="locationSourceText && locationStatus === 'success'"
            class="source-text">{{ locationSourceText }}</text>
    </view>
    <text v-if="locationFailure" class="warn-text">{{ locationFailure }}</text>

    <view v-if="geocodeLoading" class="preview is-loading">
      <text>正在根据地址获取坐标…</text>
    </view>
    <view v-else-if="geocodeResult" class="preview">
      <text class="preview-title">完整地址</text>
      <text class="preview-address">{{ fullAddressPreview }}</text>
      <view class="preview-meta">
        <text>坐标 {{ coordsText }}</text>
        <text v-if="geocodeLevel">精度：{{ geocodeLevel }}</text>
        <text v-if="sourceText">{{ sourceText }}</text>
      </view>
      <text v-if="lowAccuracy" class="warn-text">
        地址精度较低（{{ geocodeLevel || '区县' }}），建议补充门牌号；仍可保存
      </text>
    </view>
    <view v-else-if="locationStatus === 'success'" class="preview">
      <text class="preview-title">当前位置</text>
      <text class="preview-address">{{ fullAddressPreview }}</text>
      <view class="preview-meta">
        <text>坐标 {{ coordsText }}</text>
        <text v-if="locationSourceText">{{ locationSourceText }}</text>
      </view>
    </view>
    <view v-else-if="locationStatus === 'partial'" class="preview is-partial">
      <text>已获取当前位置坐标 {{ coordsText }}，请手动填写详细地址</text>
    </view>

    <text v-else-if="geocodeStale" class="hint stale">地址已修改，将重新获取坐标</text>
    <text v-if="geocodeError" class="error-text">{{ geocodeError }}</text>
    <view v-if="mismatchText" class="mismatch">
      <text>{{ mismatchText }}</text>
    </view>
    <text class="privacy">地址仅用于记录小区位置，便于派工与施工导航</text>
  </view>
</template>

<style scoped>
.loc-panel { margin-top: 22rpx; }
.label-row { display: flex; align-items: baseline; justify-content: space-between; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 12rpx; }
.label-row .label { margin-bottom: 0; }
.req { color: #e53935; }
.district-tag {
  font-size: 21rpx; color: #FF6B35; background: #FFF3EC;
  padding: 4rpx 14rpx; border-radius: 20rpx;
}
.address-input {
  width: 100%; height: 132rpx !important; min-height: 132rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 16rpx 20rpx;
  font-size: 27rpx; color: #1D2129; box-sizing: border-box; line-height: 1.5;
  margin-top: 12rpx;
}
.hint { display: block; font-size: 21rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
.hint.stale { color: #B26A00; margin-top: 10rpx; }
.warn-row {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 8rpx; padding: 10rpx 14rpx; border-radius: 8rpx; background: #FFF9E6;
}
.warn-text { font-size: 21rpx; color: #B26A00; line-height: 1.5; }
.warn-action { flex: none; font-size: 22rpx; color: #FF6B35; font-weight: 600; margin-left: 12rpx; }
.loc-row { display: flex; align-items: center; gap: 12rpx; margin-top: 12rpx; }
.loc-btn {
  padding: 12rpx 22rpx; border: 2rpx solid #FF6B35; border-radius: 30rpx;
  font-size: 23rpx; color: #FF6B35; font-weight: 600;
}
.loc-btn.disabled { opacity: .55; }
.source-text { font-size: 21rpx; color: #999; }
.preview {
  margin-top: 12rpx; padding: 14rpx 18rpx; border-radius: 10rpx;
  background: #F0FAF4; font-size: 22rpx; color: #1D2129; line-height: 1.5;
}
.preview.is-loading { background: #FFF8F4; color: #8a5a3b; }
.preview.is-partial { background: #FFF9E6; color: #B26A00; }
.preview-title { display: block; font-size: 21rpx; color: #07A65A; margin-bottom: 4rpx; }
.preview-address { display: block; font-size: 23rpx; color: #1D2129; }
.preview-meta {
  display: flex; gap: 18rpx; margin-top: 6rpx; font-size: 21rpx; color: #666;
}
.preview .warn-text { display: block; margin-top: 6rpx; }
.error-text { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; line-height: 1.5; }
.mismatch {
  margin-top: 12rpx; padding: 12rpx 16rpx; border-radius: 10rpx;
  background: #FFF9E6; font-size: 22rpx; color: #B26A00; line-height: 1.5;
}
.privacy { display: block; font-size: 20rpx; color: #bbb; margin-top: 12rpx; line-height: 1.5; }
</style>
