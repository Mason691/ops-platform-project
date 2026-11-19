<template>
  <view class="field dispatch-material">
    <view class="dm-head">
      <text class="label">物料清单</text>
      <text class="dm-optional">可选</text>
    </view>
    <view class="dm-warehouse">
      <text class="label">领用仓库</text>
      <picker mode="selector" :range="warehouseLabels" @change="onWarehouseChange">
        <view class="dm-warehouse-picker">{{ warehouseLabel }}</view>
      </picker>
    </view>
    <text class="hint">
      派工成功后自动生成物料领用草稿，员工补充批次后提交审批；
      不指定物料则不生成领用单（派工不扣库存、不发起审批）。
      数量支持 －/＋ 步进、长按连续调整，也可点击数字直接输入。
    </text>

    <view v-if="!rows.length" class="dm-empty">
      <text>暂未指定物料</text>
    </view>
    <view v-for="(row, idx) in rows" :id="'dm-qty-' + row.materialNumber"
          :key="row.materialNumber" class="dm-row">
      <view class="dm-info">
        <text class="dm-name">{{ labelOf(row) }}</text>
        <text v-if="row.unitName" class="dm-unit">单位：{{ row.unitName }}</text>
        <text class="dm-stock" :class="{ danger: Number(row.qty) > Number(row.available || 0) }">
          库存：{{ stockLoading ? '加载中...' : availableText(row) }}
        </text>
      </view>
      <view class="dm-actions">
        <QuantityStepper v-model="row.qty" :min="1" :max="maxOf(row)" compact
                         :focus-id="'dm-qty-' + row.materialNumber"
                         @blur="normalizeQty(row)" />
        <text class="dm-remove" @click="remove(idx)">删除</text>
      </view>
    </view>

    <view class="dm-add" @click="openPicker">
      <text>＋ 添加物料</text>
    </view>

    <view v-if="pickerVisible" class="dm-overlay" @click.self="pickerVisible = false">
      <view class="dm-sheet">
        <view class="dm-sheet-head">
          <text class="dm-sheet-title">选择物料</text>
          <text class="dm-sheet-close" @click="pickerVisible = false">关闭</text>
        </view>
        <input v-model="keyword" class="dm-search" placeholder="搜索编号 / 名称 / 品牌 / 型号" />
        <view v-if="loading" class="dm-state"><text>加载中...</text></view>
        <view v-else-if="!filtered.length" class="dm-state"><text>未找到物料</text></view>
        <scroll-view v-else scroll-y class="dm-list">
          <view v-for="m in filtered" :key="m.materialNumber" class="dm-option"
                @click="pick(m)">
            <view class="dm-option-main">
              <text class="dm-name">{{ formatMaterialLabel(m) }}</text>
              <text v-if="m.unitName" class="dm-unit">{{ m.unitName }}</text>
            </view>
            <text class="dm-stock">库存：{{ stockText(m.materialNumber) }}</text>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '@/api/index'
import { formatMaterialLabel } from '@/utils/materialForm'
import QuantityStepper from '@/components/QuantityStepper.vue'

