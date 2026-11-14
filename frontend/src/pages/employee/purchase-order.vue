<template>
  <view class="page">
    <view class="head">
      <text class="title">采购订单</text>
      <text class="sub">城市合伙人制单 · 提交后自动进入总经办审批</text>
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <template v-else>
      <view class="card">
        <text class="card-title">{{ editId ? '编辑并重新提交' : '新建采购订单' }}</text>

        <view class="field">
          <text class="label">供应商 <text class="req">*</text></text>
          <picker mode="selector" :range="supplierNames" :value="supplierIndex" @change="onSupplier">
            <view class="picker">{{ supplierName || '请选择供应商' }}</view>
          </picker>
        </view>

        <view class="field">
          <text class="label">收货仓库 <text class="req">*</text></text>
          <picker mode="selector" :range="warehouseNames" :value="warehouseIndex" @change="onWarehouse">
            <view class="picker">{{ warehouseName || '请选择收货仓库' }}</view>
          </picker>
        </view>

        <view class="field">
          <text class="label">单据日期</text>
          <picker mode="date" :value="form.billDate" @change="form.billDate = $event.detail.value">
            <view class="picker">{{ form.billDate || '请选择日期' }}</view>
          </picker>
        </view>

        <view class="field">
          <text class="label">商品分录 <text class="req">*</text></text>
          <view v-for="(e, i) in form.entries" :key="i" class="entry">
            <picker mode="selector" :range="materialNames" :value="materialIndex(e)"
              @change="onMaterial(i, $event)">
              <view class="picker">{{ e.materialNumber
                ? e.materialNumber + ' ' + e.materialName : '选择商品' }}</view>
            </picker>
            <view class="row2">
              <input v-model="e.qty" class="input" type="digit" placeholder="数量" />
              <input v-model="e.taxPrice" class="input" type="digit" placeholder="含税单价" />
              <text class="remove" @click="removeEntry(i)">删除</text>
            </view>
            <text class="line-total">合计 ¥{{ lineAmount(e) }}</text>
          </view>
          <view class="add" @click="addEntry"><text>+ 添加商品</text></view>
        </view>

        <view class="total">本次应付：<text class="money">¥{{ totalAmount }}</text></view>
        <button class="btn primary" :disabled="submitting" @click="submit">
          {{ submitting ? '提交中...' : '保存并提交审批' }}
        </button>
        <view v-if="editId" class="cancel-edit" @click="resetForm"><text>取消编辑</text></view>
      </view>

      <view class="card">
        <text class="card-title">我的采购订单</text>
        <view v-if="!orders.length" class="empty"><text>暂无采购订单</text></view>
        <view v-for="o in orders" :key="o.id" class="order" @click="editOrder(o)">
          <view class="order-top">
            <text class="bill-no">{{ o.billNo }}</text>
            <text class="status" :class="statusClass(o.approvalStatus)">{{ approvalLabel(o.approvalStatus) }}</text>
          </view>
          <text class="order-meta">{{ o.billDate }} · {{ o.supplierName || o.supplierNumber }}</text>
          <text class="order-meta">金额 ¥{{ money(o.totalAmount) }}</text>
          <text v-if="o.wfNodeName" class="order-meta">当前节点：{{ o.wfNodeName }}</text>
          <view v-if="o.approvalStatus === 'REJECTED'" class="reject-tip">
            <text>驳回原因：{{ o.approvalRemark || '—' }}（点击修改后重新提交）</text>
          </view>
          <view v-else-if="o.approvalStatus === 'IN_APPROVAL'" class="approving-tip">
            <text>审批中，请等待总经办核审</text>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad, onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

const loading = ref(true)
const submitting = ref(false)
const editId = ref(null)
const suppliers = ref([])
const warehouses = ref([])
const materials = ref([])
const orders = ref([])

const form = reactive({
  supplierNumber: '', stockNumber: '', billDate: today(), dataSource: 'DIRECT',
  entries: [emptyEntry()]
})

function emptyEntry() {
  return { materialNumber: '', materialName: '', qty: '', taxPrice: '' }
}
function today() {
  const d = new Date()
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')
    + '-' + String(d.getDate()).padStart(2, '0')
}

const supplierNames = computed(() => suppliers.value.map(s => s.supplierName + '（' + s.supplierCode + '）'))
const supplierIndex = computed(() => Math.max(0, suppliers.value.findIndex(s => s.supplierCode === form.supplierNumber)))
const supplierName = computed(() => {
  const s = suppliers.value.find(x => x.supplierCode === form.supplierNumber)
  return s ? s.supplierName + '（' + s.supplierCode + '）' : ''
})
const warehouseNames = computed(() => warehouses.value.map(w => w.name + '（' + w.number + '）'))
const warehouseIndex = computed(() => Math.max(0, warehouses.value.findIndex(w => w.number === form.stockNumber)))
const warehouseName = computed(() => {
  const w = warehouses.value.find(x => x.number === form.stockNumber)
  return w ? w.name + '（' + w.number + '）' : ''
})
const materialNames = computed(() => materials.value.map(m => m.number + ' ' + m.name))

