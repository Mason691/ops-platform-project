<template>
  <view class="page">
    <view class="head">
      <text class="title">物料核销</text>
      <text class="sub">核销数量 = 领取数量（剩余可核销）− 退回数量，系统自动计算；提交后部长 → 总经办审批</text>
    </view>

    <view class="card">
      <view class="field">
        <text class="label">关联领取单</text>
        <picker mode="selector" :range="pickLabels" @change="onPickChange">
          <view class="picker">{{ pickLabel }}</view>
        </picker>
      </view>
      <view class="field">
        <text class="label">备注</text>
        <input v-model="remark" class="input" placeholder="选填" maxlength="200" />
      </view>
    </view>

    <view class="section">
      <text class="section-title">核销明细（{{ rows.length }}）</text>
      <view v-if="!rows.length" class="empty">
        <text>请选择已审批且有未核销数量的领取单</text>
      </view>
      <view v-for="(row, idx) in rows" :key="row.pickEntryId" class="item">
        <view class="item-top">
          <text class="item-name">{{ row.materialName || row.materialNumber }}</text>
          <text class="remain">可核销 {{ qty(row.pickQty) }}</text>
        </view>
        <text class="item-sub">{{ row.materialNumber }}<text v-if="row.batchNo"> · 批次 {{ row.batchNo }}</text><text v-if="row.unitName"> · {{ row.unitName }}</text></text>
        <view class="row-line">
          <text class="line-label">退回数量</text>
          <input v-model="row.returnedQty" type="digit" class="qty-input" />
        </view>
        <view v-if="Number(row.returnedQty) > 0" class="row-line">
          <text class="line-label">退回仓库</text>
          <picker mode="selector" :range="warehouseLabels" @change="e => onReturnWarehouse(idx, e)">
            <view class="picker">{{ warehouseName(row.returnStockNumber) }}</view>
          </picker>
        </view>
        <view class="row-line">
          <text class="line-label">核销数量（自动）</text>
          <text class="write-off">{{ qty(writeOffOf(row)) }}</text>
        </view>
      </view>
    </view>

    <view class="footer">
      <view class="btn primary" :class="{ disabled: submitting }" @click="submit">
        <text>{{ submitting ? '提交中...' : '提交审批' }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

const pickable = ref([])
const warehouses = ref([])
const rows = ref([])
const remark = ref('')
const pickId = ref(null)
const submitting = ref(false)

const pickLabels = computed(() => pickable.value.map(p =>
  (p.billNo || ('#' + p.id)) + ' ' + (p.pickerName || '')))
const pickLabel = computed(() => {
  const p = pickable.value.find(x => x.id === pickId.value)
  return p ? (p.billNo || ('#' + p.id)) : '请选择领取单'
})
const warehouseLabels = computed(() => warehouses.value.map(w => w.name || w.number))

onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent('/pages/employee/material-write-off')
    })
    return
  }
  loadBase()
})

async function loadBase() {
  try {
    const [picks, wh] = await Promise.all([
      api.scmMaterialWriteOffPickable(),
      api.scmWarehouseList({ pageNum: 1, pageSize: 200 })
    ])
    pickable.value = picks || []
    warehouses.value = (wh && wh.records) || []
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
  }
}

async function onPickChange(e) {
  const pick = pickable.value[Number(e.detail.value)]
  if (!pick) return
  pickId.value = pick.id
  try {
    const d = await api.scmMaterialWriteOffFromPick(pick.id)
    rows.value = (d.entries || []).map(entry => ({
      ...entry, returnedQty: '0',
      returnStockNumber: entry.returnStockNumber || pick.stockNumber || null
    }))
  } catch (err) {
    rows.value = []
    uni.showToast({ title: (err && err.msg) || '明细加载失败', icon: 'none' })
  }
}

function onReturnWarehouse(idx, e) {
  const w = warehouses.value[Number(e.detail.value)]
  if (w) rows.value[idx].returnStockNumber = w.number
}
function warehouseName(number) {
  const w = warehouses.value.find(x => x.number === number)
  return w ? (w.name || w.number) : (number || '请选择退回仓库')
}

