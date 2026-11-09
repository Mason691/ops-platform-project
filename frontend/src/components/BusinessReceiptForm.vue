<template>
  <view class="card">
    <view class="card-title"><text class="bar"></text>收款信息</view>

    <view class="field">
      <text class="label">是否特批 <text class="req">*</text></text>
      <view class="radio-row">
        <view class="radio" :class="{ on: form.isSpecial }" @click="form.isSpecial = true">是</view>
        <view class="radio" :class="{ on: !form.isSpecial }" @click="form.isSpecial = false">否</view>
      </view>
      <text class="hint">选择「是」时每个产品行需单独填写提成比例；选择「否」按 0 处理</text>
    </view>

    <view class="field">
      <text class="label">市场收款日期 <text class="req">*</text></text>
      <picker mode="date" :value="form.receiptDate" @change="onDateChange">
        <view class="picker">
          <text class="picker-text" :class="{ placeholder: !form.receiptDate }">
            {{ form.receiptDate || '请选择日期' }}
          </text>
          <text class="arrow">▼</text>
        </view>
      </picker>
      <text v-if="errors.receiptDate" class="ferr">{{ errors.receiptDate }}</text>
    </view>

    <view class="field">
      <text class="label">是否收款 <text class="req">*</text></text>
      <view class="radio-row">
        <view class="radio" :class="{ on: form.isReceived }" @click="form.isReceived = true">是</view>
        <view class="radio" :class="{ on: !form.isReceived }" @click="setNotReceived">否</view>
      </view>
    </view>

    <view v-if="form.isReceived" class="field">
      <text class="label">上传凭证 <text class="req">*</text></text>
      <BusinessVoucherUploader v-model="form.voucherUrls" :max="3" />
      <text v-if="errors.voucherUrls" class="ferr">{{ errors.voucherUrls }}</text>
    </view>
  </view>
</template>

<script setup>
import BusinessVoucherUploader from '@/components/BusinessVoucherUploader.vue'

const props = defineProps({
  form: { type: Object, required: true },
  errors: { type: Object, required: true }
})

function onDateChange(e) {
  props.form.receiptDate = e.detail.value
}

function setNotReceived() {
  props.form.isReceived = false
  props.form.voucherUrls = []
}
</script>

<style scoped>
.card {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  margin-bottom: 20rpx; box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.card-title { font-size: 30rpx; font-weight: 600; color: #333; margin-bottom: 24rpx; display: flex; align-items: center; }
.bar { width: 8rpx; height: 30rpx; background: #FF6B35; border-radius: 4rpx; margin-right: 12rpx; }
.field { margin-bottom: 24rpx; }
.field:last-child { margin-bottom: 0; }
.label { font-size: 26rpx; color: #666; display: block; margin-bottom: 12rpx; }
.req { color: #e53935; }
.input, .picker {
  width: 100%; height: 80rpx; background: #f7f7f7; border-radius: 10rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
  display: flex; align-items: center; justify-content: space-between;
}
.picker-text { flex: 1; }
.placeholder { color: #bbb; }
.arrow { font-size: 20rpx; color: #999; margin-left: 12rpx; }
.radio-row { display: flex; gap: 20rpx; }
.radio {
  min-width: 140rpx; height: 68rpx; line-height: 68rpx; text-align: center;
  border-radius: 34rpx; background: #f5f5f5; color: #666; font-size: 28rpx;
  border: 2rpx solid transparent; box-sizing: border-box;
}
.radio.on { background: #FFF3ED; color: #FF6B35; border-color: #FF6B35; font-weight: 600; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx; }
</style>
