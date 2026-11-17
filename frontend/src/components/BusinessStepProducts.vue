<template>
  <view id="field-products" class="step-products">
    <view v-if="errors.products" class="products-err">{{ errors.products }}</view>

    <view v-if="!visibleRows.length && !onlyErrors" class="empty-products">
      <text>暂无产品信息，请添加商品</text>
    </view>

    <BusinessProductCard v-for="row in visibleRows" :key="row.product.uid || row.index"
                         :product="row.product" :meta="meta" :index="row.index"
                         :errors="rowErrors(row.index)"
                         :removable="form.products.length > 1"
                         @remove="remove(row.index)" />

    <view v-if="!onlyErrors" class="add-row"
          :class="{ disabled: form.products.length >= MAX_PRODUCTS }" @click="add">
      <text>＋ 添加商品</text>
    </view>

    <view v-if="!onlyErrors" class="total-card">
      <text class="total-label">合计金额</text>
      <AnimatedAmount class="total-val" :value="totalAmount" />
    </view>
    <text v-if="!onlyErrors" class="hint">
      收款金额 = 销售单价 × 销售数量，由系统自动计算并以服务端结果为准
    </text>

    <!-- 付款方式与销售价强相关：放在商品合计之后，制单人录入商品时同步确认全款/定金 -->
    <BusinessPaymentSection :form="form" :errors="errors" :only-errors="onlyErrors" />
  </view>
</template>

<script setup>
import { computed } from 'vue'
import BusinessProductCard from '@/components/BusinessProductCard.vue'
import BusinessPaymentSection from '@/components/BusinessPaymentSection.vue'
import AnimatedAmount from '@/components/AnimatedAmount.vue'
import { emptyProduct, computeProductsTotal, MAX_PRODUCTS } from '@/utils/businessForm'

/**
 * 第 2 步：商品明细 + 付款方式。可增删产品卡片，底部实时展示合计金额，
 * 并在合计下方确认全款/定金及应补余款（付款方式属于订单销售条件，不放到收款步骤）。
 * onlyErrors=true（确认页补填）时仅展示存在错误的行/付款方式，且不可增删。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

// 产品卡片渲染的全部字段：标准价查询错误、特批文件与提成比例都在卡片内处理
const ROW_FIELDS = ['category', 'brand', 'model', 'standardPrice', 'salePrice',
  'saleQuantity', 'specialFileId', 'commissionRate']

const totalAmount = computed(() => computeProductsTotal(props.form.products))

const visibleRows = computed(() => (props.form.products || [])
  .map((product, index) => ({ product, index }))
  .filter(row => !props.onlyErrors || rowHasError(row.index)))

function rowHasError(index) {
  return ROW_FIELDS.some(field => !!props.errors[`products.${index}.${field}`])
}

function rowErrors(index) {
  const result = {}
  for (const field of ROW_FIELDS) {
    const message = props.errors[`products.${index}.${field}`]
    if (message) result[field] = message
  }
  return result
}

function add() {
  if (props.form.products.length >= MAX_PRODUCTS) {
    uni.showToast({ title: `单笔业务最多${MAX_PRODUCTS}行产品`, icon: 'none' })
    return
  }
  clearRowErrors()
  props.form.products.push(emptyProduct())
}

function remove(index) {
  if (props.form.products.length <= 1) {
    uni.showToast({ title: '至少保留一行产品', icon: 'none' })
    return
  }
  clearRowErrors()
  props.form.products.splice(index, 1)
}

/** 行结构变化后旧错误键会错位，先清空；下次校验按新行号重新生成。 */
function clearRowErrors() {
  Object.keys(props.errors || {})
    .filter(key => key === 'products' || key.startsWith('products.'))
    .forEach(key => delete props.errors[key])
}
</script>

<style scoped>
.step-products { display: flex; flex-direction: column; }
.products-err {
  background: #FFF1F0; color: #e53935; border-radius: 10rpx;
  padding: 16rpx 20rpx; font-size: 24rpx; margin-bottom: 16rpx;
}
.empty-products {
  background: #fff; border-radius: 14rpx; padding: 48rpx 0;
  text-align: center; font-size: 25rpx; color: #999; margin-bottom: 16rpx;
}
.add-row {
  height: 84rpx; line-height: 84rpx; text-align: center;
  border: 2rpx dashed #FF6B35; border-radius: 14rpx;
  color: #FF6B35; font-size: 27rpx; margin-bottom: 18rpx;
  background: #FFFDFC;
}
.add-row:active { background: #FFF3ED; }
.add-row.disabled { opacity: .5; }
.total-card {
  display: flex; align-items: center; justify-content: space-between;
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.total-label { font-size: 27rpx; color: #666; font-weight: 600; }
.total-val { font-size: 42rpx; font-weight: 700; color: #FF6B35; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 12rpx; line-height: 1.5; }
</style>
