<template>
  <view class="page">
    <view class="head">
      <text class="title">物料领取</text>
      <text class="sub">提交后由财务审批，审批通过才扣减库存并生成可打印单据</text>
    </view>

    <view class="card">
      <view class="field">
        <text class="label">领取事由</text>
        <view class="chips">
          <view v-for="r in REASONS" :key="r.value" class="chip"
            :class="{ active: form.pickReason === r.value }" @click="form.pickReason = r.value">
            <text>{{ r.label }}</text>
          </view>
        </view>
      </view>
      <view v-if="form.pickReason === 'DISPATCH'" class="field">
        <text class="label">任务工单</text>
        <picker mode="selector" :range="taskOrderLabels" @change="onTaskOrderPick">
          <view class="picker">{{ taskOrderLabel }}</view>
        </picker>
      </view>
      <view class="field">
        <text class="label">领取仓库</text>
        <picker mode="selector" :range="warehouseLabels" @change="onWarehousePick">
          <view class="picker">{{ warehouseLabel }}</view>
        </picker>
      </view>
      <view class="field">
        <text class="label">备注</text>
        <input v-model="form.remark" class="input" placeholder="选填" maxlength="200" />
      </view>
    </view>

    <view class="section">
      <text class="section-title">领取明细（{{ rows.length }}）</text>
      <view v-if="!rows.length" class="empty">
        <text>请先选择仓库，再从可用库存中添加物料</text>
      </view>
      <view v-for="(row, idx) in rows" :key="row.key" class="item">
        <view class="item-top">
          <text class="item-name">{{ row.materialName || row.materialNumber }}</text>
          <text class="remove" @click="rows.splice(idx, 1)">删除</text>
        </view>
        <text class="item-sub">{{ row.materialNumber }}<text v-if="row.batchNo"> · 批次 {{ row.batchNo }}</text></text>
        <view class="item-bottom">
          <text class="stock">可用 {{ qty(row.validQty) }}</text>
          <view class="qty-box">
            <text class="qty-label">领取数量</text>
            <input v-model="row.pickQty" type="digit" class="qty-input" />
          </view>
        </view>
      </view>
      <view class="btn ghost" @click="openStockPicker">
        <text>+ 从库存添加物料</text>
      </view>
    </view>

    <view class="footer">
      <view class="btn primary" :class="{ disabled: submitting }" @click="submit">
        <text>{{ submitting ? '提交中...' : '提交审批' }}</text>
      </view>
    </view>

    <view v-if="stockVisible" class="overlay" @click.self="stockVisible = false">
      <view class="sheet">
        <view class="sheet-head">
          <text class="sheet-title">可用库存</text>
          <text class="close" @click="stockVisible = false">关闭</text>
        </view>
        <view v-if="stockLoading" class="state"><text>加载中...</text></view>
        <view v-else-if="!stockRows.length" class="state"><text>该仓库暂无可用库存</text></view>
        <scroll-view v-else scroll-y class="sheet-list">
          <view v-for="s in stockRows" :key="s.materialNumber + '|' + s.batchNo" class="stock-row"
            @click="addStock(s)">
            <view class="stock-main">
              <text class="item-name">{{ s.materialName || s.materialNumber }}</text>
              <text class="item-sub">{{ s.materialNumber }}<text v-if="s.batchNo"> · 批次 {{ s.batchNo }}</text></text>
            </view>
            <text class="stock">{{ qty(s.validQty) }}</text>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

const REASONS = [
  { value: 'DISPATCH', label: '派工施工' },
  { value: 'ACTIVITY', label: '活动物料' },
  { value: 'OTHER', label: '其它事宜' }
]

const form = reactive({ pickReason: 'DISPATCH', taskOrderId: null, stockNumber: null, remark: '' })
const warehouses = ref([])
const taskOrders = ref([])
const rows = ref([])
const submitting = ref(false)
const stockVisible = ref(false)
const stockLoading = ref(false)
const stockRows = ref([])

