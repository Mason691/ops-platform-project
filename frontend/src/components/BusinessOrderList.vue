<template>
  <view class="order-list">
    <view v-for="order in orders" :key="keyOf(order)" class="order-card">
      <view class="order-head" @click="toggle(order)">
        <view class="order-main">
          <view class="order-no-row">
            <text class="order-no">{{ order.orderNo || '未编号订单' }}</text>
            <text class="status-tag" :class="statusClass(order)">{{ order.bizStatusName || '状态未知' }}</text>
            <text v-if="order.draftFlag" class="status-tag draft">草稿 · 待重提</text>
            <text v-if="order.refundStatus === 'PENDING'" class="status-tag refunding">作废审批中</text>
            <text v-if="order.hasTradeIn" class="trade-tag">以旧换新</text>
          </view>
          <text class="order-sub">{{ order.customer || '—' }}</text>
          <text class="order-meta">
            {{ order.receiptDate || formatDate(order.createTime) || '—' }}
            · {{ order.batchNo || '无批次' }}
          </text>
          <text v-if="order.receiptDate && order.createTime" class="order-meta">
            创建 {{ formatDate(order.createTime) }}
          </text>
        </view>
        <view class="order-right">
          <text class="order-amount">¥{{ money(order.hasTradeIn ? order.payableAmount : order.totalAmount) }}</text>
          <text v-if="order.hasTradeIn" class="order-count">销售 ¥{{ money(order.totalAmount) }}</text>
          <text class="order-count">{{ order.productCount || 0 }} 项</text>
          <text class="order-arrow">{{ isExpanded(order) ? '▲' : '▼' }}</text>
        </view>
      </view>

      <view v-if="isExpanded(order)" class="order-detail">
        <view class="product-list">
          <view v-for="(p, i) in (order.products || [])" :key="p.productKey || i"
                class="product-row" :class="{ ret: p.isReturnEntry }">
            <view class="product-info">
              <text class="product-name">{{ p.brand || '' }} {{ p.model || '' }}</text>
              <text class="product-meta">
                {{ p.categoryName || p.category || '—' }} · ¥{{ money(p.salePrice) }} × {{ p.saleQuantity }}
              </text>
              <text v-if="p.isSpecial === '是'" class="product-special">特批</text>
              <text v-if="p.isTradeIn === '是'" class="product-trade">
                以旧换新 · 抵扣 -¥{{ money(p.deductionAmount) }} · 应付 ¥{{ money(p.payableAmount) }}
              </text>
            </view>
            <view class="product-money">
              <text class="product-amount">¥{{ money(p.receiptAmount) }}</text>
              <text class="product-commission">提成 ¥{{ money(p.commissionAmount) }}</text>
            </view>
          </view>
          <text v-if="!(order.products || []).length" class="empty-products">暂无商品明细</text>
        </view>

        <BusinessFlowProgress :flow="order.flow || {}" :status-options="statusOptions"
                              :biz-status="order.bizStatus" :biz-status-name="order.bizStatusName" />

        <view class="detail-actions">
          <text class="detail-link" @click.stop="openDetail(order)">查看订单详情 ›</text>
        </view>
      </view>
    </view>

    <view v-if="hasMore && !loading" class="load-more" @click="$emit('load-more')">
      <text>加载更多</text>
    </view>
    <view v-if="loading" class="load-more"><text>加载中...</text></view>
  </view>
</template>

<script setup>
import { reactive } from 'vue'
import BusinessFlowProgress from '@/components/BusinessFlowProgress.vue'

/**
 * 员工端业务订单列表：订单主行 + 展开明细 + 流程进度。
 * 数据由页面按 当前组织 + 当前经办人 拉取，列表只负责展示与展开状态。
 */
const props = defineProps({
  orders: { type: Array, default: () => [] },
  statusOptions: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false }
})
defineEmits(['load-more'])

const expanded = reactive({})

function keyOf(order) {
  return String(order.orderKey || order.orderId || order.orderNo || order.id || '')
}

function isExpanded(order) {
  return !!expanded[keyOf(order)]
}

function toggle(order) {
  const key = keyOf(order)
  if (expanded[key]) delete expanded[key]
  else expanded[key] = true
}

