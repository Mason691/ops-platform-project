<template>
  <view class="item-card">
    <view class="card-head">
      <text class="row-title">物料 {{ index + 1 }}</text>
      <view v-if="removable" class="remove-btn" @click="$emit('remove')"><text>删除</text></view>
    </view>

    <view :id="fieldId('category')" class="field">
      <text class="label">商品类别 <text class="req">*</text></text>
      <FieldChips v-if="categories.length <= 6 && categories.length" class="chips-block"
                  :class="{ 'control-error-box': errors.category }"
                  :options="categoryOptions" :model-value="row.category"
                  compact @update:model-value="setCategory" />
      <view v-else class="picker-trigger" :class="{ 'control-error': errors.category }"
            @click="categorySheet = true">
        <text :class="row.category ? 'trigger-value' : 'placeholder'">
          {{ row.category || (categories.length ? '请选择商品类别' : '供应链暂无商品类别') }}
        </text>
        <text class="arrow">›</text>
      </view>
      <text v-if="errors.category" class="ferr">{{ errors.category }}</text>
    </view>

    <view :id="fieldId('materialId')" class="field">
      <text class="label">商品名称 <text class="req">*</text></text>
      <view class="picker-trigger" :class="{ disabled: !row.category, 'control-error': errors.materialId }"
            @click="openPicker">
        <text :class="row.name ? 'trigger-value' : 'placeholder'">
          {{ row.name || (row.category ? '点击选择商品' : '请先选择类别') }}
        </text>
        <text class="arrow">›</text>
      </view>
      <view v-if="row.materialId" class="material-meta">
        <text class="meta-item">{{ row.brand || '—' }}</text>
        <text class="meta-item">{{ row.model || '—' }}</text>
        <text class="meta-item">{{ row.unit || '—' }}</text>
      </view>
      <text v-if="errors.materialId" class="ferr">{{ errors.materialId }}</text>
    </view>

    <view :id="fieldId('quantity')" class="field">
      <text class="label">领用数量 <text class="req">*</text></text>
      <view :class="{ 'control-error-box': errors.quantity }">
        <QuantityStepper v-model="row.quantity" :min="1" :max="99999" :focus-id="fieldId('quantity')" />
      </view>
      <text v-if="errors.quantity" class="ferr">{{ errors.quantity }}</text>
    </view>

    <view class="field">
      <text class="label">单条备注</text>
      <input v-model="row.remark" class="input" maxlength="200" placeholder="选填" />
    </view>

    <BottomSheetSelect v-model:visible="categorySheet" title="选择商品类别"
                       :options="categoryOptions" :model-value="row.category"
                       placeholder="搜索类别" @select="option => setCategory(option.value)" />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import FieldChips from '@/components/FieldChips.vue'
import BottomSheetSelect from '@/components/BottomSheetSelect.vue'
import QuantityStepper from '@/components/QuantityStepper.vue'

/**
 * 领用物料卡片：类别直接点选（多时弹层）；商品通过选择弹层带出
 * 名称/品牌/规格/单位后只读展示；数量步进 + 单条备注。
 */
const props = defineProps({
  row: { type: Object, required: true },
  index: { type: Number, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  removable: { type: Boolean, default: false }
})
const emit = defineEmits(['remove', 'choose'])

const categorySheet = ref(false)
const categories = computed(() => props.meta.categories || [])
const categoryOptions = computed(() => categories.value.map(c => ({ value: c.name, label: c.name })))

function fieldId(field) {
  return `field-items-${props.index}-${field}`
}

function setCategory(value) {
  if (props.row.category === value) return
  props.row.category = value
  clearMaterial()
}

function clearMaterial() {
  props.row.materialId = null
  props.row.materialNumber = ''
  props.row.name = ''
  props.row.brand = ''
  props.row.model = ''
  props.row.unit = ''
}

function openPicker() {
  if (!props.row.category) {
    uni.showToast({ title: '请先选择商品类别', icon: 'none' })
    return
  }
  emit('choose', props.row)
}
</script>

<style scoped>
.item-card {
  border: 2rpx solid #f0f0f0; border-radius: 14rpx;
  padding: 22rpx 20rpx; margin-bottom: 18rpx; background: #fcfcfc;
}
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18rpx; }
.row-title { font-size: 28rpx; font-weight: 700; color: #333; }
.remove-btn { padding: 8rpx 20rpx; border-radius: 8rpx; background: #FFF0EB; color: #e53935; font-size: 23rpx; }
.field { margin-bottom: 20rpx; }
.field:last-child { margin-bottom: 0; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 10rpx; }
.req { color: #e53935; }
.input {
  width: 100%; height: 72rpx !important; min-height: 72rpx !important;
  background: #f2f2f2; border-radius: 10rpx; padding: 0 18rpx; font-size: 26rpx; color: #333; box-sizing: border-box;
}
.picker-trigger {
  display: flex; align-items: center; justify-content: space-between;
  height: 76rpx; background: #f2f2f2; border-radius: 10rpx; padding: 0 18rpx;
}
.picker-trigger.disabled { opacity: .65; }
.trigger-value { flex: 1; min-width: 0; font-size: 27rpx; color: #333; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.placeholder { font-size: 27rpx; color: #bbb; }
.arrow { font-size: 30rpx; color: #999; }
.material-meta { display: flex; gap: 16rpx; margin-top: 10rpx; }
.meta-item { font-size: 22rpx; color: #999; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.control-error-box { border: 2rpx solid #e53935; border-radius: 12rpx; padding: 8rpx; background: #FFF8F8; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
.chips-block { width: 100%; }
</style>
