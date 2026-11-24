<template>
  <view class="step-confirm">
    <view v-if="missing.length" class="missing-card">
      <view class="missing-head">
        <text class="missing-title">待补充事项</text>
        <text class="missing-count">{{ missing.length }} 项</text>
      </view>
      <view class="missing-labels">
        <text v-for="item in missing" :key="item.key" class="missing-chip">{{ item.label }}</text>
      </view>
      <text class="missing-hint">以下是前面步骤遗漏的必填项，可直接在下方补填后提交，无需返回上一步</text>
      <BusinessStepBasic v-if="hasStepErrors(errors, 0)" :form="form" :meta="meta"
                         :errors="errors" :querying="querying" :member-status="memberStatus"
                         :only-errors="true" @query-member="$emit('query-member')" />
      <BusinessStepProducts v-if="hasStepErrors(errors, 1)" :form="form" :meta="meta"
                            :errors="errors" :only-errors="true" />
      <BusinessStepPayment v-if="hasStepErrors(errors, 2)" :form="form" :meta="meta"
                           :errors="errors" :only-errors="true" />
      <BusinessStepVoucher v-if="hasStepErrors(errors, 3)" :form="form" :meta="meta"
                           :errors="errors" :only-errors="true" />
    </view>

    <view class="summary-card">
      <view class="block-head">
        <text class="block-title">基础信息</text>
        <text class="edit" @click="$emit('edit', 0)">修改</text>
      </view>
      <view class="kv"><text class="k">批次</text><text class="v">{{ batchLabel }}</text></view>
      <view class="kv"><text class="k">客户手机</text><text class="v">{{ form.customerPhone || '—' }}</text></view>
      <view class="kv"><text class="k">客户名称</text><text class="v">{{ form.customer || '—' }}</text></view>
      <view class="kv">
        <text class="k">地址</text>
        <text class="v">{{ addressText }}</text>
      </view>
      <view class="kv">
        <text class="k">经办人</text>
        <view class="tags">
          <text v-for="name in agentNames" :key="name" class="tag">{{ name }}</text>
          <text v-if="!agentNames.length" class="v">—</text>
        </view>
      </view>
    </view>

    <view class="summary-card">
      <view class="block-head">
        <text class="block-title">商品明细</text>
        <text class="edit" @click="$emit('edit', 1)">修改</text>
      </view>
      <view v-for="(product, index) in form.products" :key="index" class="product-summary">
        <view class="product-head">
          <text class="product-name">{{ product.brand ? product.brand + ' · ' : '' }}{{ product.model }}</text>
          <text class="product-amount">¥{{ amountOf(product) }}</text>
        </view>
        <text class="product-meta">
          {{ product.category }} ｜ 单价 ¥{{ money(product.salePrice) }} × {{ product.saleQuantity || 0 }}
        </text>
        <text v-if="product.standardSalePrice != null" class="product-sub">
          标准价 ¥{{ money(product.standardSalePrice) }}
        </text>
        <view v-if="product.isTradeIn" class="product-trade-in">
          <text class="special-tag">以旧换新</text>
          <text class="product-sub">销售 ¥{{ money(computeProductAmount(product)) }}</text>
          <text class="product-sub">抵扣 -¥{{ money(product.deductionAmount) }}</text>
          <text class="product-sub">应付 ¥{{ amountOf(product) }}</text>
        </view>
        <text v-if="product.isTradeIn && (product.tradeInItems || []).length" class="product-sub">
          旧品：{{ tradeInSummary(product) }}
        </text>
        <view v-if="product.isSpecial" class="product-special">
          <text class="special-tag">特批</text>
          <text class="product-sub">提成 {{ product.commissionRate || '—' }}%</text>
          <text v-if="product.specialFileName" class="product-sub">
            ｜ {{ product.specialFileName }}
          </text>
        </view>
      </view>
    </view>

    <view class="summary-card">
      <view class="block-head">
        <text class="block-title">支付信息</text>
        <text class="edit" @click="$emit('edit', 2)">修改</text>
      </view>
      <view class="kv"><text class="k">付款方式</text><text class="v">{{ paymentTypeLabel }}</text></view>
      <view v-if="isDeposit" class="kv"><text class="k">定金金额</text><text class="v">¥{{ money(form.depositAmount) }}</text></view>
      <view v-if="isDeposit" class="kv"><text class="k">应补余款</text><text class="v amount">¥{{ money(balanceAmount) }}</text></view>
      <view class="kv"><text class="k">支付方式</text><text class="v">{{ paymentLabel }}</text></view>
      <view class="kv"><text class="k">市场收款日期</text><text class="v">{{ form.receiptDate || '—' }}</text></view>
      <view v-if="specialProducts.length" class="kv">
        <text class="k">特批/提成</text>
        <view class="special-list">
          <text v-for="(product, index) in specialProducts" :key="index" class="special-line">
            {{ product.brand ? product.brand + ' · ' : '' }}{{ product.model }}：提成 {{ product.commissionRate || '—' }}%
          </text>
        </view>
      </view>
      <view class="kv"><text class="k">备注</text><text class="v">{{ form.remark || '—' }}</text></view>
    </view>

    <view class="summary-card">
      <view class="block-head">
        <text class="block-title">凭证信息</text>
        <text class="edit" @click="$emit('edit', 3)">修改</text>
      </view>
      <view class="kv">
        <text class="k">收据图片</text>
        <view v-if="form.receiptImage && form.receiptImage.url" class="vouchers">
          <image class="voucher-thumb" :src="assetUrl(form.receiptImage.url)"
                 mode="aspectFill" @click="previewReceiptImage" />
        </view>
        <text v-else class="v">—</text>
      </view>
      <view class="kv"><text class="k">收据号</text><text class="v">{{ form.receiptNo || '—' }}</text></view>
      <view class="kv">
        <text class="k">支付凭证</text>
        <view v-if="paymentVouchers.length" class="vouchers">
          <image v-for="(file, index) in paymentVouchers" :key="file.id || index"
                 class="voucher-thumb" :src="assetUrl(file.url)" mode="aspectFill"
                 @click="previewPaymentVoucher(index)" />
        </view>
        <text v-else class="v">—</text>
      </view>
      <view class="kv">
        <text class="k">合同附件</text>
        <view v-if="contractAttachments.length" class="attachment-list">
          <view v-for="(file, index) in contractAttachments" :key="file.id || index"
                class="attachment-chip" @click="previewContract(file)">
            <image v-if="isImageFile(file)" class="attachment-mini"
                   :src="assetUrl(file.url)" mode="aspectFill" />
            <text v-else class="attachment-pdf">PDF</text>
            <text class="attachment-name">{{ file.fileName || file.name || '合同附件' }}</text>
          </view>
        </view>
        <text v-else class="v">—</text>
      </view>
    </view>

    <view class="totals-card">
      <view class="total-line">
        <text class="total-label">商品合计</text>
        <text class="total-sub">¥{{ money(totalAmount) }}</text>
      </view>
      <view v-if="deductionTotal > 0" class="total-line">
        <text class="total-label">抵扣合计</text>
        <text class="total-sub deduction">-¥{{ money(deductionTotal) }}</text>
      </view>
      <view class="total-line">
        <text class="total-label">应付合计</text>
        <AnimatedAmount class="total-amount" :value="payableTotal" />
      </view>
      <view class="total-meta">
        <text>共 {{ form.products.length }} 种商品</text>
        <text>预计生成 {{ detailCount }} 条业务明细</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import BusinessStepBasic from '@/components/BusinessStepBasic.vue'
