<template>
  <view class="page">
    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-error">{{ errorMsg }}</text>
      <view class="retry" @click="load"><text>重试</text></view>
    </view>

    <template v-else-if="order">
      <view class="card">
        <view class="head-line">
          <text class="order-no">{{ order.orderNo || '未编号订单' }}</text>
          <text class="status">{{ order.bizStatusName || '状态未知' }}</text>
        </view>
        <view class="kv"><text class="k">客户</text><text class="v">{{ order.customer || '—' }}</text></view>
        <view class="kv"><text class="k">小区/地址</text>
          <text class="v">{{ [order.communityName, order.detailedAddress].filter(Boolean).join(' ') || '—' }}</text>
        </view>
        <view class="kv"><text class="k">批次</text><text class="v">{{ order.batchNo || '—' }}</text></view>
        <view class="kv"><text class="k">市场收款日期</text><text class="v">{{ order.receiptDate || '—' }}</text></view>
        <view class="kv"><text class="k">收据号</text><text class="v">{{ order.receiptNo || '—' }}</text></view>
        <view class="kv"><text class="k">经办人</text><text class="v">{{ order.agentNames || '—' }}</text></view>
        <view class="kv"><text class="k">合计金额</text><text class="v amount">¥{{ money(order.totalAmount) }}</text></view>
        <view class="kv"><text class="k">提成合计</text><text class="v commission">¥{{ money(order.totalCommission) }}</text></view>
      </view>

      <view class="card">
        <text class="card-title">商品明细</text>
        <view v-for="(p, i) in (order.products || [])" :key="p.productKey || i"
              class="product-row" :class="{ ret: p.isReturnEntry }">
          <view class="product-info">
            <text class="product-name">{{ p.brand || '' }} {{ p.model || '' }}</text>
            <text class="product-meta">
              {{ p.categoryName || p.category || '—' }} · ¥{{ money(p.salePrice) }} × {{ p.saleQuantity }}
            </text>
            <text v-if="p.isSpecial === '是'" class="product-special">特批 · 提成 {{ p.commissionRate }}%</text>
          </view>
          <view class="product-money">
            <text class="product-amount">¥{{ money(p.receiptAmount) }}</text>
            <text class="product-commission">提成 ¥{{ money(p.commissionAmount) }}</text>
          </view>
        </view>
        <text v-if="!(order.products || []).length" class="empty">暂无商品明细</text>
      </view>

      <view class="card">
        <BusinessFlowProgress :flow="order.flow || {}" :status-options="order.bizStatusOptions || []"
                              :biz-status="order.bizStatus" :biz-status-name="order.bizStatusName" />
      </view>

      <view v-if="flowRecords.length" class="card">
        <text class="card-title">流程处理记录</text>
        <view v-for="(record, i) in flowRecords" :key="record.id || i" class="record">
          <view class="record-dot" />
          <view class="record-body">
            <view class="record-line1">
              <text class="record-node">{{ record.nodeName || '—' }}</text>
              <text class="record-action">{{ actionLabel(record.actionType) }}</text>
              <text class="record-time">{{ formatTime(record.createTime) }}</text>
            </view>
            <text class="record-operator">{{ record.operatorName || '—' }}</text>
            <text v-if="record.opinion" class="record-opinion">{{ record.opinion }}</text>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api'
import BusinessFlowProgress from '@/components/BusinessFlowProgress.vue'

/**
 * 员工端业务订单详情：本人参与的商品明细 + 流程进度 + 节点处理留痕。
 * 接口按 当前组织 + 当前经办人 校验，非本人参与订单返回无权查看。
 */
const orderId = ref('')
const loading = ref(true)
const errorMsg = ref('')
const order = ref(null)

