<template>
  <view class="step-info">
    <view v-if="show('requisitionId')" id="field-requisitionId" class="section">
      <view class="label-row">
        <text class="label">选择领用单 <text class="req">*</text></text>
        <text class="label-tip">提交后自动生成核销单号</text>
      </view>
      <view class="picker-trigger" :class="{ 'control-error': errors.requisitionId }"
            @click="pickerVisible = true">
        <text :class="form.requisitionNo ? 'trigger-value' : 'placeholder'">
          {{ form.requisitionNo || '点击选择已通过审批的领用单' }}
        </text>
        <text class="arrow">›</text>
      </view>
      <text v-if="errors.requisitionId" class="ferr">{{ errors.requisitionId }}</text>
      <view v-if="requisition" class="requisition-card">
        <view class="req-row"><text class="req-k">领用日期</text><text class="req-v">{{ requisition.expectedDate || '—' }}</text></view>
        <view class="req-row"><text class="req-k">领用用途</text><text class="req-v">{{ requisition.purpose || '—' }}</text></view>
        <view class="req-row"><text class="req-k">领用人</text><text class="req-v">{{ form.applicantName || '—' }}</text></view>
        <view class="req-row"><text class="req-k">部门</text><text class="req-v">{{ form.deptName || '未设置部门' }}</text></view>
        <view class="req-row"><text class="req-k">物料</text><text class="req-v">{{ materialSummary }}</text></view>
      </view>
    </view>

    <view class="section readonly">
      <text class="label">所属组织 / 部门</text>
      <view class="readonly-row">
        <text class="readonly-value">{{ meta.company || meta.corpName || '—' }}</text>
        <text class="readonly-sub">{{ meta.deptName || '未设置部门' }}</text>
      </view>
    </view>

    <view v-if="show('writeOffDate')" id="field-writeOffDate" class="section">
      <text class="label">核销日期 <text class="req">*</text></text>
      <picker mode="date" :value="form.writeOffDate" @change="onDateChange">
        <view class="picker-trigger" :class="{ 'control-error': errors.writeOffDate }">
          <text :class="form.writeOffDate ? 'trigger-value' : 'placeholder'">
            {{ form.writeOffDate || '请选择日期' }}
          </text>
          <text class="arrow">›</text>
        </view>
      </picker>
      <text v-if="errors.writeOffDate" class="ferr">{{ errors.writeOffDate }}</text>
    </view>

    <view v-if="show('usageDesc')" id="field-usageDesc" class="section">
      <text class="label">使用说明 <text class="req">*</text></text>
      <textarea class="textarea" :class="{ 'control-error': errors.usageDesc }"
                v-model="form.usageDesc" :maxlength="1000"
                placeholder="请简述物料使用场景与结果（如：完成某小区维修，剩余物料退回仓库）"
                @focus="keepFieldVisible('field-usageDesc')" />
      <view class="field-foot">
        <text v-if="errors.usageDesc" class="ferr">{{ errors.usageDesc }}</text>
        <text class="counter">{{ (form.usageDesc || '').length }}/1000</text>
      </view>
    </view>

    <view v-if="show('agentUserIds')" id="field-agentUserIds" class="section">
      <text class="label">核销人 <text class="req">*</text></text>
      <BusinessAgentPicker :form="form" :meta="meta" :errors="errors"
                           hint="默认本人；如需代核销，可添加本组织同事，核销人可多选" />
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

    <RequisitionPickerSheet v-model:visible="pickerVisible" @select="onSelect" />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import BusinessAgentPicker from '@/components/BusinessAgentPicker.vue'
import RequisitionPickerSheet from '@/components/RequisitionPickerSheet.vue'
import { focusField, keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 第 1 步：核销信息。选择领用单后带出领用单基础信息（页面负责加载明细）；
 * 组织/部门只读；核销人默认本人且不可取消。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  requisition: { type: Object, default: null },
  onlyErrors: { type: Boolean, default: false }
})
const emit = defineEmits(['select-requisition'])

const pickerVisible = ref(false)
const show = key => !props.onlyErrors || !!props.errors[key]
const materialSummary = computed(() => {
  const entries = (props.requisition && props.requisition.entries) || []
  if (!entries.length) return '—'
  const first = entries[0].materialName
  return entries.length === 1 ? first : `${first} 等${entries.length}种`
})

function onDateChange(event) {
  props.form.writeOffDate = event.detail.value
}

function onSelect(summary) {
  pickerVisible.value = false
  emit('select-requisition', summary)
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
.picker-trigger {
  display: flex; align-items: center; justify-content: space-between;
  height: 80rpx; background: #f7f7f7; border-radius: 12rpx; padding: 0 20rpx;
}
.trigger-value { flex: 1; min-width: 0; font-size: 28rpx; color: #333; }
.placeholder { font-size: 28rpx; color: #bbb; }
.arrow { font-size: 32rpx; color: #999; }
.requisition-card {
  margin-top: 16rpx; border-radius: 12rpx; background: #FFF8F4;
  border: 2rpx solid #FFE3D4; padding: 16rpx 18rpx;
}
.req-row { display: flex; padding: 6rpx 0; }
.req-k { width: 140rpx; font-size: 24rpx; color: #999; flex-shrink: 0; }
.req-v { flex: 1; font-size: 25rpx; color: #333; line-height: 1.5; }
.textarea {
  width: 100%; height: 168rpx !important; min-height: 168rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 18rpx 20rpx;
  font-size: 28rpx; color: #333; box-sizing: border-box; line-height: 1.5;
}
.readonly { background: #fafafa; }
.readonly-row { display: flex; flex-direction: column; gap: 6rpx; }
.readonly-value { font-size: 29rpx; color: #333; font-weight: 600; }
.readonly-sub { font-size: 24rpx; color: #999; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.field-foot { display: flex; align-items: flex-start; justify-content: space-between; }
.counter { font-size: 21rpx; color: #bbb; margin-left: 16rpx; }
</style>
