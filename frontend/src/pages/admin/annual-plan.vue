<template>
  <view class="page">
    <!-- ═══ Header ═══ -->
    <view class="ap-header">
      <text class="ap-title">📋 年度任务</text>
      <text class="ap-sub">集团年度销售计划管理</text>
    </view>

    <!-- ═══ Summary ═══ -->
    <view class="ap-stats" v-if="!loading">
      <view class="ap-stat-item">
        <text class="ap-stat-num">¥{{ fmt(summary.totalAmount) }}</text>
        <text class="ap-stat-label">年度计划总金额</text>
      </view>
      <view class="ap-stat-item">
        <text class="ap-stat-num">{{ summary.orgCount || 0 }}</text>
        <text class="ap-stat-label">子公司数</text>
      </view>
      <view class="ap-stat-item">
        <text class="ap-stat-num">{{ categorySummary.length }}</text>
        <text class="ap-stat-label">品类数</text>
      </view>
    </view>

    <!-- ═══ Category Summary ═══ -->
    <view class="ap-section" v-if="categorySummary.length">
      <view class="ap-section-title">品类汇总</view>
      <view class="ap-cat-list">
        <view class="ap-cat-item" v-for="c in categorySummary" :key="c.category">
          <text class="ap-cat-name">{{ c.categoryName }}</text>
          <text class="ap-cat-amt">¥{{ fmt(c.total) }}</text>
        </view>
      </view>
    </view>

    <!-- ═══ Toolbar ═══ -->
    <view class="ap-toolbar">
      <view class="ap-filters">
        <picker class="ap-filter" mode="selector" :range="orgRange" @change="onOrgChange">
          <text>{{ filterOrgLabel || '全部子公司' }} ▾</text>
        </picker>
        <picker class="ap-filter" mode="selector" :range="catRange" @change="onCatChange">
          <text>{{ filterCatLabel || '全部分类' }} ▾</text>
        </picker>
        <view class="ap-btn ap-btn--search" @click="search">搜索</view>
      </view>
      <view class="ap-actions" v-if="canEdit">
        <view class="ap-btn ap-btn--import" @click="showImport = true">📥 导入</view>
        <view class="ap-btn ap-btn--add" @click="openForm()">➕ 新增</view>
      </view>
    </view>

    <!-- ═══ Table ═══ -->
    <view v-if="loading" class="ap-loading">加载中...</view>
    <view v-else-if="records.length === 0" class="ap-empty">暂无年度计划数据</view>
    <view v-else class="ap-list">
      <view class="ap-card" v-for="d in records" :key="d.id">
        <view class="ap-card-row">
          <text class="ap-card-org">{{ d.orgName }}</text>
          <text :class="['ap-card-status', d.status === 1 ? 'ok' : 'pending']">
            {{ d.status === 1 ? '已审核' : '待审核' }}
          </text>
        </view>
        <view class="ap-card-row">
          <text class="ap-card-label">品类</text>
          <text class="ap-card-val">{{ d.categoryName }}</text>
        </view>
        <view class="ap-card-row">
          <text class="ap-card-label">计划金额</text>
          <text class="ap-card-amt">¥{{ fmt(d.planAmount) }}</text>
        </view>
        <view class="ap-card-row" v-if="d.remark">
          <text class="ap-card-label">备注</text>
          <text class="ap-card-val">{{ d.remark }}</text>
        </view>
        <view class="ap-card-actions" v-if="canEdit">
          <view class="ap-card-btn" @click="openForm(d)" :class="{ disabled: d.status === 1 }">编辑</view>
          <view class="ap-card-btn warn" v-if="d.status === 0" @click="doApprove(d.id)">审核</view>
          <view class="ap-card-btn danger" @click="doDelete(d.id)">删除</view>
        </view>
      </view>
    </view>

    <Pager v-model:pageNum="pageNum" v-model:pageSize="pageSize" :total="total" @change="loadData" />

    <!-- ═══ Form Modal ═══ -->
    <view v-if="showForm" class="ap-overlay" @click.self="showForm = false">
      <view class="ap-modal">
        <view class="ap-modal-header">
          <text class="ap-modal-title">{{ editingId ? '编辑计划' : '新增计划' }}</text>
          <text class="ap-modal-close" @click="showForm = false">✕</text>
        </view>
        <view class="ap-modal-body">
          <view class="ap-field">
            <text class="ap-field-label">子公司</text>
            <picker mode="selector" :range="orgRange" :disabled="!!editingId" @change="onFormOrgChange">
              <text :class="['ap-field-val', editingId ? 'disabled' : '']">{{ formOrgLabel || '请选择' }} ▾</text>
            </picker>
          </view>
          <view class="ap-field">
            <text class="ap-field-label">品类</text>
            <picker mode="selector" :range="catRange" @change="onFormCatChange">
              <text class="ap-field-val">{{ formCatLabel || '请选择' }} ▾</text>
            </picker>
          </view>
          <view class="ap-field">
            <text class="ap-field-label">计划金额</text>
            <input class="ap-input" v-model="formAmount" type="digit" placeholder="请输入金额" />
          </view>
          <view class="ap-field">
            <text class="ap-field-label">备注</text>
            <input class="ap-input" v-model="formRemark" placeholder="备注（选填）" />
          </view>
        </view>
        <view class="ap-modal-footer">
          <view class="ap-btn ap-btn--cancel" @click="showForm = false">取消</view>
          <view class="ap-btn ap-btn--save" @click="doSave">保存</view>
        </view>
      </view>
    </view>

    <!-- ═══ Import Modal ═══ -->
    <view v-if="showImport" class="ap-overlay" @click.self="showImport = false">
      <view class="ap-modal">
        <view class="ap-modal-header">
          <text class="ap-modal-title">导入年度计划</text>
          <text class="ap-modal-close" @click="showImport = false">✕</text>
        </view>
        <view class="ap-modal-body">
          <view class="ap-import-hint">
            <text>上传 Excel 文件 (.xlsx)，表头需包含：子公司、品类、计划金额</text>
          </view>
          <view class="ap-file-area" @click="selectFile">
            <text class="ap-file-icon">📁</text>
            <text v-if="!importFileName" class="ap-file-hint">点击选择 Excel 文件</text>
            <text v-else class="ap-file-name">{{ importFileName }}</text>
          </view>
          <view v-if="importResult" class="ap-import-result" :class="importResult.status === 'success' ? 'ok' : 'fail'">
            <text>{{ importResult.status === 'success' ? '导入成功！共 ' + importResult.count + ' 条' : '导入失败' }}</text>
            <text v-for="(e, i) in (importResult.errors || [])" :key="i" class="ap-import-err">{{ e }}</text>
          </view>
        </view>
        <view class="ap-modal-footer">
          <view class="ap-btn ap-btn--cancel" @click="showImport = false">关闭</view>
          <view class="ap-btn ap-btn--save" @click="doImport" :class="{ disabled: !importFile }">开始导入</view>
        </view>
        <!-- hidden file input -->
        <input
          ref="fileInputRef"
          type="file"
          accept=".xlsx,.xls"
          style="display:none"
          @change="onFilePicked"
        />
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/index'
import Pager from '@/components/Pager.vue'

