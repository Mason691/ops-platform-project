<template>
  <view class="product-card">
    <view class="card-head">
      <text class="row-title">产品 {{ index + 1 }}</text>
      <view v-if="removable" class="remove-btn" @click="$emit('remove')">
        <text>删除</text>
      </view>
    </view>

    <view :id="fieldId('category')" class="field">
      <text class="label" @click="focusField(fieldId('category'))">品类 <text class="req">*</text></text>
      <FieldChips class="chips-block" :class="{ 'control-error-box': errors.category }"
                  :options="categoryOptions" :model-value="product.category"
                  @update:model-value="setCategory" compact />
      <text v-if="!categoryOptions.length" class="hint">供应链暂无可用品类，请联系管理员</text>
      <text v-if="errors.category" class="ferr">{{ errors.category }}</text>
    </view>

    <view :id="fieldId('brand')" class="field">
      <text class="label" @click="focusField(fieldId('brand'))">品牌 <text class="req">*</text></text>
      <FieldChips v-if="brands.length <= 6 && brands.length" class="chips-block"
                  :class="{ 'control-error-box': errors.brand }"
                  :options="brandChips" :model-value="product.brand"
                  @update:model-value="setBrand" compact />
      <view v-else class="picker-trigger"
            :class="{ disabled: !brands.length, 'control-error': errors.brand }"
            @click="openBrandSheet">
        <text :class="product.brand ? 'trigger-value' : 'placeholder'">
          {{ brandPlaceholder }}
        </text>
        <text class="arrow">›</text>
      </view>
      <text v-if="errors.brand" class="ferr">{{ errors.brand }}</text>
      <text v-else-if="product.category && !brands.length" class="hint">该品类暂无可用品牌</text>
    </view>

    <view :id="fieldId('model')" class="field">
      <text class="label" @click="focusField(fieldId('model'))">型号 <text class="req">*</text></text>
      <view class="picker-trigger"
            :class="{ disabled: !models.length, 'control-error': errors.model }"
            @click="openModelSheet">
        <text :class="product.model ? 'trigger-value' : 'placeholder'">
          {{ modelPlaceholder }}
        </text>
        <text class="arrow">›</text>
      </view>
      <text v-if="errors.model" class="ferr">{{ errors.model }}</text>
      <text v-else-if="product.category && product.brand && !models.length" class="hint">
        该品类与品牌组合暂无可用型号
      </text>
    </view>

    <view class="row">
      <view :id="fieldId('salePrice')" class="col">
        <view class="price-head">
          <text class="label" @click="focusField(fieldId('salePrice'))">
            销售单价 <text class="req">*</text>
          </text>
          <text v-if="product.standardSalePrice != null" class="standard-price">
            标准价 ¥{{ standardText }}
          </text>
        </view>
        <view class="price-input-row">
          <input class="input" :class="{ 'control-error': errors.salePrice }"
                 type="digit" :value="product.salePrice" placeholder="0.00"
                 :disabled="!!product.priceLocked"
                 @input="onPriceInput" @blur="formatPrice"
                 @focus="keepFieldVisible(fieldId('salePrice'))" />
          <text v-if="product.priceLocked && product.priceConfigured === true"
                class="edit-price" @click="unlockPrice">修改</text>
          <text v-else-if="!product.priceLocked && product.standardSalePrice != null"
                class="edit-price" @click="useStandardPrice">用标准价</text>
        </view>
        <text v-if="product.priceLoading" class="hint">正在查询标准售价...</text>
        <text :id="fieldId('standardPrice')" v-else-if="errors.standardPrice"
              class="ferr">{{ errors.standardPrice }}</text>
        <text v-else-if="product.priceConfigured === false" class="ferr">{{ product.priceError }}</text>
        <text v-if="errors.salePrice" class="ferr">{{ errors.salePrice }}</text>
        <text v-if="product.isSpecial" class="special-tag">低于标准价 · 特批</text>
      </view>
      <view :id="fieldId('saleQuantity')" class="col">
        <text class="label">销售数量 <text class="req">*</text></text>
        <view :class="{ 'control-error-box': errors.saleQuantity }">
          <QuantityStepper v-model="product.saleQuantity" />
        </view>
        <text v-if="errors.saleQuantity" class="ferr">{{ errors.saleQuantity }}</text>
      </view>
    </view>

    <view class="amount-row">
      <text class="amount-label">本商品小计</text>
      <AnimatedAmount class="amount-val" :value="amount" />
    </view>

    <BusinessSpecialApproval v-if="product.isSpecial" :product="product" :index="index"
                             :errors="errors" />

    <BottomSheetSelect v-model:visible="brandSheet" title="选择品牌" :options="brandOptions"
                       :model-value="product.brand" placeholder="搜索品牌"
                       @select="option => setBrand(option.value)" />
    <BottomSheetSelect v-model:visible="modelSheet" title="选择型号" :options="modelOptions"
                       :model-value="product.model" placeholder="搜索规格型号"
                       @select="option => setModel(option.value)" />
  </view>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import FieldChips from '@/components/FieldChips.vue'
