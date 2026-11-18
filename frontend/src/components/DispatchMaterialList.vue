<template>
  <view class="field dispatch-material">
    <view class="dm-head">
      <text class="label">物料清单</text>
      <text class="dm-optional">可选</text>
    </view>
    <text class="hint">
      派工成功后自动生成物料领用草稿，员工补充仓库、批次后提交审批；
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
      </view>
      <view class="dm-actions">
        <QuantityStepper v-model="row.qty" :min="1" :max="9999" compact
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
            <text class="dm-name">{{ formatMaterialLabel(m) }}</text>
            <text v-if="m.unitName" class="dm-unit">{{ m.unitName }}</text>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '@/api/index'
import { formatMaterialLabel } from '@/utils/materialForm'
import QuantityStepper from '@/components/QuantityStepper.vue'

/**
 * 派工出库物料清单（需求预填）。
 *
 * 只采集商品编码 + 数量；仓库/批次由员工在物料领用草稿中补充，
 * 因此这里不查询库存、不校验库存。为空时派工照常进行，仅不生成领用单。
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const rows = computed({
  get: () => props.modelValue || [],
  set: value => emit('update:modelValue', value)
})
const pickerVisible = ref(false)
const loading = ref(false)
const keyword = ref('')
const options = ref([])

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
  pickerVisible.value = true
  if (options.value.length || loading.value) return
  loading.value = true
  try {
    const list = (await api.scmMaterialPickMaterialOptions()) || []
    // 接口字段为 materialNumber；formatMaterialLabel 统一读 number，补齐后列表可显示编号
    options.value = list.map(m => ({ ...m, number: m.materialNumber || m.number }))
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
  rows.value = [...rows.value, {
    materialNumber: material.materialNumber,
    name: material.name,
    brandName: material.brandName,
    model: material.model,
    unitName: material.unitName,
    qty: '1'
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
  } else {
    row.qty = String(qty)
  }
}

watch(pickerVisible, visible => { if (!visible) keyword.value = '' })
</script>

<style scoped>
.dispatch-material { display: block; padding: 20rpx 0; }
.dm-head { display: flex; align-items: center; gap: 12rpx; }
.dm-optional { font-size: 22rpx; color: #86909C; }
.hint { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; line-height: 1.5; }
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
