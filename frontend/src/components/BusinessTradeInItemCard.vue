<template>
  <view class="trade-in-item">
    <view class="item-head">
      <text class="item-title">旧品 {{ itemIndex + 1 }}</text>
      <text class="item-remove" @click="$emit('remove')">删除</text>
    </view>

    <view class="field">
      <text class="label">旧品商品 <text class="req">*</text></text>
      <view class="picker-trigger" :class="{ 'control-error': errors.productName }"
            @click="sheetVisible = true">
        <text :class="item.productName || item.brand || item.isManual ? 'trigger-value' : 'placeholder'">
          {{ pickerLabel }}
        </text>
        <text class="arrow">›</text>
      </view>
      <text class="hint">仅可选择品牌为「旧品」的商品，或选择“其他旧品”手工填写</text>
    </view>

    <view v-if="!item.isManual" class="readonly-row">
      <view class="readonly-item"><text class="rk">品牌</text><text class="rv">{{ item.brand || '—' }}</text></view>
      <view class="readonly-item"><text class="rk">型号</text><text class="rv">{{ item.model || '—' }}</text></view>
    </view>

    <template v-else>
      <view class="field">
        <text class="label">旧品名称 <text class="req">*</text></text>
        <input class="input" :class="{ 'control-error': errors.productName }"
               :value="item.productName" maxlength="200" placeholder="如 旧燃气灶"
               @input="onInput('productName', $event)" />
      </view>
      <view class="row2">
        <view class="field half">
          <text class="label">品牌</text>
          <input class="input" :value="item.brand" maxlength="100" placeholder="选填"
                 @input="onInput('brand', $event)" />
        </view>
        <view class="field half">
          <text class="label">型号</text>
          <input class="input" :value="item.model" maxlength="200" placeholder="选填"
                 @input="onInput('model', $event)" />
        </view>
      </view>
    </template>

    <view class="row2">
      <view class="field half">
        <text class="label">数量 <text class="req">*</text></text>
        <view :class="{ 'control-error-box': errors.quantity }">
          <QuantityStepper v-model="item.quantity" />
        </view>
        <text v-if="errors.quantity" class="ferr">{{ errors.quantity }}</text>
      </view>
      <view class="field half">
        <text class="label">备注</text>
        <input class="input" :value="item.remark" maxlength="500" placeholder="成色/故障等（选填）"
               @input="onInput('remark', $event)" />
      </view>
    </view>

    <text v-if="errors.productName" class="ferr">{{ errors.productName }}</text>

    <BottomSheetSelect v-model:visible="sheetVisible" title="选择旧品" :options="materialOptions"
                       :model-value="pickerValue" placeholder="搜索品牌=旧品的商品"
                       @select="onSelect" />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import BottomSheetSelect from '@/components/BottomSheetSelect.vue'
import QuantityStepper from '@/components/QuantityStepper.vue'

/**
 * 单件旧品：选择商品库旧品（品牌/型号自动带出只读）或“其他旧品”手工录入。
 * 数量为正整数；备注选填。
 */
const props = defineProps({
  item: { type: Object, required: true },
  itemIndex: { type: Number, required: true },
  materialOptions: { type: Array, default: () => [] },
  errors: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['remove'])

const sheetVisible = ref(false)

const pickerValue = computed(() => {
  if (props.item.productId) return String(props.item.productId)
  return props.item.isManual ? '__manual__' : ''
})

const pickerLabel = computed(() => {
  if (props.item.productName) return props.item.productName
  if (props.item.brand || props.item.model) {
    return [props.item.brand, props.item.model].filter(Boolean).join(' ')
  }
  if (props.item.isManual) return '其他旧品（手工填写）'
  return '请选择旧品'
})

function onSelect(option) {
  if (!option) return
  if (option.value === '__manual__') {
    props.item.isManual = true
    props.item.productId = null
    props.item.materialNumber = ''
    props.item.productName = ''
    props.item.brand = ''
    props.item.model = ''
    props.item.category = ''
    props.item.unit = ''
    return
  }
  const material = option.material || {}
  props.item.isManual = false
  props.item.productId = material.id || null
  props.item.materialNumber = material.number || ''
  props.item.productName = material.name
    || [material.brand, material.model].filter(Boolean).join(' ')
  props.item.brand = material.brand || ''
  props.item.model = material.model || ''
  props.item.category = material.category || ''
  props.item.unit = material.unit || ''
}

function onInput(field, event) {
  props.item[field] = String(event.detail.value ?? '')
}
</script>

<style scoped>
.trade-in-item {
  background: #fff; border: 1rpx solid #E5EAF2; border-radius: 12rpx;
  padding: 18rpx; display: flex; flex-direction: column; gap: 14rpx;
}
.item-head { display: flex; align-items: center; justify-content: space-between; }
.item-title { font-size: 24rpx; font-weight: 600; color: #1D2129; }
.item-remove { font-size: 21rpx; color: #e53935; padding: 4rpx 8rpx; }
.field { display: flex; flex-direction: column; gap: 8rpx; }
.field.half { flex: 1; min-width: 0; }
.label { font-size: 23rpx; color: #666; }
.req { color: #e53935; }
.hint { font-size: 20rpx; color: #A9AEB8; line-height: 1.4; }
.input {
  width: 100%; height: 68rpx !important; min-height: 68rpx !important;
  background: #F7F8FA; border-radius: 10rpx; padding: 0 16rpx;
  font-size: 25rpx; color: #333; box-sizing: border-box;
}
.picker-trigger {
  display: flex; align-items: center; justify-content: space-between;
  height: 68rpx; background: #F7F8FA; border-radius: 10rpx; padding: 0 16rpx;
}
.trigger-value {
  flex: 1; min-width: 0; font-size: 25rpx; color: #333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.placeholder { font-size: 25rpx; color: #bbb; }
.arrow { font-size: 28rpx; color: #999; }
.readonly-row { display: flex; gap: 16rpx; }
.readonly-item { flex: 1; display: flex; gap: 8rpx; font-size: 22rpx; }
.rk { color: #86909C; }
.rv { color: #1D2129; }
.row2 { display: flex; gap: 16rpx; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.control-error-box {
  border: 2rpx solid #e53935; border-radius: 12rpx; padding: 8rpx; background: #FFF8F8;
}
.ferr { font-size: 21rpx; color: #e53935; line-height: 1.4; }
</style>
