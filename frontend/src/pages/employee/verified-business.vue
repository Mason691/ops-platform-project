<template>
  <view class="page">
    <view class="head">
      <text class="title">已核对业务</text>
      <text class="sub">财务核对完成的业务数据，可发起采购申请</text>
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
      <view class="btn ghost" @click="loadData"><text>重试</text></view>
    </view>
    <view v-else-if="!list.length" class="state">
      <text class="empty-icon">📋</text>
      <text>暂无财务核对完成的业务订单</text>
    </view>
    <view v-else class="list">
      <view v-if="truncated" class="warn">
        <text>结果超过 500 行，已截断；可在 PC 端按条件查询</text>
      </view>

      <view v-for="item in list" :key="itemKey(item)" class="card">
        <view class="card-top">
          <text class="order-no">{{ item.orderNo || item.receiptNo || '未填单号' }}</text>
          <text class="amount">¥{{ money(item.totalAmount) }}</text>
        </view>
        <text class="customer">{{ item.customerName || '—' }} {{ item.customerPhone || '' }}</text>
        <text v-if="item.communityName || item.detailedAddress" class="address">
          {{ item.communityName || '' }} {{ item.detailedAddress || '' }}
        </text>
        <view class="meta">
          <text>{{ item.saleDate || '—' }}</text>
          <text>商品 {{ (item.items && item.items.length) || item.itemCount }} 项 · 数量 {{ item.totalQty }}</text>
        </view>

        <view v-if="item.items && item.items.length" class="items">
          <view class="items-head">
            <text class="items-title">商品明细（{{ item.items.length }}）</text>
            <text v-if="hasMore(item)" class="items-toggle" @click="toggleDetails(item)">
              {{ expanded[itemKey(item)] ? '收起' : '展开全部' }}
            </text>
          </view>
          <view v-for="(line, idx) in visibleItems(item)" :key="itemKey(item) + '-' + idx" class="item-row">
            <view class="item-main">
              <text class="item-name">{{ line.materialName || line.model || line.category || '未匹配商品' }}</text>
              <text v-if="matchLabel(line.matchType)" class="item-tag">{{ matchLabel(line.matchType) }}</text>
            </view>
            <text class="item-sub">{{ itemSub(line) }}</text>
            <view class="item-calc">
              <text class="item-qty">{{ qtyText(line.qty) }} × ¥{{ money(line.price) }}</text>
              <text class="item-amount">¥{{ money(line.amount) }}</text>
            </view>
          </view>
        </view>

        <view class="card-bottom">
          <text v-if="item.requested" class="requested">已申请 {{ item.requestBillNo || '' }}</text>
          <text v-else class="hint">申请后生成采购订单草稿，请在 PC 端核对商品并完善供应商/仓库/单价</text>
          <view class="btn primary" :class="{ disabled: item.requested }"
            @click="initiate(item)">
            <text>{{ item.requested ? '已申请'
              : (submittingKey === itemKey(item) ? '提交中...' : '发起采购申请') }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

const loading = ref(true)
const errorMsg = ref('')
const list = ref([])
const truncated = ref(false)
const submittingKey = ref('')
const expanded = ref({})

onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent('/pages/employee/verified-business')
    })
    return
  }
  loadData()
})

onPullDownRefresh(async () => {
  await loadData()
  uni.stopPullDownRefresh()
})