/** 核销数量 = 领取数量（剩余可核销）− 退回数量；仅展示，服务端最终校验 */
function writeOffOf(row) {
  const value = (Number(row.pickQty) || 0) - (Number(row.returnedQty) || 0)
  return value < 0 ? 0 : Number(value.toFixed(4))
}
function qty(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n === 0) return '0'
  return n.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

async function submit() {
  if (submitting.value) return
  if (!pickId.value) { uni.showToast({ title: '请选择领取单', icon: 'none' }); return }
  if (!rows.value.length) { uni.showToast({ title: '没有可核销明细', icon: 'none' }); return }
  for (const row of rows.value) {
    const returned = Number(row.returnedQty) || 0
    if (returned < 0 || returned > Number(row.pickQty)) {
      uni.showToast({ title: '退回数量必须≥0且≤可核销数量', icon: 'none' }); return
    }
    if (returned > 0 && !row.returnStockNumber) {
      uni.showToast({ title: '退回数量>0时必须选择退回仓库', icon: 'none' }); return
    }
  }
  submitting.value = true
  try {
    const result = await api.scmMaterialWriteOffCreate({
      pickId: pickId.value,
      remark: remark.value || null,
      entries: rows.value.map(row => ({
        pickEntryId: row.pickEntryId,
        pickQty: row.pickQty,
        returnedQty: Number(row.returnedQty) || 0,
        returnStockNumber: (Number(row.returnedQty) || 0) > 0 ? row.returnStockNumber : null
      }))
    })
    const display = result && result.currentApproverDisplay
    uni.showModal({
      title: '已提交审批',
      content: '已提交给部长审批' + (display ? '\n' + display : ''),
      showCancel: false,
      success: () => uni.navigateBack()
    })
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '提交失败', icon: 'none', duration: 2500 })
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 180rpx; }
.head { padding: 8rpx 8rpx 20rpx; display: flex; flex-direction: column; gap: 8rpx; }
.title { font-size: 36rpx; font-weight: 700; color: #1D2129; }
.sub { font-size: 23rpx; color: #86909C; line-height: 1.5; }
.card { background: #fff; border-radius: 16rpx; padding: 8rpx 24rpx; }
.field { display: flex; align-items: center; min-height: 92rpx; border-bottom: 1rpx solid #F2F3F5; }
.field:last-child { border-bottom: none; }
.label { width: 150rpx; font-size: 28rpx; color: #4E5969; flex-shrink: 0; }
.picker { font-size: 28rpx; color: #1D2129; }
.input { flex: 1; font-size: 28rpx; color: #1D2129; }
.section { margin-top: 24rpx; }
.section-title { font-size: 26rpx; color: #86909C; padding-left: 4rpx; }
.empty { margin-top: 12rpx; padding: 40rpx; background: #fff; border-radius: 16rpx; text-align: center; font-size: 24rpx; color: #86909C; }
.item { margin-top: 12rpx; background: #fff; border-radius: 16rpx; padding: 24rpx; }
.item-top { display: flex; justify-content: space-between; align-items: center; }
.item-name { font-size: 28rpx; font-weight: 600; color: #1D2129; }
.remain { font-size: 24rpx; color: #00B42A; }
.item-sub { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.row-line { margin-top: 16rpx; display: flex; justify-content: space-between; align-items: center; }
.line-label { font-size: 24rpx; color: #4E5969; }
.qty-input { width: 160rpx; height: 60rpx; text-align: right; border: 1rpx solid #E5E6EB; border-radius: 8rpx; padding: 0 12rpx; font-size: 26rpx; }
.write-off { font-size: 28rpx; color: #409EFF; font-weight: 600; }
.btn { margin-top: 16rpx; height: 88rpx; border-radius: 12rpx; display: flex; align-items: center; justify-content: center; font-size: 28rpx; }
.btn.primary { background: linear-gradient(135deg, #FF8C5A, #FF6B35); color: #fff; font-weight: 600; }
.btn.disabled { opacity: .6; }
.footer { position: fixed; left: 0; right: 0; bottom: 0; padding: 16rpx 24rpx 40rpx; background: #F5F6FA; }
</style>