onLoad(options => {
  orderId.value = options && options.orderId ? decodeURIComponent(options.orderId) : ''
  if (!orderId.value) {
    loading.value = false
    errorMsg.value = '缺少订单ID'
    return
  }
  load()
})

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    order.value = await api.employeeOrderDetail(orderId.value)
  } catch (e) {
    errorMsg.value = (e && e.msg) || '订单加载失败，请重试'
  } finally {
    loading.value = false
  }
}

const flowRecords = computed(() => {
  const list = order.value && order.value.flowRecords
  return Array.isArray(list) ? list : []
})

function actionLabel(type) {
  return {
    OPS_FINANCE_CHECK: '财务核对', OPS_DISPATCH_OUTBOUND: '派工出库',
    OPS_CONSTRUCTION_RECORD: '施工记录', OPS_ACCEPTANCE: '施工验收',
    OPS_AFTER_SALES: '售后维护'
  }[type] || type || '处理'
}

function money(value) {
  return (Number(value) || 0).toFixed(2)
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 20rpx 24rpx 40rpx; box-sizing: border-box; }
.card {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  display: flex; flex-direction: column; gap: 10rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .03); margin-bottom: 16rpx;
}
.head-line { display: flex; align-items: center; gap: 12rpx; flex-wrap: wrap; }
.order-no { font-size: 30rpx; font-weight: 700; color: #1D2129; }
.status { font-size: 20rpx; padding: 2rpx 12rpx; border-radius: 6rpx; background: #E8F3FF; color: #2F6FEB; }
.kv { display: flex; align-items: flex-start; gap: 16rpx; font-size: 23rpx; line-height: 1.5; }
.kv .k { flex: 0 0 168rpx; color: #86909C; }
.kv .v { flex: 1; min-width: 0; color: #1D2129; word-break: break-all; }
.kv .v.amount { color: #FF6B35; font-weight: 700; }
.kv .v.commission { color: #0B7A42; font-weight: 600; }
.card-title { font-size: 24rpx; font-weight: 600; color: #4E5969; margin-bottom: 4rpx; }
.product-row {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx;
  padding: 14rpx 0; border-bottom: 1rpx solid #F7F8FA;
}
.product-row:last-child { border-bottom: none; }
.product-row.ret .product-amount, .product-row.ret .product-commission { color: #C62828; }
.product-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.product-name { font-size: 26rpx; color: #1D2129; font-weight: 500; }
.product-meta { font-size: 21rpx; color: #86909C; }
.product-special { font-size: 20rpx; color: #C2410C; background: #FFF0E8; border-radius: 4rpx; padding: 1rpx 8rpx; align-self: flex-start; }
.product-money { flex-shrink: 0; display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; }
.product-amount { font-size: 26rpx; font-weight: 600; color: #1D2129; }
.product-commission { font-size: 20rpx; color: #0B7A42; }
.empty { font-size: 23rpx; color: #A9AEB8; }
.record { display: flex; gap: 14rpx; padding: 10rpx 0; }
.record-dot {
  width: 14rpx; height: 14rpx; border-radius: 50%; background: #FF6B35;
  margin-top: 10rpx; flex-shrink: 0;
}
.record-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.record-line1 { display: flex; align-items: center; gap: 10rpx; flex-wrap: wrap; }
.record-node { font-size: 24rpx; font-weight: 600; color: #1D2129; }
.record-action { font-size: 20rpx; padding: 1rpx 8rpx; border-radius: 4rpx; background: #E8F8E8; color: #0B7A42; }
.record-time { margin-left: auto; font-size: 20rpx; color: #A9AEB8; }
.record-operator { font-size: 21rpx; color: #86909C; }
.record-opinion { font-size: 21rpx; color: #4E5969; line-height: 1.5; }
.state { text-align: center; padding: 160rpx 32rpx; font-size: 25rpx; color: #86909C; }
.state-error { display: block; color: #C62828; margin-bottom: 20rpx; line-height: 1.5; }
.retry {
  display: inline-block; padding: 12rpx 36rpx; background: #FF6B35;
  color: #fff; border-radius: 28rpx; font-size: 24rpx;
}
</style>
