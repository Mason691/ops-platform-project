<template>
  <view class="products">
    <view class="products-head">
      <text class="title">施工商品（默认全选）</text>
      <text v-if="products.length" class="toggle" @click="toggleAll">
        {{ allSelected ? '取消全选' : '全选' }}
      </text>
    </view>

    <view v-if="!products.length" class="empty">
      该订单没有四类可施工商品（燃气灶/抽油烟机/热水器/壁挂炉），请改选「入户随访」
    </view>

    <view
      v-for="p in products"
      :key="p.productKey"
      class="product"
      @click="toggle(p.productKey)"
    >
      <view class="check" :class="{ checked: isSelected(p.productKey) }">
        {{ isSelected(p.productKey) ? '✓' : '' }}
      </view>
      <view class="info">
        <text class="name">{{ p.productName || ((p.brand || '') + ' ' + (p.model || '')) }}</text>
        <text class="meta">
          {{ categoryLabel(p.category) }} · 数量 {{ p.saleQuantity }} · ¥{{ money(p.salePrice) }}
        </text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  products: { type: Array, default: () => [] },
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const CATEGORY_LABEL = { '1': '燃气灶', '2': '抽油烟机', '3': '热水器', '4': '壁挂炉' }

const allSelected = computed(() =>
  props.products.length > 0
  && props.products.every(p => props.modelValue.includes(p.productKey)))

function isSelected(key) {
  return props.modelValue.includes(key)
}

function toggle(key) {
  const next = isSelected(key)
    ? props.modelValue.filter(k => k !== key)
    : props.modelValue.concat(key)
  emit('update:modelValue', next)
}

function toggleAll() {
  emit('update:modelValue', allSelected.value
    ? []
    : props.products.map(p => p.productKey))
}

function categoryLabel(key) {
  return CATEGORY_LABEL[String(key)] || key || '—'
}

function money(value) {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(2) : String(value)
}
</script>

<style scoped>
.products { background: #fff; border-radius: 20rpx; padding: 24rpx; margin-top: 20rpx; }
.products-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12rpx; }
.title { font-size: 26rpx; color: #4E5969; font-weight: 600; }
.toggle { font-size: 24rpx; color: #FF6B35; }
.empty { padding: 24rpx 0; font-size: 24rpx; color: #FF7D00; line-height: 1.6; }
.product { display: flex; align-items: flex-start; gap: 16rpx; padding: 16rpx 0; border-top: 1rpx solid #F2F3F5; }
.check {
  width: 36rpx; height: 36rpx; margin-top: 4rpx; border-radius: 8rpx;
  border: 2rpx solid #C9CDD4; color: #fff; font-size: 24rpx; line-height: 32rpx;
  text-align: center; flex-shrink: 0; box-sizing: border-box;
}
.check.checked { background: #FF6B35; border-color: #FF6B35; }
.info { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.name { font-size: 26rpx; color: #1D2129; }
.meta { font-size: 22rpx; color: #86909C; }
</style>
