<template>
  <view class="step-receipt">
    <view v-if="show('isSpecial')" id="field-isSpecial" class="section">
      <text class="label">是否特批 <text class="req">*</text></text>
      <SegmentedControl :model-value="form.isSpecial" :options="yesNoOptions"
                        @update:model-value="setSpecial" />
      <text v-if="!onlyErrors" class="hint">
        选择「是」时需为每个产品单独填写提成比例；选择「否」按 0 处理
      </text>
    </view>

    <view v-if="commissionVisible" class="section expand">
      <text class="label">提成比例（%） <text class="req">*</text></text>
      <view v-for="row in commissionRows" :key="row.index"
            :id="`field-products-${row.index}-commissionRate`" class="rate-row">
        <text class="rate-name">{{ row.label }}</text>
        <input class="rate-input" :class="{ 'control-error': row.error }"
               type="digit" :value="row.product.commissionRate"
               placeholder="0-100" maxlength="6"
               @input="event => onRateInput(row.product, event)"
               @blur="formatRate(row.product)"
               @focus="keepFieldVisible(`field-products-${row.index}-commissionRate`)" />
        <text v-if="row.error" class="ferr">{{ row.error }}</text>
      </view>
      <text v-if="!commissionRows.length" class="hint">暂无产品，请先返回商品明细添加</text>
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

    <view v-if="show('isReceived')" id="field-isReceived" class="section">
      <text class="label">是否收款 <text class="req">*</text></text>
      <SegmentedControl :model-value="form.isReceived" :options="yesNoOptions"
                        @update:model-value="setReceived" />
    </view>

    <view v-if="voucherVisible" id="field-voucherUrls" class="section expand"
          :class="{ 'section-error': errors.voucherUrls }">
      <text class="label">上传凭证 <text class="req">*</text></text>
      <BusinessVoucherUploader v-model="form.voucherUrls" :max="3" />
      <text v-if="errors.voucherUrls" class="ferr">{{ errors.voucherUrls }}</text>
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
import { computed } from 'vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import BusinessVoucherUploader from '@/components/BusinessVoucherUploader.vue'
import { MAX_REMARK } from '@/utils/businessForm'
import { focusField, keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 第 3 步：收款信息。是否特批/是否收款为分段选择器，勾选后对应区域
 * 展开；特批提成按产品行分别录入（后端 commission_rate 以产品行为粒度）。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

const yesNoOptions = [
  { value: true, label: '是' },
  { value: false, label: '否' }
]

const show = key => !props.onlyErrors || !!props.errors[key]

const commissionVisible = computed(() => {
  if (!props.form.isSpecial) return false
  if (!props.onlyErrors) return true
  return rateErrors().length > 0
})

const voucherVisible = computed(() => {
  if (!props.form.isReceived) return false
  if (!props.onlyErrors) return true
  return !!props.errors.voucherUrls
})

const commissionRows = computed(() => {
  const rows = (props.form.products || []).map((product, index) => {
    const error = props.errors[`products.${index}.commissionRate`] || ''
    return {
      index,
      product,
      error,
      label: `第${index + 1}个 · ${product.brand || '未选品牌'} ${product.model || ''}`.trim()
    }
  })
  return props.onlyErrors ? rows.filter(row => !!row.error) : rows
})

function rateErrors() {
  return Object.keys(props.errors || {}).filter(key => key.endsWith('.commissionRate'))
}

function setSpecial(value) {
  props.form.isSpecial = value === true
}

function setReceived(value) {
  props.form.isReceived = value === true
  // 不主动清空已上传凭证：误触“否”后切回“是”仍可保留，
  // 提交时由 buildPayload 在 isReceived=false 时忽略。
}

function onDateChange(event) {
  props.form.receiptDate = event.detail.value
}

function onRateInput(product, event) {
  // 保留原始输入（含负号/超范围值），失焦仅格式化，不 clamp；
  // 由 validateBusinessForm 提示“请输入0-100的提成比例”，避免静默改成合法值。
  product.commissionRate = String(event.detail.value ?? '')
}

function formatRate(product) {
  const raw = String(product.commissionRate || '').trim()
  if (raw === '') return
  const value = Number(raw)
  if (!Number.isFinite(value)) return
  product.commissionRate = value.toFixed(2)
}
</script>

<style scoped>
.step-receipt { display: flex; flex-direction: column; gap: 20rpx; }
.section {
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.section.expand { animation: expandIn .22s ease-out; }
.section-error { border: 2rpx solid #e53935; background: #FFF8F8; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.label { display: block; font-size: 26rpx; color: #666; margin-bottom: 14rpx; }
.req { color: #e53935; }
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
.rate-row {
  display: flex; align-items: center; flex-wrap: wrap;
  padding: 14rpx 0; border-bottom: 1rpx solid #f5f5f5;
}
.rate-row:last-child { border-bottom: none; }
.rate-name {
  flex: 1; min-width: 0; font-size: 25rpx; color: #555;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.rate-input {
  width: 180rpx; height: 68rpx !important; min-height: 68rpx !important;
  background: #f7f7f7; border-radius: 10rpx;
  padding: 0 16rpx; font-size: 26rpx; color: #333; margin-left: 16rpx;
  text-align: right; box-sizing: border-box;
}
.textarea {
  width: 100%; height: 168rpx !important; min-height: 168rpx !important;
  background: #f7f7f7; border-radius: 12rpx;
  padding: 18rpx 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
  line-height: 1.5;
}
.field-foot { display: flex; align-items: flex-start; justify-content: space-between; }
.counter { font-size: 21rpx; color: #bbb; margin-left: 16rpx; }
.ferr { display: block; width: 100%; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 10rpx; line-height: 1.5; }
@keyframes expandIn {
  from { opacity: 0; transform: translateY(-8rpx); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