import BottomSheetSelect from '@/components/BottomSheetSelect.vue'
import QuantityStepper from '@/components/QuantityStepper.vue'
import AnimatedAmount from '@/components/AnimatedAmount.vue'
import BusinessSpecialApproval from '@/components/BusinessSpecialApproval.vue'
import { useProductPrice } from '@/hooks/useProductPrice'
import { computeProductAmount } from '@/utils/businessForm'
import { focusField, keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 单张产品卡片：品类/品牌直接点选，型号底部弹层搜索；单价两位小数，
 * 数量步进器，小计实时联动。字段 id 供校验失败时滚动聚焦。
 */
const props = defineProps({
  product: { type: Object, required: true },
  meta: { type: Object, required: true },
  index: { type: Number, required: true },
  errors: { type: Object, default: () => ({}) },
  removable: { type: Boolean, default: false }
})
defineEmits(['remove'])

const brandSheet = ref(false)
const modelSheet = ref(false)
const { lookup: lookupPrice, resetPrice, evaluateSpecial, unlockPrice, useStandardPrice }
  = useProductPrice(props.product)

onMounted(() => {
  // 草稿恢复/步骤切换回显：即使本地已有标准价也按最新配置重判一次，
  // 但保留用户已填价格与特批附件（preservePrice）。
  if (props.product.model) {
    lookupPrice({ preservePrice: true })
  }
})

const categoryOptions = computed(() => (props.meta.categories || [])
  .map(item => ({ value: item.name, label: item.name })))

const brands = computed(() => {
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
const brandChips = computed(() => brands.value.map(brand => ({ value: brand, label: brand })))
const brandOptions = computed(() => brandChips.value)

const models = computed(() => {
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
const modelOptions = computed(() => models.value.map(model => ({ value: model, label: model })))

const brandPlaceholder = computed(() => {
  if (props.product.brand) return props.product.brand
  if (!props.product.category) return '请先选择品类'
  if (!brands.value.length) return '暂无可用品牌'
  return '请选择品牌'
})
const modelPlaceholder = computed(() => {
  if (props.product.model) return props.product.model
  if (!props.product.category) return '请先选择品类'
  if (!props.product.brand) return '请先选择品牌'
  if (!models.value.length) return '暂无可用型号'
  return '请选择型号'
})

const amount = computed(() => computeProductAmount(props.product))
const standardText = computed(() => {
  const value = Number(props.product.standardSalePrice)
  return Number.isFinite(value) ? value.toFixed(2) : ''
})

function fieldId(field) {
  return `field-products-${props.index}-${field}`
}

function setCategory(value) {
  props.product.category = value
  props.product.brand = ''
  props.product.model = ''
  props.product.productId = null
  resetPrice()
}

function setBrand(value) {
  props.product.brand = value
  props.product.model = ''
  props.product.productId = null
  resetPrice()
}

function setModel(value) {
  props.product.model = value
  const material = (props.meta.materials || []).find(item =>
    item && item.brand === props.product.brand
    && item.category === props.product.category && item.model === value)
  props.product.productId = material && material.id != null ? material.id : null
  resetPrice()
  lookupPrice()
}

function openBrandSheet() {
  if (brands.value.length) brandSheet.value = true
}

function openModelSheet() {
  if (models.value.length) modelSheet.value = true
}

function onPriceInput(event) {
  // 保留原始输入供校验提示；实时同步特批标记（不弹提示），失焦时再提示一次
  props.product.salePrice = String(event.detail.value ?? '')
  evaluateSpecial(false)
}

function formatPrice() {
  const value = Number(props.product.salePrice)
  if (Number.isFinite(value) && value > 0) {
    props.product.salePrice = value.toFixed(2)
  }
  evaluateSpecial(true)
}
</script>

<style scoped>
.product-card {
  border: 2rpx solid #f0f0f0; border-radius: 14rpx;
  padding: 22rpx 20rpx; margin-bottom: 18rpx; background: #fcfcfc;
}
.card-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 18rpx;
}
.row-title { font-size: 28rpx; font-weight: 700; color: #333; }
.remove-btn {
  padding: 8rpx 20rpx; border-radius: 8rpx; background: #FFF0EB;
  color: #e53935; font-size: 23rpx;
}
.field { margin-bottom: 20rpx; }
.field:last-child { margin-bottom: 0; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 10rpx; }
.req { color: #e53935; }
.input {
  width: 100%; height: 76rpx !important; min-height: 76rpx !important;
  background: #f2f2f2; border-radius: 10rpx;
  padding: 0 18rpx; font-size: 27rpx; color: #333; box-sizing: border-box;
}
.picker-trigger {
  display: flex; align-items: center; justify-content: space-between;
  height: 76rpx; background: #f2f2f2; border-radius: 10rpx; padding: 0 18rpx;
}
.picker-trigger.disabled { opacity: .65; }
.trigger-value {
  flex: 1; min-width: 0; font-size: 27rpx; color: #333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.placeholder { font-size: 27rpx; color: #bbb; }
.arrow { font-size: 30rpx; color: #999; }
.row { display: flex; gap: 16rpx; margin-bottom: 18rpx; }
.col { flex: 1; min-width: 0; }
.price-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8rpx; }
.standard-price { font-size: 21rpx; color: #FF6B35; white-space: nowrap; }
.price-input-row { display: flex; align-items: center; gap: 12rpx; }
.price-input-row .input { flex: 1; min-width: 0; }
.edit-price { font-size: 22rpx; color: #FF6B35; white-space: nowrap; }
.special-tag {
  display: inline-block; margin-top: 6rpx; font-size: 21rpx;
  color: #C2410C; background: #FFF0E8; border-radius: 6rpx; padding: 2rpx 10rpx;
}
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.control-error-box {
  border: 2rpx solid #e53935; border-radius: 12rpx;
  padding: 8rpx; background: #FFF8F8;
}
.chips-block { width: 100%; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 6rpx; line-height: 1.5; }
.amount-row {
  display: flex; align-items: center; justify-content: space-between;
  background: #FFF3ED; border-radius: 10rpx; padding: 14rpx 18rpx;
}
.amount-label { font-size: 24rpx; color: #666; }
.amount-val { font-size: 32rpx; font-weight: 700; color: #FF6B35; }
</style>
