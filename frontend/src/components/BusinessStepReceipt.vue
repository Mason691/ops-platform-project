<template>
  <view class="step-receipt">
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

    <view v-if="show('receiptNo')" id="field-receiptNo" class="section">
      <text class="label">收据号（选填）</text>
      <input class="input" :class="{ 'control-error': receiptError }"
             :value="form.receiptNo" :maxlength="MAX_RECEIPT_NO"
             placeholder="选填，未取得可留空由财务核对补录"
             @input="onReceiptInput" @blur="onReceiptBlur"
             @focus="keepFieldVisible('field-receiptNo')" />
      <text class="section-hint">
        如已取得收据号可先行填写；未取得可留空。支持字母、数字、短横线、下划线、斜杠，最多50字。
      </text>
      <text v-if="receiptChecking" class="hint">正在校验收据号...</text>
      <text v-if="receiptError" class="ferr">{{ receiptError }}</text>
    </view>

    <view v-if="!onlyErrors" class="section">
      <text class="label">收款凭证（选填）</text>
      <text class="section-hint">
        请上传收款到账凭证（如银行到账截图、微信/支付宝收款记录），用于财务核对；请勿上传收据或白条。
      </text>
      <BusinessVoucherUploader v-model="form.voucherUrls" :max="3" />
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
import BusinessVoucherUploader from '@/components/BusinessVoucherUploader.vue'
import PaymentQr from '@/components/PaymentQr.vue'
import { computed, ref } from 'vue'
import { api } from '@/api/index'
import { MAX_RECEIPT_NO, MAX_REMARK, PAYMENT_METHODS, RECEIPT_NO_RE } from '@/utils/businessForm'
import { focusField, keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 第 3 步：收款信息。
 * 原“是否收款”已由支付方式替代；支付方式必填，二维码时展示防篡改收款码。
 * 特批文件与提成比例已前移到商品明细（按产品行独立）。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

const paymentOptions = PAYMENT_METHODS
const show = key => !props.onlyErrors || !!props.errors[key]

const receiptChecking = ref(false)
let receiptCheckToken = 0

/**
 * 收据号就地错误：优先使用全量校验结果；失焦查重的结果按“当前值快照”
 * 匹配，用户改动后旧提示自动失效，避免把上一个号码的冲突提示带到新值。
 */
const receiptError = computed(() => {
  if (props.errors.receiptNo) return props.errors.receiptNo
  const value = String(props.form.receiptNo || '').trim()
  // 字符集/长度即时提示，不必等到点“下一步”
  if (value.length > MAX_RECEIPT_NO) return `收据号不能超过${MAX_RECEIPT_NO}个字符`
  if (value && !RECEIPT_NO_RE.test(value)) {
    return '收据号只能包含字母、数字、短横线、下划线和斜杠'
  }
  const check = props.form.receiptNoCheck
  return check && check.value === value ? check.message : ''
})

function setPayment(value) {
  props.form.paymentMethod = value
}

function onDateChange(event) {
  props.form.receiptDate = event.detail.value
}

function onReceiptInput(event) {
  const value = String(event.detail.value ?? '')
  props.form.receiptNo = value
  // 值已变化，旧的查重结论（快照）立即作废
  props.form.receiptNoCheck = null
}

async function onReceiptBlur() {
  const value = String(props.form.receiptNo || '').trim()
  props.form.receiptNo = value
  const token = ++receiptCheckToken
  if (!value || value.length > MAX_RECEIPT_NO || !RECEIPT_NO_RE.test(value)) {
    props.form.receiptNoCheck = null
    receiptChecking.value = false
    return
  }
  receiptChecking.value = true
  try {
    const res = await api.employeeCheckReceiptNo({
      receiptNo: value,
      customer: String(props.form.customer || '').trim()
    })
    // 查询期间用户可能已改号/切步，丢弃过期响应
    if (token !== receiptCheckToken) return
    if (String(props.form.receiptNo || '').trim() !== value) return
    props.form.receiptNoCheck = res && res.duplicate
      ? { value, message: res.message || '该收据号已被其他客户占用，请更换' }
      : null
  } catch (e) {
    // 查重是提示性能力：接口失败不阻断填写，提交时后端仍会二次校验
    if (token === receiptCheckToken) props.form.receiptNoCheck = null
  } finally {
    if (token === receiptCheckToken) receiptChecking.value = false
  }
}
</script>

<style scoped>
.step-receipt { display: flex; flex-direction: column; gap: 20rpx; }
.section {
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.qr-section { padding-bottom: 18rpx; }
.label { display: block; font-size: 26rpx; color: #666; margin-bottom: 14rpx; }
.req { color: #e53935; }
.input {
  width: 100%; height: 80rpx !important; min-height: 80rpx !important;
  background: #f7f7f7; border-radius: 12rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
}
.section-hint {
  display: block; font-size: 22rpx; color: #999;
  line-height: 1.6; margin: -4rpx 0 14rpx;
}
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
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
