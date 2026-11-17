<template>
  <view class="special-approval">
    <view class="special-warn">
      <text class="warn-icon">!</text>
      <text class="warn-text">本次售价低于标准销售价，需上传特批文件</text>
    </view>

    <view :id="`field-products-${index}-specialFileId`" class="field">
      <text class="label">特批文件 <text class="req">*</text></text>
      <BusinessSpecialFileUploader :model-value="specialFile" :error="errors.specialFileId"
                                   @update:model-value="setFile" />
    </view>

    <view :id="`field-products-${index}-commissionRate`" class="field">
      <text class="label">提成比例（%） <text class="req">*</text></text>
      <input class="input" :class="{ 'control-error': errors.commissionRate }"
             type="digit" :value="product.commissionRate" placeholder="0-100，保留两位小数"
             @input="onRateInput" @blur="formatRate"
             @focus="keepFieldVisible(`field-products-${index}-commissionRate`)" />
      <text v-if="errors.commissionRate" class="ferr">{{ errors.commissionRate }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import BusinessSpecialFileUploader from '@/components/BusinessSpecialFileUploader.vue'
import { keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 低价特批区：固定提示 + 特批文件 + 提成比例（按产品行独立）。
 * 是否特批由父级根据“销售价 vs 标准价”自动判定，本组件不提供手工开关。
 */
const props = defineProps({
  product: { type: Object, required: true },
  index: { type: Number, required: true },
  errors: { type: Object, default: () => ({}) }
})

const specialFile = computed(() => {
  if (!props.product || !props.product.specialFileId) return null
  return {
    id: props.product.specialFileId,
    url: props.product.specialFileUrl,
    name: props.product.specialFileName
  }
})

function setFile(file) {
  props.product.specialFileId = file ? file.id : null
  props.product.specialFileUrl = file ? file.url : ''
  props.product.specialFileName = file ? file.name : ''
}

function onRateInput(event) {
  // 保留原始输入（含负号/超范围值），由校验提示 0-100，避免静默改写
  props.product.commissionRate = String(event.detail.value ?? '')
}

function formatRate() {
  const raw = String(props.product.commissionRate || '').trim()
  if (raw === '') return
  const value = Number(raw)
  if (!Number.isFinite(value)) return
  props.product.commissionRate = value.toFixed(2)
}
</script>

<style scoped>
.special-approval {
  margin-top: 16rpx; padding: 18rpx; border-radius: 12rpx;
  background: #FFF8F4; border: 2rpx solid #FFD9C8;
  display: flex; flex-direction: column; gap: 16rpx;
}
.special-warn { display: flex; align-items: flex-start; gap: 10rpx; }
.warn-icon {
  width: 30rpx; height: 30rpx; line-height: 28rpx; text-align: center;
  border-radius: 50%; background: #C2410C; color: #fff;
  font-size: 21rpx; font-weight: 700; flex-shrink: 0;
}
.warn-text { flex: 1; font-size: 23rpx; color: #C2410C; line-height: 1.5; }
.field { margin: 0; }
.label { display: block; font-size: 24rpx; color: #7a4a33; margin-bottom: 10rpx; }
.req { color: #e53935; }
.input {
  width: 100%; height: 76rpx !important; min-height: 76rpx !important;
  background: #fff; border-radius: 10rpx; padding: 0 18rpx;
  font-size: 27rpx; color: #333; box-sizing: border-box;
}
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
</style>
