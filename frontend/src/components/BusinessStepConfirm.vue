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
      <BusinessStepReceipt v-if="hasStepErrors(errors, 2)" :form="form" :meta="meta"
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
          <text class="product-name">{{ product.brand }} · {{ product.model }}</text>
          <text class="product-amount">¥{{ amountOf(product) }}</text>
        </view>
        <text class="product-meta">
          {{ product.category }} ｜ 单价 ¥{{ money(product.salePrice) }} × {{ product.saleQuantity || 0 }}
        </text>
        <text v-if="product.standardSalePrice != null" class="product-sub">
          标准价 ¥{{ money(product.standardSalePrice) }}
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
        <text class="block-title">收款信息</text>
        <text class="edit" @click="$emit('edit', 2)">修改</text>
      </view>
      <view class="kv"><text class="k">支付方式</text><text class="v">{{ paymentLabel }}</text></view>
      <view class="kv"><text class="k">市场收款日期</text><text class="v">{{ form.receiptDate || '—' }}</text></view>
      <view v-if="form.voucherUrls && form.voucherUrls.length" class="kv">
        <text class="k">收款凭证</text>
        <view class="vouchers">
          <image v-for="(url, index) in form.voucherUrls" :key="url + index" class="voucher-thumb"
                 :src="assetUrl(url)" mode="aspectFill" @click="previewVoucher(index)" />
        </view>
      </view>
      <view class="kv"><text class="k">备注</text><text class="v">{{ form.remark || '—' }}</text></view>
    </view>

    <view class="totals-card">
      <view class="total-line">
        <text class="total-label">合计金额</text>
        <AnimatedAmount class="total-amount" :value="totalAmount" />
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
import BusinessStepReceipt from '@/components/BusinessStepReceipt.vue'
import AnimatedAmount from '@/components/AnimatedAmount.vue'
import {
  PAYMENT_METHODS, computeProductAmount, computeProductsTotal, hasStepErrors, normalizeMoney
} from '@/utils/businessForm'
import { assetUrl } from '@/utils/image'

/**
 * 第 4 步：确认提交。顶部待补充事项卡片就地补填缺失字段；
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
const paymentLabel = computed(() => {
  const found = PAYMENT_METHODS.find(item => item.value === props.form.paymentMethod)
  return found ? found.label : '—'
})
const detailCount = computed(() => {
  const products = (props.form.products || []).length
  const agents = Math.max(1, (props.form.agentUserIds || []).length)
  return products * agents
})

function amountOf(product) {
  return computeProductAmount(product).toFixed(2)
}

function money(value) {
  return normalizeMoney(value).toFixed(2)
}

function previewVoucher(index) {
  const urls = (props.form.voucherUrls || []).map(assetUrl)
  uni.previewImage({ urls, current: urls[index] })
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
.totals-card { background: linear-gradient(135deg, #FFF6F1, #FFF); }
.total-line { display: flex; align-items: baseline; justify-content: space-between; }
.total-label { font-size: 27rpx; color: #666; font-weight: 600; }
.total-amount { font-size: 46rpx; font-weight: 700; color: #FF6B35; }
.total-meta {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 14rpx; font-size: 23rpx; color: #999;
}
</style>
