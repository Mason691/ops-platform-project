<template>
  <view class="step-info">
    <view v-if="show('purpose')" id="field-purpose" class="section">
      <view class="label-row">
        <text class="label">领用用途 <text class="req">*</text></text>
        <text class="label-tip">提交后自动生成领用单号</text>
      </view>
      <textarea class="textarea" :class="{ 'control-error': errors.purpose }"
                v-model="form.purpose" :maxlength="500"
                placeholder="请说明领用物资的用途（如：某小区维修、活动物料等）"
                @focus="keepFieldVisible('field-purpose')" />
      <view class="field-foot">
        <text v-if="errors.purpose" class="ferr">{{ errors.purpose }}</text>
        <text class="counter">{{ (form.purpose || '').length }}/500</text>
      </view>
    </view>

    <view v-if="show('expectedDate')" id="field-expectedDate" class="section">
      <text class="label">期望领用日期 <text class="req">*</text></text>
      <picker mode="date" :value="form.expectedDate" @change="onDateChange">
        <view class="picker-trigger" :class="{ 'control-error': errors.expectedDate }">
          <text :class="form.expectedDate ? 'trigger-value' : 'placeholder'">
            {{ form.expectedDate || '请选择日期' }}
          </text>
          <text class="arrow">›</text>
        </view>
      </picker>
      <text v-if="errors.expectedDate" class="ferr">{{ errors.expectedDate }}</text>
    </view>

    <view class="section readonly">
      <text class="label">所属组织 / 部门</text>
      <view class="readonly-row">
        <text class="readonly-value">{{ meta.company || meta.corpName || '—' }}</text>
        <text class="readonly-sub">{{ meta.deptName || '未设置部门' }}</text>
      </view>
    </view>

    <view v-if="show('agentUserIds')" id="field-agentUserIds" class="section">
      <text class="label">领用人 <text class="req">*</text></text>
      <BusinessAgentPicker :form="form" :meta="meta" :errors="errors"
                           hint="默认本人；如需代领，可添加本组织同事，领用人可多选" />
    </view>

    <view v-if="show('remark')" id="field-remark" class="section">
      <text class="label" @click="focusField('field-remark')">备注</text>
      <textarea class="textarea" :class="{ 'control-error': errors.remark }"
                v-model="form.remark" :maxlength="500" placeholder="选填，最多500字"
                @focus="keepFieldVisible('field-remark')" />
      <view class="field-foot">
        <text v-if="errors.remark" class="ferr">{{ errors.remark }}</text>
        <text class="counter">{{ (form.remark || '').length }}/500</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import BusinessAgentPicker from '@/components/BusinessAgentPicker.vue'
import { focusField, keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 第 1 步：领用信息。组织/部门只读；领用人默认本人且不可取消（BusinessAgentPicker 内置）。
 * onlyErrors=true（确认页补填）时仅渲染有错误的字段。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

const show = key => !props.onlyErrors || !!props.errors[key]

function onDateChange(event) {
  props.form.expectedDate = event.detail.value
}
</script>

<style scoped>
.step-info { display: flex; flex-direction: column; gap: 20rpx; }
.section {
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.label-row { display: flex; align-items: baseline; justify-content: space-between; }
.label { display: block; font-size: 26rpx; color: #666; margin-bottom: 14rpx; }
.label-tip { font-size: 21rpx; color: #aaa; }
.req { color: #e53935; }
.textarea {
  width: 100%; height: 168rpx !important; min-height: 168rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 18rpx 20rpx;
  font-size: 28rpx; color: #333; box-sizing: border-box; line-height: 1.5;
}
.picker-trigger {
  display: flex; align-items: center; justify-content: space-between;
  height: 80rpx; background: #f7f7f7; border-radius: 12rpx; padding: 0 20rpx;
}
.trigger-value { flex: 1; min-width: 0; font-size: 28rpx; color: #333; }
.placeholder { font-size: 28rpx; color: #bbb; }
.arrow { font-size: 32rpx; color: #999; }
.readonly { background: #fafafa; }
.readonly-row { display: flex; flex-direction: column; gap: 6rpx; }
.readonly-value { font-size: 29rpx; color: #333; font-weight: 600; }
.readonly-sub { font-size: 24rpx; color: #999; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.field-foot { display: flex; align-items: flex-start; justify-content: space-between; }
.counter { font-size: 21rpx; color: #bbb; margin-left: 16rpx; }
</style>
