<template>
  <view class="step-items">
    <view v-if="errors.items" class="items-err">{{ errors.items }}</view>

    <view v-if="!onlyErrors" class="filter-card">
      <view class="filter-head" @click="filterOpen = !filterOpen">
        <text class="filter-title">筛选物料</text>
        <text class="filter-toggle">{{ filterOpen ? '收起' : '展开' }}</text>
      </view>
      <view v-if="filterOpen" class="filter-body">
        <FieldChips :options="filterCategoryOptions" :model-value="filterCategory"
                    compact @update:model-value="v => filterCategory = v" />
        <view class="search-row">
          <input v-model="filterKeyword" class="search-input" placeholder="按商品名称关键字过滤" />
          <text v-if="filterKeyword" class="search-clear" @click="filterKeyword = ''">清空</text>
        </view>
      </view>
    </view>

    <view v-if="!visibleRows.length" class="empty">
      <text>{{ emptyText }}</text>
    </view>

    <template v-for="row in visibleRows" :key="row.data.uid || row.index">
      <MaterialRequisitionItemCard v-if="mode === 'requisition'" :row="row.data" :index="row.index"
                                   :meta="meta" :errors="rowErrors(row.index)"
                                   :removable="form.items.length > 1"
                                   @remove="remove(row.index)" @choose="openPicker" />
      <MaterialWriteOffItemCard v-else :row="row.data" :index="row.index"
                                :errors="rowErrors(row.index)" />
    </template>

    <view v-if="mode === 'requisition' && !onlyErrors" class="add-row"
          :class="{ disabled: form.items.length >= MAX_ITEMS }" @click="addRow">
      <text>＋ 添加物料</text>
    </view>
    <text v-if="mode === 'requisition' && !onlyErrors" class="hint">
      同一物料重复添加会自动合并数量；类别与名称筛选仅影响展示，不影响已填数据。
    </text>

    <MaterialPickerSheet v-if="mode === 'requisition'"
                         v-model:visible="pickerVisible" :categories="meta.categories || []"
                         :category="pickerCategory" :keyword="pickerKeyword"
                         @select="onMaterialSelect" />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import FieldChips from '@/components/FieldChips.vue'
import MaterialRequisitionItemCard from '@/components/MaterialRequisitionItemCard.vue'
import MaterialWriteOffItemCard from '@/components/MaterialWriteOffItemCard.vue'
import MaterialPickerSheet from '@/components/MaterialPickerSheet.vue'
import { MAX_ITEMS, emptyRequisitionItem } from '@/utils/materialForm'

/**
 * 第 2 步：物料明细/核销。
 * - requisition：类别+名称筛选、添加物料、重复物料合并数量；
 * - writeoff：只展示领用单带入的物料，可筛选展示但不影响已填数据。
 * onlyErrors=true 时只渲染存在错误的行，用于确认页就地补填。
 */
const props = defineProps({
  mode: { type: String, default: 'requisition' },
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  onlyErrors: { type: Boolean, default: false }
})

const filterOpen = ref(false)
const filterCategory = ref('')
const filterKeyword = ref('')
const pickerVisible = ref(false)
const activeRow = ref(null)
const pickerCategory = ref('')
const pickerKeyword = ref('')

const allCategories = computed(() => {
  if (props.mode === 'requisition') return (props.meta.categories || []).map(c => c.name)
  const seen = new Set()
  return (props.form.items || []).map(row => row.category).filter(name => {
    if (!name || seen.has(name)) return false
    seen.add(name)
    return true
  })
})

const filterCategoryOptions = computed(() => [
  { value: '', label: '全部类别' },
  ...allCategories.value.map(name => ({ value: name, label: name }))
])

const emptyText = computed(() => props.mode === 'requisition'
  ? '暂无物料，请点击下方「添加物料」'
  : '没有符合筛选条件的物料')

const visibleRows = computed(() => {
  const rows = (props.form.items || []).map((data, index) => ({ data, index }))
  return rows.filter(row => {
    if (props.onlyErrors) {
      if (props.errors.items && !rows.some(item => rowHasError(item.index))) return true
      return rowHasError(row.index)
    }
    if (filterCategory.value && row.data.category !== filterCategory.value) return false
    const word = filterKeyword.value.trim().toLowerCase()
    if (!word) return true
    return [row.data.name, row.data.materialName, row.data.brand, row.data.model]
      .some(value => String(value || '').toLowerCase().includes(word))
  })
})

function rowHasError(index) {
  const prefix = `items.${index}.`
  return Object.keys(props.errors || {}).some(key => key.startsWith(prefix))
}

