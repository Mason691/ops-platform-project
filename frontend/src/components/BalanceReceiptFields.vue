<template>
  <view class="field">
    <text class="label">收款方式 <text class="req">*</text></text>
    <view class="chips">
      <view v-for="m in payMethods" :key="m.code" class="chip"
            :class="{ on: paymentMethod === m.code }"
            @click="$emit('update:paymentMethod', m.code)">
        <text>{{ m.label }}</text>
      </view>
    </view>
    <text v-if="payError" class="ferr">{{ payError }}</text>
  </view>

  <view class="field">
    <text class="label">收款日期 <text class="req">*</text></text>
    <picker mode="date" :value="receiptDate" :end="today" @change="onDate">
      <view class="picker-box" :class="{ 'control-error': dateError }">
        <text class="picker-text">{{ receiptDate || '请选择收款日期' }}</text>
        <text class="picker-arrow">›</text>
      </view>
    </picker>
    <text v-if="dateError" class="ferr">{{ dateError }}</text>
  </view>

  <view class="field">
    <text class="label">收据号</text>
    <input class="text-input" :value="receiptNo" maxlength="50"
           placeholder="选填，字母/数字/短横线/下划线/斜杠"
           @input="$emit('update:receiptNo', $event.detail.value)" />
    <text v-if="receiptNoError" class="ferr">{{ receiptNoError }}</text>
  </view>
</template>

<script setup>
/** 货款补录收款明细字段：方式/日期/收据号；校验由父页面统一执行。 */
defineProps({
  paymentMethod: { type: String, default: 'QR' },
  receiptDate: { type: String, default: '' },
  receiptNo: { type: String, default: '' },
  today: { type: String, default: '' },
  payError: { type: String, default: '' },
  dateError: { type: String, default: '' },
  receiptNoError: { type: String, default: '' }
})
const emit = defineEmits(['update:paymentMethod', 'update:receiptDate', 'update:receiptNo'])

const payMethods = [
  { code: 'QR', label: '二维码' },
  { code: 'CASH', label: '现金' },
  { code: 'TRANSFER', label: '转账' }
]

function onDate(event) {
  emit('update:receiptDate', event.detail.value)
}
</script>

<style scoped>
.field { margin-top: 18rpx; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 10rpx; }
.req { color: #e53935; }
.chips { display: flex; gap: 12rpx; }
.chip {
  flex: 1; text-align: center; padding: 18rpx 0; border-radius: 12rpx;
  background: #f7f7f7; color: #4E5969; font-size: 27rpx; border: 1rpx solid transparent;
}
.chip.on { background: #FFF1E8; color: #FF6B35; border-color: #FF6B35; font-weight: 600; }
.picker-box {
  display: flex; align-items: center; justify-content: space-between;
  height: 88rpx; padding: 0 20rpx; background: #f7f7f7; border-radius: 12rpx;
  font-size: 28rpx; color: #1D2129; box-sizing: border-box;
}
.picker-arrow { color: #C9CDD4; font-size: 32rpx; }
.text-input {
  width: 100%; height: 88rpx !important; min-height: 88rpx !important;
  padding: 0 20rpx; background: #f7f7f7; border-radius: 12rpx;
  font-size: 28rpx; color: #1D2129; box-sizing: border-box;
}
.control-error { border: 1rpx solid #e53935; background: #FFF8F8; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
</style>