/**
 * 派工出库物料清单（需求预填）。
 *
 * 派工阶段选择领用仓库后，按仓库展示物料可用库存，数量不得超过库存；
 * 仓库随派工一起传给 SCM，自动生成的物料领用草稿直接带入该仓库。
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  stockNumber: { type: String, default: null }
})
const emit = defineEmits(['update:modelValue', 'update:stockNumber'])

const rows = computed({
  get: () => props.modelValue || [],
  set: value => emit('update:modelValue', value)
})
const pickerVisible = ref(false)
const loading = ref(false)
const keyword = ref('')
const options = ref([])
const warehouses = ref([])
const stockMap = ref({})
const stockLoading = ref(false)

const warehouseLabels = computed(() => warehouses.value.map(w => w.name || w.number))
const warehouseLabel = computed(() => {
  const w = warehouses.value.find(x => x.number === props.stockNumber)
  return w ? (w.name || w.number) : (props.stockNumber || '请选择仓库')
})

onMounted(loadWarehouses)
watch(() => props.stockNumber, () => loadStock())

async function loadWarehouses() {
  try {
    const [list, def] = await Promise.all([
      api.scmWarehouseList({ pageNum: 1, pageSize: 200 }),
      api.scmWarehouseDefault().catch(() => null)
    ])
    warehouses.value = (list && list.records) || []
    if (!props.stockNumber) {
      const defaultNo = (def && def.number) || (warehouses.value[0] && warehouses.value[0].number)
      if (defaultNo) emit('update:stockNumber', defaultNo)
    } else {
      await loadStock()
    }
  } catch (e) {
    warehouses.value = []
  }
}

function onWarehouseChange(e) {
  const w = warehouses.value[Number(e.detail.value)]
  if (!w) return
  emit('update:stockNumber', w.number)
}

/** 按仓库聚合物料总库存（含批次），派工阶段不区分批次。 */
async function loadStock() {
  if (!props.stockNumber) {
    stockMap.value = {}
    return
  }
  stockLoading.value = true
  try {
    const res = await api.scmInventoryQuery({
      pageNum: 1, pageSize: 200, stockNumber: props.stockNumber
    })
    const map = {}
    for (const group of (res && res.records) || []) {
      for (const d of group.details || []) {
        map[d.materialNumber] = (map[d.materialNumber] || 0) + (Number(d.validQty) || 0)
      }
    }
    stockMap.value = map
    // 切仓后库存可能变小，已录入数量同步压到库存上限
    rows.value = rows.value.map(row => {
      const available = map[row.materialNumber] == null ? 0 : map[row.materialNumber]
      const qty = Number(row.qty)
      return {
        ...row,
        available,
        qty: Number.isFinite(qty) && qty > available ? String(available > 0 ? available : 1) : row.qty
      }
    })
  } catch (e) {
    stockMap.value = {}
    uni.showToast({ title: (e && e.msg) || '库存加载失败', icon: 'none' })
  } finally {
    stockLoading.value = false
  }
}

function availableOf(row) {
  if (!props.stockNumber) return null
  const value = stockMap.value[row.materialNumber]
  return value == null ? 0 : Number(value)
}

function availableText(row) {
  const value = availableOf(row)
  return value === null ? '—' : qtyText(value)
}

function stockText(materialNumber) {
  if (!props.stockNumber) return '请先选择仓库'
  const value = stockMap.value[materialNumber]
  return value == null ? '0' : qtyText(value)
}

function maxOf(row) {
  const value = availableOf(row)
  return value === null ? 9999 : Math.max(1, value)
}

