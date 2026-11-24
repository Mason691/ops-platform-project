<template>
  <view class="card">
    <view class="card-top">
      <view class="order-title">
        <text class="order-no">{{ group.orderNo || group.receiptNo || '未编号订单' }}</text>
        <text class="status-tag" :class="tagClass(group.status)">{{ group.statusName }}</text>
      </view>
      <text class="batch">批次 {{ group.batchNo || '—' }}</text>
    </view>
    <text class="customer">{{ group.customerName || '—' }}</text>
    <view class="meta">
      <text>财务核对 {{ fmtTime(group.financeCheckTime) }}</text>
      <text>商品 {{ group.records.length }} 项 · 待发起 {{ group.pendingCount }}</text>
    </view>

    <view class="items">
      <view v-for="row in group.records" :key="row.id" class="item">
        <view class="item-top">
          <view class="item-main">
            <text class="item-name">
              {{ [row.brand, row.model].filter(Boolean).join(' ') || row.category || '商品' }}
            </text>
            <text class="item-meta">
              品类 {{ row.category || '—' }} · 数量 {{ qty(row.quantity) }}{{ row.unit || '' }}
            </text>
            <text class="selected-tag">勾选来源：部长审核</text>
          </view>
          <text class="status-tag" :class="tagClass(row.status)">{{ row.statusName }}</text>
        </view>
        <text v-if="row.status === 'INVALID' && row.invalidReason" class="item-invalid">
          {{ row.invalidReason }}
        </text>
        <text v-if="row.requestBillNo" class="bill-no">采购单号 {{ row.requestBillNo }}</text>
        <ProductStockInfo
          :brand="row.brand || ''"
          :category="row.category || ''"
          :model="row.model || ''"
          :quantity="row.quantity"
          compact />
      </view>
    </view>

    <view class="card-bottom">
      <text v-if="group.pendingCount" class="hint">
        发起后生成采购订单草稿，请在 PC 端完善供应商/仓库/单价
      </text>
      <text v-else-if="group.status === 'REQUESTED'" class="done">
        已发起 {{ group.requestBillNo || '' }}
      </text>
      <text v-else-if="group.status === 'HANDLED'" class="done">已处理，无需重复发起</text>
      <text v-else class="done muted">已失效</text>
      <view v-if="group.pendingCount" class="btn primary" :class="{ disabled: submitting }"
            @click="$emit('initiate', group)">
        <text>{{ submitting ? '提交中...' : '发起采购申请' }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import ProductStockInfo from '@/components/ProductStockInfo.vue'

defineProps({
  group: { type: Object, required: true },
  submitting: { type: Boolean, default: false }
})
defineEmits(['initiate'])

function tagClass(status) {
  return {
    PENDING: 'tag-pending',
    REQUESTED: 'tag-requested',
    HANDLED: 'tag-handled',
    INVALID: 'tag-invalid'
  }[status] || 'tag-handled'
}

function fmtTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function qty(value) {
  const number = Number(value)
  return Number.isFinite(number) ? String(number) : '0'
}
</script>

<style scoped>
.card { background: #fff; border-radius: 20rpx; padding: 26rpx; margin-bottom: 20rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.04); }
.card-top { display: flex; justify-content: space-between; align-items: center; gap: 12rpx; }
.order-title { display: flex; align-items: center; gap: 10rpx; flex: 1; min-width: 0; }
.order-no { font-size: 30rpx; font-weight: 700; color: #1D2129; flex: 1; min-width: 0; word-break: break-all; }
.batch { font-size: 22rpx; color: #86909C; flex-shrink: 0; }
.status-tag { font-size: 20rpx; line-height: 1; padding: 7rpx 10rpx; border-radius: 8rpx; flex-shrink: 0; }
.tag-pending { color: #C75A00; background: #FFF5E8; }
.tag-requested { color: #0B7A42; background: #E8FFEA; }
.tag-handled { color: #4E5969; background: #F2F3F5; }
.tag-invalid { color: #F53F3F; background: #FFF1F0; }
.customer { display: block; margin-top: 12rpx; font-size: 27rpx; color: #4E5969; }
.meta { display: flex; justify-content: space-between; gap: 12rpx; margin-top: 10rpx; font-size: 22rpx; color: #86909C; }
.items { margin-top: 14rpx; padding-top: 14rpx; border-top: 1rpx dashed #f0f0f0; }
.item { padding: 14rpx; margin-top: 10rpx; background: #FAFBFC; border-radius: 12rpx; }
.item:first-child { margin-top: 0; }
.item-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 12rpx; }
.item-main { flex: 1; min-width: 0; }
.item-name { display: block; font-size: 25rpx; color: #1D2129; font-weight: 600; word-break: break-all; }
.item-meta { display: block; margin-top: 4rpx; font-size: 21rpx; color: #86909C; }
.selected-tag { display: inline-block; margin-top: 6rpx; font-size: 19rpx; color: #C75A00; background: #FFF5E8; padding: 3rpx 8rpx; border-radius: 6rpx; }
.item-invalid { display: block; margin-top: 6rpx; font-size: 21rpx; color: #F53F3F; line-height: 1.5; }
.bill-no { display: block; margin-top: 6rpx; font-size: 21rpx; color: #0B7A42; }
.card-bottom { display: flex; justify-content: space-between; align-items: center; gap: 16rpx; margin-top: 20rpx; padding-top: 18rpx; border-top: 1rpx solid #f0f0f0; }
.hint { flex: 1; font-size: 21rpx; color: #86909C; line-height: 1.5; }
.done { flex: 1; font-size: 23rpx; color: #0B7A42; }
.done.muted { color: #86909C; }
.btn { padding: 15rpx 24rpx; border-radius: 36rpx; font-size: 25rpx; text-align: center; flex-shrink: 0; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn.disabled { background: #F2F3F5; color: #86909C; }
</style>