// ═══ State ═══
const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const canEdit = ref(false)

// Summary
const summary = ref({ totalAmount: 0, orgCount: 0 })
const categorySummary = ref([])

// Filters
const filterOrgId = ref(null)
const filterOrgLabel = ref('')
const filterCategory = ref('')
const filterCatLabel = ref('')
const orgOptions = ref([])
const catOptions = ref([])

// Form
const showForm = ref(false)
const editingId = ref(null)
const formOrgId = ref(null)
const formOrgLabel = ref('')
const formCatKey = ref('')
const formCatLabel = ref('')
const formAmount = ref('')
const formRemark = ref('')

// Import
const showImport = ref(false)
const importFile = ref(null)
const importFileName = ref('')
const importResult = ref(null)

// Computed
const orgRange = ref([])
const catRange = ref([])

onMounted(async () => {
  await loadPermission()
  await loadData()
  await loadSummary()
})

async function loadPermission() {
  try {
    const prof = await api.permissionProfile()
    canEdit.value = (prof && prof.orgRoleType !== 'partner')
  } catch { canEdit.value = false }
}

async function loadSummary() {
  try {
    const s = await api.annualPlanSummary()
    const sc = await api.annualPlanSummaryByCategory()
    summary.value = s || {}
    categorySummary.value = (sc && sc.categories) ? sc.categories : []
  } catch { /* ignore */ }
}

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (filterOrgId.value) params.orgId = filterOrgId.value
    if (filterCategory.value) params.category = filterCategory.value
    const data = await api.annualPlanList(params)
    records.value = data.records || []
    total.value = data.total || 0
    // Update picker options
    updatePickerOptions(records.value)
  } catch { /* ignore */ }
  loading.value = false
}

