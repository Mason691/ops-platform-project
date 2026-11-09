<template>
  <view class="product-row">
    <view class="row-head">
      <text class="row-title">产品 {{ index }}</text>
      <view v-if="removable" class="remove-btn" @click="$emit('remove')">
        <text>删除</text>
      </view>
    </view>

    <view class="field">
      <text class="label">品类 <text class="req">*</text></text>
      <picker mode="selector" :range="categoryNames" :value="categoryIndex"
              :disabled="!categoryNames.length" @change="onCategoryChange">
        <view class="picker">
          <text class="picker-text" :class="{ placeholder: !product.category }">
            {{ product.category || '请选择品类' }}
          </text>
          <text class="arrow">▼</text>
        </view>
      </picker>
      <text v-if="errors.category" class="ferr">{{ errors.category }}</text>
    </view>

    <view class="field">
      <text class="label">品牌 <text class="req">*</text></text>
      <picker mode="selector" :range="brandOptions" :value="brandIndex"
              :disabled="!product.category || !brandOptions.length" @change="onBrandChange">
        <view class="picker">
          <text class="picker-text" :class="{ placeholder: !product.brand }">
            {{ product.brand || '请选择品牌' }}
          </text>
          <text class="arrow">▼</text>
        </view>
      </picker>
      <text v-if="errors.brand" class="ferr">{{ errors.brand }}</text>
      <text v-else-if="!product.category" class="hint">请先选择品类</text>
      <text v-else-if="!brandOptions.length" class="hint">该品类暂无可用品牌</text>
    </view>

    <view class="field">
      <text class="label">型号 <text class="req">*</text></text>
      <picker mode="selector" :range="modelOptions" :value="modelIndex"
              :disabled="!product.category || !product.brand || !modelOptions.length"
              @change="onModelChange">
        <view class="picker">
          <text class="picker-text" :class="{ placeholder: !product.model }">{{ modelLabel }}</text>
          <text class="arrow">▼</text>
        </view>
      </picker>
      <text v-if="errors.model" class="ferr">{{ errors.model }}</text>
      <text v-else-if="product.category && product.brand && !modelOptions.length" class="hint">
        该品类与品牌组合暂无可用型号
      </text>
    </view>

    <view class="row">
      <view class="col">
        <text class="label">销售单价 <text class="req">*</text></text>
        <input class="input" v-model="product.salePrice" type="digit" placeholder="0.00" />
        <text v-if="errors.salePrice" class="ferr">{{ errors.salePrice }}</text>
      </view>
      <view class="col">
        <text class="label">销售数量 <text class="req">*</text></text>
        <input class="input" v-model="product.saleQuantity" type="number" placeholder="1" />
        <text v-if="errors.saleQuantity" class="ferr">{{ errors.saleQuantity }}</text>
      </view>
    </view>

    <view v-if="isSpecial" class="field">
      <text class="label">提成比例(%) <text class="req">*</text></text>
      <input class="input" v-model="product.commissionRate" type="digit"
             placeholder="0-100，保留两位小数" />
      <text v-if="errors.commissionRate" class="ferr">{{ errors.commissionRate }}</text>
    </view>

    <view class="amount-row">
      <text class="amount-label">收款金额（自动计算）</text>
      <text class="amount-val">¥{{ receiptAmount }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { computeProductAmount } from '@/utils/businessForm'

const props = defineProps({
  product: { type: Object, required: true },
  meta: { type: Object, required: true },
  index: { type: Number, required: true },
  errors: { type: Object, default: () => ({}) },
  isSpecial: { type: Boolean, default: false },
  removable: { type: Boolean, default: false }
})
defineEmits(['remove'])

// 品类由 scm_bd_material_group 提供（后端已按 PRODUCT_CATEGORY 字典过滤）
const categoryNames = computed(() => (props.meta.categories || []).map(item => item.name))

// 品牌由 scm_bd_brand 提供，按已选品类在 scm_bd_material 中出现的品牌集合过滤
const brandOptions = computed(() => {
  if (!props.product.category) return []
  const seen = new Set()
  const result = []
  for (const material of props.meta.materials || []) {
    if (material.category !== props.product.category) continue
    if (seen.has(material.brand)) continue
    seen.add(material.brand)
    result.push(material.brand)
  }
  return result.sort()
})

// 型号由 scm_bd_material 提供，按已选品类 + 品牌过滤
const modelOptions = computed(() => {
  if (!props.product.category || !props.product.brand) return []
  const seen = new Set()
  const result = []
  for (const material of props.meta.materials || []) {
    if (material.brand !== props.product.brand
      || material.category !== props.product.category) continue
    if (seen.has(material.model)) continue
    seen.add(material.model)
    result.push(material.model)
  }
  return result.sort()
})

const brandIndex = computed(() => Math.max(0, brandOptions.value.indexOf(props.product.brand)))
const categoryIndex = computed(() =>
  Math.max(0, categoryNames.value.indexOf(props.product.category)))
const modelIndex = computed(() => Math.max(0, modelOptions.value.indexOf(props.product.model)))
const modelLabel = computed(() => {
  if (props.product.model) return props.product.model
  if (!props.product.category) return '请先选择品类'
  if (!props.product.brand) return '请先选择品牌'
  return '请选择型号'
})
const receiptAmount = computed(() => computeProductAmount(props.product).toFixed(2))

function onCategoryChange(e) {
  props.product.category = categoryNames.value[Number(e.detail.value)] || ''
  props.product.brand = ''
  props.product.model = ''
}
function onBrandChange(e) {
  props.product.brand = brandOptions.value[Number(e.detail.value)] || ''
  props.product.model = ''
}
function onModelChange(e) {
  props.product.model = modelOptions.value[Number(e.detail.value)] || ''
}
</script>

<style scoped>
.product-row {
  border: 1rpx solid #f0f0f0; border-radius: 12rpx;
  padding: 20rpx; margin-bottom: 20rpx; background: #fcfcfc;
}
.row-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 20rpx;
}
.row-title { font-size: 28rpx; font-weight: 600; color: #333; }
.remove-btn {
  padding: 8rpx 20rpx; border-radius: 8rpx; background: #FFF0EB;
  color: #e53935; font-size: 24rpx;
}
.field { margin-bottom: 20rpx; }
.field:last-child { margin-bottom: 0; }
.label { font-size: 26rpx; color: #666; display: block; margin-bottom: 10rpx; }
.req { color: #e53935; }
.input, .picker {
  width: 100%; height: 76rpx; background: #f7f7f7; border-radius: 10rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
  display: flex; align-items: center; justify-content: space-between;
}
.picker-text { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.placeholder { color: #bbb; }
.arrow { font-size: 20rpx; color: #999; margin-left: 12rpx; }
.row { display: flex; gap: 16rpx; }
.col { flex: 1; min-width: 0; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 6rpx; }
.amount-row {
  display: flex; align-items: center; justify-content: space-between;
  background: #FFF3ED; border-radius: 10rpx; padding: 16rpx 20rpx;
}
.amount-label { font-size: 24rpx; color: #666; }
.amount-val { font-size: 32rpx; font-weight: 700; color: #FF6B35; }
</style>
