<template>
  <view class="step-basic">
    <view v-if="show('batchNo')" id="field-batchNo" class="section">
      <view class="label-row">
        <text class="label">批次 <text class="req">*</text></text>
        <text class="label-tip">提交后自动生成订单编号</text>
      </view>
      <FieldChips class="chips-block" :class="{ 'control-error-box': errors.batchNo }"
                  :options="batchChips" :model-value="batchValue"
                  @update:model-value="setBatch" compact />
      <view v-if="showMoreBatches" class="more-link" @click="batchSheet = true">
        <text>查看更多批次 ›</text>
      </view>
      <view v-if="form.newBatch" class="new-batch-tip">
        <text>将自动使用月度批次：</text>
        <text class="strong">{{ meta.newBatchPrefix || '—' }}</text>
      </view>
      <text v-if="errors.batchNo" class="ferr">{{ errors.batchNo }}</text>
      <text v-else-if="!onlyErrors" class="hint">仅可选择未上报集团的批次；新建批次按公司月度自动生成</text>
    </view>

    <view v-if="show('customerPhone')" id="field-customerPhone" class="section">
      <text class="label" @click="focusField('field-customerPhone')">
        客户手机 <text class="req">*</text>
      </text>
      <view class="phone-row">
        <input class="input phone-input" :class="{ 'control-error': errors.customerPhone }"
               :value="form.customerPhone" type="tel"
               maxlength="11" placeholder="请输入11位手机号"
               @input="onPhoneInput" @focus="keepFieldVisible('field-customerPhone')" />
        <view class="query-btn" :class="{ disabled: querying }" @click="$emit('query-member')">
          {{ querying ? '查询中' : '查询' }}
        </view>
      </view>
      <text v-if="errors.customerPhone" class="ferr">{{ errors.customerPhone }}</text>
      <text v-else-if="memberStatus === 'notfound'" class="hint warn-text">
        未找到会员，保存时将自动创建会员
      </text>
      <text v-else-if="memberStatus === 'found'" class="hint found-text">
        已匹配平台会员，客户名称可继续修改
      </text>
    </view>

    <view v-if="show('customer')" id="field-customer" class="section">
      <text class="label" @click="focusField('field-customer')">
        客户名称 <text class="req">*</text>
      </text>
      <input class="input" :class="{ 'control-error': errors.customer }"
             v-model="form.customer" maxlength="50" placeholder="查询后自动填充，也可手动输入"
             @focus="keepFieldVisible('field-customer')" />
      <text v-if="errors.customer" class="ferr">{{ errors.customer }}</text>
    </view>

    <view v-if="show('communityId')" id="field-communityId" class="section">
      <text class="label">小区 <text class="req">*</text></text>
      <template v-if="communities.length > 8">
        <view class="picker-trigger" :class="{ 'control-error': errors.communityId }"
              @click="communitySheet = true">
          <text :class="form.communityName ? 'trigger-value' : 'placeholder'">
            {{ form.communityName || '请选择小区' }}
          </text>
          <text class="arrow">›</text>
        </view>
      </template>
      <template v-else-if="communities.length">
        <view v-if="communities.length > 4" class="search-row">
          <input class="search-input" v-model="communityKeyword" placeholder="搜索小区" />
          <text v-if="communityKeyword" class="search-clear" @click="communityKeyword = ''">清空</text>
        </view>
        <FieldChips class="chips-block" :class="{ 'control-error-box': errors.communityId }"
                    :options="communityChips" :model-value="form.communityId"
                    @update:model-value="setCommunity" compact />
      </template>
      <text v-else class="warn-text">组织暂未配置小区，请联系管理员在后台维护后再制单</text>
      <text v-if="errors.communityId" class="ferr">{{ errors.communityId }}</text>
    </view>

    <view v-if="show('detailAddress')" id="field-detailAddress" class="section">
      <text class="label" @click="focusField('field-detailAddress')">
        详细地址 <text class="req">*</text>
      </text>
      <textarea class="textarea" :class="{ 'control-error': errors.detailAddress }"
                v-model="form.detailAddress" maxlength="200"
                placeholder="如：3栋2单元501号" @focus="keepFieldVisible('field-detailAddress')" />
      <view class="field-foot">
        <text v-if="errors.detailAddress" class="ferr">{{ errors.detailAddress }}</text>
        <text class="counter">{{ (form.detailAddress || '').length }}/200</text>
      </view>
    </view>

    <view v-if="show('agentUserIds')" id="field-agentUserIds" class="section">
      <text class="label">经办人 <text class="req">*</text></text>
      <BusinessAgentPicker :form="form" :meta="meta" :errors="errors" />
    </view>

    <BottomSheetSelect v-model:visible="batchSheet" title="选择批次" :options="batchOptions"
                       :model-value="form.batchNo" placeholder="搜索批次"
                       @select="option => setBatch(option.value)" />
    <BottomSheetSelect v-model:visible="communitySheet" title="选择小区" :options="communityOptions"
                       :model-value="form.communityId" placeholder="搜索小区名称"
                       @select="option => setCommunity(option.value)" />
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import FieldChips from '@/components/FieldChips.vue'
import BottomSheetSelect from '@/components/BottomSheetSelect.vue'
import BusinessAgentPicker from '@/components/BusinessAgentPicker.vue'
import { normalizePhone } from '@/utils/businessForm'
import { focusField, keepFieldVisible } from '@/utils/fieldFocus'