function openDetail(order) {
  const id = order.orderId
  if (!id) {
    uni.showToast({ title: '该订单为历史数据，暂无详情', icon: 'none' })
    return
  }
  uni.navigateTo({ url: '/pages/employee/order-detail?orderId=' + encodeURIComponent(id) })
}

function statusClass(order) {
  if (String(order.bizStatus) === '-1') return 'void'
  if (order.draftFlag) return 'draft'
  const status = order.flow && order.flow.status
  if (status === 'COMPLETED') return 'done'
  if (status === 'REJECTED' || status === 'TERMINATED') return 'stopped'
  if (status === 'NOT_STARTED' || status === 'UNKNOWN') return 'pending'
  return 'running'
}

function money(value) {
  return (Number(value) || 0).toFixed(2)
}

function formatDate(value) {
  if (!value) return ''
  const text = String(value)
  return text.length >= 10 ? text.slice(0, 10) : text
}
</script>

<style scoped>
.order-list { display: flex; flex-direction: column; gap: 16rpx; }
.order-card {
  background: #fff; border-radius: 16rpx; overflow: hidden;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.order-head { display: flex; align-items: flex-start; gap: 16rpx; padding: 24rpx; }
.order-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.order-no-row { display: flex; align-items: center; gap: 10rpx; min-width: 0; }
.order-no {
  font-size: 29rpx; font-weight: 700; color: #1D2129;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.status-tag {
  flex-shrink: 0; font-size: 20rpx; padding: 2rpx 10rpx; border-radius: 6rpx;
  background: #E8F3FF; color: #2F6FEB;
}
.status-tag.done { background: #E8F8E8; color: #0B7A42; }
.status-tag.stopped { background: #FFF1F0; color: #C62828; }
.status-tag.pending { background: #F2F3F5; color: #86909C; }
.status-tag.void { background: #FFF1F0; color: #C62828; font-weight: 700; }
.status-tag.draft { background: #F2F3F5; color: #86909C; }
.status-tag.refunding { background: #FFF7E6; color: #B26A00; }
.trade-tag {
  flex-shrink: 0; font-size: 19rpx; padding: 2rpx 10rpx; border-radius: 6rpx;
  background: #FFF0E8; color: #C2410C;
}
.order-sub { font-size: 25rpx; color: #4E5969; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.order-meta { font-size: 21rpx; color: #A9AEB8; }
.order-right { flex-shrink: 0; display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; }
.order-amount { font-size: 30rpx; font-weight: 700; color: #FF6B35; }
.order-count { font-size: 20rpx; color: #A9AEB8; }
.order-arrow { font-size: 20rpx; color: #C9CDD4; margin-top: 2rpx; }
.order-detail {
  border-top: 1rpx solid #F2F3F5; padding: 20rpx 24rpx 24rpx;
  display: flex; flex-direction: column; gap: 18rpx;
}
.product-list { display: flex; flex-direction: column; gap: 14rpx; }
.product-row {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx;
  padding: 14rpx 0; border-bottom: 1rpx solid #F7F8FA;
}
.product-row:last-child { border-bottom: none; }
.product-row.ret .product-amount, .product-row.ret .product-commission { color: #C62828; }
.product-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.product-name { font-size: 26rpx; color: #1D2129; font-weight: 500; }
.product-meta { font-size: 21rpx; color: #86909C; }
.product-special {
  align-self: flex-start; font-size: 19rpx; padding: 1rpx 8rpx; border-radius: 4rpx;
  background: #FFF0E8; color: #C2410C;
}
.product-trade { font-size: 20rpx; color: #B26A00; line-height: 1.5; }
.product-money { flex-shrink: 0; display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; }
.product-amount { font-size: 26rpx; font-weight: 600; color: #1D2129; }
.product-commission { font-size: 20rpx; color: #0B7A42; }
.empty-products { font-size: 23rpx; color: #A9AEB8; padding: 8rpx 0; }
.detail-actions { display: flex; justify-content: flex-end; }
.detail-link { font-size: 23rpx; color: #FF6B35; padding: 6rpx 0; }
.load-more {
  text-align: center; padding: 24rpx; font-size: 24rpx; color: #86909C;
  background: #fff; border-radius: 16rpx;
}
</style>
