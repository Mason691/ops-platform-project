<template>
  <view class="page">
    <!-- ═══ Header ═══ -->
    <view class="cr-header">
      <text class="cr-title">📋 个人提成规则</text>
      <text class="cr-sub">按品类配置提成类型与比例</text>
    </view>

    <!-- ═══ Toolbar ═══ -->
    <view class="cr-toolbar">
      <view class="cr-filters">
        <picker class="cr-picker" :range="filterCategoryOptions" range-key="label" @change="onFilterCategory">
          <view class="cr-filter-input">{{ filterCategoryLabel || '全部品类' }}</view>
        </picker>
        <picker class="cr-picker" :range="statusOptions" range-key="label" @change="onFilterStatus">
          <view class="cr-filter-input">{{ statusLabel || '全部状态' }}</view>
        </picker>
      </view>
      <view class="cr-btn cr-btn--add" @click="openCreate">+ 新增规则</view>
    </view>

    <!-- ═══ List ═══ -->
    <view v-if="loading" class="cr-loading">加载中...</view>
    <view v-else-if="rules.length === 0" class="cr-empty">暂无规则，点击"新增规则"创建</view>
    <view v-else class="cr-list">
      <view v-for="r in rules" :key="r.id" class="cr-card">
        <view class="cr-card-top">
          <view class="cr-card-cat">{{ catLabel(r.category) }}</view>
          <view class="cr-card-type" :class="typeClass(r.commissionType)">{{ r.commissionType || '固定' }}</view>
          <view :class="['cr-card-status', r.status === 1 ? 'on' : 'off']">{{ r.status === 1 ? '启用' : '禁用' }}</view>
        </view>
        <view class="cr-card-body">
          <view class="cr-card-row" v-if="r.commissionType === '固定金额'">
            <text class="cr-card-label">提成金额</text>
            <text class="cr-card-val cr-card-val--amount">¥{{ r.commissionAmount || 0 }}</text>
          </view>
          <view class="cr-card-row" v-else>
            <text class="cr-card-label">提成比例</text>
            <text class="cr-card-val">{{ r.commissionRate || 0 }}%</text>
          </view>
          <view class="cr-card-row" v-if="r.ruleDesc">
            <text class="cr-card-label">规则说明</text>
            <text class="cr-card-val">{{ r.ruleDesc }}</text>
          </view>
        </view>
        <view class="cr-card-actions">
          <text class="cr-action-edit" @click="openEdit(r)">编辑</text>
          <text class="cr-action-del" @click="doDelete(r)">删除</text>
        </view>
      </view>
    </view>

    <!-- ═══ Pagination ═══ -->
    <view v-if="total > pageSize" class="cr-pager">
      <view class="cr-btn cr-btn--page" :class="{ disabled: pageNum <= 1 }" @click="goPage(pageNum - 1)">上一页</view>
      <text class="cr-page-info">{{ pageNum }} / {{ totalPages }}</text>
      <view class="cr-btn cr-btn--page" :class="{ disabled: pageNum >= totalPages }" @click="goPage(pageNum + 1)">下一页</view>
    </view>

    <!-- ═══ Form Modal ═══ -->
    <view v-if="showForm" class="cr-overlay" @click.self="closeForm">
      <view class="cr-modal">
        <view class="cr-modal-header">
          <text class="cr-modal-title">{{ editingId ? '编辑规则' : '新增规则' }}</text>
          <text class="cr-modal-close" @click="closeForm">✕</text>
        </view>
        <view class="cr-modal-body">
          <!-- 品类下拉 -->
          <view class="cr-field">
            <text class="cr-field-label">品类 <text class="cr-required">*</text></text>
            <picker :range="categoryOptions" range-key="label" :value="formCatIdx" @change="onCatChange">
              <view class="cr-field-input" :class="{ placeholder: formCatIdx < 0 }">
                {{ formCatIdx >= 0 ? categoryOptions[formCatIdx].label : '请选择品类' }}
              </view>
            </picker>
          </view>

          <!-- 提成类型 -->
          <view class="cr-field">
            <text class="cr-field-label">提成类型 <text class="cr-required">*</text></text>
            <picker :range="typeOptions" range-key="label" :value="formTypeIdx" @change="onTypeChange">
              <view class="cr-field-input">{{ typeOptions[formTypeIdx]?.label || '请选择' }}</view>
            </picker>
          </view>

          <!-- 提成金额（固定金额时显示） -->
          <view class="cr-field" v-if="form.commissionType === '固定金额'">
            <text class="cr-field-label">提成金额（元）<text class="cr-required">*</text></text>
            <input class="cr-field-input" v-model="form.commissionAmount" type="digit" placeholder="每笔固定提成金额" />
          </view>

          <!-- 提成比例（非固定金额时显示） -->
          <view class="cr-field" v-if="form.commissionType !== '固定金额'">
            <text class="cr-field-label">提成比例（%）</text>
            <input class="cr-field-input" v-model="form.commissionRate" type="digit" placeholder="例如：5" />
          </view>

          <!-- 规则说明 -->
          <view class="cr-field">
            <text class="cr-field-label">规则说明</text>
            <input class="cr-field-input" v-model="form.ruleDesc" placeholder="可选，如阶梯规则的具体描述" />
          </view>

          <!-- 优先级 -->
          <view class="cr-field">
            <text class="cr-field-label">优先级</text>
            <input class="cr-field-input" v-model="form.priority" type="number" placeholder="数字越大越优先" />
          </view>

          <!-- 状态 -->
          <view class="cr-field">
            <text class="cr-field-label">状态</text>
            <switch :checked="form.status === 1" @change="e => form.status = e.detail.value ? 1 : 0" color="#FF6B35" />
          </view>
        </view>
        <view class="cr-modal-footer">
          <view class="cr-btn cr-btn--cancel" @click="closeForm">取消</view>
          <view class="cr-btn cr-btn--save" :class="{ loading: saving }" @click="doSave">
            {{ saving ? '保存中...' : '保存' }}
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { api } from '@/api/index'

