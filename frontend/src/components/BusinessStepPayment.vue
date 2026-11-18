<template>
  <view class="step-payment">
    <view v-if="show('paymentMethod')" id="field-paymentMethod" class="section">
      <text class="label">支付方式 <text class="req">*</text></text>
      <SegmentedControl :model-value="form.paymentMethod" :options="paymentOptions"
                        @update:model-value="setPayment" />
      <text v-if="errors.paymentMethod" class="ferr">{{ errors.paymentMethod }}</text>
    </view>

    <view v-if="!onlyErrors && form.paymentMethod === 'QR'" class="section qr-section">
      <text class="label">收款二维码</text>
      <PaymentQr />
    </view>

    <view v-if="show('receiptDate')" id="field-receiptDate" class="section">
      <text class="label">市场收款日期 <text class="req">*</text></text>
      <picker mode="date" :value="form.receiptDate" @change="onDateChange">
        <view class="picker-trigger" :class="{ 'control-error': errors.receiptDate }">
          <text :class="form.receiptDate ? 'trigger-value' : 'placeholder'">
            {{ form.receiptDate || '请选择日期' }}
          </text>
          <text class="arrow">›</text>
        </view>
      </picker>
      <text v-if="errors.receiptDate" class="ferr">{{ errors.receiptDate }}</text>
    </view>

    <view v-if="!onlyErrors" class="section">
      <text class="label">付款方式与特批</text>
      <text class="section-hint">
        {{ paymentTypeText }}；特批商品与提成比例在「商品明细」中按商品行填写。
      </text>
      <text v-if="specialProducts.length" class="section-hint special-hint">
        本单有 {{ specialProducts.length }} 个商品低于标准售价，已标记特批，请在商品明细核对特批文件与提成比例。
      </text>
    </view>

    <view v-if="show('remark')" id="field-remark" class="section">
      <text class="label" @click="focusField('field-remark')">备注</text>
      <textarea class="textarea" :class="{ 'control-error': errors.remark }"
                v-model="form.remark" :maxlength="MAX_REMARK"
                placeholder="选填，最多500字"
                @focus="keepFieldVisible('field-remark')" />
      <view class="field-foot">
        <text v-if="errors.remark" class="ferr">{{ errors.remark }}</text>
        <text class="counter">{{ (form.remark || '').length }}/{{ MAX_REMARK }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import SegmentedControl from '@/components/SegmentedControl.vue'
import PaymentQr from '@/components/PaymentQr.vue'
import { computed } from 'vue'
import { MAX_REMARK, PAYMENT_METHODS } from '@/utils/businessForm'
import { focusField, keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 第 3 步：支付信息。
 * 收据号与支付凭证已移至「凭证信息」步骤；本步只保留支付方式、市场收款日期、备注。
 * 付款方式（全款/定金）与特批/提成仍按现有商品行模型在「商品明细」维护，这里只读展示。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

const paymentOptions = PAYMENT_METHODS
const show = key => !props.onlyErrors || !!props.errors[key]
const paymentTypeText = computed(() => (props.form.paymentType === 'DEPOSIT'
  ? '定金支付' : '全款支付'))
const specialProducts = computed(() => (props.form.products || [])
  .filter(product => product && product.isSpecial))

function setPayment(value) {
  props.form.paymentMethod = value
}

function onDateChange(event) {
  props.form.receiptDate = event.detail.value
}
</script>

<style scoped>
.step-payment { display: flex; flex-direction: column; gap: 20rpx; }
.section {
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.qr-section { padding-bottom: 18rpx; }
.label { display: block; font-size: 26rpx; color: #666; margin-bottom: 14rpx; }
.req { color: #e53935; }
.section-hint {
  display: block; font-size: 22rpx; color: #999;
  line-height: 1.6; margin: -4rpx 0 14rpx;
}
.special-hint { color: #C2410C; margin-bottom: 0; }
.picker-trigger {
  display: flex; align-items: center; justify-content: space-between;
  height: 80rpx; background: #f7f7f7; border-radius: 12rpx; padding: 0 20rpx;
}
.trigger-value {
  flex: 1; min-width: 0; font-size: 28rpx; color: #333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.placeholder { font-size: 28rpx; color: #bbb; }
.arrow { font-size: 32rpx; color: #999; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.textarea {
  width: 100%; height: 168rpx !important; min-height: 168rpx !important;
  background: #f7f7f7; border-radius: 12rpx;
  padding: 18rpx 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
  line-height: 1.5;
}
.field-foot { display: flex; align-items: flex-start; justify-content: space-between; }
.counter { font-size: 21rpx; color: #bbb; margin-left: 16rpx; }
.ferr { display: block; width: 100%; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
</style>
