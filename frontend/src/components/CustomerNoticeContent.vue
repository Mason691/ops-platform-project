<template>
  <view class="customer-notice">
    <view class="notice">
      <text class="notice-title">施工用户告知书</text>
      <text class="notice-content">{{ content || '告知书内容加载中...' }}</text>
    </view>

    <view v-if="resign && existingNotice && existingNotice.signImageUrl" class="existing">
      <text class="label">上一次签字（提交后作废，历史版本保留）</text>
      <image :src="assetUrl(existingNotice.signImageUrl)" class="existing-img" mode="aspectFit" />
    </view>
  </view>
</template>

<script setup>
import { assetUrl } from '@/utils/image'

/** 告知书正文 + 重签时的上一版签字预览（签字页与订单详情共用同一口径）。 */
defineProps({
  content: { type: String, default: '' },
  existingNotice: { type: Object, default: null },
  resign: { type: Boolean, default: false }
})
</script>

<style scoped>
.customer-notice { display: flex; flex-direction: column; }
.notice {
  background: #FAFAFA; border: 1px solid #EEEFF2;
  border-radius: 6px; padding: 12px;
}
.notice-title {
  display: block; text-align: center; font-size: 15px; font-weight: 700;
  color: #1D2129; margin-bottom: 8px;
}
.notice-content {
  display: block; white-space: pre-wrap; font-size: 12px; line-height: 1.9;
  color: #4E5969; word-break: break-all;
}
.existing { margin-top: 11px; }
.label { display: block; font-size: 12.5px; font-weight: 600; color: #1D2129; }
.existing-img {
  margin-top: 5px; width: 100%; height: 100px; background: #fff;
  border: 1px solid #EEEFF2; border-radius: 5px;
}
</style>