async function loadData() {
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await api.scmFinanceCheckedOrders()
    list.value = (data && data.candidates) || []
    truncated.value = !!(data && data.truncated)
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function itemKey(item) {
  return item.orderNo || item.receiptNo || ('ID:' + item.businessDataId)
}

/** 商品明细默认展示前 3 行，避免订单列表过长；可展开全部 */
function hasMore(item) {
  return (item.items || []).length > 3
}

function visibleItems(item) {
  const rows = item.items || []
  return expanded.value[itemKey(item)] ? rows : rows.slice(0, 3)
}

function toggleDetails(item) {
  const key = itemKey(item)
  expanded.value = { ...expanded.value, [key]: !expanded.value[key] }
}

function itemSub(line) {
  return [line.brand, line.category, line.model].filter(Boolean).join(' · ')
}

function matchLabel(matchType) {
  if (!matchType || matchType === 'MATCHED') {
    return ''
  }
  return matchType === 'AMBIGUOUS' ? '多匹配' : '未匹配'
}

function qtyText(value) {
  const n = Number(value)
  return Number.isFinite(n) ? String(n) : '0'
}

/** 发起采购申请：生成采购订单草稿，PC 端完善供应商/仓库/单价后提交审批 */
function initiate(item) {
  if (item.requested || submittingKey.value) {
    return
  }
  const name = item.orderNo || item.receiptNo || '该业务订单'
  uni.showModal({
    title: '发起采购申请',
    content: `确定为 ${name} 发起采购申请？将生成采购订单草稿，供应商、收货仓库和单价需在 PC 端完善。`,
    success: async (res) => {
      if (!res.confirm) {
        return
      }
      submittingKey.value = itemKey(item)
      try {
        const order = await api.scmCreatePurchaseRequest({
          orderNo: item.orderNo || null,
          receiptNo: item.receiptNo || null,
          rowIds: item.rowIds || []
        })
        // 分录按商品/单价合并，条目数不等于业务行数；是否缺商品以 PC 端草稿核对为准
        uni.showToast({
          title: '采购申请已发起（' + (order.billNo || '') + '），请 PC 端完善',
          icon: 'none',
          duration: 2500
        })
        await loadData()
      } catch (e) {
        uni.showToast({ title: (e && e.msg) || '发起失败', icon: 'none', duration: 2500 })
      } finally {
        submittingKey.value = ''
      }
    }
  })
}

function money(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f6f8; padding-bottom: 40rpx; }
.head { padding: 36rpx 32rpx 20rpx; background: linear-gradient(135deg, #FF6B35, #E85D2C); }
.title { display: block; font-size: 40rpx; font-weight: 700; color: #fff; }
.sub { display: block; margin-top: 8rpx; font-size: 24rpx; color: rgba(255,255,255,.85); }
.state { padding: 120rpx 40rpx; display: flex; flex-direction: column; align-items: center; color: #86909C; font-size: 28rpx; }
.state-err { color: #F53F3F; margin-bottom: 24rpx; text-align: center; }
.empty-icon { font-size: 72rpx; margin-bottom: 16rpx; }
.list { padding: 24rpx; }
.warn { background: #fff7e8; color: #FF7D00; font-size: 24rpx; padding: 16rpx 20rpx; border-radius: 12rpx; margin-bottom: 16rpx; }
.card { background: #fff; border-radius: 20rpx; padding: 26rpx; margin-bottom: 20rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.04); }
.card-top { display: flex; justify-content: space-between; align-items: center; gap: 12rpx; }
.order-no { font-size: 30rpx; font-weight: 700; color: #1D2129; flex: 1; }
.amount { font-size: 30rpx; font-weight: 700; color: #F53F3F; }
.customer { display: block; margin-top: 12rpx; font-size: 27rpx; color: #4E5969; }
.address { display: block; margin-top: 6rpx; font-size: 24rpx; color: #86909C; }
.meta { display: flex; justify-content: space-between; margin-top: 12rpx; font-size: 23rpx; color: #86909C; }
.items { margin-top: 14rpx; padding-top: 14rpx; border-top: 1rpx dashed #f0f0f0; }
.items-head { display: flex; justify-content: space-between; align-items: center; }
.items-title { font-size: 23rpx; color: #4E5969; font-weight: 600; }
.items-toggle { font-size: 22rpx; color: #FF6B35; padding: 4rpx 8rpx; }
.item-row { margin-top: 10rpx; padding: 12rpx 14rpx; background: #fafbfc; border-radius: 10rpx; }
.item-main { display: flex; justify-content: space-between; align-items: center; gap: 12rpx; }
.item-name { flex: 1; font-size: 24rpx; color: #1D2129; word-break: break-all; }
.item-tag { font-size: 20rpx; color: #FF7D00; border: 1rpx solid #FFB65C; border-radius: 6rpx; padding: 0 6rpx; flex-shrink: 0; }
.item-sub { display: block; margin-top: 4rpx; font-size: 21rpx; color: #86909C; }
.item-calc { display: flex; justify-content: space-between; align-items: center; margin-top: 4rpx; font-size: 22rpx; }
.item-qty { color: #86909C; }
.item-amount { color: #F53F3F; font-weight: 600; }
.card-bottom { display: flex; justify-content: space-between; align-items: center; gap: 16rpx; margin-top: 20rpx; padding-top: 18rpx; border-top: 1rpx solid #f0f0f0; }
.hint { flex: 1; font-size: 22rpx; color: #86909C; line-height: 1.5; }
.requested { flex: 1; font-size: 24rpx; color: #00B42A; }
.btn { padding: 16rpx 26rpx; border-radius: 36rpx; font-size: 26rpx; text-align: center; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn.ghost { background: #fff; color: #FF6B35; border: 1rpx solid #FF6B35; margin-top: 16rpx; }
.btn.disabled { background: #f2f3f5; color: #86909C; }
</style>
