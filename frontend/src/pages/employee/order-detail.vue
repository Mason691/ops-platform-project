<template>
  <view class="page">
    <EmployeeHomeLink />
    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-error">{{ errorMsg }}</text>
      <view class="retry" @click="load"><text>重试</text></view>
    </view>

    <template v-else-if="order">
      <view v-if="showInboundReminder" class="inbound-alert">
        <text class="inbound-title">⚠️ 本订单含以旧换新旧品，请及时办理旧品入库。</text>
        <text v-if="order.tradeInSummary" class="inbound-line">旧品：{{ order.tradeInSummary }}</text>
        <text v-if="order.tradeInPurchaseBillNo" class="inbound-line">
          关联采购单：{{ order.tradeInPurchaseBillNo }}（未审核）
        </text>
        <text class="inbound-line">入库状态：{{ order.tradeInInboundStatusName || '旧品未入库' }}</text>
        <text class="inbound-hint">请在供应链管理 PC 端打开该采购订单办理旧品入库</text>
      </view>
      <view v-else-if="tradeInInboundDone && atConstructionStage" class="inbound-done">
        <text class="inbound-done-title">✓ 旧品已入库</text>
        <text v-if="order.tradeInPurchaseBillNo" class="inbound-done-sub">
          关联采购单 {{ order.tradeInPurchaseBillNo }}
        </text>
      </view>

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
        <view class="kv">
          <text class="k">{{ order.hasTradeIn ? '销售金额' : '合计金额' }}</text>
          <text class="v amount">¥{{ money(order.totalAmount) }}</text>
        </view>
        <view v-if="order.hasTradeIn" class="kv">
          <text class="k">抵扣金额</text>
          <text class="v deduction">-¥{{ money(order.tradeInDeductionAmount) }}</text>
        </view>
        <view v-if="order.hasTradeIn" class="kv">
          <text class="k">应付合计</text>
          <text class="v amount">¥{{ money(order.payableAmount) }}</text>
        </view>
        <view class="kv"><text class="k">提成合计</text><text class="v commission">¥{{ money(order.totalCommission) }}</text></view>
      </view>

      <view v-if="order.hasTradeIn" class="card">
        <view class="trade-head">
          <text class="card-title">以旧换新</text>
          <text class="trade-status">{{ order.tradeInInboundStatusName || '旧品待入库' }}</text>
        </view>
        <view class="kv"><text class="k">抵扣金额</text><text class="v deduction">-¥{{ money(order.tradeInDeductionAmount) }}</text></view>
        <view class="kv"><text class="k">旧品汇总</text><text class="v">{{ order.tradeInSummary || '—' }}</text></view>
        <view class="kv">
          <text class="k">采购订单</text>
          <text class="v">{{ purchaseOrderText }}</text>
        </view>
        <view v-if="showPurchaseRetry" class="trade-error">
          <text class="trade-error-text">
            {{ order.tradeInPurchaseError
              ? '采购订单生成失败：' + order.tradeInPurchaseError
              : '采购订单尚未生成完成，可重试（重复提交不会重复创建）。' }}
          </text>
          <view class="trade-retry" :class="{ disabled: retrying }" @click="retryPurchase">
            <text>{{ retrying ? '重试中...' : '重试生成' }}</text>
          </view>
        </view>
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
            <view v-if="p.isTradeIn === '是'" class="product-trade">
              <text class="trade-tag">以旧换新</text>
              <text>抵扣 -¥{{ money(p.deductionAmount) }} · 应付 ¥{{ money(p.payableAmount) }}</text>
            </view>
            <text v-if="p.isTradeIn === '是' && (p.tradeInItems || []).length" class="product-old">
              旧品：{{ tradeInItemsText(p.tradeInItems) }}
            </text>
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
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'
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
const retrying = ref(false)

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

/** 旧品入库完成（SCM inboundStatus=ALL）后提醒消失，改为已入库标识。 */
const tradeInInboundDone = computed(() => {
  const o = order.value || {}
  return o.hasTradeIn && (o.tradeInInboundStatus === 'ALL'
    || o.tradeInInboundStatusName === '旧品已入库')
})

/**
 * 施工完成（业务状态 5）起持续提示旧品入库，直到入库完成；
 * 字典 key 非数字时退回状态名称判断。
 */
const atConstructionStage = computed(() => {
  const o = order.value || {}
  if (!o.hasTradeIn) return false
  const match = String(o.bizStatus == null ? '' : o.bizStatus).match(/\d+/)
  if (match) return Number(match[0]) >= 5
  return /施工完成|验收|完成/.test(String(o.bizStatusName || ''))
})
const showInboundReminder = computed(() =>
  atConstructionStage.value && !tradeInInboundDone.value)

