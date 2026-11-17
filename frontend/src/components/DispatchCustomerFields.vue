<template>
  <view class="card">
    <text class="section">客户信息（默认取来源订单，可修改）</text>
    <view class="field"><text class="label">客户名称</text><input v-model="form.customerName" class="input" maxlength="100" /></view>
    <view class="field"><text class="label">客户手机</text><input v-model="form.customerPhone" class="input" type="number" maxlength="11" /></view>
    <view class="field"><text class="label">小区</text><input v-model="form.communityName" class="input" maxlength="200" /></view>
    <view class="field"><text class="label">详细地址</text><input v-model="form.detailedAddress" class="input" maxlength="500" /></view>
  </view>
</template>

<script setup>
defineProps({
  form: { type: Object, required: true }
})
</script>

<style scoped>
.card { background: #fff; border-radius: 20rpx; padding: 24rpx; margin-top: 20rpx; }
.section { display: block; font-size: 26rpx; color: #4E5969; font-weight: 600; margin-bottom: 12rpx; }
.field { display: flex; align-items: center; gap: 16rpx; padding: 12rpx 0; }
.label { width: 148rpx; flex-shrink: 0; font-size: 26rpx; color: #4E5969; }
.input {
  flex: 1; height: 68rpx; padding: 0 20rpx; border-radius: 12rpx;
  background: #F7F8FA; font-size: 26rpx; color: #1D2129; box-sizing: border-box;
}
</style>
