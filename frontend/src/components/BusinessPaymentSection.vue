<template>
  <view v-if="!onlyErrors || errors.paymentType || errors.depositAmount"
        id="field-paymentType" class="payment-card">
    <view class="label-row">
      <text class="label">付款方式 <text class="req">*</text></text>
      <text class="sales-total">
        {{ deductionTotal > 0 ? '应付合计' : '销售价合计' }} ¥{{ money(payableTotal) }}
        <text v-if="deductionTotal > 0" class="sales-sub">（销售 ¥{{ money(salesTotal) }}）</text>
      </text>
    </view>
    <SegmentedControl :model-value="form.paymentType || 'FULL'" :options="paymentTypeOptions"
                      @update:model-value="changePaymentType" />
    <text class="section-hint">
      全款支付 / 定金支付；与收款信息中的支付方式（二维码/现金/转账）相互独立。
    </text>
    <text v-if="errors.paymentType" class="ferr">{{ errors.paymentType }}</text>

    <view v-if="(form.paymentType || 'FULL') === 'DEPOSIT' && show('depositAmount')"
          id="field-depositAmount" class="deposit-block">
      <text class="label">定金金额 <text class="req">*</text></text>
      <input class="input" type="digit" :class="{ 'control-error': errors.depositAmount }"
             :value="form.depositAmount" placeholder="请输入定金金额（>0 且小于应付合计）"
             @input="onDepositInput" @blur="formatDeposit"
             @focus="keepFieldVisible('field-depositAmount')" />
      <text v-if="errors.depositAmount" class="ferr">{{ errors.depositAmount }}</text>
      <view class="balance-row">
        <text class="balance-label">应补余款</text>
        <text class="balance-value">¥{{ money(balanceAmount) }}</text>
      </view>
      <text class="section-hint">应补余款 = 应付合计 − 定金金额，由系统自动计算，不支持手工填写。</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { computeProductsDeductionTotal, computeProductsPayableTotal, computeProductsTotal, normalizeMoney } from '@/utils/businessForm'
import { keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 付款方式（全款/定金）与应补余款。
 *
 * 归属商品明细步骤（第 2 步）：销售价合计来自当前商品行，定金/余款与销售价强相关，
 * 放在商品录入后立即确认，避免到收款步骤才发现金额不对。
 *
 * paymentType 是制单时选择的付款方式（FULL/DEPOSIT，制单后不变）；
 * 订单是否已收齐全款看 isFullPayment，二者不可混用。
 */
const props = defineProps({
  form: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

const paymentTypeOptions = [
  { value: 'FULL', label: '全款支付' },
  { value: 'DEPOSIT', label: '定金支付' }
]
const salesTotal = computed(() => computeProductsTotal(props.form.products))
const deductionTotal = computed(() => computeProductsDeductionTotal(props.form.products))
const payableTotal = computed(() => computeProductsPayableTotal(props.form.products))
const balanceAmount = computed(() =>
  Math.max(0, payableTotal.value - normalizeMoney(props.form.depositAmount)))
const money = value => (Number(value) || 0).toFixed(2)
const show = key => !props.onlyErrors || !!props.errors[key]

/** 切换付款方式：二次确认，确认后清空定金金额（余款随之为0）。 */
function changePaymentType(value) {
  const current = props.form.paymentType || 'FULL'
  if (value === current) return
  const hasDeposit = current === 'DEPOSIT' && normalizeMoney(props.form.depositAmount) > 0
  uni.showModal({
    title: '切换付款方式',
    content: hasDeposit
      ? '切换后将清空已填写的定金金额与应补余款，是否继续？'
      : '切换付款方式后请按新的方式核对收款信息，是否继续？',
    success: res => {
      if (!res.confirm) return
      props.form.paymentType = value
      props.form.depositAmount = ''
    }
  })
}

function onDepositInput(event) {
  // 保留原始输入供校验提示，不静默改写
  props.form.depositAmount = String(event.detail.value ?? '')
}

function formatDeposit() {
  const raw = String(props.form.depositAmount || '').trim()
  if (raw === '') return
  const value = Number(raw)
  if (Number.isFinite(value) && value > 0) props.form.depositAmount = value.toFixed(2)
}
</script>

<style scoped>
.payment-card {
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04); margin-top: 20rpx;
}
.label-row { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 14rpx; }
.label { display: block; font-size: 26rpx; color: #666; margin-bottom: 14rpx; }
.label-row .label { margin-bottom: 0; }
.sales-total { font-size: 26rpx; color: #FF6B35; font-weight: 700; }
.sales-sub { font-size: 21rpx; color: #86909C; font-weight: 400; }
.section-hint { display: block; font-size: 22rpx; color: #999; line-height: 1.6; margin: 12rpx 0 0; }
.deposit-block { margin-top: 22rpx; padding-top: 20rpx; border-top: 1rpx solid #F2F3F5; }
.input {
  width: 100%; height: 80rpx !important; min-height: 80rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 0 20rpx;
  font-size: 30rpx; color: #1D2129; box-sizing: border-box;
}
.balance-row {
  display: flex; align-items: baseline; justify-content: space-between;
  margin-top: 16rpx; padding: 16rpx 18rpx; border-radius: 12rpx; background: #FFF8F4;
}
.balance-label { font-size: 25rpx; color: #7a4a33; }
.balance-value { font-size: 34rpx; color: #FF6B35; font-weight: 700; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.control-error { border: 1rpx solid #e53935; }
.req { color: #e53935; }
</style>
