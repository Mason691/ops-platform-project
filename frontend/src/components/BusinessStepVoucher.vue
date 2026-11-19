<template>
  <view class="step-voucher">
    <view v-if="show('receiptImage')" class="section">
      <text class="label">收据图片</text>
      <BusinessAttachmentUploader
        v-model="receiptImages"
        file-type="RECEIPT"
        :max="1"
        :max-size-mb="MAX_RECEIPT_SIZE_MB"
        accept="image"
        add-text="上传收据图片"
        :hint-text="`收据原图上传，不压缩；上传后自动识别收据号；最多1张，单个不超过${MAX_RECEIPT_SIZE_MB}MB`"
        :error="errors.receiptImage"
        @uploaded="onReceiptUploaded" />
    </view>

    <view v-if="show('receiptNo')" id="field-receiptNo" class="section">
      <text class="label">收据号 <text class="req">*</text></text>
      <input class="input" :class="{ 'control-error': receiptError }"
             :value="form.receiptNo" :maxlength="MAX_RECEIPT_NO"
             placeholder="必填，可手填或由收据图片识别"
             @input="onReceiptInput" @blur="onReceiptBlur"
             @focus="keepFieldVisible('field-receiptNo')" />
      <ReceiptOcrPanel
        :loading="ocr.loading"
        :message="ocr.message"
        :warning="ocr.warning"
        :red="ocr.red"
        :blue="ocr.blue"
        :has-result="ocr.hasResult"
        :has-candidates="ocr.hasCandidates"
        :show-choices="ocr.showChoices"
        :same-value="ocr.sameValue"
        :current-choice="ocr.currentChoice"
        :manual="ocr.manual"
        :can-retry="ocr.canRetry"
        @switch="ocr.switchTo"
        @retry="ocr.runOcr()" />
      <text class="section-hint">
        识别规则：同时识别收据上红色/蓝色 7 位数字（通常位于 NO. 后）；红蓝不一致时默认使用{{ preferredLabel }}，可一键切换。结果可人工修改，修改后不再自动覆盖。
      </text>
      <text v-if="receiptChecking" class="hint">正在校验收据号...</text>
      <text v-if="receiptError" class="ferr">{{ receiptError }}</text>
    </view>

    <view v-if="show('paymentVouchers')" id="field-paymentVouchers" class="section">
      <text class="label">支付凭证 <text class="req">*</text></text>
      <text class="req-hint">请上传银行或微信支付凭证（必填）</text>
      <BusinessAttachmentUploader
        v-model="form.paymentVouchers"
        file-type="PAYMENT"
        :max="MAX_PAYMENT_VOUCHERS"
        accept="image"
        add-text="上传支付凭证"
        hint-text="支持银行到账截图、微信/支付宝收款记录；最多3张，图片自动压缩"
        :error="errors.paymentVouchers" />
    </view>

    <view v-if="show('contractAttachments')" id="field-contractAttachments" class="section">
      <text class="label">合同附件 <text class="req">*</text></text>
      <BusinessAttachmentUploader
        v-model="form.contractAttachments"
        file-type="CONTRACT"
        :max="MAX_CONTRACT_ATTACHMENTS"
        accept="image-pdf"
        add-text="上传合同附件"
        hint-text="支持 JPG、PNG、PDF；1~5 份，单个不超过10MB，图片自动压缩"
        :error="errors.contractAttachments" />
    </view>
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import BusinessAttachmentUploader from '@/components/BusinessAttachmentUploader.vue'
import ReceiptOcrPanel from '@/components/ReceiptOcrPanel.vue'
import { useReceiptOcr } from '@/hooks/useReceiptOcr'
import { api } from '@/api/index'
import {
  MAX_CONTRACT_ATTACHMENTS, MAX_PAYMENT_VOUCHERS, MAX_RECEIPT_NO,
  MAX_RECEIPT_SIZE_MB, RECEIPT_NO_RE
} from '@/utils/businessForm'
import { keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 第 4 步：凭证信息。
 * 收据图片（可选）→ OCR 同时识别红/蓝数字；红蓝不一致时默认采用配置色，
 * 并展示两组候选供一键切换；用户手改/切换后不再自动覆盖。
 * 收据号必填；支付凭证 1~3 张必填；合同附件 1~5 份必填（图片/PDF）。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, default: () => ({}) },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

const show = key => !props.onlyErrors || !!props.errors[key]
const ocr = useReceiptOcr(props.form)
const preferredLabel = computed(() => (ocr.preferredColor.value === 'BLUE' ? '蓝色' : '红色'))
const receiptImages = computed({
  get: () => (props.form.receiptImage ? [props.form.receiptImage] : []),
  set: list => {
    props.form.receiptImage = (list && list[0]) || null
    if (!props.form.receiptImage) ocr.reset()
  }
})

const receiptChecking = ref(false)
let receiptCheckToken = 0

onBeforeUnmount(() => {
  // 离开凭证步骤：记录最终采用值，并使在途 OCR/查重响应失效
  ocr.dispose()
  receiptCheckToken += 1
})

const receiptError = computed(() => {
  if (props.errors.receiptNo) return props.errors.receiptNo
  const value = String(props.form.receiptNo || '').trim()
  if (value.length > MAX_RECEIPT_NO) return `收据号不能超过${MAX_RECEIPT_NO}个字符`
  if (value && !RECEIPT_NO_RE.test(value)) {
    return '收据号只能包含字母、数字、短横线、下划线和斜杠'
  }
  const check = props.form.receiptNoCheck
  return check && check.value === value ? check.message : ''
})

function onReceiptUploaded(record) {
  props.form.receiptImage = record || null
  ocr.reset()
  if (record && record.id) ocr.runOcr(record.id)
}

function onReceiptInput(event) {
  const value = String(event.detail.value ?? '')
  props.form.receiptNo = value
  props.form.receiptNoCheck = null
  // 手动输入后保留识别候选提示，但不再允许识别结果自动覆盖
  ocr.onManualInput()
}

async function onReceiptBlur() {
  const value = String(props.form.receiptNo || '').trim()
  props.form.receiptNo = value
  ocr.logFinal()
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
.step-voucher { display: flex; flex-direction: column; gap: 20rpx; }
.section {
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.label { display: block; font-size: 26rpx; color: #666; margin-bottom: 14rpx; }
.req { color: #e53935; }
.req-hint {
  display: block; font-size: 23rpx; color: #e53935;
  font-weight: 600; margin: -4rpx 0 14rpx;
}
.section-hint {
  display: block; font-size: 22rpx; color: #999;
  line-height: 1.6; margin: 14rpx 0 0;
}
.input {
  width: 100%; height: 80rpx !important; min-height: 80rpx !important;
  background: #f7f7f7; border-radius: 12rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
}
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
</style>
