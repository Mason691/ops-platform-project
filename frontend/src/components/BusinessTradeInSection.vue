<template>
  <view :id="tradeInSectionId" class="trade-in-section">
    <view class="section-head">
      <text class="section-title">旧品信息</text>
      <text class="section-sub">以旧换新抵扣</text>
    </view>

    <view class="field">
      <text class="label">抵扣金额 <text class="req">*</text></text>
      <view :id="deductionFieldId">
        <input class="input" :class="{ 'control-error': errors.deductionAmount }"
               type="digit" :value="product.deductionAmount"
               placeholder="必须大于0，最多两位小数"
               @input="onDeductionInput" @blur="formatDeduction"
               @focus="keepDeductionVisible" />
      </view>
      <text class="hint">
        销售金额 ¥{{ salesText }}，抵扣后 ¥{{ payableText }}（抵扣不得超过销售金额）
      </text>
      <text v-if="errors.deductionAmount" class="ferr">{{ errors.deductionAmount }}</text>
    </view>

    <view class="items-head">
      <text class="items-title">旧品明细 <text class="req">*</text></text>
      <text class="items-count">{{ totalQuantity }} 件</text>
    </view>
    <text class="hint">仅列出品牌为「旧品」的商品；未找到请选择“其他旧品”手工填写。</text>
    <text class="hint">提交后系统将自动生成旧品采购单；满足条件时直接审核并推送金蝶，可直接办理入库。</text>
    <text v-if="errors.tradeInItems" class="ferr">{{ errors.tradeInItems }}</text>

    <view v-if="!items.length" class="empty-items">
      <text>请至少添加 1 件旧品</text>
    </view>

    <BusinessTradeInItemCard v-for="(item, i) in items" :key="item.uid || i"
                             :item="item" :item-index="i"
                             :material-options="materialOptions"
                             :errors="itemErrors(i)"
                             @remove="remove(i)" />

    <view class="add-item" :class="{ disabled: items.length >= MAX_TRADE_IN_ITEMS }"
          @click="addItem">
      <text>＋ 添加旧品</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import BusinessTradeInItemCard from '@/components/BusinessTradeInItemCard.vue'
import { keepFieldVisible } from '@/utils/fieldFocus'
import {
  MAX_TRADE_IN_ITEMS, computeProductAmount, computeProductDeduction,
  computeProductPayable, emptyTradeInItem, isOldGoodsBrand
} from '@/utils/businessForm'

/**
 * 商品行以旧换新区：抵扣金额 + 多件旧品（可增删）。
 * 旧品可选商品库商品（品牌/型号自动带出）或“其他旧品”手工录入。
 */
const props = defineProps({
  product: { type: Object, required: true },
  index: { type: Number, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) }
})

const items = computed(() => (Array.isArray(props.product.tradeInItems)
  ? props.product.tradeInItems : []))

/** 旧品件数按数量合计，而不是录入行数。 */
const totalQuantity = computed(() => items.value.reduce((sum, item) => {
  const quantity = Number(item && item.quantity)
  return Number.isInteger(quantity) && quantity > 0 ? sum + quantity : sum
}, 0))

const materialOptions = computed(() => {
  const seen = new Set()
  const result = [{ value: '__manual__', label: '其他旧品（手工填写）', material: null }]
  for (const material of props.meta.materials || []) {
    if (!material || material.id == null || seen.has(material.id)) continue
    // 旧品选择只允许“旧品”品牌，普通销售商品不能混入
    if (!isOldGoodsBrand(material.brand)) continue
    seen.add(material.id)
    result.push({
      value: String(material.id),
      label: [material.brand, material.model, material.category].filter(Boolean).join(' · '),
      material
    })
  }
  return result
})

const salesText = computed(() => money(computeProductAmount(props.product)))
const payableText = computed(() => money(computeProductPayable(props.product)))
const tradeInSectionId = computed(() => `field-products-${props.index}-tradeInItems`)
const deductionFieldId = computed(() => `field-products-${props.index}-deductionAmount`)

function itemErrors(itemIndex) {
  const prefix = `products.${props.index}.tradeInItems.${itemIndex}`
  return {
    productName: props.errors[`${prefix}.productName`] || '',
    quantity: props.errors[`${prefix}.quantity`] || ''
  }
}

function onDeductionInput(event) {
  props.product.deductionAmount = String(event.detail.value ?? '')
}

function formatDeduction() {
  const value = Number(props.product.deductionAmount)
  if (Number.isFinite(value) && value > 0) {
    props.product.deductionAmount = value.toFixed(2)
  }
}

function keepDeductionVisible() {
  keepFieldVisible(deductionFieldId.value)
}

function addItem() {
  if (items.value.length >= MAX_TRADE_IN_ITEMS) {
    uni.showToast({ title: `每个商品最多${MAX_TRADE_IN_ITEMS}件旧品`, icon: 'none' })
    return
  }
  if (!Array.isArray(props.product.tradeInItems)) props.product.tradeInItems = []
  props.product.tradeInItems.push(emptyTradeInItem())
}

function remove(index) {
  if (!Array.isArray(props.product.tradeInItems)) return
  props.product.tradeInItems.splice(index, 1)
  // 行结构变化后旧错误键会错位，清空本商品行的旧品错误
  Object.keys(props.errors || {})
    .filter(key => key.startsWith(`products.${props.index}.tradeInItems.`))
    .forEach(key => delete props.errors[key])
}

function money(value) {
  return (Number(value) || 0).toFixed(2)
}
</script>

<style scoped>
.trade-in-section {
  margin-top: 16rpx; padding: 18rpx; border-radius: 12rpx;
  background: #F7F9FC; border: 1rpx solid #E5EAF2;
  display: flex; flex-direction: column; gap: 14rpx;
  animation: tradeInIn .2s ease-out;
}
@keyframes tradeInIn {
  from { opacity: 0; transform: translateY(-8rpx); }
  to { opacity: 1; transform: translateY(0); }
}
.section-head { display: flex; align-items: baseline; justify-content: space-between; }
.section-title { font-size: 25rpx; font-weight: 700; color: #1D2129; }
.section-sub { font-size: 20rpx; color: #86909C; }
.field { display: flex; flex-direction: column; gap: 8rpx; }
.label { font-size: 23rpx; color: #666; }
.req { color: #e53935; }
.input {
  width: 100%; height: 72rpx !important; min-height: 72rpx !important;
  background: #fff; border-radius: 10rpx; padding: 0 16rpx;
  font-size: 27rpx; color: #333; box-sizing: border-box;
}
.hint { font-size: 20rpx; color: #86909C; line-height: 1.4; }
.items-head { display: flex; align-items: center; justify-content: space-between; margin-top: 4rpx; }
.items-title { font-size: 24rpx; font-weight: 600; color: #1D2129; }
.items-count { font-size: 20rpx; color: #86909C; }
.empty-items {
  padding: 18rpx; text-align: center; font-size: 22rpx; color: #A9AEB8;
  background: #fff; border-radius: 10rpx;
}
.add-item {
  height: 68rpx; line-height: 68rpx; text-align: center; border-radius: 34rpx;
  border: 2rpx dashed #FF6B35; color: #FF6B35; font-size: 24rpx; background: #fff;
}
.add-item.disabled { opacity: .45; }
.ferr { font-size: 21rpx; color: #e53935; line-height: 1.4; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
</style>
