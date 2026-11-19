<template>
  <view class="page">
    <view class="head">
      <view class="head-title-row">
        <text class="title">物料领用单</text>
        <EmployeeHomeLink />
      </view>
      <text class="sub">补充领取仓库与批次后提交本组织部长审批；提交前请确认数量</text>
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
      <view class="retry" @click="load"><text>重试</text></view>
    </view>

    <template v-else-if="detail">
      <view class="card">
        <view class="kv"><text class="k">单据编号</text><text class="v">{{ detail.billNo || '草稿未编号' }}</text></view>
        <view class="kv"><text class="k">单据日期</text><text class="v">{{ detail.billDate || '—' }}</text></view>
        <view class="kv"><text class="k">领取事由</text><text class="v">{{ reasonLabel(detail.pickReason) }}</text></view>
        <view class="kv"><text class="k">领用人</text><text class="v">{{ detail.pickerName || '—' }}</text></view>
        <view class="kv"><text class="k">状态</text>
          <text class="v" :class="materialPickStatusClass(detail.approvalStatus)">{{ materialPickStatusLabel(detail.approvalStatus) }}</text>
        </view>
        <view class="field">
          <text class="label">领取仓库</text>
          <picker v-if="editable" mode="selector" :range="warehouseLabels" @change="onWarehousePick">
            <view class="picker">{{ warehouseLabel }}</view>
          </picker>
          <text v-else class="picker">{{ warehouseName || detail.stockNumber || '—' }}</text>
        </view>
      </view>

      <view v-if="detail.taskOrder" class="card order-card">
        <view class="order-head">
          <text class="order-title">
            关联工单 {{ detail.taskOrder.orderNo || detail.taskOrderNo || '已删除' }}
          </text>
          <text class="order-link" @click="openTaskOrder(detail)">查看工单</text>
        </view>
        <template v-if="detail.taskOrder.readable">
          <view class="kv"><text class="k">客户</text><text class="v">{{ detail.taskOrder.customerName || '—' }}</text></view>
          <view class="kv"><text class="k">客户手机</text><text class="v">{{ detail.taskOrder.customerPhoneMasked || '—' }}</text></view>
          <view class="kv"><text class="k">订单类型</text><text class="v">{{ detail.taskOrder.orderType || '—' }}</text></view>
          <view class="kv"><text class="k">订单状态</text><text class="v">{{ detail.taskOrder.statusLabel || '—' }}</text></view>
          <view class="kv"><text class="k">派工员工</text><text class="v">{{ detail.taskOrder.assigneeName || '—' }}</text></view>
          <view class="kv"><text class="k">派工时间</text><text class="v">{{ detail.taskOrder.dispatchTime || '—' }}</text></view>
        </template>
        <text v-else class="order-unavailable">
          {{ detail.taskOrder.statusLabel || '无权查看该工单' }}
        </text>
      </view>

      <view class="section">
        <text class="section-title">物料明细（{{ rows.length }}）</text>
        <view v-if="!rows.length" class="empty"><text>暂无物料，请从库存添加</text></view>
        <view v-for="(row, idx) in rows" :key="row.key" class="item">
          <view class="item-top">
            <text class="item-name">{{ row.materialName || row.materialNumber }}</text>
            <text v-if="editable" class="remove" @click="removeRow(idx)">删除</text>
          </view>
          <text class="item-sub">{{ row.materialNumber }}<text v-if="row.spec"> · {{ row.spec }}</text></text>
          <view class="item-meta">
            <text :class="row.stockNumber ? 'ok' : 'warn'">
              {{ row.stockNumber || '待选仓库' }}
            </text>
            <text v-if="row.batchNo"> · 批次 {{ row.batchNo }}</text>
            <text v-else-if="editable && row.stockNumber" class="batch-link" @click="chooseBatch(row)">
              选择批次
            </text>
          </view>
          <view class="item-meta">
            <text class="stock-label">库存：{{ row.available === null ? '—' : qty(row.available) }}</text>
          </view>
          <view class="item-bottom">
            <text class="qty-label">领取数量</text>
            <view v-if="editable" class="qty-control">
              <view class="qty-btn" @click="decreaseRowQty(row)">－</view>
              <input v-model="row.pickQty" type="digit" class="qty-input"
                     @blur="normalizeRowQty(row)" />
              <view class="qty-btn plus" @click="increaseRowQty(row)">＋</view>
            </view>
            <text v-else class="qty-value">{{ row.pickQty }}</text>
          </view>
        </view>
        <view v-if="editable" class="btn ghost" @click="openStockPicker">
          <text>＋ 从库存添加物料</text>
        </view>
      </view>

      <view v-if="editable" class="footer">
        <view class="btn ghost footer-btn" :class="{ disabled: saving }" @click="saveDraft">
          <text>{{ saving ? '保存中...' : '保存草稿' }}</text>
        </view>
        <view class="btn primary footer-btn" :class="{ disabled: submitting }" @click="submit">
          <text>{{ submitting ? '提交中...' : '提交审批' }}</text>
        </view>
      </view>
    </template>

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
            <text class="stock">库存 {{ qty(s.validQty) }}</text>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script setup>
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'
import {
  materialPickStatusLabel,
  materialPickStatusClass
} from '@/utils/materialPickStatus'