// ── State ──
const rules = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)

// Filters
const filterCategory = ref('')
const filterStatus = ref(null)

// Form
const showForm = ref(false)
const editingId = ref(null)
const saving = ref(false)
const form = reactive({
  category: '',
  commissionType: '固定',
  commissionRate: null,
  commissionAmount: null,
  ruleDesc: '',
  priority: 0,
  status: 1,
})
const formCatIdx = ref(-1)
const formTypeIdx = ref(0)

// Dict options
const categoryOptions = ref([])  // [{label, key}] from API
const typeOptions = [
  { label: '固定（按比例）', value: '固定' },
  { label: '阶梯', value: '阶梯' },
  { label: '固定金额', value: '固定金额' },
]

// Filter options
const filterCategoryOptions = computed(() => [{ label: '全部品类', key: '' }, ...categoryOptions.value.map(c => ({ label: c.label, key: c.key }))])
const statusOptions = [
  { label: '全部状态', value: null },
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
]

const filterCategoryLabel = computed(() => {
  const found = filterCategoryOptions.value.find(f => f.key === filterCategory.value)
  return found?.label || ''
})
const statusLabel = computed(() => {
  const found = statusOptions.find(s => s.value === filterStatus.value)
  return found?.label || ''
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

// ── Display helpers ──
function catLabel(key) {
  const c = categoryOptions.value.find(c => c.key === key)
  return c ? c.label : (key || '-')
}
function typeClass(t) {
  if (t === '固定金额') return 'cr-type-fixed'
  if (t === '阶梯') return 'cr-type-tier'
  return 'cr-type-pct'
}

// ── Init ──
onMounted(async () => {
  await loadCategories()
  await loadData()
})

async function loadCategories() {
  try {
    const list = await api.commissionCategories()
    if (Array.isArray(list)) {
      categoryOptions.value = list.map(item => ({
        label: item.itemValue || item.itemKey,
        key: item.itemKey,
      }))
    }
  } catch (e) { console.warn('[commission-rules] load categories failed:', e) }
}

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (filterCategory.value) params.category = filterCategory.value
    if (filterStatus.value !== null) params.status = filterStatus.value
    const data = await api.commissionPersonalList(params)
    rules.value = data.records || []
    total.value = data.total || 0
  } catch (e) { console.error('[commission-rules] load failed:', e) }
  finally { loading.value = false }
}