const warehouseLabels = computed(() => warehouses.value.map(w => w.name || w.number))
const warehouseLabel = computed(() => {
  const w = warehouses.value.find(x => x.number === form.stockNumber)
  return w ? (w.name || w.number) : '请选择仓库'
})
const taskOrderLabels = computed(() => taskOrders.value.map(t =>
  (t.dispatchNo || ('#' + t.id)) + ' ' + (t.customerName || '')))
const taskOrderLabel = computed(() => {
  const t = taskOrders.value.find(x => x.id === form.taskOrderId)
  return t ? (t.dispatchNo || ('#' + t.id)) : '请选择任务工单'
})

onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent('/pages/employee/material-pick')
    })
    return
  }
  loadBase()
})

async function loadBase() {
  try {
    const [wh, tasks] = await Promise.all([
      api.scmWarehouseList({ pageNum: 1, pageSize: 200 }),
      api.scmMaterialPickTaskOrders().catch(() => [])
    ])
    warehouses.value = (wh && wh.records) || []
    taskOrders.value = tasks || []
    if (!form.stockNumber && warehouses.value.length === 1) {
      form.stockNumber = warehouses.value[0].number
    }
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '基础数据加载失败', icon: 'none' })
  }
}

function onWarehousePick(e) {
  const w = warehouses.value[Number(e.detail.value)]
  if (!w) return
  form.stockNumber = w.number
  rows.value = []
}
function onTaskOrderPick(e) {
  const t = taskOrders.value[Number(e.detail.value)]
  if (t) form.taskOrderId = t.id
}

async function openStockPicker() {
  if (!form.stockNumber) {
    uni.showToast({ title: '请先选择领取仓库', icon: 'none' })
    return
  }
  stockVisible.value = true
  stockLoading.value = true
  try {
    const res = await api.scmInventoryQuery({
      pageNum: 1, pageSize: 200, stockNumber: form.stockNumber
    })
    // 接口按 (仓库,商品) 汇总分页，批次级可用量在 details 里；按商品+批次合并
    const merged = new Map()
    for (const group of (res && res.records) || []) {
      for (const d of group.details || []) {
        const key = d.materialNumber + '|' + (d.batchNo || '')
        const row = merged.get(key) || {
          materialNumber: d.materialNumber, materialName: d.materialName,
          batchNo: d.batchNo || '', stockNumber: d.stockNumber || form.stockNumber,
          validQty: 0
        }
        row.validQty += Number(d.validQty) || 0
        merged.set(key, row)
      }
    }
    stockRows.value = [...merged.values()].filter(r => r.validQty > 0)
  } catch (e) {
    stockRows.value = []
    uni.showToast({ title: (e && e.msg) || '库存加载失败', icon: 'none' })
  } finally {
    stockLoading.value = false
  }
}

function addStock(s) {
  const key = s.materialNumber + '|' + (s.batchNo || '')
  if (rows.value.some(r => r.key === key)) {
    uni.showToast({ title: '该物料批次已在明细中', icon: 'none' })
    return
  }
  rows.value.push({
    key, materialNumber: s.materialNumber, materialName: s.materialName,
    batchNo: s.batchNo || '', stockNumber: s.stockNumber || form.stockNumber,
    validQty: Number(s.validQty), pickQty: '1'
  })
  stockVisible.value = false
}

function qty(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n === 0) return '0'
  return n.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