const totalAmount = computed(() => {
  const sum = form.entries.reduce((acc, e) =>
    acc + (Number(e.qty) || 0) * (Number(e.taxPrice) || 0), 0)
  return sum.toFixed(2)
})

onLoad((options) => { editId.value = (options && options.orderId) || null })
onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({ url: '/pages/employee/login?redirect=' + encodeURIComponent('/pages/employee/purchase-order') })
    return
  }
  loadOptions()
  loadOrders()
})
onPullDownRefresh(async () => {
  await Promise.all([loadOptions(), loadOrders()])
  uni.stopPullDownRefresh()
})

async function loadOptions() {
  loading.value = true
  try {
    const [sp, wh, mt] = await Promise.all([
      api.scmSuppliers({ pageNum: 1, pageSize: 500 }),
      api.scmWarehouses(),
      api.scmMaterials({ pageNum: 1, pageSize: 1000 })
    ])
    suppliers.value = (sp && sp.records) || []
    warehouses.value = (wh && wh.records) || []
    materials.value = (mt && mt.records) || []
    if (suppliers.value.length === 1) form.supplierNumber = suppliers.value[0].supplierCode
    if (warehouses.value.length === 1) form.stockNumber = warehouses.value[0].number
    if (editId.value) await prefill(editId.value)
  } catch (e) {
    toast((e && e.msg) || '加载基础资料失败')
  } finally {
    loading.value = false
  }
}

async function loadOrders() {
  try {
    const data = await api.scmPurOrderList({ pageNum: 1, pageSize: 20 })
    orders.value = (data && data.records) || []
  } catch (e) {
    // 无 h5:create/h5:review 权限时保持空列表，避免阻断页面
    orders.value = []
  }
}

async function prefill(orderId) {
  const d = await api.scmPurOrderDetail(orderId)
  form.supplierNumber = d.supplierNumber || ''
  form.billDate = d.billDate || today()
  form.stockNumber = ((d.entries || []).find(e => e.stockNumber) || {}).stockNumber || ''
  form.dataSource = d.dataSource || 'DIRECT'
  form.entries = (d.entries || []).map(e => ({
    materialNumber: e.materialNumber,
    materialName: materialNameOf(e.materialNumber),
    qty: String(e.qty == null ? '' : e.qty),
    taxPrice: String(e.taxPrice == null ? '' : e.taxPrice),
    // 保留来源追溯：驳回后在 H5 修改数量/单价不能丢失业务数据/商城来源
    sourceReceiptNos: e.sourceReceiptNos,
    sourceOrderNos: e.sourceOrderNos,
    sourceMallOrderNo: e.sourceMallOrderNo
  }))
  if (!form.entries.length) form.entries = [emptyEntry()]
}

function materialNameOf(number) {
  const m = materials.value.find(x => x.number === number)
  return m ? m.name : ''
}
function materialIndex(entry) {
  return Math.max(0, materials.value.findIndex(m => m.number === entry.materialNumber))
}

function onSupplier(e) {
  const s = suppliers.value[Number(e.detail.value)]
  if (s) form.supplierNumber = s.supplierCode
}
function onWarehouse(e) {
  const w = warehouses.value[Number(e.detail.value)]
  if (w) form.stockNumber = w.number
}
function onMaterial(index, e) {
  const m = materials.value[Number(e.detail.value)]
  if (!m) return
  form.entries[index].materialNumber = m.number
  form.entries[index].materialName = m.name
}
function addEntry() { form.entries.push(emptyEntry()) }
function removeEntry(index) {
  if (form.entries.length === 1) return
  form.entries.splice(index, 1)
}
function lineAmount(entry) {
  return ((Number(entry.qty) || 0) * (Number(entry.taxPrice) || 0)).toFixed(2)
}

function editOrder(order) {
  if (order.approvalStatus === 'IN_APPROVAL') {
    refreshOne(order)
    return
  }
  if (!['REJECTED', 'NONE'].includes(order.approvalStatus)) {
    return toast(order.approvalStatus === 'APPROVED' ? '订单已审批通过' : '订单审批中，不可修改')
  }
  editId.value = order.id
  prefill(order.id)
  uni.pageScrollTo({ scrollTop: 0, duration: 200 })
}

/** 审批状态自愈：回调丢失时按底座实例补偿 */
async function refreshOne(order) {
  try {
    const d = await api.scmPurOrderRefresh(order.id)
    if (d.approvalStatus !== 'IN_APPROVAL') {
      toast('审批状态已同步：' + approvalLabel(d.approvalStatus))
      loadOrders()
    } else {
      toast('审批中，请等待总经办核审')
    }
  } catch (e) {
    toast((e && e.msg) || '刷新审批状态失败')
  }
}
function resetForm() {
  editId.value = null
  form.supplierNumber = suppliers.value.length === 1 ? suppliers.value[0].supplierCode : ''
  form.stockNumber = warehouses.value.length === 1 ? warehouses.value[0].number : ''
  form.billDate = today()
  form.entries = [emptyEntry()]
}