// ── Filter ──
function onFilterCategory(e) { filterCategory.value = filterCategoryOptions.value[e.detail.value]?.key || ''; pageNum.value = 1; loadData() }
function onFilterStatus(e) { filterStatus.value = statusOptions[e.detail.value]?.value ?? null; pageNum.value = 1; loadData() }

// ── Pagination ──
function goPage(n) { if (n < 1 || n > totalPages.value) return; pageNum.value = n; loadData() }

// ── Form ──
function openCreate() {
  editingId.value = null
  Object.assign(form, { category: '', commissionType: '固定', commissionRate: null, commissionAmount: null, ruleDesc: '', priority: 0, status: 1 })
  formCatIdx.value = -1
  formTypeIdx.value = 0
  showForm.value = true
}

function openEdit(r) {
  editingId.value = r.id
  Object.assign(form, {
    category: r.category || '',
    commissionType: r.commissionType || '固定',
    commissionRate: r.commissionRate,
    commissionAmount: r.commissionAmount,
    ruleDesc: r.ruleDesc || '',
    priority: r.priority || 0,
    status: r.status ?? 1,
  })
  formCatIdx.value = categoryOptions.value.findIndex(c => c.key === r.category)
  formTypeIdx.value = typeOptions.findIndex(t => t.value === (r.commissionType || '固定'))
  if (formTypeIdx.value < 0) formTypeIdx.value = 0
  showForm.value = true
}

function closeForm() { showForm.value = false }

function onCatChange(e) {
  formCatIdx.value = e.detail.value
  form.category = categoryOptions.value[e.detail.value]?.key || ''
}
function onTypeChange(e) {
  formTypeIdx.value = e.detail.value
  form.commissionType = typeOptions[e.detail.value]?.value || '固定'
  // Clear irrelevant field when switching type
  if (form.commissionType === '固定金额') {
    form.commissionRate = null
  } else {
    form.commissionAmount = null
  }
}

async function doSave() {
  if (!form.category) { uni.showToast({ title: '请选择品类', icon: 'none' }); return }
  if (form.commissionType === '固定金额' && (!form.commissionAmount || parseFloat(form.commissionAmount) <= 0)) {
    uni.showToast({ title: '固定金额类型必须填写提成金额且大于0', icon: 'none' }); return
  }
  saving.value = true
  try {
    const payload = { ...form }
    if (form.commissionType !== '固定金额') payload.commissionAmount = null
    if (form.commissionType === '固定金额') payload.commissionRate = null
    if (payload.priority === '' || payload.priority === null) payload.priority = 0
    if (editingId.value) {
      await api.commissionPersonalUpdate(editingId.value, payload)
    } else {
      await api.commissionPersonalCreate(payload)
    }
    uni.showToast({ title: '保存成功', icon: 'success' })
    closeForm()
    loadData()
  } catch (e) {
    uni.showToast({ title: e?.msg || '保存失败', icon: 'none' })
  } finally { saving.value = false }
}

async function doDelete(r) {
  const ok = await new Promise(resolve => {
    uni.showModal({
      title: '确认删除',
      content: `确定删除品类「${catLabel(r.category)}」的提成规则吗？`,
      success: res => resolve(res.confirm),
    })
  })
  if (!ok) return
  try {
    await api.commissionPersonalDelete(r.id)
    uni.showToast({ title: '已删除', icon: 'success' })
    loadData()
  } catch (e) { uni.showToast({ title: '删除失败', icon: 'none' }) }
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding-bottom: 40rpx; }