function updatePickerOptions(list) {
  const orgs = [{ label: '全部子公司', value: null }]
  const cats = [{ label: '全部分类', value: '' }]
  const seenOrg = new Set()
  const seenCat = new Set()
  list.forEach(r => {
    if (!seenOrg.has(r.orgId)) {
      seenOrg.add(r.orgId)
      orgs.push({ label: r.orgName || String(r.orgId), value: r.orgId })
    }
    if (!seenCat.has(r.category)) {
      seenCat.add(r.category)
      cats.push({ label: r.categoryName || r.category, value: r.category })
    }
  })
  orgOptions.value = orgs
  catOptions.value = cats
  orgRange.value = orgs.map(o => o.label)
  catRange.value = cats.map(c => c.label)
}

function search() { pageNum.value = 1; loadData() }

// ═══ Picker handlers ═══
function onOrgChange(e) {
  const idx = e.detail.value
  filterOrgId.value = orgOptions.value[idx]?.value
  filterOrgLabel.value = orgOptions.value[idx]?.label
}
function onCatChange(e) {
  const idx = e.detail.value
  filterCategory.value = catOptions.value[idx]?.value
  filterCatLabel.value = catOptions.value[idx]?.label
}

// ═══ Form ═══
function openForm(row) {
  if (row && row.id) {
    editingId.value = row.id
    formOrgId.value = row.orgId
    formOrgLabel.value = row.orgName || String(row.orgId)
    formCatKey.value = row.category
    formCatLabel.value = row.categoryName || row.category
    formAmount.value = String(row.planAmount || '')
    formRemark.value = row.remark || ''
    // Sync picker ranges
    orgRange.value = orgOptions.value.map(o => o.label)
    catRange.value = catOptions.value.map(c => c.label)
  } else {
    editingId.value = null
    formOrgId.value = null
    formOrgLabel.value = ''
    formCatKey.value = ''
    formCatLabel.value = ''
    formAmount.value = ''
    formRemark.value = ''
  }
  showForm.value = true
}

function onFormOrgChange(e) {
  const idx = e.detail.value
  formOrgId.value = orgOptions.value[idx]?.value
  formOrgLabel.value = orgOptions.value[idx]?.label
}
function onFormCatChange(e) {
  const idx = e.detail.value
  formCatKey.value = catOptions.value[idx]?.value
  formCatLabel.value = catOptions.value[idx]?.label
}

async function doSave() {
  if (!formOrgId.value || !formCatKey.value || !formAmount.value) {
    uni.showToast({ title: '请填写完整信息', icon: 'none' })
    return
  }
  const payload = {
    orgId: formOrgId.value,
    category: formCatKey.value,
    planAmount: parseFloat(formAmount.value)
  }
  if (formRemark.value) payload.remark = formRemark.value

  try {
    if (editingId.value) {
      await api.annualPlanUpdate(editingId.value, payload)
    } else {
      await api.annualPlanCreate(payload)
    }
    uni.showToast({ title: '保存成功', icon: 'success' })
    showForm.value = false
    loadData()
    loadSummary()
  } catch (e) {
    uni.showToast({ title: e.msg || '保存失败', icon: 'none' })
  }
}