/**
 * 第 1 步：基础信息。批次/小区/经办人均为直接点选或卡片，
 * 选项多时使用底部弹层搜索；onlyErrors=true 时仅渲染缺失字段（确认页就地补填）。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  querying: { type: Boolean, default: false },
  memberStatus: { type: String, default: 'idle' },
  onlyErrors: { type: Boolean, default: false }
})
const emit = defineEmits(['query-member'])

const batchSheet = ref(false)
const communitySheet = ref(false)
const communityKeyword = ref('')
let queryTimer = null

const show = key => !props.onlyErrors || !!props.errors[key]

const batchValue = computed(() => props.form.newBatch ? '__new__' : props.form.batchNo)
const showMoreBatches = computed(() => (props.meta.batches || []).length > 6)
const batchChips = computed(() => {
  const all = props.meta.batches || []
  const visible = all.length > 6 ? all.slice(0, 3) : [...all]
  // 已选批次可能来自“查看更多”弹层，必须保留在标签行，否则用户看不到当前选择
  const selected = props.form.newBatch ? '' : props.form.batchNo
  if (selected && !visible.includes(selected)) visible.push(selected)
  return [
    { value: '__new__', label: '＋ 新建批次' },
    ...visible.map(batch => ({ value: batch, label: batch }))
  ]
})
const batchOptions = computed(() =>
  (props.meta.batches || []).map(batch => ({ value: batch, label: batch })))

const communities = computed(() => props.meta.communities || [])
const communityOptions = computed(() => communities.value.map(item => ({
  value: item.id, label: item.name
})))
const communityChips = computed(() => {
  const word = communityKeyword.value.trim().toLowerCase()
  const list = word
    ? communities.value.filter(item => String(item.name || '').toLowerCase().includes(word))
    : communities.value
  return list.map(item => ({ value: item.id, label: item.name }))
})

function setBatch(value) {
  if (value === '__new__') {
    props.form.newBatch = true
    props.form.batchNo = ''
    return
  }
  props.form.newBatch = false
  props.form.batchNo = value
}

function setCommunity(value) {
  const found = communities.value.find(item => String(item.id) === String(value))
  props.form.communityId = found ? found.id : null
  props.form.communityName = found ? found.name : ''
}

function onPhoneInput(event) {
  props.form.customerPhone = normalizePhone(event.detail.value)
}

// 输入满 11 位自动查询；防抖避免输入过程中反复请求。
watch(() => props.form.customerPhone, phone => {
  clearTimeout(queryTimer)
  if (normalizePhone(phone).length !== 11) return
  queryTimer = setTimeout(() => emit('query-member'), 400)
})

onBeforeUnmount(() => clearTimeout(queryTimer))
</script>

<style scoped>
.step-basic { display: flex; flex-direction: column; gap: 20rpx; }
.section {
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.label-row { display: flex; align-items: baseline; justify-content: space-between; }
.label {
  display: block; font-size: 26rpx; color: #666; margin-bottom: 14rpx;
}
.label-tip { font-size: 21rpx; color: #aaa; }
.req { color: #e53935; }
.input {
  width: 100%; height: 80rpx !important; min-height: 80rpx !important;
  background: #f7f7f7; border-radius: 12rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
}
.textarea {
  width: 100%; height: 168rpx !important; min-height: 168rpx !important;
  background: #f7f7f7; border-radius: 12rpx;
  padding: 18rpx 20rpx; font-size: 28rpx; color: #333; box-sizing: border-box;
  line-height: 1.5;
}
.phone-row { display: flex; align-items: center; gap: 16rpx; }
.phone-input { flex: 1; }
.query-btn {
  height: 80rpx; line-height: 80rpx; padding: 0 30rpx; border-radius: 12rpx;
  background: #FF6B35; color: #fff; font-size: 26rpx; white-space: nowrap;
}
.query-btn.disabled { opacity: .6; }
.more-link { margin-top: 14rpx; font-size: 24rpx; color: #FF6B35; }
.new-batch-tip {
  margin-top: 14rpx; padding: 14rpx 18rpx; border-radius: 10rpx;
  background: #FFF8F4; font-size: 24rpx; color: #8a5a3b;
}
.strong { color: #FF6B35; font-weight: 600; }
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
.search-row {
  display: flex; align-items: center; background: #f5f5f5;
  border-radius: 10rpx; padding: 0 18rpx; margin-bottom: 14rpx;
}
.search-input { flex: 1; height: 66rpx !important; min-height: 66rpx !important; font-size: 26rpx; color: #333; }
.search-clear { font-size: 23rpx; color: #999; padding-left: 12rpx; }
.field-foot { display: flex; align-items: flex-start; justify-content: space-between; }
.counter { font-size: 21rpx; color: #bbb; margin-left: 16rpx; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.control-error-box {
  border: 2rpx solid #e53935; border-radius: 12rpx;
  padding: 8rpx; background: #FFF8F8;
}
.chips-block { width: 100%; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
.warn-text { display: block; font-size: 23rpx; color: #B26A00; margin-top: 8rpx; line-height: 1.5; }
.found-text { color: #07A65A; }
</style>