const purchaseOrderText = computed(() => {
  const o = order.value || {}
  const status = o.tradeInPurchaseStatus
  if (status === 'SUCCESS') {
    return o.tradeInPurchaseBillNo
      ? `${o.tradeInPurchaseBillNo}（未审核 · ${o.tradeInInboundStatusName || '旧品未入库'}）`
      : '已生成（单号待同步）'
  }
  if (status === 'PENDING') return '生成中'
  if (status === 'FAILED') return '生成失败'
  return '—'
})

/** 采购单未成功生成（含 PENDING 残留/历史空状态）时提供幂等重试入口。 */
const showPurchaseRetry = computed(() => {
  const o = order.value || {}
  if (!o.hasTradeIn || o.tradeInPurchaseStatus === 'SUCCESS') return false
  return ['FAILED', 'PENDING'].includes(o.tradeInPurchaseStatus) || !o.tradeInPurchaseStatus
})

function tradeInItemsText(items) {
  return (items || [])
    .map(item => `${item.productName || item.brand || '旧品'} ×${item.quantity || 0}`)
    .join('、')
}

async function retryPurchase() {
  if (retrying.value) return
  retrying.value = true
  try {
    const result = await api.employeeRetryTradeInPurchase(orderId.value)
    if (result && result.status === 'SUCCESS') {
      order.value.tradeInPurchaseStatus = 'SUCCESS'
      order.value.tradeInPurchaseOrderId = result.purchaseOrderId
      order.value.tradeInPurchaseBillNo = result.billNo
      order.value.tradeInPurchaseError = ''
      uni.showToast({ title: '采购订单已生成', icon: 'success' })
    } else {
      order.value.tradeInPurchaseStatus = 'FAILED'
      order.value.tradeInPurchaseError = (result && result.error) || '生成失败'
      uni.showToast({
        title: order.value.tradeInPurchaseError, icon: 'none', duration: 3000
      })
    }
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '重试失败，请稍后再试', icon: 'none' })
  } finally {
    retrying.value = false
  }
}

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
.kv .v.deduction { color: #C62828; font-weight: 700; }
.inbound-alert {
  background: #FFF7E6; border: 2rpx solid #FFB74D; border-radius: 16rpx;
  padding: 22rpx 24rpx; margin-bottom: 16rpx;
  display: flex; flex-direction: column; gap: 8rpx;
}
.inbound-title { font-size: 26rpx; font-weight: 700; color: #B26A00; line-height: 1.5; }
.inbound-line { font-size: 23rpx; color: #8C5A00; line-height: 1.5; word-break: break-all; }
.inbound-hint { font-size: 21rpx; color: #A9812F; margin-top: 2rpx; }
.inbound-done {
  background: #F0FBF4; border: 2rpx solid #BFE9CF; border-radius: 16rpx;
  padding: 20rpx 24rpx; margin-bottom: 16rpx;
  display: flex; align-items: center; justify-content: space-between; gap: 12rpx;
}
.inbound-done-title { font-size: 25rpx; font-weight: 700; color: #0B7A42; }
.inbound-done-sub { font-size: 21rpx; color: #3D8B62; }
.trade-head { display: flex; align-items: center; justify-content: space-between; }
.trade-status {
  font-size: 20rpx; padding: 2rpx 12rpx; border-radius: 6rpx;
  background: #FFF7E6; color: #B26A00;
}
.trade-error {
  margin-top: 6rpx; padding: 16rpx 18rpx; border-radius: 10rpx;
  background: #FFF1F0; display: flex; flex-direction: column; gap: 12rpx;
}
.trade-error-text { font-size: 21rpx; color: #C62828; line-height: 1.5; word-break: break-all; }
.trade-retry {
  align-self: flex-start; padding: 8rpx 28rpx; border-radius: 24rpx;
  background: #FF6B35; color: #fff; font-size: 22rpx;
}
.trade-retry.disabled { opacity: .55; }
.product-trade { display: flex; align-items: center; flex-wrap: wrap; gap: 8rpx; font-size: 20rpx; color: #B26A00; }
.trade-tag {
  font-size: 18rpx; padding: 1rpx 8rpx; border-radius: 4rpx;
  background: #FFF0E8; color: #C2410C;
}
.product-old { font-size: 20rpx; color: #A9812F; line-height: 1.5; }
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