async function doDelete(id) {
  const ok = await new Promise(resolve => {
    uni.showModal({ title: '确认删除', content: '确定删除此计划？', success: r => resolve(r.confirm) })
  })
  if (!ok) return
  try {
    await api.annualPlanDelete(id)
    uni.showToast({ title: '已删除', icon: 'success' })
    loadData()
    loadSummary()
  } catch (e) {
    uni.showToast({ title: e.msg || '删除失败', icon: 'none' })
  }
}

async function doApprove(id) {
  try {
    await api.annualPlanApprove(id)
    uni.showToast({ title: '审核通过', icon: 'success' })
    loadData()
    loadSummary()
  } catch (e) {
    uni.showToast({ title: e.msg || '审核失败', icon: 'none' })
  }
}

// ═══ Import ═══
function selectFile() {
  // Trigger hidden file input
  const el = document.querySelector('.ap-overlay input[type="file"]')
  if (el) el.click()
}
function onFilePicked(e) {
  const file = e.target?.files?.[0]
  if (file) {
    importFile.value = file
    importFileName.value = file.name
  }
}
async function doImport() {
  if (!importFile.value) return
  try {
    const result = await api.annualPlanImport(importFile.value)
    importResult.value = result
    if (result && result.status === 'success') {
      loadData()
      loadSummary()
    }
  } catch (e) {
    importResult.value = { status: 'fail', errors: [e.msg || '导入失败'] }
  }
}