async function submit() {
  if (submitting.value) return
  if (!form.stockNumber) { uni.showToast({ title: '请选择领取仓库', icon: 'none' }); return }
  if (form.pickReason === 'DISPATCH' && !form.taskOrderId) {
    uni.showToast({ title: '派工施工必须选择任务工单', icon: 'none' }); return
  }
  if (!rows.value.length) { uni.showToast({ title: '请添加物料分录', icon: 'none' }); return }
  for (const row of rows.value) {
    const picked = Number(row.pickQty)
    if (!(picked > 0)) { uni.showToast({ title: '领取数量必须大于0', icon: 'none' }); return }
    if (picked > row.validQty) {
      uni.showToast({ title: '领取数量超过可用库存：' + row.materialNumber, icon: 'none' }); return
    }
  }
  submitting.value = true
  try {
    const result = await api.scmMaterialPickCreate({
      pickReason: form.pickReason,
      taskOrderId: form.pickReason === 'DISPATCH' ? form.taskOrderId : null,
      stockNumber: form.stockNumber,
      remark: form.remark || null,
      entries: rows.value.map(row => ({
        materialNumber: row.materialNumber, stockNumber: row.stockNumber,
        batchNo: row.batchNo, pickQty: Number(row.pickQty), sourceTaskEntryId: null
      }))
    })
    const display = result && result.currentApproverDisplay
    uni.showModal({
      title: '已提交审批',
      content: '已提交给财务审批' + (display ? '\n' + display : ''),
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
.sub { font-size: 24rpx; color: #86909C; }
.card { background: #fff; border-radius: 16rpx; padding: 8rpx 24rpx; }
.field { display: flex; align-items: center; min-height: 92rpx; border-bottom: 1rpx solid #F2F3F5; }
.field:last-child { border-bottom: none; }
.label { width: 150rpx; font-size: 28rpx; color: #4E5969; flex-shrink: 0; }
.picker { flex: 1; font-size: 28rpx; color: #1D2129; }
.input { flex: 1; font-size: 28rpx; color: #1D2129; }
.chips { flex: 1; display: flex; gap: 12rpx; flex-wrap: wrap; }
.chip { padding: 10rpx 20rpx; border-radius: 999rpx; background: #F2F3F5; font-size: 24rpx; color: #4E5969; }
.chip.active { background: #FFF0EB; color: #FF6B35; font-weight: 600; }
.section { margin-top: 24rpx; }
.section-title { font-size: 26rpx; color: #86909C; padding-left: 4rpx; }
.empty { margin-top: 12rpx; padding: 40rpx; background: #fff; border-radius: 16rpx; text-align: center; font-size: 24rpx; color: #86909C; }
.item { margin-top: 12rpx; background: #fff; border-radius: 16rpx; padding: 24rpx; }
.item-top { display: flex; justify-content: space-between; align-items: center; }
.item-name { font-size: 28rpx; font-weight: 600; color: #1D2129; }
.remove { font-size: 24rpx; color: #F53F3F; }
.item-sub { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.item-bottom { margin-top: 16rpx; display: flex; justify-content: space-between; align-items: center; }
.stock { font-size: 24rpx; color: #00B42A; }
.qty-box { display: flex; align-items: center; gap: 12rpx; }
.qty-label { font-size: 24rpx; color: #4E5969; }
.qty-input { width: 140rpx; height: 60rpx; text-align: right; border: 1rpx solid #E5E6EB; border-radius: 8rpx; padding: 0 12rpx; font-size: 26rpx; }
.btn { margin-top: 16rpx; height: 88rpx; border-radius: 12rpx; display: flex; align-items: center; justify-content: center; font-size: 28rpx; }
.btn.ghost { background: #fff; color: #FF6B35; border: 1rpx dashed #FFB99A; }
.btn.primary { background: linear-gradient(135deg, #FF8C5A, #FF6B35); color: #fff; font-weight: 600; }
.btn.disabled { opacity: .6; }
.footer { position: fixed; left: 0; right: 0; bottom: 0; padding: 16rpx 24rpx 40rpx; background: #F5F6FA; }
.overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: flex-end; z-index: 50; }
.sheet { width: 100%; max-height: 80vh; background: #fff; border-radius: 24rpx 24rpx 0 0; padding: 24rpx; display: flex; flex-direction: column; }
.sheet-head { display: flex; justify-content: space-between; align-items: center; }
.sheet-title { font-size: 30rpx; font-weight: 700; }
.close { font-size: 26rpx; color: #86909C; }
.sheet-list { margin-top: 16rpx; max-height: 64vh; }
.stock-row { display: flex; justify-content: space-between; align-items: center; padding: 20rpx 4rpx; border-bottom: 1rpx solid #F2F3F5; }
.stock-main { display: flex; flex-direction: column; gap: 4rpx; }
.state { padding: 60rpx 0; text-align: center; font-size: 24rpx; color: #86909C; }
</style>