/**
 * 物料领用草稿编辑：派工自动生成的草稿在此补充仓库/批次后提交。
 *
 * 草稿态 stock_number/batch_no 允许为空；提交时后端强制校验非空（此处只做友好提示）。
 * 派工自动草稿的工单/事由由后端固化，页面只读展示。
 */
const id = ref(null)
const detail = ref(null)
const rows = ref([])
const warehouses = ref([])
const loading = ref(true)
const errorMsg = ref('')
const saving = ref(false)
const submitting = ref(false)
const stockVisible = ref(false)
const stockLoading = ref(false)
const stockRows = ref([])
const form = ref({ stockNumber: null })
let rowKey = 0

const editable = computed(() => {
  const status = detail.value && detail.value.approvalStatus
  return status === 'DRAFT' || status === 'REJECTED'
})
const warehouseLabels = computed(() => warehouses.value.map(w => w.name || w.number))
const warehouseName = computed(() => {
  const w = warehouses.value.find(x => x.number === form.value.stockNumber)
  return w ? (w.name || w.number) : form.value.stockNumber
})
const warehouseLabel = computed(() => warehouseName.value || '请选择仓库')

onLoad((options) => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect='
        + encodeURIComponent('/pages/employee/material-pick-draft?id=' + (options.id || ''))
    })
    return
  }
  id.value = options.id
  load()
})

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    const [d, wh, def] = await Promise.all([
      api.scmMaterialPickDetail(id.value),
      api.scmWarehouseList({ pageNum: 1, pageSize: 200 }).catch(() => ({ records: [] })),
      api.scmWarehouseDefault().catch(() => null)
    ])
    detail.value = d
    warehouses.value = (wh && wh.records) || []
    // 派工旧草稿/历史草稿可能没有仓库：优先原仓库，其次组织默认仓库
    form.value.stockNumber = d.stockNumber || (def && def.number) || null
    rows.value = (d.entries || []).map(e => ({
      key: 'r' + (++rowKey),
      materialNumber: e.materialNumber,
      materialName: e.materialName,
      spec: e.spec,
      stockNumber: e.stockNumber || form.value.stockNumber || '',
      batchNo: e.batchNo || '',
      pickQty: String(e.pickQty == null ? '' : e.pickQty),
      costPrice: e.costPrice,
      sourceTaskEntryId: e.sourceTaskEntryId,
      available: null
    }))
    await refreshAllStock()
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function onWarehousePick(e) {
  const w = warehouses.value[Number(e.detail.value)]
  if (!w) return
  form.value.stockNumber = w.number
  // 表头仓库为唯一口径：所有分录统一到该仓库，批次由员工逐行选择
  rows.value.forEach(row => {
    row.stockNumber = w.number
    row.batchNo = ''
  })
  refreshAllStock()
}

async function refreshAllStock() {
  await Promise.all(rows.value.map(row => refreshRowStock(row)))
}

async function refreshRowStock(row) {
  if (!form.value.stockNumber || !row.materialNumber) {
    row.available = null
    return
  }
  try {
    row.available = await api.scmMaterialPickStock({
      stockNumber: form.value.stockNumber,
      materialNumber: row.materialNumber,
      batchNo: row.batchNo || ''
    })
  } catch (e) {
    row.available = null
  }
}

