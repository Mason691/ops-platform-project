<template>
  <view class="monthly-section">
    <text class="section-title">📅 月度明细</text>

    <view class="month-card" v-for="m in months" :key="m.month">
      <view class="month-header" @click="toggleMonth(m.month)">
        <view class="month-info">
          <text class="month-label">{{ m.monthLabel || m.month }}</text>
          <text class="month-summary">
            {{ formatMoney(m.totalSale) }} / {{ formatMoney(m.totalCommission) }} / {{ m.orderCount }}单
          </text>
        </view>
        <text class="month-expand">{{ expanded[m.month] ? '▲' : '▼' }}</text>
      </view>

      <view class="month-detail" v-if="expanded[m.month]">
        <view class="detail-row" v-for="(d, di) in (m.details || [])" :key="di">
          <view class="detail-info">
            <text class="detail-product">{{ d.brand || '-' }} {{ d.model || '' }}</text>
            <text class="detail-meta">
              <text class="detail-cat">{{ d.categoryName || d.category || '-' }}</text>
              <text v-if="d.customer" class="detail-customer"> · 客户: {{ d.customer }}</text>
            </text>
            <text class="detail-date">{{ d.saleDate || '-' }}</text>
            <text v-if="d.communityName" class="detail-address">小区: {{ d.communityName }}</text>
            <text v-if="d.detailedAddress" class="detail-address">详细地址: {{ d.detailedAddress }}</text>
            <text v-if="d.voucherUrl" class="detail-voucher"
                  @click.stop="previewVoucher(d.voucherUrl)">🧾 查看凭证</text>
            <text v-if="d.returnStatus && d.returnStatus !== '未申请'" class="detail-return">
              退货{{ d.returnStatus }}<template v-if="Number(d.returnQuantity) > 0"> · 已退{{ d.returnQuantity }} · 结算{{ d.settleQuantity }}</template>
            </text>
          </view>
          <view class="detail-money">
            <text class="detail-sale">{{ formatMoney(d.receiptAmount != null ? d.receiptAmount : d.salePrice) }}</text>
            <text class="detail-commission">提成 {{ formatMoney(d.commissionAmount) }}</text>
            <text v-if="d.commissionRate != null && d.commissionRate !== ''" class="detail-rate">比例 {{ formatRate(d.commissionRate) }}</text>
            <text class="detail-status" :class="statusClass(d.calcStatus)">
              {{ d.calcStatus || '未计算' }}
            </text>
            <text v-if="canReturn(d)" class="detail-return-btn" @click.stop="requestReturn(d)">申请退货</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { reactive } from 'vue'
import { assetUrl } from '@/utils/image'
import { api } from '@/api/index'

defineProps({
  months: { type: Array, default: () => [] }
})
const emit = defineEmits(['submitted'])

const expanded = reactive({})

function toggleMonth(key) {
  if (expanded[key]) delete expanded[key]
  else expanded[key] = true
}

function formatMoney(val) {
  const n = Number(val) || 0
  return '¥' + n.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

function formatRate(val) {
  const n = Number(val)
  if (isNaN(n)) return '-'
  return (n % 1 === 0 ? n.toFixed(0) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')) + '%'
}

function statusClass(status) {
  if (status === '经办人已核对') return 'status-checked'
  if (status === '已计算') return 'status-calc'
  return 'status-raw'
}

function previewVoucher(raw) {
  const urls = String(raw || '').split(',').map(s => assetUrl(s.trim())).filter(Boolean)
  if (!urls.length) return
  uni.previewImage({ urls, current: urls[0] })
}

/** 可申请退货：未上报集团、无审批中申请、仍有未退数量 */
function canReturn(d) {
  if (!d || !d.id) return false
  if (d.calcStatus === '已上报集团') return false
  if (d.returnStatus === '审批中') return false
  const sold = Number(d.saleQuantity ?? 1)
  const returned = Number(d.returnQuantity ?? 0)
  return sold - returned > 0
}

function requestReturn(d) {
  const max = Math.max(1, Number(d.saleQuantity ?? 1) - Number(d.returnQuantity ?? 0))
  uni.showModal({
    title: '申请退货',
    editable: true,
    placeholderText: `请输入退货数量（最多 ${max}）`,
    success: async (res) => {
      if (!res.confirm) return
      const qty = parseInt(res.content, 10)
      if (!qty || qty < 1 || qty > max) {
        uni.showToast({ title: '请输入 1-' + max + ' 的退货数量', icon: 'none' })
        return
      }
      try {
        await api.employeeRequestReturn(d.id, { returnQuantity: qty })
        uni.showToast({ title: '已提交，等待审批', icon: 'success' })
        emit('submitted')
      } catch (e) {
        uni.showToast({ title: (e && e.msg) || '提交失败', icon: 'none' })
      }
    }
  })
}
</script>

<style scoped>
.monthly-section { padding: 0 24rpx; }
.section-title { font-size: 28rpx; color: #999; font-weight: 600; padding: 24rpx 0 16rpx; display: block; }
.month-card {
  background: #fff; border-radius: 16rpx; margin-bottom: 16rpx;
  overflow: hidden; box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.month-header { display: flex; justify-content: space-between; align-items: center; padding: 24rpx; }
.month-info { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.month-label { font-size: 30rpx; font-weight: 600; color: #333; }
.month-summary { font-size: 24rpx; color: #999; }
.month-expand { font-size: 24rpx; color: #ccc; padding: 8rpx; }
.month-detail { border-top: 1rpx solid #f0f0f0; }
.detail-row {
  display: flex; justify-content: space-between; align-items: flex-start;
  padding: 20rpx 24rpx; border-bottom: 1rpx solid #f8f8f8;
}
.detail-row:last-child { border-bottom: none; }
.detail-info { display: flex; flex-direction: column; gap: 4rpx; flex: 1; }
.detail-product { font-size: 28rpx; color: #333; font-weight: 500; }
.detail-meta { font-size: 22rpx; color: #999; }
.detail-cat { color: #FF6B35; }
.detail-customer { color: #999; }
.detail-date { font-size: 22rpx; color: #bbb; }
.detail-address { font-size: 22rpx; color: #666; word-break: break-all; }
.detail-voucher { font-size: 22rpx; color: #2196F3; }
.detail-return { font-size: 22rpx; color: #E6A23C; }
.detail-return-btn { font-size: 22rpx; color: #FF6B35; border: 1rpx solid #FF6B35; border-radius: 8rpx; padding: 4rpx 12rpx; margin-top: 6rpx; }
.detail-money { display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; }
.detail-sale { font-size: 28rpx; color: #FF6B35; font-weight: 600; }
.detail-commission { font-size: 22rpx; color: #4caf50; }
.detail-rate { font-size: 20rpx; color: #2196F3; }
.detail-status { font-size: 22rpx; padding: 2rpx 10rpx; border-radius: 6rpx; margin-left: 8rpx; }
.status-calc { background: #E6F0FF; color: #409EFF; }
.status-checked { background: #E8F8E8; color: #07C160; }
.status-raw { background: #F5F5F5; color: #999; }
</style>