function fmt(v) {
  if (v === null || v === undefined) return '0.00'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.page { padding:20rpx; background:#f5f5f5; min-height:100vh; }
.ap-header { background:linear-gradient(135deg,#FF6B35,#FF8C5A); padding:30rpx; border-radius:16rpx; margin-bottom:20rpx; color:#fff; }
.ap-title { font-size:40rpx; font-weight:700; display:block; }
.ap-sub { font-size:24rpx; opacity:.85; margin-top:4rpx; }
.ap-stats { display:flex; gap:16rpx; margin-bottom:20rpx; }
.ap-stat-item { flex:1; background:#fff; border-radius:12rpx; padding:24rpx 16rpx; text-align:center; box-shadow:0 2rpx 12rpx rgba(0,0,0,.06); }
.ap-stat-num { font-size:32rpx; font-weight:700; color:#303133; display:block; }
.ap-stat-label { font-size:22rpx; color:#909399; margin-top:4rpx; }
.ap-section { background:#fff; border-radius:12rpx; padding:20rpx; margin-bottom:20rpx; }
.ap-section-title { font-size:28rpx; font-weight:600; margin-bottom:12rpx; }
.ap-cat-list { display:flex; flex-wrap:wrap; gap:12rpx; }
.ap-cat-item { display:flex; justify-content:space-between; background:#f5f7fa; border-radius:8rpx; padding:12rpx 16rpx; min-width:45%; }
.ap-cat-name { font-size:24rpx; color:#606266; }
.ap-cat-amt { font-size:24rpx; font-weight:600; color:#FF6B35; }
.ap-toolbar { display:flex; flex-wrap:wrap; gap:12rpx; margin-bottom:20rpx; justify-content:space-between; }
.ap-filters { display:flex; gap:12rpx; }
.ap-filter { background:#fff; border-radius:8rpx; padding:12rpx 16rpx; font-size:24rpx; color:#606266; }
.ap-actions { display:flex; gap:12rpx; }
.ap-btn { padding:12rpx 20rpx; border-radius:8rpx; font-size:24rpx; font-weight:500; }
.ap-btn--search { background:#FF6B35; color:#fff; }
.ap-btn--add { background:#67C23A; color:#fff; }
.ap-btn--import { background:#409EFF; color:#fff; }
.ap-btn--cancel { background:#f5f5f5; color:#606266; }
.ap-btn--save { background:#FF6B35; color:#fff; }
.ap-btn.disabled { opacity:.5; pointer-events:none; }
.ap-loading { text-align:center; padding:60rpx; color:#909399; }
.ap-empty { text-align:center; padding:60rpx; color:#909399; font-size:28rpx; }
.ap-list { display:flex; flex-direction:column; gap:16rpx; }
.ap-card { background:#fff; border-radius:12rpx; padding:20rpx; box-shadow:0 2rpx 12rpx rgba(0,0,0,.04); }
.ap-card-row { display:flex; justify-content:space-between; align-items:center; padding:6rpx 0; }
.ap-card-org { font-size:28rpx; font-weight:600; color:#303133; }
.ap-card-status { font-size:22rpx; padding:2rpx 12rpx; border-radius:8rpx; }
.ap-card-status.ok { background:#e1f3d8; color:#67C23A; }
.ap-card-status.pending { background:#fdf6ec; color:#E6A23C; }
.ap-card-label { font-size:24rpx; color:#909399; }
.ap-card-val { font-size:24rpx; color:#606266; }
.ap-card-amt { font-size:28rpx; font-weight:700; color:#FF6B35; }
.ap-card-actions { display:flex; gap:12rpx; margin-top:12rpx; padding-top:12rpx; border-top:1rpx solid #ebeef5; }
.ap-card-btn { font-size:22rpx; padding:6rpx 16rpx; border-radius:6rpx; background:#ecf5ff; color:#409EFF; }
.ap-card-btn.warn { background:#fdf6ec; color:#E6A23C; }
.ap-card-btn.danger { background:#fef0f0; color:#F56C6C; }
.ap-card-btn.disabled { opacity:.4; pointer-events:none; }
.ap-overlay { position:fixed; inset:0; background:rgba(0,0,0,.4); display:flex; align-items:flex-end; justify-content:center; z-index:999; }
.ap-overlay.locked { pointer-events:none; }
.ap-modal { background:#fff; border-radius:16rpx 16rpx 0 0; width:100%; max-height:70vh; overflow-y:auto; padding-bottom:env(safe-area-inset-bottom); }
.ap-modal-header { padding:24rpx 30rpx; border-bottom:1rpx solid #ebeef5; display:flex; justify-content:space-between; align-items:center; }
.ap-modal-title { font-size:32rpx; font-weight:600; }
.ap-modal-close { font-size:36rpx; color:#909399; padding:8rpx; }
.ap-modal-body { padding:24rpx 30rpx; }
.ap-modal-footer { padding:16rpx 30rpx; display:flex; justify-content:flex-end; gap:16rpx; border-top:1rpx solid #ebeef5; }
.ap-field { margin-bottom:20rpx; }
.ap-field-label { font-size:24rpx; color:#606266; display:block; margin-bottom:8rpx; }
.ap-field-val { background:#f5f7fa; border-radius:8rpx; padding:16rpx; font-size:26rpx; color:#303133; display:block; }
.ap-field-val.disabled { color:#c0c4cc; }
.ap-input { background:#f5f7fa; border-radius:8rpx; padding:16rpx; font-size:26rpx; width:100%; box-sizing:border-box; }
.ap-import-hint { font-size:22rpx; color:#909399; margin-bottom:16rpx; line-height:1.6; }
.ap-file-area { background:#f5f7fa; border:2rpx dashed #dcdfe6; border-radius:12rpx; padding:40rpx; text-align:center; }
.ap-file-icon { font-size:48rpx; display:block; margin-bottom:12rpx; }
.ap-file-hint { font-size:24rpx; color:#c0c4cc; }
.ap-file-name { font-size:26rpx; color:#303133; word-break:break-all; }
.ap-import-result { margin-top:16rpx; padding:16rpx; border-radius:8rpx; }
.ap-import-result.ok { background:#e1f3d8; color:#67C23A; }
.ap-import-result.fail { background:#fef0f0; color:#F56C6C; }
.ap-import-err { display:block; font-size:22rpx; margin-top:4rpx; }
</style>
