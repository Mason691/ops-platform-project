<template>
  <view class="step-voucher">
    <view v-if="show('receiptImage')" class="section">
      <text class="label">收据图片</text>
      <BusinessAttachmentUploader
        v-model="receiptImages"
        file-type="RECEIPT"
        :max="1"
        accept="image"
        add-text="上传收据图片"
        hint-text="建议上传收据原图，上传后自动识别收据号；最多1张，图片自动压缩"
        :error="errors.receiptImage"
        @uploaded="onReceiptUploaded" />
      <view v-if="ocrLoading" class="ocr-row">
        <text class="ocr-spinner"></text>
        <text class="ocr-hint">正在识别收据号…</text>
      </view>
      <view v-else-if="ocrMessage" class="ocr-row">
        <text class="ocr-hint" :class="{ warn: ocrWarning }">{{ ocrMessage }}</text>
        <text v-if="form.receiptImage && ocrWarning" class="ocr-retry" @click="runOcr()">
          重新识别
        </text>
      </view>
    </view>

    <view v-if="show('receiptNo')" id="field-receiptNo" class="section">
      <text class="label">收据号 <text class="req">*</text></text>
      <input class="input" :class="{ 'control-error': receiptError }"
             :value="form.receiptNo" :maxlength="MAX_RECEIPT_NO"
             placeholder="必填，可手填或由收据图片识别"
             @input="onReceiptInput" @blur="onReceiptBlur"
             @focus="keepFieldVisible('field-receiptNo')" />
      <text class="section-hint">
        识别规则：取收据「入账日期」后 NO. 的 7 位数字；识别结果可人工修改，修改后不再自动覆盖。
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
import { computed, ref } from 'vue'
import BusinessAttachmentUploader from '@/components/BusinessAttachmentUploader.vue'
import { api } from '@/api/index'
import {
  MAX_CONTRACT_ATTACHMENTS, MAX_PAYMENT_VOUCHERS, MAX_RECEIPT_NO, RECEIPT_NO_RE
} from '@/utils/businessForm'
import { keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 第 4 步：凭证信息。
 * 收据图片（可选）→ OCR 识别收据号；收据号必填；支付凭证 1~3 张必填；
 * 合同附件 1~5 份必填（图片/PDF）。
 *
 * OCR 覆盖策略：收据号为空或仍等于上次自动识别值时自动填入；用户已手工改动
 * 时弹窗询问是否覆盖，避免静默冲掉人工核对结果。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, default: () => ({}) },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

const show = key => !props.onlyErrors || !!props.errors[key]
const receiptImages = computed({
  get: () => (props.form.receiptImage ? [props.form.receiptImage] : []),
  set: list => {
    props.form.receiptImage = (list && list[0]) || null
    if (!props.form.receiptImage) {
      ocrMessage.value = ''
      ocrWarning.value = false
    }
  }
})

const ocrLoading = ref(false)
const ocrMessage = ref('')
const ocrWarning = ref(false)
const lastAutoReceiptNo = ref('')
const receiptChecking = ref(false)
let receiptCheckToken = 0
let ocrToken = 0

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
  ocrMessage.value = ''
  ocrWarning.value = false
  if (record && record.id) runOcr(record.id)
}

async function runOcr(attachmentId) {
  const id = attachmentId || (props.form.receiptImage && props.form.receiptImage.id)
  if (!id) return
  // 允许新图直接发起识别；用 token 丢弃被新请求取代的旧响应
  const token = ++ocrToken
  ocrLoading.value = true
  ocrMessage.value = '正在识别收据号…'
  ocrWarning.value = false
  try {
    const res = await api.employeeReceiptOcr({ attachmentId: id })
    if (token !== ocrToken) return
    // 识别期间可能已删除/更换图片：丢弃过期响应，避免把旧图结果写进新图
    if (!props.form.receiptImage || String(props.form.receiptImage.id) !== String(id)) return
    const recognized = String((res && res.receiptNo) || '').trim()
    if (!recognized) {
      ocrMessage.value = (res && res.message) || '未能识别收据号，请手动填写'
      ocrWarning.value = true
      return
    }
    const current = String(props.form.receiptNo || '').trim()
    if (!current || current === lastAutoReceiptNo.value) {
      applyRecognized(recognized, res)
      return
    }
    if (current === recognized) {
      ocrMessage.value = (res && res.message) || '已从收据图片自动识别，请核对'
      ocrWarning.value = !!(res && res.exact === false)
      return
    }
    uni.showModal({
      title: '识别到收据号',
      content: `识别结果 ${recognized}，当前收据号为 ${current}，是否用识别结果覆盖？`,
      confirmText: '覆盖',
      cancelText: '保留当前',
      success: modal => {
        if (modal.confirm) applyRecognized(recognized, res)
        else ocrMessage.value = '已保留手工填写的收据号'
      }
    })
  } catch (e) {
    if (token !== ocrToken) return
    ocrMessage.value = (e && e.msg) || '识别失败，请手动填写收据号'
    ocrWarning.value = true
  } finally {
    if (token === ocrToken) ocrLoading.value = false
  }
}

function applyRecognized(recognized, res) {
  props.form.receiptNo = recognized
  props.form.receiptNoCheck = null
  lastAutoReceiptNo.value = recognized
  ocrMessage.value = (res && res.message) || '已从收据图片自动识别，请核对'
  ocrWarning.value = !!(res && res.exact === false)
}

function onReceiptInput(event) {
  const value = String(event.detail.value ?? '')
  props.form.receiptNo = value
  props.form.receiptNoCheck = null
  if (String(value).trim() !== lastAutoReceiptNo.value) {
    lastAutoReceiptNo.value = ''
    // 用户已手工改动：旧的识别提示不再适用
    if (!ocrLoading.value && ocrMessage.value) {
      ocrMessage.value = ''
      ocrWarning.value = false
    }
  }
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
  line-height: 1.6; margin: -4rpx 0 14rpx;
}
.input {
  width: 100%; height: 80rpx !important; min-height: 80rpx !important;
  background: #f7f7f7; border-radius: 12rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
}
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.ocr-row { display: flex; align-items: center; gap: 10rpx; margin-top: 12rpx; }
.ocr-hint { flex: 1; font-size: 22rpx; color: #00B42A; line-height: 1.5; }
.ocr-hint.warn { color: #C2410C; }
.ocr-retry { font-size: 22rpx; color: #FF6B35; padding: 4rpx 8rpx; }
.ocr-spinner {
  width: 24rpx; height: 24rpx; border-radius: 50%;
  border: 3rpx solid #FFD9C8; border-top-color: #FF6B35;
  animation: spin .7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
