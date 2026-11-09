<template>
  <view class="card">
    <view class="card-head" @click="collapsed = !collapsed">
      <view class="card-title">
        <text class="bar"></text>产品明细
        <text class="count">（{{ products.length }}）</text>
      </view>
      <text class="toggle">{{ collapsed ? '展开' : '收起' }}</text>
    </view>

    <text v-if="errors.products" class="ferr products-err">{{ errors.products }}</text>

    <template v-if="!collapsed">
      <BusinessProductForm v-for="(product, index) in products" :key="index"
                           :product="product" :meta="meta" :index="index + 1"
                           :errors="rowErrors(index)" :is-special="isSpecial"
                           :removable="products.length > 1" @remove="remove(index)" />

      <view class="add-row" :class="{ disabled: products.length >= MAX_PRODUCTS }" @click="add">
        <text>＋ 添加产品</text>
      </view>

      <view class="total-row">
        <text class="total-label">收款金额合计</text>
        <text class="total-val">¥{{ totalAmount }}</text>
      </view>
    </template>

    <view v-else class="collapsed-summary">
      <text>已录入 {{ products.length }} 个产品，合计 ¥{{ totalAmount }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import BusinessProductForm from '@/components/BusinessProductForm.vue'
import { emptyProduct, computeProductsTotal, MAX_PRODUCTS } from '@/utils/businessForm'

const props = defineProps({
  products: { type: Array, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, required: true },
  isSpecial: { type: Boolean, default: false }
})

const collapsed = ref(false)
const ROW_FIELDS = ['category', 'brand', 'model', 'salePrice', 'saleQuantity', 'commissionRate']

const totalAmount = computed(() => computeProductsTotal(props.products).toFixed(2))

// 提交校验失败时自动展开，保证用户能看到具体行号与原因
watch(() => props.errors, (value) => {
  const hasRowError = Object.keys(value || {}).some(key => key.startsWith('products.'))
  if (hasRowError) collapsed.value = false
}, { deep: true })

function rowErrors(index) {
  const result = {}
  for (const field of ROW_FIELDS) {
    const message = props.errors[`products.${index}.${field}`]
    if (message) result[field] = message
  }
  return result
}

function add() {
  if (props.products.length >= MAX_PRODUCTS) {
    uni.showToast({ title: `单笔业务最多${MAX_PRODUCTS}行产品`, icon: 'none' })
    return
  }
  clearRowErrors()
  props.products.push(emptyProduct())
  collapsed.value = false
}

function remove(index) {
  if (props.products.length <= 1) {
    uni.showToast({ title: '至少保留一行产品', icon: 'none' })
    return
  }
  clearRowErrors()
  props.products.splice(index, 1)
}

/** 行结构变化后旧错误键会错位，先清空再按新行号重新校验。 */
function clearRowErrors() {
  Object.keys(props.errors || {})
    .filter(key => key === 'products' || key.startsWith('products.'))
    .forEach(key => delete props.errors[key])
}
</script>

<style scoped>
.card {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  margin-bottom: 20rpx; box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.card-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 20rpx;
}
.card-title {
  display: flex; align-items: center; font-size: 30rpx;
  font-weight: 600; color: #333;
}
.bar {
  width: 8rpx; height: 30rpx; background: #FF6B35;
  border-radius: 4rpx; margin-right: 12rpx;
}
.count { font-size: 24rpx; color: #999; font-weight: 400; }
.toggle { font-size: 24rpx; color: #FF6B35; }
.products-err { margin-bottom: 12rpx; }
.add-row {
  height: 76rpx; line-height: 76rpx; text-align: center;
  border: 1rpx dashed #FF6B35; border-radius: 10rpx;
  color: #FF6B35; font-size: 26rpx; margin-bottom: 20rpx;
}
.add-row.disabled { opacity: .5; }
.total-row {
  display: flex; align-items: center; justify-content: space-between;
  background: #FFF3ED; border-radius: 12rpx; padding: 20rpx 24rpx;
}
.total-label { font-size: 26rpx; color: #666; }
.total-val { font-size: 38rpx; font-weight: 700; color: #FF6B35; }
.collapsed-summary {
  background: #f7f7f7; border-radius: 10rpx; padding: 16rpx 20rpx;
  font-size: 24rpx; color: #666;
}
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
</style>