function qtyText(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '0'
  return n.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

const filtered = computed(() => {
  const kw = String(keyword.value || '').trim().toLowerCase()
  const list = options.value || []
  if (!kw) return list
  return list.filter(m => formatMaterialLabel(m).toLowerCase().includes(kw))
})

function labelOf(row) {
  return formatMaterialLabel({
    number: row.materialNumber,
    brandName: row.brandName,
    name: row.name,
    model: row.model
  })
}

async function openPicker() {
  if (!props.stockNumber) {
    uni.showToast({ title: '请先选择领用仓库', icon: 'none' })
    return
  }
  pickerVisible.value = true
  if (stockLoading.value) return
  if (options.value.length || loading.value) {
    if (Object.keys(stockMap.value).length === 0) await loadStock()
    return
  }
  loading.value = true
  try {
    const list = (await api.scmMaterialPickMaterialOptions()) || []
    // 接口字段为 materialNumber；formatMaterialLabel 统一读 number，补齐后列表可显示编号
    options.value = list.map(m => ({ ...m, number: m.materialNumber || m.number }))
    if (Object.keys(stockMap.value).length === 0) await loadStock()
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '物料加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function pick(material) {
  if (rows.value.some(row => row.materialNumber === material.materialNumber)) {
    uni.showToast({ title: '该物料已在清单中', icon: 'none' })
    return
  }
  const available = stockMap.value[material.materialNumber] == null
    ? 0 : Number(stockMap.value[material.materialNumber])
  if (!(available > 0)) {
    uni.showToast({ title: '该物料在当前仓库库存不足', icon: 'none' })
    return
  }
  rows.value = [...rows.value, {
    materialNumber: material.materialNumber,
    name: material.name,
    brandName: material.brandName,
    model: material.model,
    unitName: material.unitName,
    available,
    qty: available >= 1 ? '1' : String(available)
  }]
}

function remove(index) {
  const next = [...rows.value]
  next.splice(index, 1)
  rows.value = next
}

/** 个/件/台等计件单位不允许小数；空值、负数、非法输入回退到 1 并提示。 */
const PIECE_UNITS = ['个', '件', '台', '套', '把', '张', '块', '只', '支']

function normalizeQty(row) {
  const qty = Number(row.qty)
  const piece = PIECE_UNITS.includes(String(row.unitName || '').trim())
  if (!Number.isFinite(qty) || qty <= 0 || (piece && !Number.isInteger(qty))) {
    row.qty = '1'
    uni.showToast({ title: '数量已调整为 1', icon: 'none' })
    return
  }
  const available = availableOf(row)
  if (available !== null && qty > available) {
    row.qty = String(available > 0 ? available : 1)
    uni.showToast({ title: '数量不能大于库存 ' + qtyText(available), icon: 'none' })
    return
  }
  row.qty = String(qty)
}

watch(pickerVisible, visible => { if (!visible) keyword.value = '' })
</script>

<style scoped>
.dispatch-material { display: block; padding: 20rpx 0; }
.dm-head { display: flex; align-items: center; gap: 12rpx; }
.dm-optional { font-size: 22rpx; color: #86909C; }
.hint { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; line-height: 1.5; }
.dm-warehouse { display: flex; align-items: center; gap: 16rpx; margin-top: 12rpx; }
.dm-warehouse-picker { min-width: 260rpx; height: 68rpx; line-height: 68rpx; padding: 0 20rpx; background: #F7F8FA; border-radius: 10rpx; font-size: 26rpx; color: #1D2129; }
.dm-stock { font-size: 22rpx; color: #4E5969; }
.dm-stock.danger { color: #F53F3F; }
.dm-option-main { display: flex; align-items: baseline; gap: 8rpx; }
.dm-empty { margin-top: 16rpx; padding: 28rpx; background: #F7F8FA; border-radius: 12rpx; text-align: center; font-size: 24rpx; color: #86909C; }
.dm-row { margin-top: 14rpx; padding: 18rpx; background: #F7F8FA; border-radius: 12rpx; display: flex; align-items: center; gap: 16rpx; }
.dm-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.dm-name { font-size: 26rpx; color: #1D2129; word-break: break-all; }
.dm-unit { font-size: 22rpx; color: #86909C; }
.dm-actions { display: flex; align-items: center; gap: 12rpx; flex-shrink: 0; }
.dm-remove { font-size: 24rpx; color: #F53F3F; padding: 8rpx 4rpx; }
.dm-add { margin-top: 16rpx; height: 72rpx; border: 1rpx dashed #FFB99A; border-radius: 12rpx; display: flex; align-items: center; justify-content: center; font-size: 26rpx; color: #FF6B35; background: #FFF7F3; }
.dm-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: flex-end; z-index: 80; }
.dm-sheet { width: 100%; max-height: 78vh; background: #fff; border-radius: 24rpx 24rpx 0 0; padding: 24rpx; display: flex; flex-direction: column; }
.dm-sheet-head { display: flex; justify-content: space-between; align-items: center; }
.dm-sheet-title { font-size: 30rpx; font-weight: 700; color: #1D2129; }
.dm-sheet-close { font-size: 26rpx; color: #86909C; }
.dm-search { margin-top: 16rpx; height: 72rpx; background: #F7F8FA; border-radius: 36rpx; padding: 0 24rpx; font-size: 26rpx; }
.dm-list { margin-top: 12rpx; max-height: 60vh; }
.dm-option { padding: 22rpx 8rpx; border-bottom: 1rpx solid #F2F3F5; display: flex; flex-direction: column; gap: 4rpx; }
.dm-option:active { background: #FFF7F3; }
.dm-state { padding: 60rpx 0; text-align: center; font-size: 24rpx; color: #86909C; }
</style>
