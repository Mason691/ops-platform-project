<template>
  <view class="receipt-ocr-field">
    <view v-if="showUploader" class="field">
      <text class="label">收据图片 <text class="optional">（选填，用于识别收据号）</text></text>
      <BusinessAttachmentUploader
        v-model="receiptImages"
        file-type="RECEIPT"
        :max="1"
        :max-size-mb="MAX_RECEIPT_SIZE_MB"
        accept="image"
        add-text="上传收据图片"
        :hint-text="uploaderHint"
        @uploaded="onUploaded" />
    </view>

    <view id="field-receiptNo" class="field">
      <text class="label">收据号 <text class="optional">（选填）</text></text>
      <input class="text-input" :class="{ 'control-error': error }"
             :value="form.receiptNo" :maxlength="maxlength"
             :placeholder="placeholder"
             @input="onInput" @blur="onBlur"
             @focus="keepFieldVisible('field-receiptNo')" />
      <ReceiptOcrPanel
        :loading="ocrLoading"
        :message="ocrMessage"
        :warning="ocrWarning"
        :red="ocrRed"
        :blue="ocrBlue"
        :has-result="ocrHasResult"
        :has-candidates="ocrHasCandidates"
        :show-choices="ocrShowChoices"
        :same-value="ocrSameValue"
        :current-choice="ocrCurrentChoice"
        :manual="ocrManual"
        :can-retry="ocrCanRetry"
        :review="ocrReview"
        :candidates="ocrReviewCandidates"
        :selected-value="String(form.receiptNo || '')"
        @switch="switchOcrCandidate"
        @select="selectOcrCandidate"
        @retry="runOcr()" />
      <text class="hint">{{ hintText }}</text>
      <text v-if="error" class="ferr">{{ error }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, watch } from 'vue'
import BusinessAttachmentUploader from '@/components/BusinessAttachmentUploader.vue'
import ReceiptOcrPanel from '@/components/ReceiptOcrPanel.vue'
import { useReceiptOcr } from '@/hooks/useReceiptOcr'
import { MAX_RECEIPT_SIZE_MB } from '@/utils/businessForm'
import { keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 收据图片 + 收据号 OCR 复合字段（可复用技能组件）。
 *
 * 上传收据原图后自动调用 OCR，识别红色/蓝色 NO. 后的 7 位数字并回填；
 * 多候选时展示红/蓝或同色候选供人工点选；用户手改后不再自动覆盖。
 * OCR 失败只提示，不阻止表单提交。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  receiptImage: { type: Object, default: null },
  error: { type: String, default: '' },
  maxlength: { type: Number, default: 50 },
  placeholder: { type: String, default: '选填，字母/数字/短横线/下划线/斜杠' },
  showUploader: { type: Boolean, default: true },
  hintText: {
    type: String,
    default: '识别红色/蓝色 NO. 后的 7 位数字；红蓝不一致时默认采用系统配置色，可点击候选切换。手动修改后不再自动覆盖。'
  }
})
const emit = defineEmits(['update:modelValue', 'update:receiptImage', 'change'])

const form = reactive({
  receiptNo: String(props.modelValue || ''),
  receiptImage: props.receiptImage || null,
  receiptNoCheck: null
})

const {
  loading: ocrLoading,
  message: ocrMessage,
  warning: ocrWarning,
  red: ocrRed,
  blue: ocrBlue,
  hasResult: ocrHasResult,
  hasCandidates: ocrHasCandidates,
  showChoices: ocrShowChoices,
  sameValue: ocrSameValue,
  currentChoice: ocrCurrentChoice,
  manual: ocrManual,
  canRetry: ocrCanRetry,
  review: ocrReview,
  reviewCandidates: ocrReviewCandidates,
  reset: resetOcr,
  runOcr,
  onManualInput: onOcrManualInput,
  switchTo: switchOcrCandidate,
  selectCandidate: selectOcrCandidate,
  logFinal: logOcrFinal,
  dispose: disposeOcr
} = useReceiptOcr(form)

const uploaderHint = `收据原图上传，不压缩；上传后自动识别收据号；最多1张，单个不超过${MAX_RECEIPT_SIZE_MB}MB`

const receiptImages = computed({
  get: () => (form.receiptImage ? [form.receiptImage] : []),
  set: list => {
    const next = (list && list[0]) || null
    form.receiptImage = next
    emit('update:receiptImage', next)
    if (!next) resetOcr()
  }
})

watch(() => props.modelValue, value => {
  const next = String(value || '')
  if (next !== form.receiptNo) form.receiptNo = next
})

watch(() => props.receiptImage, value => {
  const currentId = form.receiptImage && form.receiptImage.id
  const nextId = value && value.id
  if (currentId !== nextId) {
    form.receiptImage = value || null
    if (!value) resetOcr()
  }
})

watch(() => form.receiptNo, value => {
  if (String(value || '') !== String(props.modelValue || '')) {
    emit('update:modelValue', String(value || ''))
  }
})

onBeforeUnmount(() => disposeOcr())

function onInput(event) {
  form.receiptNo = String(event.detail.value ?? '')
  form.receiptNoCheck = null
  emit('change', form.receiptNo)
  onOcrManualInput()
}

function onBlur() {
  form.receiptNo = String(form.receiptNo || '').trim()
  logOcrFinal()
}

function onUploaded(record) {
  form.receiptImage = record || null
  emit('update:receiptImage', form.receiptImage)
  resetOcr()
  if (record && record.id) runOcr(record.id)
}
</script>

<style scoped>
.receipt-ocr-field { display: flex; flex-direction: column; }
.field { margin-top: 18rpx; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 10rpx; }
.optional { color: #A9AEB8; font-weight: 400; }
.text-input {
  width: 100%; height: 88rpx !important; min-height: 88rpx !important;
  padding: 0 20rpx; background: #f7f7f7; border-radius: 12rpx;
  font-size: 28rpx; color: #1D2129; box-sizing: border-box;
}
.control-error { border: 1rpx solid #e53935; background: #FFF8F8; }
.hint { display: block; font-size: 21rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
</style>
