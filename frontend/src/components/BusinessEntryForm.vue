<template>
  <view class="entry-form">
    <view class="card">
      <view class="card-title"><text class="bar"></text>批次信息</view>
      <view class="field">
        <text class="label">批次 <text class="req">*</text></text>
        <picker mode="selector" :range="batchOptions" range-key="label" :value="batchIndex" @change="onBatchChange">
          <view class="picker">
            <text class="picker-text">{{ batchLabel }}</text>
            <text class="arrow">▼</text>
          </view>
        </picker>
        <text v-if="errors.batchNo" class="ferr">{{ errors.batchNo }}</text>
        <text v-if="form.newBatch" class="hint">提交后自动生成：{{ meta.newBatchPrefix }}xxx</text>
        <text v-else class="hint">仅可选择未上报集团的批次</text>
      </view>
    </view>

    <view class="card">
      <view class="card-title"><text class="bar"></text>客户信息</view>
      <view class="field">
        <text class="label">客户手机 <text class="req">*</text></text>
        <view class="phone-row">
          <input class="input phone-input" v-model="form.customerPhone" type="number"
                 maxlength="11" placeholder="请输入11位手机号" />
          <view class="query-btn" :class="{ disabled: querying }" @click="$emit('query-member')">
            {{ querying ? '查询中' : '查询' }}
          </view>
        </view>
        <text v-if="errors.customerPhone" class="ferr">{{ errors.customerPhone }}</text>
      </view>
      <view class="field">
        <text class="label">客户名称 <text class="req">*</text></text>
        <input class="input" v-model="form.customer" maxlength="50"
               placeholder="查询后自动填充，也可手动输入" />
        <text v-if="errors.customer" class="ferr">{{ errors.customer }}</text>
      </view>
      <view class="field">
        <text class="label">小区选择</text>
        <picker mode="selector" :range="communityOptions" range-key="name"
                :value="communityIndex" @change="onCommunityChange">
          <view class="picker">
            <text class="picker-text">{{ communityLabel }}</text>
            <text class="arrow">▼</text>
          </view>
        </picker>
        <text class="hint">选填；会员已关联小区时自动带出</text>
      </view>
      <view class="field">
        <text class="label">详细地址</text>
        <input class="input" v-model="form.detailAddress" maxlength="200"
               placeholder="如：3栋2单元501号（选填）" />
      </view>
      <view class="address-preview">
        <text class="preview-label">收货地址</text>
        <text class="preview-value">{{ deliveryPreview || '未填写' }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, required: true },
  querying: { type: Boolean, default: false }
})
defineEmits(['query-member'])

const batchOptions = computed(() => [
  { label: '＋ 新建批次', value: '__new__' },
  ...(props.meta.batches || []).map(batch => ({ label: batch, value: batch }))
])

const batchIndex = computed(() => {
  if (props.form.newBatch) return 0
  const index = batchOptions.value.findIndex(item => item.value === props.form.batchNo)
  return index >= 0 ? index : 0
})

const batchLabel = computed(() => {
  if (!props.form.newBatch && props.form.batchNo) return props.form.batchNo
  const option = batchOptions.value[batchIndex.value]
  return option ? option.label : '请选择批次'
})

function onBatchChange(e) {
  const option = batchOptions.value[Number(e.detail.value)]
  if (!option || option.value === '__new__') {
    props.form.newBatch = true
    props.form.batchNo = ''
  } else {
    props.form.newBatch = false
    props.form.batchNo = option.value
  }
}

const communityOptions = computed(() => [
  { id: null, name: '不选择' },
  ...(props.meta.communities || []).map(item => ({ id: item.id, name: item.name }))
])

const communityIndex = computed(() => {
  const index = communityOptions.value.findIndex(
    item => String(item.id) === String(props.form.communityId))
  return index >= 0 ? index : 0
})

const communityLabel = computed(() => {
  const option = communityOptions.value[communityIndex.value]
  return option ? option.name : '不选择'
})

const deliveryPreview = computed(() => {
  const community = String(props.form.communityName || '').trim()
  const detail = String(props.form.detailAddress || '').trim()
  return [community, detail].filter(Boolean).join(' ')
})

function onCommunityChange(e) {
  const option = communityOptions.value[Number(e.detail.value)]
  if (!option || option.id == null) {
    props.form.communityId = null
    props.form.communityName = ''
    return
  }
  props.form.communityId = option.id
  props.form.communityName = option.name
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
.picker-text { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #333; }
.arrow { font-size: 20rpx; color: #999; margin-left: 12rpx; }
.phone-row { display: flex; align-items: center; gap: 16rpx; }
.phone-input { flex: 1; }
.query-btn {
  height: 80rpx; line-height: 80rpx; padding: 0 28rpx; border-radius: 10rpx;
  background: #FF6B35; color: #fff; font-size: 26rpx; white-space: nowrap;
}
.query-btn.disabled { opacity: .6; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
.address-preview {
  display: flex; align-items: baseline; gap: 12rpx;
  padding: 16rpx 20rpx; background: #f7f7f7; border-radius: 10rpx;
}
.preview-label { font-size: 24rpx; color: #666; white-space: nowrap; }
.preview-value { flex: 1; font-size: 26rpx; color: #333; word-break: break-all; }
</style>