function normalizeRowQty(row) {
  const qty = Number(row.pickQty)
  if (!Number.isFinite(qty) || qty <= 0) {
    row.pickQty = '1'
    return
  }
  if (row.available !== null && qty > Number(row.available)) {
    row.pickQty = String(Number(row.available))
    uni.showToast({ title: '数量不能大于库存 ' + qty(Number(row.available)), icon: 'none' })
  }
}

function increaseRowQty(row) {
  const next = Number(row.pickQty || 0) + 1
  if (row.available !== null && next > Number(row.available)) {
    uni.showToast({ title: '数量不能大于库存 ' + qty(Number(row.available)), icon: 'none' })
    return
  }
  row.pickQty = String(next)
}

function decreaseRowQty(row) {
  const next = Number(row.pickQty || 0) - 1
  row.pickQty = String(next > 0 ? next : 0.0001)
}

function removeRow(index) {
  rows.value.splice(index, 1)
}

function openStockPicker() {
  if (!form.value.stockNumber) {
    uni.showToast({ title: '请先选择领取仓库', icon: 'none' })
    return
  }
  stockVisible.value = true
  stockLoading.value = true
  loadStock()
}

async function loadStock() {
  try {
    const res = await api.scmInventoryQuery({
      pageNum: 1, pageSize: 200, stockNumber: form.value.stockNumber
    })
    const merged = new Map()
    for (const group of (res && res.records) || []) {
      for (const d of group.details || []) {
        const key = d.materialNumber + '|' + (d.batchNo || '')
        const row = merged.get(key) || {
          materialNumber: d.materialNumber, materialName: d.materialName,
          batchNo: d.batchNo || '', validQty: 0
        }
        row.validQty += Number(d.validQty) || 0
        merged.set(key, row)
      }
    }
    stockRows.value = [...merged.values()].filter(row => row.validQty > 0)
  } catch (e) {
    stockRows.value = []
    uni.showToast({ title: (e && e.msg) || '库存加载失败', icon: 'none' })
  } finally {
    stockLoading.value = false
  }
}

function addStock(stock) {
  const exists = rows.value.some(row =>
    row.materialNumber === stock.materialNumber && (row.batchNo || '') === (stock.batchNo || ''))
  if (exists) {
    uni.showToast({ title: '该物料批次已在明细中', icon: 'none' })
    return
  }
  const available = Number(stock.validQty) || 0
  rows.value.push({
    key: 'r' + (++rowKey),
    materialNumber: stock.materialNumber,
    materialName: stock.materialName,
    spec: '',
    stockNumber: form.value.stockNumber,
    batchNo: stock.batchNo || '',
    pickQty: available >= 1 ? '1' : String(available || 1),
    costPrice: null,
    sourceTaskEntryId: null,
    available
  })
  stockVisible.value = false
}

async function chooseBatch(row) {
  const stockNumber = form.value.stockNumber || row.stockNumber
  if (!stockNumber) {
    uni.showToast({ title: '请先选择领取仓库', icon: 'none' })
    return
  }
  try {
    const batches = await api.scmMaterialPickBatches({
      stockNumber, materialNumber: row.materialNumber
    })
    const list = batches || []
    if (!list.length) {
      uni.showToast({ title: '该仓库无可领用批次', icon: 'none' })
      return
    }
    uni.showActionSheet({
      itemList: list.map(b => `${b.batch_no}（可用 ${qty(b.valid_qty)}）`),
      success: async (res) => {
        const picked = list[res.tapIndex]
        if (picked) {
          row.batchNo = picked.batch_no
          await refreshRowStock(row)
          normalizeRowQty(row)
        }
      }
    })
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '批次加载失败', icon: 'none' })
  }
}

function buildPayload() {
  return {
    billDate: detail.value.billDate,
    pickReason: detail.value.pickReason,
    taskOrderId: detail.value.taskOrderId,
    stockNumber: form.value.stockNumber || null,
    remark: detail.value.remark,
    entries: rows.value.map(row => ({
      materialNumber: row.materialNumber,
      stockNumber: form.value.stockNumber || null,
      batchNo: row.batchNo || '',
      pickQty: Number(row.pickQty),
      costPrice: row.costPrice,
      sourceTaskEntryId: row.sourceTaskEntryId
    }))
  }
}