import BusinessStepProducts from '@/components/BusinessStepProducts.vue'
import BusinessStepPayment from '@/components/BusinessStepPayment.vue'
import BusinessStepVoucher from '@/components/BusinessStepVoucher.vue'
import AnimatedAmount from '@/components/AnimatedAmount.vue'
import {
  PAYMENT_METHODS, computeProductAmount, computeProductPayable,
  computeProductsDeductionTotal, computeProductsPayableTotal, computeProductsTotal,
  hasStepErrors, normalizeMoney
} from '@/utils/businessForm'
import { assetUrl } from '@/utils/image'

/**
 * 第 5 步：确认提交。顶部待补充事项卡片就地补填缺失字段；
 * 下方为只读订单汇总，每块可跳回对应步骤修改。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  missing: { type: Array, default: () => [] },
  querying: { type: Boolean, default: false },
  memberStatus: { type: String, default: 'idle' }
})
defineEmits(['edit', 'query-member'])

const batchLabel = computed(() => props.form.newBatch
  ? (props.meta.newBatchPrefix || '新建批次')
  : (props.form.batchNo || '—'))

const addressText = computed(() => {
  const parts = [props.form.communityName, props.form.detailAddress].filter(Boolean)
  return parts.length ? parts.join(' ') : '—'
})

const agentNames = computed(() => {
  const ids = new Set((props.form.agentUserIds || []).map(id => String(id)))
  const employees = props.meta.employees || []
  const names = employees
    .filter(item => item && ids.has(String(item.userId)))
    .map(item => item.name)
  if (props.meta.employeeName && ids.has(String(props.meta.employeeUserId))
    && !names.includes(props.meta.employeeName)) {
    names.unshift(props.meta.employeeName)
  }
  return names
})

const totalAmount = computed(() => computeProductsTotal(props.form.products))
const deductionTotal = computed(() => computeProductsDeductionTotal(props.form.products))
const payableTotal = computed(() => computeProductsPayableTotal(props.form.products))
const isDeposit = computed(() => props.form.paymentType === 'DEPOSIT')
const paymentTypeLabel = computed(() => isDeposit.value ? '定金支付' : '全款支付')
const balanceAmount = computed(() =>
  Math.max(0, payableTotal.value - normalizeMoney(props.form.depositAmount)))
const money = value => (Number(value) || 0).toFixed(2)
const paymentLabel = computed(() => {
  const found = PAYMENT_METHODS.find(item => item.value === props.form.paymentMethod)
  return found ? found.label : '—'
})
const specialProducts = computed(() => (props.form.products || [])
  .filter(product => product && product.isSpecial))
const paymentVouchers = computed(() => Array.isArray(props.form.paymentVouchers)
  ? props.form.paymentVouchers : [])
const contractAttachments = computed(() => Array.isArray(props.form.contractAttachments)
  ? props.form.contractAttachments : [])
const detailCount = computed(() => {
  const products = (props.form.products || []).length
  const agents = Math.max(1, (props.form.agentUserIds || []).length)
  return products * agents
})

function amountOf(product) {
  return computeProductPayable(product).toFixed(2)
}

function tradeInSummary(product) {
  const items = Array.isArray(product.tradeInItems) ? product.tradeInItems : []
  const parts = items.slice(0, 5).map(item =>
    `${item.productName || item.brand || '旧品'} ×${item.quantity}`)
  if (items.length > 5) parts.push(`等${items.length}行`)
  return parts.join('、')
}

function previewReceiptImage() {
  const url = props.form.receiptImage && props.form.receiptImage.url
  if (!url) return
  uni.previewImage({ urls: [assetUrl(url)], current: assetUrl(url) })
}

function previewPaymentVoucher(index) {
  const urls = paymentVouchers.value.map(file => assetUrl(file.url))
  uni.previewImage({ urls, current: urls[index] })
}

function isImageFile(file) {
  if (!file) return false
  if (String(file.contentType || '').startsWith('image/')) return true
  return /\.(jpe?g|png)$/i.test(file.fileName || file.name || file.url || '')
}

function previewContract(file) {
  if (!file || !file.url) return
  if (isImageFile(file)) {
    const urls = contractAttachments.value.filter(isImageFile).map(item => assetUrl(item.url))
    uni.previewImage({ urls, current: assetUrl(file.url) })
    return
  }
  // #ifdef H5
  if (typeof window !== 'undefined') window.open(assetUrl(file.url), '_blank')
  // #endif
  // #ifndef H5
  uni.downloadFile({
    url: assetUrl(file.url),
    success: res => {
      if (res.statusCode === 200) {
        uni.openDocument({ filePath: res.tempFilePath, fileType: 'pdf', showMenu: true })
      } else {
        uni.showToast({ title: '文件下载失败', icon: 'none' })
      }
    },
    fail: () => uni.showToast({ title: '文件下载失败', icon: 'none' })
  })
  // #endif
}
</script>

<style scoped>
.step-confirm { display: flex; flex-direction: column; gap: 20rpx; }
.missing-card {
  background: #FFF8F4; border: 2rpx solid #FFD9C8; border-radius: 16rpx;
  padding: 22rpx 20rpx; display: flex; flex-direction: column; gap: 16rpx;
}
.missing-head { display: flex; align-items: center; justify-content: space-between; }
.missing-title { font-size: 30rpx; font-weight: 700; color: #C2410C; }
.missing-count {
  font-size: 23rpx; color: #C2410C; background: #FFE8DC;
  border-radius: 20rpx; padding: 4rpx 18rpx;
}
.missing-labels { display: flex; flex-wrap: wrap; gap: 10rpx; }
.missing-chip {
  font-size: 22rpx; color: #C2410C; background: #fff;
  border-radius: 8rpx; padding: 6rpx 14rpx;
}
.missing-hint { font-size: 22rpx; color: #9a6a55; line-height: 1.5; }
.summary-card, .totals-card {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.block-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16rpx;
}
.block-title { font-size: 29rpx; font-weight: 700; color: #333; }
.edit { font-size: 25rpx; color: #FF6B35; padding: 4rpx 8rpx; }
.kv { display: flex; align-items: flex-start; padding: 10rpx 0; }
.k { width: 168rpx; flex-shrink: 0; font-size: 25rpx; color: #999; }
.v { flex: 1; font-size: 26rpx; color: #333; line-height: 1.5; word-break: break-all; }
.tags { flex: 1; display: flex; flex-wrap: wrap; gap: 10rpx; }
.tag {
  font-size: 23rpx; color: #FF6B35; background: #FFF3ED;
  border-radius: 8rpx; padding: 4rpx 14rpx;
}
.product-summary { padding: 14rpx 0; border-top: 1rpx solid #f5f5f5; }
.block-head + .product-summary { border-top: none; }
.product-head { display: flex; align-items: center; justify-content: space-between; }
.product-name {
  flex: 1; min-width: 0; margin-right: 16rpx;
  font-size: 26rpx; color: #333; font-weight: 600;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.product-amount { font-size: 27rpx; color: #FF6B35; font-weight: 700; white-space: nowrap; }
.product-meta { display: block; font-size: 22rpx; color: #999; margin-top: 6rpx; }
.product-sub { display: block; font-size: 21rpx; color: #999; margin-top: 4rpx; }
.product-special { display: flex; align-items: center; flex-wrap: wrap; gap: 8rpx; margin-top: 6rpx; }
.special-tag {
  font-size: 20rpx; color: #C2410C; background: #FFF0E8;
  border-radius: 6rpx; padding: 2rpx 8rpx;
}
.vouchers { flex: 1; display: flex; flex-wrap: wrap; gap: 12rpx; }
.voucher-thumb {
  width: 96rpx; height: 96rpx; border-radius: 8rpx; background: #f5f5f5;
}
.attachment-list { flex: 1; display: flex; flex-direction: column; gap: 10rpx; }
.attachment-chip {
  display: flex; align-items: center; gap: 12rpx;
  padding: 10rpx 12rpx; border-radius: 10rpx; background: #FAFBFC;
}
.attachment-mini {
  width: 56rpx; height: 56rpx; border-radius: 8rpx; background: #f5f5f5;
  flex-shrink: 0;
}
.attachment-pdf {
  width: 56rpx; height: 56rpx; line-height: 56rpx; text-align: center;
  border-radius: 8rpx; background: #FFF7F2; color: #C2410C;
  font-size: 20rpx; font-weight: 700; flex-shrink: 0;
}
.attachment-name {
  flex: 1; min-width: 0; font-size: 23rpx; color: #333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.special-list { flex: 1; display: flex; flex-direction: column; gap: 4rpx; }
.special-line { font-size: 24rpx; color: #C2410C; line-height: 1.5; word-break: break-all; }
.totals-card { background: linear-gradient(135deg, #FFF6F1, #FFF); }
.total-line { display: flex; align-items: baseline; justify-content: space-between; }
.total-label { font-size: 27rpx; color: #666; font-weight: 600; }
.total-amount { font-size: 46rpx; font-weight: 700; color: #FF6B35; }
.total-meta {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 14rpx; font-size: 23rpx; color: #999;
}
</style>
