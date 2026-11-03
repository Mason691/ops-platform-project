<template>
  <view class="card">
    <view class="card-title"><text class="bar"></text>产品信息</view>

    <view class="field">
      <text class="label">品牌 <text class="req">*</text></text>
      <picker mode="selector" :range="brands" :value="brandIndex" :disabled="!brands.length" @change="onBrandChange">
        <view class="picker">
          <text class="picker-text" :class="{ placeholder: !form.brand }">{{ form.brand || '请选择品牌' }}</text>
          <text class="arrow">▼</text>
        </view>
      </picker>
      <text v-if="errors.brand" class="ferr">{{ errors.brand }}</text>
    </view>

    <view class="field">
      <text class="label">品类 <text class="req">*</text></text>
      <picker mode="selector" :range="categoryNames" :value="categoryIndex" :disabled="!categoryNames.length"
              @change="onCategoryChange">
        <view class="picker">
          <text class="picker-text" :class="{ placeholder: !form.category }">{{ form.category || '请选择品类' }}</text>
          <text class="arrow">▼</text>
        </view>
      </picker>
      <text v-if="errors.category" class="ferr">{{ errors.category }}</text>
    </view>

    <view class="field">
      <text class="label">型号 <text class="req">*</text></text>
      <picker mode="selector" :range="modelOptions" :value="modelIndex"
              :disabled="!modelOptions.length" @change="onModelChange">
        <view class="picker">
          <text class="picker-text" :class="{ placeholder: !form.model }">{{ modelLabel }}</text>
          <text class="arrow">▼</text>
        </view>
      </picker>
      <text v-if="errors.model" class="ferr">{{ errors.model }}</text>
      <text v-else-if="form.brand && form.category && !modelOptions.length" class="hint">
        该品牌与品类组合暂无可用型号
      </text>
    </view>

    <view class="row">
      <view class="col">
        <text class="label">销售单价 <text class="req">*</text></text>
        <input class="input" v-model="form.salePrice" type="digit" placeholder="0.00" />
        <text v-if="errors.salePrice" class="ferr">{{ errors.salePrice }}</text>
      </view>
      <view class="col">
        <text class="label">销售数量 <text class="req">*</text></text>
        <input class="input" v-model="form.saleQuantity" type="number" placeholder="1" />
        <text v-if="errors.saleQuantity" class="ferr">{{ errors.saleQuantity }}</text>
      </view>
    </view>

    <view class="amount-row">
      <text class="amount-label">收款金额（自动计算）</text>
      <text class="amount-val">¥{{ receiptAmount }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { computeReceiptAmount } from '@/utils/businessForm'

const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, required: true }
})

const brands = computed(() => props.meta.brands || [])
const categoryNames = computed(() => (props.meta.categories || []).map(item => item.name))

const modelOptions = computed(() => {
  if (!props.form.brand || !props.form.category) return []
  const seen = new Set()
  const result = []
  for (const material of props.meta.materials || []) {
    if (material.brand !== props.form.brand || material.category !== props.form.category) continue
    if (seen.has(material.model)) continue
    seen.add(material.model)
    result.push(material.model)
  }
  return result.sort()
})

const brandIndex = computed(() => Math.max(0, brands.value.indexOf(props.form.brand)))
const categoryIndex = computed(() => Math.max(0, categoryNames.value.indexOf(props.form.category)))
const modelIndex = computed(() => Math.max(0, modelOptions.value.indexOf(props.form.model)))
const modelLabel = computed(() => {
  if (props.form.model) return props.form.model
  if (!props.form.brand || !props.form.category) return '请先选择品牌和品类'
  return '请选择型号'
})
const receiptAmount = computed(() =>
  computeReceiptAmount(props.form.salePrice, props.form.saleQuantity).toFixed(2))

function onBrandChange(e) {
  props.form.brand = brands.value[Number(e.detail.value)] || ''
  props.form.model = ''
}
function onCategoryChange(e) {
  props.form.category = categoryNames.value[Number(e.detail.value)] || ''
  props.form.model = ''
}
function onModelChange(e) {
  props.form.model = modelOptions.value[Number(e.detail.value)] || ''
}
</script>

<style scoped>
.card {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  margin-bottom: 20rpx; box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.card-title { font-size: 30rpx; font-weight: 600; color: #333; margin-bottom: 24rpx; display: flex; align-items: center; }
.bar { width: 8rpx; height: 30rpx; background: #FF6B35; border-radius: 4rpx; margin-right: 12rpx; }
.field { margin-bottom: 24rpx; }
.label { font-size: 26rpx; color: #666; display: block; margin-bottom: 12rpx; }
.req { color: #e53935; }
.input, .picker {
  width: 100%; height: 80rpx; background: #f7f7f7; border-radius: 10rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
  display: flex; align-items: center; justify-content: space-between;
}
.picker-text { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.placeholder { color: #bbb; }
.arrow { font-size: 20rpx; color: #999; margin-left: 12rpx; }
.row { display: flex; gap: 16rpx; }
.col { flex: 1; min-width: 0; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx; }
.amount-row {
  display: flex; align-items: center; justify-content: space-between;
  background: #FFF3ED; border-radius: 12rpx; padding: 20rpx 24rpx;
}
.amount-label { font-size: 26rpx; color: #666; }
.amount-val { font-size: 36rpx; font-weight: 700; color: #FF6B35; }
</style>