/* Header */
.cr-header { background: linear-gradient(135deg, #FF6B35, #FF8C5A); padding: 60rpx 40rpx 40rpx; color: #fff; }
.cr-title { display: block; font-size: 40rpx; font-weight: 700; }
.cr-sub { display: block; font-size: 26rpx; opacity: .8; margin-top: 8rpx; }

/* Toolbar */
.cr-toolbar { padding: 20rpx 30rpx; display: flex; justify-content: space-between; align-items: center; gap: 16rpx; }
.cr-filters { display: flex; gap: 12rpx; flex: 1; }
.cr-picker { flex: 1; }
.cr-filter-input { height: 64rpx; line-height: 64rpx; background: #fff; border-radius: 12rpx; padding: 0 16rpx; font-size: 26rpx; border: 1rpx solid #e0e0e0; color: #333; text-align: center; }

/* Buttons */
.cr-btn { height: 64rpx; line-height: 64rpx; padding: 0 24rpx; border-radius: 12rpx; font-size: 26rpx; text-align: center; }
.cr-btn--add { background: #FF6B35; color: #fff; white-space: nowrap; }
.cr-btn--page { background: #fff; border: 1rpx solid #ddd; color: #333; }
.cr-btn.disabled { opacity: .5; pointer-events: none; }
.cr-btn--cancel { background: #f0f0f0; color: #666; flex: 1; }
.cr-btn--save { background: #FF6B35; color: #fff; flex: 1; height: 80rpx; line-height: 80rpx; }
.cr-btn.loading { opacity: .7; pointer-events: none; }

/* List */
.cr-loading, .cr-empty { text-align: center; padding: 120rpx 0; color: #999; font-size: 28rpx; }
.cr-list { margin: 0 30rpx; display: flex; flex-direction: column; gap: 16rpx; }
.cr-card { background: #fff; border-radius: 16rpx; padding: 24rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.06); }
.cr-card-top { display: flex; align-items: center; gap: 16rpx; margin-bottom: 16rpx; }
.cr-card-cat { font-size: 30rpx; font-weight: 600; color: #333; }
.cr-card-type { padding: 4rpx 16rpx; border-radius: 8rpx; font-size: 22rpx; }
.cr-type-pct { background: #e8f5e9; color: #2e7d32; }
.cr-type-tier { background: #fff3e0; color: #e65100; }
.cr-type-fixed { background: #e3f2fd; color: #1565c0; }
.cr-card-status { padding: 4rpx 16rpx; border-radius: 8rpx; font-size: 22rpx; }
.cr-card-status.on { background: #e8f5e9; color: #2e7d32; }
.cr-card-status.off { background: #ffebee; color: #c62828; }
.cr-card-body { display: flex; flex-direction: column; gap: 8rpx; }
.cr-card-row { display: flex; gap: 16rpx; }
.cr-card-label { font-size: 24rpx; color: #999; min-width: 120rpx; }
.cr-card-val { font-size: 24rpx; color: #333; }
.cr-card-val--amount { color: #FF6B35; font-weight: 600; }
.cr-card-actions { display: flex; justify-content: flex-end; gap: 24rpx; margin-top: 16rpx; padding-top: 16rpx; border-top: 1rpx solid #f0f0f0; }
.cr-action-edit { font-size: 26rpx; color: #FF6B35; }
.cr-action-del { font-size: 26rpx; color: #e53935; }

/* Pager */
.cr-pager { display: flex; align-items: center; justify-content: center; gap: 24rpx; padding: 30rpx; }
.cr-page-info { font-size: 26rpx; color: #666; }

/* Modal */
.cr-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 999; }
.cr-modal { background: #fff; border-radius: 24rpx; width: 88%; max-width: 640rpx; max-height: 80vh; display: flex; flex-direction: column; }
.cr-modal-header { display: flex; align-items: center; justify-content: space-between; padding: 36rpx 36rpx 20rpx; border-bottom: 1rpx solid #f0f0f0; }
.cr-modal-title { font-size: 34rpx; font-weight: 700; color: #333; }
.cr-modal-close { font-size: 36rpx; color: #999; padding: 8rpx; }
.cr-modal-body { padding: 36rpx; overflow-y: auto; flex: 1; }
.cr-modal-footer { display: flex; gap: 16rpx; padding: 0 36rpx 36rpx; justify-content: center; }

/* Form fields */
.cr-field { margin-bottom: 28rpx; }
.cr-field-label { display: block; font-size: 26rpx; color: #333; margin-bottom: 12rpx; font-weight: 500; }
.cr-required { color: #e53935; }
.cr-field-input { height: 80rpx; background: #f9f9f9; border: 1rpx solid #e0e0e0; border-radius: 12rpx; padding: 0 20rpx; font-size: 28rpx; color: #333; display: flex; align-items: center; }
.cr-field-input.placeholder { color: #bbb; }
</style>