function rowErrors(index) {
  const prefix = `items.${index}.`
  const result = {}
  Object.keys(props.errors || {}).forEach(key => {
    if (key.startsWith(prefix)) result[key.slice(prefix.length)] = props.errors[key]
  })
  return result
}

function addRow() {
  if (props.form.items.length >= MAX_ITEMS) {
    uni.showToast({ title: `单笔最多${MAX_ITEMS}种物料`, icon: 'none' })
    return
  }
  activeRow.value = null
  pickerCategory.value = filterCategory.value
  pickerKeyword.value = filterKeyword.value
  pickerVisible.value = true
}

function openPicker(row) {
  activeRow.value = row
  pickerCategory.value = row.category || filterCategory.value
  pickerKeyword.value = ''
  pickerVisible.value = true
}

function onMaterialSelect(material) {
  if (!material) return
  const target = activeRow.value
  activeRow.value = null
  if (target) {
    // 编辑卡片时选到已在其它行的物料：合并到已有行，避免产生重复明细
    const duplicate = props.form.items.find(row => row !== target && row.materialId
      && String(row.materialId) === String(material.id))
    if (duplicate) {
      duplicate.quantity = (Number(duplicate.quantity) || 0) + (Number(target.quantity) || 0)
      if (props.form.items.length > 1) {
        props.form.items.splice(props.form.items.indexOf(target), 1)
      }
      uni.showToast({ title: '该物料已在明细中，已合并数量', icon: 'none', duration: 2200 })
      return
    }
    applyMaterial(target, material)
    return
  }
  const existed = props.form.items.find(row => row.materialId
    && String(row.materialId) === String(material.id))
  if (existed) {
    existed.quantity = (Number(existed.quantity) || 0) + 1
    uni.showToast({ title: '该物料已在明细中，已合并数量', icon: 'none', duration: 2200 })
    return
  }
  const slot = props.form.items.find(row => row && !row.materialId)
  const targetRow = slot || emptyRequisitionItem()
  if (!slot) props.form.items.push(targetRow)
  applyMaterial(targetRow, material)
}

function applyMaterial(row, material) {
  row.category = material.category || row.category
  row.materialId = material.id
  row.materialNumber = material.number || ''
  row.name = material.name || ''
  row.brand = material.brand || ''
  row.model = material.model || ''
  row.unit = material.unit || ''
  if (!row.quantity || Number(row.quantity) < 1) row.quantity = 1
}

function remove(index) {
  if (props.form.items.length <= 1) {
    uni.showToast({ title: '至少保留一条物料', icon: 'none' })
    return
  }
  clearRowErrors()
  props.form.items.splice(index, 1)
}

/** 行结构变化后旧错误键会错位，先清空；下次校验按新行号重新生成。 */
function clearRowErrors() {
  Object.keys(props.errors || {})
    .filter(key => key === 'items' || key.startsWith('items.'))
    .forEach(key => delete props.errors[key])
}
</script>

<style scoped>
.step-items { display: flex; flex-direction: column; }
.items-err {
  background: #FFF1F0; color: #e53935; border-radius: 10rpx;
  padding: 16rpx 20rpx; font-size: 24rpx; margin-bottom: 16rpx;
}
.filter-card { background: #fff; border-radius: 14rpx; margin-bottom: 16rpx; padding: 18rpx 20rpx; }
.filter-head { display: flex; align-items: center; justify-content: space-between; }
.filter-title { font-size: 26rpx; color: #333; font-weight: 600; }
.filter-toggle { font-size: 23rpx; color: #FF6B35; }
.filter-body { margin-top: 14rpx; display: flex; flex-direction: column; gap: 12rpx; }
.search-row {
  display: flex; align-items: center; background: #f5f5f5;
  border-radius: 10rpx; padding: 0 18rpx;
}
.search-input { flex: 1; height: 66rpx !important; min-height: 66rpx !important; font-size: 26rpx; color: #333; }
.search-clear { font-size: 23rpx; color: #999; padding-left: 12rpx; }
.empty {
  background: #fff; border-radius: 14rpx; padding: 48rpx 0;
  text-align: center; font-size: 25rpx; color: #999; margin-bottom: 16rpx;
}
.add-row {
  height: 84rpx; line-height: 84rpx; text-align: center;
  border: 2rpx dashed #FF6B35; border-radius: 14rpx;
  color: #FF6B35; font-size: 27rpx; background: #FFFDFC;
}
.add-row:active { background: #FFF3ED; }
.add-row.disabled { opacity: .5; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 12rpx; line-height: 1.5; }
</style>