async function submit() {
  if (submitting.value) return
  if (!form.supplierNumber) return toast('请选择供应商')
  if (!form.stockNumber) return toast('请选择收货仓库')
  const entries = form.entries.filter(e => e.materialNumber)
  if (!entries.length) return toast('请至少添加一条商品分录')
  if (form.entries.some(e => !e.materialNumber && ((Number(e.qty) || 0) > 0 || Number(e.taxPrice) > 0))) {
    return toast('存在未选择商品的明细行，请选择商品')
  }
  for (const e of entries) {
    if (!(Number(e.qty) > 0)) return toast('数量必须大于 0：' + e.materialNumber)
    if (e.taxPrice === '' || e.taxPrice === null || Number(e.taxPrice) < 0) {
      return toast('采购单价必填：' + e.materialNumber)
    }
  }
  submitting.value = true
  try {
    const payload = {
      supplierNumber: form.supplierNumber,
      billDate: form.billDate,
      dataSource: form.dataSource || 'DIRECT',
      entries: entries.map(e => ({
        materialNumber: e.materialNumber,
        stockNumber: form.stockNumber,
        qty: Number(e.qty),
        taxPrice: Number(e.taxPrice),
        sourceReceiptNos: e.sourceReceiptNos,
        sourceOrderNos: e.sourceOrderNos,
        sourceMallOrderNo: e.sourceMallOrderNo
      }))
    }
    if (editId.value) {
      await api.scmPurOrderUpdate(editId.value, payload)
      await api.scmPurOrderSubmit(editId.value)
    } else {
      await api.scmPurOrderCreate(payload)
    }
    toast('已提交总经办审批')
    resetForm()
    loadOrders()
  } catch (e) {
    toast((e && e.msg) || '提交失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function approvalLabel(status) {
  return { NONE: '待提交', IN_APPROVAL: '审批中', APPROVED: '已通过', REJECTED: '已驳回' }[status] || status || '-'
}
function statusClass(status) {
  return { IN_APPROVAL: 'warn', APPROVED: 'ok', REJECTED: 'danger' }[status] || ''
}
function money(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}
function toast(title) { uni.showToast({ title, icon: 'none', duration: 2500 }) }
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f6f8; padding-bottom: 40rpx; }
.head { padding: 36rpx 32rpx 20rpx; background: linear-gradient(135deg, #FF6B35, #E85D2C); }
.title { display: block; font-size: 40rpx; font-weight: 700; color: #fff; }
.sub { display: block; margin-top: 8rpx; font-size: 24rpx; color: rgba(255,255,255,.85); }
.state { padding: 120rpx; text-align: center; color: #86909C; font-size: 28rpx; }
.card { background: #fff; border-radius: 20rpx; margin: 24rpx; padding: 26rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.04); }
.card-title { display: block; font-size: 30rpx; font-weight: 700; color: #1D2129; margin-bottom: 18rpx; }
.field { margin-bottom: 20rpx; }
.label { display: block; font-size: 26rpx; color: #4E5969; margin-bottom: 10rpx; }
.req { color: #F53F3F; }
.picker { padding: 20rpx 22rpx; background: #f7f8fa; border-radius: 12rpx; font-size: 27rpx; color: #1D2129; }
.entry { border: 1rpx solid #e5e6eb; border-radius: 14rpx; padding: 16rpx; margin-bottom: 14rpx; }
.row2 { display: flex; gap: 12rpx; margin-top: 12rpx; align-items: center; }
.input { flex: 1; padding: 16rpx; background: #f7f8fa; border-radius: 10rpx; font-size: 27rpx; }
.remove { color: #F53F3F; font-size: 24rpx; padding: 0 8rpx; }
.line-total { display: block; margin-top: 10rpx; font-size: 24rpx; color: #86909C; text-align: right; }
.add { padding: 18rpx; text-align: center; color: #FF6B35; border: 1rpx dashed #FF6B35; border-radius: 12rpx; font-size: 26rpx; }
.total { text-align: right; font-size: 28rpx; margin: 18rpx 0; }
.money { color: #F53F3F; font-weight: 700; }
.btn { border-radius: 44rpx; font-size: 30rpx; }
.btn.primary { background: #FF6B35; color: #fff; }
.cancel-edit { margin-top: 16rpx; text-align: center; color: #86909C; font-size: 25rpx; }
.empty { padding: 40rpx; text-align: center; color: #86909C; font-size: 26rpx; }
.order { border-bottom: 1rpx solid #f0f0f0; padding: 18rpx 0; }
.order-top { display: flex; justify-content: space-between; align-items: center; }
.bill-no { font-size: 28rpx; font-weight: 600; color: #1D2129; }
.status { font-size: 24rpx; color: #86909C; }
.status.warn { color: #FF7D00; }
.status.ok { color: #00B42A; }
.status.danger { color: #F53F3F; }
.order-meta { display: block; margin-top: 6rpx; font-size: 24rpx; color: #86909C; }
.reject-tip { margin-top: 10rpx; padding: 12rpx 16rpx; background: #fff1f0; border-radius: 10rpx; color: #F53F3F; font-size: 23rpx; }
.approving-tip { margin-top: 10rpx; color: #FF7D00; font-size: 23rpx; }
</style>
