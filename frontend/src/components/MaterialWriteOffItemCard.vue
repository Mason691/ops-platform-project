<template>
  <view class="item-card">
    <view class="card-head">
      <text class="row-title">{{ row.materialName }}</text>
      <text class="remain">可核销 {{ row.remainingQty }}</text>
    </view>
    <text class="row-sub">
      {{ [row.category, row.brand, row.model].filter(Boolean).join(' · ') }}
    </text>
    <text class="row-sub">
      领用数量 {{ row.requisitionQty }} {{ row.unit || '' }}<text v-if="row.materialNumber"> · {{ row.materialNumber }}</text>
    </text>

    <view :id="fieldId('writeOffQty')" class="field">
      <text class="label">本次核销数量 <text class="req">*</text></text>
      <view :class="{ 'control-error-box': errors.writeOffQty || errors.sum }">
        <QuantityStepper v-model="row.writeOffQty" :min="0" :max="row.remainingQty" :focus-id="fieldId('writeOffQty')" />
      </view>
      <text v-if="errors.writeOffQty" class="ferr">{{ errors.writeOffQty }}</text>
    </view>

    <view :id="fieldId('returnQty')" class="field">
      <text class="label">退回数量 <text class="req">*</text></text>
      <view :class="{ 'control-error-box': errors.returnQty || errors.sum }">
        <QuantityStepper v-model="row.returnQty" :min="0" :max="row.remainingQty" :focus-id="fieldId('returnQty')" />
      </view>
      <text v-if="errors.returnQty" class="ferr">{{ errors.returnQty }}</text>
      <text v-if="errors.sum" class="ferr">{{ errors.sum }}</text>
      <text v-else-if="overRemaining" class="ferr">核销+退回超过可核销余量 {{ row.remainingQty }}</text>
      <text v-else class="hint">未填写的剩余数量可在下一次核销继续处理</text>
    </view>

    <view class="field">
      <text class="label">核销说明</text>
      <input v-model="row.remark" class="input" maxlength="200" placeholder="选填" />
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import QuantityStepper from '@/components/QuantityStepper.vue'

/**
 * 核销物料卡片：只读展示领用信息，填写本次核销数量与退回数量。
 * 允许部分核销（两项之和 ≤ 可核销余量），剩余留待下次核销。
 */
const props = defineProps({
  row: { type: Object, required: true },
  index: { type: Number, required: true },
  errors: { type: Object, default: () => ({}) }
})

const overRemaining = computed(() =>
  (Number(props.row.writeOffQty) || 0) + (Number(props.row.returnQty) || 0)
  > Number(props.row.remainingQty || 0))

function fieldId(field) {
  return `field-items-${props.index}-${field}`
}
</script>

<style scoped>
.item-card {
  border: 2rpx solid #f0f0f0; border-radius: 14rpx;
  padding: 22rpx 20rpx; margin-bottom: 18rpx; background: #fcfcfc;
}
.card-head { display: flex; align-items: center; justify-content: space-between; }
.row-title { font-size: 28rpx; font-weight: 700; color: #333; }
.remain { font-size: 23rpx; color: #00B42A; background: #F0FBF4; border-radius: 8rpx; padding: 4rpx 12rpx; }
.row-sub { display: block; margin-top: 6rpx; font-size: 22rpx; color: #999; line-height: 1.5; }
.field { margin-top: 20rpx; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 10rpx; }
.req { color: #e53935; }
.input {
  width: 100%; height: 72rpx !important; min-height: 72rpx !important;
  background: #f2f2f2; border-radius: 10rpx; padding: 0 18rpx; font-size: 26rpx; color: #333; box-sizing: border-box;
}
.control-error-box { border: 2rpx solid #e53935; border-radius: 12rpx; padding: 8rpx; background: #FFF8F8; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
.hint { display: block; font-size: 21rpx; color: #999; margin-top: 6rpx; line-height: 1.5; }
</style>
