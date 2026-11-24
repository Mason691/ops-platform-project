<template>
  <view class="card deposit-card">
    <view class="card-head">
      <text class="card-title">定金与尾款</text>
      <text class="paid-tag">尾款已补录</text>
    </view>

    <view class="section deposit-section">
      <view class="section-head">
        <text class="section-title">定金</text>
        <text class="section-amount">{{ money(order.depositAmount) }}</text>
      </view>
      <view class="kv"><text class="k">收款方式</text>
        <text class="v">{{ order.paymentMethodName || order.paymentMethod || '—' }}</text>
      </view>
      <view class="kv"><text class="k">收款日期</text>
        <text class="v">{{ formatDate(order.depositReceiptDate) }}</text>
      </view>
      <view class="kv"><text class="k">收据号</text>
        <text class="v strong">{{ order.receiptNo || '—' }}</text>
      </view>
      <view class="kv image-row">
        <text class="k">收据图片</text>
        <view class="v image-cell">
          <image v-if="depositImage" class="thumb" :src="depositImage" mode="aspectFill"
                 @click="previewImage(depositImage)" @error="onImgError" />
          <text v-else class="empty">未上传</text>
        </view>
      </view>
    </view>

    <view class="section balance-section">
      <view class="section-head">
        <text class="section-title">尾款</text>
        <text class="section-amount received">{{ money(receivedAmount) }}</text>
      </view>
      <view class="kv"><text class="k">应补尾款</text>
        <text class="v">{{ money(order.balanceAmount) }}</text>
      </view>
      <view class="kv"><text class="k">实收尾款</text>
        <text class="v strong">{{ money(receivedAmount) }}</text>
      </view>
      <view class="kv"><text class="k">收款方式</text>
        <text class="v">{{ order.balancePaymentMethodName || order.balancePaymentMethod || '—' }}</text>
      </view>
      <view class="kv"><text class="k">收款日期</text>
        <text class="v">{{ formatDate(order.balanceReceiptDate || order.balancePaidAt) }}</text>
      </view>
      <view class="kv"><text class="k">收据号</text>
        <text class="v strong">{{ order.balanceReceiptNo || '—' }}</text>
      </view>
      <view class="kv image-row">
        <text class="k">收款凭证</text>
        <view class="v image-cell">
          <template v-if="voucher">
            <image v-if="voucher.isImage" class="thumb" :src="voucher.url" mode="aspectFill"
                   @click="previewImage(voucher.url)" @error="onImgError" />
            <view v-else class="thumb pdf-thumb" @click="openVoucher">
              <text>PDF</text>
              <text class="pdf-hint">点击查看</text>
            </view>
          </template>
          <text v-else class="empty">未上传</text>
        </view>
      </view>
      <view v-if="order.balanceRemark" class="kv remark-row">
        <text class="k">收款备注</text>
        <text class="v remark">{{ order.balanceRemark }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { assetUrl, onImgError } from '@/utils/image'

/**
 * 财务核对/派工出库任务页的定金与尾款只读展示。
 *
 * 仅由父组件在「定金支付且已补录尾款」时渲染；定金收据图片可能缺失
 * （旧单或未上传），尾款凭证可能是 PDF，需分别处理预览。
 */
const props = defineProps({
  order: { type: Object, default: () => ({}) }
})

const depositImage = computed(() => {
  const url = String((props.order && props.order.receiptImageUrl) || '').trim()
  return url ? assetUrl(url) : ''
})

/** 尾款已标记全款但历史数据缺实收金额时，用应补尾款兜底展示 */
const receivedAmount = computed(() => {
  const o = props.order || {}
  return o.balanceReceivedAmount == null ? o.balanceAmount : o.balanceReceivedAmount
})

const voucher = computed(() => {
  const raw = String((props.order && props.order.balanceVoucherUrl) || '').trim()
  if (!raw) return null
  return { url: assetUrl(raw), isImage: /\.(jpe?g|png|webp|gif)(\?|#|$)/i.test(raw) }
})

function money(value) {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  return Number.isFinite(n) ? '¥' + n.toFixed(2) : String(value)
}

function formatDate(value) {
  if (!value) return '—'
  const match = String(value).match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/)
  if (!match) return String(value)
  return match[1] + '-' + match[2].padStart(2, '0') + '-' + match[3].padStart(2, '0')
}

function previewImage(url) {
  uni.previewImage({ urls: [url], current: url })
}

function openVoucher() {
  if (!voucher.value) return
  // #ifdef H5
  window.open(voucher.value.url, '_blank')
  // #endif
  // #ifndef H5
  uni.openDocument({ filePath: voucher.value.url, showMenu: true })
  // #endif
}
</script>

<style scoped>
.card { margin: 20rpx 24rpx; background: #fff; border-radius: 20rpx; padding: 26rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.04); }
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18rpx; }
.card-title { font-size: 30rpx; font-weight: 600; color: #1D2129; }
.paid-tag { font-size: 21rpx; padding: 4rpx 12rpx; border-radius: 8rpx; background: #E8FFEA; color: #0B7A42; font-weight: 600; }
.section { border-radius: 16rpx; padding: 20rpx 22rpx; }
.deposit-section { background: #FFF7F2; border: 1rpx solid #FFE1D2; }
.balance-section { margin-top: 16rpx; background: #F4FBF6; border: 1rpx solid #D7F0DF; }
.section-head { display: flex; align-items: center; justify-content: space-between; padding-bottom: 10rpx; border-bottom: 1rpx solid rgba(0,0,0,.05); }
.section-title { font-size: 26rpx; font-weight: 600; color: #4E5969; }
.section-amount { font-size: 30rpx; font-weight: 700; color: #FF6B35; }
.section-amount.received { color: #0B7A42; }
.kv { display: flex; justify-content: space-between; padding: 9rpx 0; font-size: 26rpx; }
.k { color: #86909C; flex: 0 0 150rpx; }
.v { color: #1D2129; text-align: right; flex: 1; word-break: break-all; }
.v.strong { font-weight: 600; }
.image-row { align-items: flex-start; }
.image-cell { display: flex; justify-content: flex-end; }
.thumb { width: 132rpx; height: 132rpx; border-radius: 10rpx; background: #F2F3F5; }
.pdf-thumb { display: flex; flex-direction: column; align-items: center; justify-content: center; background: #FFF1F0; color: #E53935; font-size: 28rpx; font-weight: 700; border: 1rpx solid #FFD4D1; box-sizing: border-box; }
.pdf-hint { margin-top: 6rpx; font-size: 19rpx; font-weight: 400; color: #8C5A00; }
.empty { font-size: 24rpx; color: #86909C; }
.remark-row { align-items: flex-start; }
.remark { white-space: pre-wrap; line-height: 1.5; }
</style>