function validateRows() {
  if (!rows.value.length) return '请至少添加一条物料'
  for (const row of rows.value) {
    if (!(Number(row.pickQty) > 0)) return '领取数量必须大于0：' + row.materialNumber
    if (row.available !== null && Number(row.pickQty) > Number(row.available)) {
      return `领取数量不能大于库存：${row.materialNumber}（库存 ${qty(Number(row.available))}）`
    }
  }
  return ''
}

async function saveDraft() {
  const error = validateRows()
  if (error) {
    uni.showToast({ title: error, icon: 'none' })
    return false
  }
  saving.value = true
  try {
    await api.scmMaterialPickSave(id.value, buildPayload())
    uni.showToast({ title: '草稿已保存', icon: 'success' })
    await load()
    return true
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '保存失败', icon: 'none', duration: 2500 })
    return false
  } finally {
    saving.value = false
  }
}

async function submit() {
  if (submitting.value) return
  if (!form.value.stockNumber) {
    uni.showToast({ title: '请选择仓库后提交', icon: 'none' })
    return
  }
  const error = validateRows()
  if (error) {
    uni.showToast({ title: error, icon: 'none' })
    return
  }
  // 提交前预览审批人：预览失败不阻塞提交，只展示兜底文案
  let confirmText = '提交后将进入本组织部长审批流程，审批人信息提交后可在详情查看'
  try {
    const preview = await api.scmMaterialPickApprovalPreview(id.value)
    if (preview && preview.displayName) {
      confirmText = `您将把物料领用单 ${detail.value.billNo || ''} 提交给 ${preview.displayName} 审批，确认提交？`
    } else if (preview && preview.fallbackMessage) {
      confirmText = preview.fallbackMessage
    }
  } catch (e) {
    // 预览接口不可用/超时：保持兜底文案
  }
  const confirmed = await new Promise(resolve => {
    uni.showModal({
      title: '提交审批',
      content: confirmText,
      confirmText: '确认提交',
      cancelText: '取消',
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
  if (!confirmed) return

  submitting.value = true
  try {
    // 先保存草稿（提交接口只读库内分录），再提交审批
    await api.scmMaterialPickSave(id.value, buildPayload())
    const result = await api.scmMaterialPickSubmit(id.value)
    const display = result && result.currentApproverDisplay
    uni.showModal({
      title: '已提交审批',
      content: display ? '已提交给 ' + display + ' 审批'
        : '已提交审批，待分配审批人，请联系管理员',
      showCancel: false,
      success: () => uni.redirectTo({ url: '/pages/employee/material-pick-drafts' })
    })
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '提交失败', icon: 'none', duration: 2500 })
  } finally {
    submitting.value = false
  }
}

function qty(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n === 0) return '0'
  return n.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}
function reasonLabel(value) {
  return { DISPATCH: '派工施工', ACTIVITY: '活动物料', OTHER: '其它事宜' }[value] || value || '—'
}

function openTaskOrder(item) {
  const info = item && item.taskOrder
  if (!info || !info.id) {
    uni.showToast({ title: '该工单已删除/作废', icon: 'none' })
    return
  }
  if (info.deleted) {
    uni.showToast({ title: '该工单已删除/作废', icon: 'none' })
    return
  }
  if (info.readable === false) {
    uni.showToast({ title: '无权查看该工单', icon: 'none' })
    return
  }
  uni.navigateTo({
    url: '/pages/employee/material-task-order?pickId=' + encodeURIComponent(id.value)
  })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 200rpx; }
.head { padding: 8rpx 8rpx 20rpx; display: flex; flex-direction: column; gap: 8rpx; }
.head-title-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.title { font-size: 36rpx; font-weight: 700; color: #1D2129; }
.sub { font-size: 22rpx; color: #86909C; }
.state { padding: 80rpx 0; text-align: center; font-size: 24rpx; color: #86909C; }
.state-err { color: #F53F3F; }
.retry { margin-top: 20rpx; color: #FF6B35; font-size: 26rpx; }
.card { background: #fff; border-radius: 16rpx; padding: 8rpx 24rpx; }
.order-card { margin-top: 20rpx; padding-bottom: 16rpx; }
.order-head { display: flex; align-items: center; justify-content: space-between; min-height: 76rpx; border-bottom: 1rpx solid #F2F3F5; }
.order-title { font-size: 28rpx; font-weight: 600; color: #1D2129; }
.order-link { color: #FF6B35; font-size: 24rpx; }
.order-unavailable { display: block; padding: 20rpx 0; color: #86909C; font-size: 24rpx; }
.kv { display: flex; align-items: center; min-height: 76rpx; border-bottom: 1rpx solid #F2F3F5; }
.kv:last-child { border-bottom: none; }
.k { width: 160rpx; font-size: 26rpx; color: #86909C; flex-shrink: 0; }
.v { flex: 1; font-size: 26rpx; color: #1D2129; }
.st-draft { color: #FF7D00; }
.st-pending { color: #165DFF; }
.st-ok { color: #00B42A; }
.st-reject { color: #F53F3F; }
.field { display: flex; align-items: center; min-height: 92rpx; border-bottom: 1rpx solid #F2F3F5; }
.label { width: 160rpx; font-size: 26rpx; color: #86909C; flex-shrink: 0; }
.picker { flex: 1; font-size: 28rpx; color: #1D2129; }
.section { margin-top: 24rpx; }
.section-title { font-size: 26rpx; color: #86909C; padding-left: 4rpx; }
.empty { margin-top: 12rpx; padding: 40rpx; background: #fff; border-radius: 16rpx; text-align: center; font-size: 24rpx; color: #86909C; }
.item { margin-top: 12rpx; background: #fff; border-radius: 16rpx; padding: 24rpx; }
.item-top { display: flex; justify-content: space-between; align-items: center; gap: 12rpx; }
.item-name { font-size: 28rpx; font-weight: 600; color: #1D2129; word-break: break-all; }
.remove { font-size: 24rpx; color: #F53F3F; flex-shrink: 0; }
.item-sub { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.item-meta { margin-top: 10rpx; font-size: 22rpx; color: #4E5969; }
.stock-label { color: #1D2129; font-weight: 600; }
.ok { color: #00B42A; }
.warn { color: #FF7D00; }
.batch-link { margin-left: 10rpx; color: #FF6B35; font-weight: 600; }
.item-bottom { margin-top: 16rpx; display: flex; justify-content: space-between; align-items: center; }
.qty-label { font-size: 24rpx; color: #4E5969; }
.qty-input { width: 120rpx; height: 60rpx; text-align: center; border: 1rpx solid #E5E6EB; border-radius: 8rpx; padding: 0 8rpx; font-size: 26rpx; }
.qty-control { display: flex; align-items: center; gap: 8rpx; }
.qty-btn {
  width: 56rpx; height: 56rpx; line-height: 56rpx; text-align: center;
  border: 1rpx solid #FFB99A; border-radius: 8rpx;
  color: #FF6B35; background: #FFF7F3; font-size: 30rpx;
}
.qty-btn.plus { background: #FF6B35; color: #fff; border-color: #FF6B35; }
.qty-value { font-size: 28rpx; color: #1D2129; font-weight: 600; }
.btn { height: 88rpx; border-radius: 12rpx; display: flex; align-items: center; justify-content: center; font-size: 28rpx; }
.btn.ghost { margin-top: 16rpx; background: #fff; color: #FF6B35; border: 1rpx dashed #FFB99A; }
.btn.primary { background: linear-gradient(135deg, #FF8C5A, #FF6B35); color: #fff; font-weight: 600; }
.btn.disabled { opacity: .6; }
.footer { position: fixed; left: 0; right: 0; bottom: 0; padding: 16rpx 24rpx 40rpx; background: #F5F6FA; display: flex; gap: 16rpx; }
.footer-btn { flex: 1; margin-top: 0; }
.overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: flex-end; z-index: 50; }
.sheet { width: 100%; max-height: 80vh; background: #fff; border-radius: 24rpx 24rpx 0 0; padding: 24rpx; display: flex; flex-direction: column; }
.sheet-head { display: flex; justify-content: space-between; align-items: center; }
.sheet-title { font-size: 30rpx; font-weight: 700; }
.close { font-size: 26rpx; color: #86909C; }
.sheet-list { margin-top: 16rpx; max-height: 64vh; }
.stock-row { display: flex; justify-content: space-between; align-items: center; padding: 20rpx 4rpx; border-bottom: 1rpx solid #F2F3F5; }
.stock-main { display: flex; flex-direction: column; gap: 4rpx; }
.stock { font-size: 24rpx; color: #00B42A; }
</style>
