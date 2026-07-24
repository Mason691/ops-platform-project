<template>
  <view class="page">
    <!-- ═══ Header ═══ -->
    <view class="gu-header">
      <text class="gu-title">🔥 燃气用户管理</text>
      <text class="gu-sub">查看和管理燃气用户小区归属</text>
    </view>

    <!-- ═══ Toolbar ═══ -->
    <view class="gu-toolbar">
      <view class="gu-filters">
        <input class="gu-filter" v-model="filterKeyword" placeholder="用户号/姓名/手机号/地址" confirm-type="search" @confirm="search" />
        <picker class="gu-filter gu-filter--picker" mode="selector" :range="communityPickerRange" range-key="name"
          @change="onCommunityFilterChange">
          <view class="gu-picker-val">{{ filterCommunityLabel }}</view>
        </picker>
        <view class="gu-btn gu-btn--search" @click="search">搜索</view>
      </view>
      <view class="gu-actions">
        <view class="gu-btn" :class="filterUnassigned ? 'gu-btn--unassigned-active' : 'gu-btn--unassigned'" @click="toggleUnassigned">🔍 未指定小区</view>
        <view class="gu-btn gu-btn--assign" :class="{ disabled: !selected.length }" @click="openBatchAssign">📋 批量指定小区</view>
        <view class="gu-btn gu-btn--import" @click="openImport">📥 从燃气平台导入</view>
        <view class="gu-btn gu-btn--convert" @click="openConvertConfirm">🔄 批量转化会员</view>
      </view>
    </view>

    <!-- ═══ Table ═══ -->
    <view v-if="loading" class="gu-loading">加载中...</view>
    <view v-else-if="records.length === 0" class="gu-empty">暂无燃气用户数据</view>
    <view v-else class="gu-table-wrap">
      <scroll-view scroll-x class="gu-scroll">
        <view class="gu-table">
          <view class="gu-tr gu-th">
            <view class="gu-td" style="width:60rpx">
              <input type="checkbox" :checked="allChecked" @click="toggleAll" />
            </view>
            <view class="gu-td" style="width:140rpx">用户号</view>
            <view class="gu-td" style="width:140rpx">姓名</view>
            <view class="gu-td" style="width:200rpx">手机号</view>
            <view class="gu-td" style="width:200rpx">区域</view>
            <view class="gu-td" style="width:200rpx">所属小区</view>
            <view class="gu-td" style="width:150rpx">操作</view>
          </view>
          <view v-for="d in records" :key="d.id" class="gu-tr">
            <view class="gu-td" style="width:60rpx">
              <input type="checkbox" :checked="selected.includes(d.id)" @click="toggleOne(d.id)" />
            </view>
            <view class="gu-td" style="width:140rpx">{{ d.userNo }}</view>
            <view class="gu-td" style="width:140rpx">{{ d.fullName || '-' }}</view>
            <view class="gu-td" style="width:200rpx">{{ d.phone || '-' }}</view>
            <view class="gu-td" style="width:200rpx">{{ d.areaName || '-' }}</view>
            <view class="gu-td" style="width:200rpx">
              <text :class="{ 'gu-community-bound': d.communityName }">{{ d.communityName || '(未指定)' }}</text>
            </view>
            <view class="gu-td" style="width:150rpx">
              <view class="gu-btn gu-btn--sm" @click="openSingleEdit(d)">编辑小区</view>
            </view>
          </view>
        </view>
      </scroll-view>
    </view>

    <Pager v-model:pageNum="pageNum" v-model:pageSize="pageSize" :total="total" @change="loadData" />

    <!-- ═══ Batch Assign Modal ═══ -->
    <view v-if="showBatchAssign" class="gu-overlay" @click.self="closeBatchAssign">
      <view class="gu-modal gu-modal--sm">
        <view class="gu-modal-header">
          <text class="gu-modal-title">批量指定小区</text>
          <text class="gu-modal-close" @click="closeBatchAssign">✕</text>
        </view>
        <view class="gu-modal-body">
          <view class="gu-field">
            <text class="gu-field-label">已选用户</text>
            <text class="gu-field-static"><text class="gu-highlight">{{ selected.length }}</text> 个用户</text>
          </view>
          <view class="gu-field">
            <text class="gu-field-label">目标小区 <text class="gu-required">*</text></text>
            <picker :range="communityPickerRange" range-key="name" :value="assignCommunityIdx" @change="onAssignCommunityChange">
              <view class="gu-field-input" :class="{ placeholder: assignCommunityIdx < 0 }">
                {{ assignCommunityIdx >= 0 ? communityPickerRange[assignCommunityIdx].name : '请选择目标小区' }}
              </view>
            </picker>
          </view>
        </view>
        <view class="gu-modal-footer">
          <view class="gu-btn gu-btn--cancel" @click="closeBatchAssign">取消</view>
          <view class="gu-btn gu-btn--confirm" :class="{ loading: assigning }" @click="doBatchAssign">
            {{ assigning ? '绑定中...' : '确认绑定' }}
          </view>
        </view>
      </view>
    </view>

    <!-- ═══ Single Edit Modal ═══ -->
    <view v-if="showSingleEdit" class="gu-overlay" @click.self="closeSingleEdit">
      <view class="gu-modal gu-modal--sm">
        <view class="gu-modal-header">
          <text class="gu-modal-title">编辑小区归属</text>
          <text class="gu-modal-close" @click="closeSingleEdit">✕</text>
        </view>
        <view class="gu-modal-body">
          <view class="gu-field">
            <text class="gu-field-label">用户</text>
            <text class="gu-field-static">{{ singleEditUser?.fullName || '-' }}（{{ singleEditUser?.userNo }}）</text>
          </view>
          <view v-if="singleEditUser?.communityName" class="gu-field">
            <text class="gu-field-label">当前小区</text>
            <text class="gu-field-static gu-current-community">{{ singleEditUser?.communityName }}</text>
          </view>
          <view class="gu-field">
            <text class="gu-field-label">目标小区 <text class="gu-required">*</text></text>
            <picker :range="communityPickerRange" range-key="name" :value="singleEditCommunityIdx" @change="onSingleEditCommunityChange">
              <view class="gu-field-input" :class="{ placeholder: singleEditCommunityIdx < 0 }">
                {{ singleEditCommunityIdx >= 0 ? communityPickerRange[singleEditCommunityIdx].name : '请选择小区' }}
              </view>
            </picker>
          </view>
        </view>
        <view class="gu-modal-footer">
          <view class="gu-btn gu-btn--cancel" @click="closeSingleEdit">取消</view>
          <view class="gu-btn gu-btn--confirm" :class="{ loading: savingSingle }" @click="doSingleEdit">
            {{ savingSingle ? '保存中...' : '保存' }}
          </view>
        </view>
      </view>
    </view>
  </view>

    <!-- ═══ Import Modal ═══ -->
    <view v-if="showImport" class="gu-overlay" @click.self="closeImport">
      <view class="gu-modal gu-modal--sm">
        <view class="gu-modal-header">
          <text class="gu-modal-title">从燃气平台导入</text>
          <text v-if="importState !== 'querying' && importState !== 'importing'" class="gu-modal-close" @click="closeImport">✕</text>
        </view>
        <view class="gu-modal-body" style="padding:36rpx">
          <!-- Company input (shown in idle, preview) -->
          <view v-if="importState === 'idle' || importState === 'preview'" class="gu-import-field">
            <text class="gu-field-label">燃气公司名称</text>
            <view style="display:flex;gap:16rpx">
              <input class="gu-field-input" style="flex:1" v-model="importCompanyName" placeholder="如：武山、德保" confirm-type="search" @confirm="doPreview" />
              <view class="gu-btn gu-btn--search" @click="doPreview" :class="{ loading: importState === 'querying' }">
                {{ importState === 'querying' ? '查询中...' : '查询' }}
              </view>
            </view>
          </view>
          <!-- Query result preview -->
          <view v-if="importState === 'preview' && importPreviewResult" style="margin-top:24rpx;text-align:center">
            <view class="gu-preview-card">
              <view class="gu-preview-row"><text class="gu-preview-label">外部数据库</text><text class="gu-preview-val">{{ importPreviewResult.totalCount }} 条</text></view>
              <view class="gu-preview-row"><text class="gu-preview-label">已有用户</text><text class="gu-preview-val gu-preview-existing">{{ importPreviewResult.existingCount }} 条</text></view>
              <view class="gu-preview-row" style="border-top:1rpx solid #f0f0f0;padding-top:12rpx"><text class="gu-preview-label" style="font-weight:600">预计新增</text><text class="gu-preview-val" style="font-weight:600;color:#FF6B35">{{ importPreviewResult.newCount }} 条</text></view>
            </view>
          </view>
          <!-- Importing: spinner -->
          <view v-if="importState === 'importing'" style="text-align:center;padding:20rpx 0">
            <text style="font-size:48rpx;display:block;margin-bottom:16rpx">⏳</text>
            <text class="gu-import-desc">{{ importMsg || '正在导入...' }}</text>
          </view>
          <!-- Done: result -->
          <view v-if="importState === 'done'" style="text-align:center;padding:20rpx 0">
            <text v-if="importResult?.status === 'success'" style="font-size:60rpx;display:block">✅</text>
            <text v-else style="font-size:60rpx;display:block">❌</text>
            <text :class="importResult?.status === 'success' ? 'gu-import-result' : 'gu-import-error-title'">{{ importMsg }}</text>
          </view>
        </view>
        <view class="gu-modal-footer">
          <view v-if="importState === 'preview'" class="gu-btn gu-btn--confirm" @click="doImport">确认导入</view>
          <view v-if="importState === 'done'" class="gu-btn gu-btn--confirm" @click="onImportDone">完成，刷新列表</view>
          <view v-if="importState === 'idle' || importState === 'preview' || importState === 'done'" class="gu-btn gu-btn--cancel" @click="closeImport">取消</view>
        </view>
      </view>
    </view>

    <!-- ═══ Convert Confirm Modal ═══ -->
    <view v-if="showConvertConfirm" class="gu-overlay" @click.self="closeConvertConfirm">
      <view class="gu-modal gu-modal--sm">
        <view class="gu-modal-header">
          <text class="gu-modal-title">批量转化会员</text>
          <text class="gu-modal-close" @click="closeConvertConfirm">✕</text>
        </view>
        <view class="gu-modal-body">
          <text class="gu-import-desc">将当前组织下所有<text class="gu-highlight">未转化</text>的燃气用户匹配并创建为平台会员。</text>
          <view style="margin-top:24rpx">
            <view class="gu-preview-card">
              <view class="gu-preview-row"><text class="gu-preview-label">转化方式</text><text class="gu-preview-val">按手机号自动匹配</text></view>
              <view class="gu-preview-row"><text class="gu-preview-label">处理模式</text><text class="gu-preview-val">异步后台处理</text></view>
              <view class="gu-preview-row"><text class="gu-preview-label">匹配逻辑</text><text class="gu-preview-val">手机号相同 → 自动关联</text></view>
              <view class="gu-preview-row"><text class="gu-preview-label">未匹配用户</text><text class="gu-preview-val">自动创建会员</text></view>
            </view>
          </view>
          <text style="display:block;margin-top:24rpx;font-size:24rpx;color:#999">⚠️ 任务提交后将在后台异步执行，您可关闭此页面继续其他操作。</text>
        </view>
        <view class="gu-modal-footer">
          <view class="gu-btn gu-btn--cancel" @click="closeConvertConfirm">取消</view>
          <view class="gu-btn gu-btn--confirm" :class="{ loading: convertState === 'submitting' }" @click="doConvertSubmit">
            {{ convertState === 'submitting' ? '提交中...' : '确认转化' }}
          </view>
        </view>
      </view>
    </view>

    <!-- ═══ Convert Progress/Result Modal ═══ -->
    <view v-if="showConvertProgress" class="gu-overlay">
      <view class="gu-modal gu-modal--sm">
        <view class="gu-modal-header">
          <text class="gu-modal-title">转化任务</text>
          <text v-if="convertState === 'done'" class="gu-modal-close" @click="closeConvertProgress">✕</text>
        </view>
        <view class="gu-modal-body">
          <!-- Submitting -->
          <view v-if="convertState === 'submitting'" style="text-align:center;padding:20rpx 0">
            <text style="font-size:48rpx;display:block;margin-bottom:16rpx">⏳</text>
            <text class="gu-import-desc">正在提交转化任务...</text>
          </view>
          <!-- Polling -->
          <view v-if="convertState === 'polling'" style="text-align:center;padding:20rpx 0">
            <text style="font-size:48rpx;display:block;margin-bottom:16rpx">⏳</text>
            <text class="gu-import-desc">转化任务已提交，正在后台处理中...</text>
            <text v-if="convertMsg" style="display:block;margin-top:12rpx;font-size:24rpx;color:#999">{{ convertMsg }}</text>
          </view>
          <!-- Done: success -->
          <view v-if="convertState === 'done' && convertResult?.status === 'success'" style="text-align:center;padding:20rpx 0">
            <text style="font-size:60rpx;display:block;margin-bottom:12rpx">✅</text>
            <view class="gu-preview-card">
              <view class="gu-preview-row"><text class="gu-preview-label">转化成功</text><text class="gu-preview-val" style="color:#07C160">{{ convertResult.successRows || 0 }} 人</text></view>
              <view class="gu-preview-row"><text class="gu-preview-label">已存在会员跳过</text><text class="gu-preview-val gu-preview-existing">{{ convertResult.existingCount || 0 }} 人</text></view>
              <view class="gu-preview-row"><text class="gu-preview-label">失败</text><text class="gu-preview-val" style="color:#e53935">{{ convertResult.failRows || 0 }} 人</text></view>
            </view>
            <view v-if="convertResult.failRows > 0 && convertResult.errors?.length" style="margin-top:12rpx;text-align:left">
              <view class="gu-btn gu-btn--xs" style="display:inline-block" @click="convertShowErrors = !convertShowErrors">
                {{ convertShowErrors ? '收起详情 ▲' : '展开失败详情 ▼' }}
              </view>
              <view v-if="convertShowErrors" style="margin-top:8rpx;max-height:300rpx;overflow-y:auto;background:#fff5f5;border-radius:8rpx;padding:16rpx">
                <view v-for="(err, i) in convertResult.errors" :key="i" style="font-size:22rpx;color:#e53935;padding:4rpx 0;border-bottom:1rpx solid #ffe0e0">{{ err }}</view>
              </view>
            </view>
          </view>
          <!-- Done: fail -->
          <view v-if="convertState === 'done' && convertResult?.status === 'fail'" style="text-align:center;padding:20rpx 0">
            <text style="font-size:60rpx;display:block;margin-bottom:12rpx">❌</text>
            <text class="gu-import-error-title">转化失败</text>
            <text class="gu-import-error" style="margin-top:8rpx">{{ convertMsg || '未知错误' }}</text>
          </view>
        </view>
        <view class="gu-modal-footer">
          <view v-if="convertState === 'polling'" class="gu-btn gu-btn--cancel" style="pointer-events:none;opacity:.5">处理中...</view>
          <view v-if="convertState === 'done'" class="gu-btn gu-btn--confirm" @click="onConvertDone">确定</view>
        </view>
      </view>
    </view>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '@/api/index'
import Pager from '@/components/Pager.vue'

// ── Main list state ──
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const selected = ref([])

// Filters
const filterKeyword = ref('')
const filterCommunityId = ref('')
const filterUnassigned = ref(false)
const communityOptions = ref([])       // raw [{id, name}] from API

// Community picker: prepend "全部小区" option
const communityPickerRange = computed(() => {
  return [{ id: '', name: '全部小区' }, ...communityOptions.value]
})

const filterCommunityLabel = computed(() => {
  if (!filterCommunityId.value) return '全部小区'
  const found = communityOptions.value.find(c => String(c.id) === String(filterCommunityId.value))
  return found ? found.name : '全部小区'
})

// ── Batch assign state ──
const showBatchAssign = ref(false)
const assignCommunityIdx = ref(-1)
const assigning = ref(false)

// ── Single edit state ──
const showSingleEdit = ref(false)
const singleEditUser = ref(null)
const singleEditCommunityIdx = ref(-1)
const savingSingle = ref(false)

// ── Import state ──
const showImport = ref(false)
const importState = ref('idle')       // idle | querying | preview | importing | done
const importMsg = ref('')
const importResult = ref(null)
const importCompanyName = ref('')
const importPreviewResult = ref(null)
let pollTimer = null
const importTaskId = ref(null)

// ── Convert state ──
const showConvertConfirm = ref(false)
const showConvertProgress = ref(false)
const convertState = ref('idle')       // idle | submitting | polling | done
const convertMsg = ref('')
const convertResult = ref(null)        // { successRows, failRows, skippedCount, errorMsg, errors }
const convertTaskId = ref(null)
const convertShowErrors = ref(false)   // toggle error detail expansion
let convertPollTimer = null

// ── Computed ──
const allChecked = computed(() => records.value.length > 0 && records.value.every(d => selected.value.includes(d.id)))

// ── Lifecycle ──
onMounted(async () => {
  await loadCommunities()
  await loadData()
})
onBeforeUnmount(() => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  if (convertPollTimer) { clearInterval(convertPollTimer); convertPollTimer = null }
})

// ── Data loading ──
async function loadCommunities() {
  try {
    const list = await api.gasUserCommunities()
    communityOptions.value = Array.isArray(list) ? list : []
  } catch (e) {
    console.warn('[gas-user] load communities failed:', e)
  }
}

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (filterKeyword.value) params.keyword = filterKeyword.value
    if (filterUnassigned.value) params.unassigned = true
    else if (filterCommunityId.value) params.communityId = Number(filterCommunityId.value)
    const data = await api.gasUserList(params)
    records.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    uni.showToast({ title: e?.msg || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function search() { pageNum.value = 1; loadData() }
function toggleUnassigned() {
  filterUnassigned.value = !filterUnassigned.value
  if (filterUnassigned.value) filterCommunityId.value = ''
  pageNum.value = 1
  loadData()
}

function onCommunityFilterChange(e) {
  const idx = Number(e.detail.value)
  filterCommunityId.value = idx <= 0 ? '' : String(communityPickerRange.value[idx]?.id || '')
  if (filterCommunityId.value) filterUnassigned.value = false
}

// ── Selection ──
function toggleOne(id) {
  const idx = selected.value.indexOf(id)
  if (idx >= 0) selected.value.splice(idx, 1)
  else selected.value.push(id)
}
function toggleAll() {
  if (allChecked.value) selected.value = []
  else selected.value = records.value.map(d => d.id)
}

// ── Batch assign dialog ──
function openBatchAssign() {
  if (!selected.value.length) {
    uni.showToast({ title: '请先在列表中勾选用户', icon: 'none' })
    return
  }
  assignCommunityIdx.value = -1
  showBatchAssign.value = true
}

function closeBatchAssign() {
  showBatchAssign.value = false
  assigning.value = false
}

function onAssignCommunityChange(e) {
  assignCommunityIdx.value = e.detail.value
}

async function doBatchAssign() {
  const communityId = communityPickerRange.value[assignCommunityIdx.value]?.id
  if (!communityId) {
    uni.showToast({ title: '请选择目标小区', icon: 'none' })
    return
  }
  assigning.value = true
  try {
    const result = await api.gasUserBatchAssignCommunity({
      userIds: selected.value,
      communityId: communityId,
    })
    uni.showToast({ title: `成功为 ${result.updated || selected.value.length} 个用户指定小区`, icon: 'success' })
    closeBatchAssign()
    selected.value = []
    loadData()
  } catch (e) {
    uni.showToast({ title: e?.msg || '绑定失败', icon: 'none' })
  } finally {
    assigning.value = false
  }
}

// ── Single edit ──
function openSingleEdit(user) {
  singleEditUser.value = user
  // Find matching community index in picker range (skip the "全部" option at index 0)
  singleEditCommunityIdx.value = communityPickerRange.value.findIndex(c => c.id === user.communityId)
  if (singleEditCommunityIdx.value < 0) singleEditCommunityIdx.value = -1
  showSingleEdit.value = true
}

function closeSingleEdit() {
  showSingleEdit.value = false
  singleEditUser.value = null
  savingSingle.value = false
}

function onSingleEditCommunityChange(e) {
  singleEditCommunityIdx.value = e.detail.value
}

async function doSingleEdit() {
  const communityId = communityPickerRange.value[singleEditCommunityIdx.value]?.id
  // Allow saving with empty communityId to clear the association
  savingSingle.value = true
  try {
    await api.gasUserUpdate(singleEditUser.value.id, { communityId: communityId || null })
    uni.showToast({ title: '保存成功', icon: 'success' })
    closeSingleEdit()
    loadData()
  } catch (e) {
    uni.showToast({ title: e?.msg || '保存失败', icon: 'none' })
  } finally {
    savingSingle.value = false
  }
}

// ── Import from gas platform ──
function openImport() {
  importState.value = 'idle'
  importMsg.value = ''
  importResult.value = null
  importPreviewResult.value = null
  importTaskId.value = null
  importCompanyName.value = ''
  showImport.value = true
}
function closeImport() {
  if (importState.value === 'importing' || importState.value === 'querying') {
    uni.showToast({ title: '任务执行中，请等待完成', icon: 'none' })
    return
  }
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  showImport.value = false
}
async function doPreview() {
  const name = importCompanyName.value?.trim()
  if (!name) { uni.showToast({ title: '请输入燃气公司名称', icon: 'none' }); return }
  importState.value = 'querying'
  try {
    const result = await api.gasUserPreviewFromPlatform(name)
    importPreviewResult.value = result
    importState.value = 'preview'
    importMsg.value = ''
  } catch (e) {
    importState.value = 'idle'
    uni.showToast({ title: e?.msg || '查询失败，请检查公司名称', icon: 'none' })
  }
}
async function doImport() {
  const name = importCompanyName.value?.trim()
  if (!name) { uni.showToast({ title: '请输入燃气公司名称', icon: 'none' }); return }
  importState.value = 'importing'
  importMsg.value = '正在提交导入任务...'
  try {
    const result = await api.gasUserImportSubmit(name)
    importTaskId.value = result.taskId
    importMsg.value = result.message || '导入任务已提交，正在后台处理...'
    startImportPolling()
  } catch (e) {
    importState.value = 'done'
    importResult.value = { status: 'fail' }
    importMsg.value = e?.msg || '提交导入任务失败，请稍后重试'
  }
}
function startImportPolling() {
  if (pollTimer) clearInterval(pollTimer)
  let pollCount = 0
  let consecutiveErrors = 0
  const MAX_POLLS = 120  // 6 min at 3s interval
  pollTimer = setInterval(async () => {
    pollCount++
    if (pollCount > MAX_POLLS) {
      clearInterval(pollTimer); pollTimer = null
      importState.value = 'done'
      importResult.value = { status: 'fail' }
      importMsg.value = '导入任务超时，请稍后查看任务记录'
      return
    }
    if (!importTaskId.value) return
    try {
      const status = await api.gasUserImportStatus(importTaskId.value)
      consecutiveErrors = 0
      importMsg.value = status.progressMsg || status.message || ''
      if (status.status === 'DONE') {
        clearInterval(pollTimer); pollTimer = null
        importState.value = 'done'
        importResult.value = { status: 'success' }
        importMsg.value = status.message || '导入完成'
      } else if (status.status === 'FAILED') {
        clearInterval(pollTimer); pollTimer = null
        importState.value = 'done'
        importResult.value = { status: 'fail' }
        importMsg.value = status.errorMsg || status.message || '导入失败'
      }
    } catch (e) {
      consecutiveErrors++
      if (consecutiveErrors >= 5) {
        clearInterval(pollTimer); pollTimer = null
        importState.value = 'done'
        importResult.value = { status: 'fail' }
        importMsg.value = '网络异常，无法获取导入状态，请稍后查看任务记录'
      }
    }
  }, 3000)
}
function onImportDone() {
  closeImport()
  pageNum.value = 1
  loadData()
}

// ── Convert flow ──
function openConvertConfirm() {
  convertState.value = 'idle'
  convertMsg.value = ''
  convertResult.value = null
  convertTaskId.value = null
  convertShowErrors.value = false
  showConvertConfirm.value = true
}
function closeConvertConfirm() {
  if (convertState.value === 'submitting') return
  showConvertConfirm.value = false
}
function closeConvertProgress() {
  if (convertState.value === 'polling') {
    uni.showToast({ title: '任务执行中，请等待完成', icon: 'none' })
    return
  }
  if (convertPollTimer) { clearInterval(convertPollTimer); convertPollTimer = null }
  showConvertProgress.value = false
}
async function doConvertSubmit() {
  convertState.value = 'submitting'
  try {
    const result = await api.gasUserConvertSubmit({ scope: 'all' })
    convertTaskId.value = result.taskId
    showConvertConfirm.value = false
    showConvertProgress.value = true
    convertState.value = 'polling'
    convertMsg.value = '任务已提交，正在后台处理中...'
    startConvertPolling()
  } catch (e) {
    convertState.value = 'idle'
    uni.showToast({ title: e?.msg || '提交失败，请重试', icon: 'none' })
  }
}
function startConvertPolling() {
  if (convertPollTimer) clearInterval(convertPollTimer)
  let pollCount = 0
  let consecutiveErrors = 0
  const MAX_POLLS = 200  // 10 min at 3s interval (conversion can take a while)
  convertPollTimer = setInterval(async () => {
    pollCount++
    if (pollCount > MAX_POLLS) {
      clearInterval(convertPollTimer); convertPollTimer = null
      convertState.value = 'done'
      convertResult.value = { status: 'fail' }
      convertMsg.value = '转化任务超时，请稍后查看任务记录'
      return
    }
    if (!convertTaskId.value) return
    try {
      const status = await api.gasUserConvertStatus(convertTaskId.value)
      consecutiveErrors = 0
      convertMsg.value = status.progressMsg || status.phase || ''
      if (status.status === 'DONE') {
        clearInterval(convertPollTimer); convertPollTimer = null
        convertState.value = 'done'
        convertResult.value = {
          status: 'success',
          successRows: status.successRows || 0,
          failRows: status.failRows || 0,
          existingCount: 0, // populated from handler's existingCount field
          errors: [],
          message: status.progressMsg || ''
        }
        // Parse errorMsg for failure details
        if (status.errorMsg) {
          convertResult.value.errors = status.errorMsg.split('\n').filter(Boolean)
        }
      } else if (status.status === 'FAILED') {
        clearInterval(convertPollTimer); convertPollTimer = null
        convertState.value = 'done'
        convertResult.value = { status: 'fail' }
        convertMsg.value = status.errorMsg || '转化失败'
      }
    } catch (e) {
      consecutiveErrors++
      if (consecutiveErrors >= 5) {
        clearInterval(convertPollTimer); convertPollTimer = null
        convertState.value = 'done'
        convertResult.value = { status: 'fail' }
        convertMsg.value = '网络异常，无法获取任务状态，请稍后查看任务记录'
      }
    }
  }, 3000)
}
function onConvertDone() {
  closeConvertProgress()
  pageNum.value = 1
  loadData()
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding-bottom: 40rpx }

/* Header */
.gu-header {
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  padding: 60rpx 40rpx 40rpx; color: #fff;
}
.gu-title { display: block; font-size: 40rpx; font-weight: 700 }
.gu-sub { display: block; font-size: 26rpx; opacity: .8; margin-top: 8rpx }

/* Toolbar */
.gu-toolbar { padding: 20rpx 30rpx; display: flex; flex-direction: column; gap: 16rpx }
.gu-filters { display: flex; gap: 12rpx; flex-wrap: wrap }
.gu-filter { flex: 1; min-width: 160rpx; height: 64rpx; background: #fff; border-radius: 12rpx; padding: 0 16rpx; font-size: 26rpx; border: 1rpx solid #e0e0e0 }
.gu-filter--picker { display: flex; align-items: center }
.gu-picker-val { font-size: 26rpx; color: #333; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.gu-actions { display: flex; gap: 12rpx; flex-wrap: wrap; justify-content: flex-end }

/* Buttons */
.gu-btn { height: 64rpx; line-height: 64rpx; padding: 0 24rpx; border-radius: 12rpx; font-size: 26rpx; text-align: center; cursor: pointer }
.gu-btn--search { background: #FF6B35; color: #fff; flex-shrink: 0 }
.gu-btn--assign { background: #07C160; color: #fff }
.gu-btn--unassigned { background: #fff; color: #FF6B35; border: 1rpx solid #FF6B35 }
.gu-btn--unassigned-active { background: #FF6B35; color: #fff }
.gu-btn--sm { height: 48rpx; line-height: 48rpx; padding: 0 16rpx; font-size: 22rpx; background: #FF6B35; color: #fff }
.gu-btn--xs { height: 48rpx; line-height: 48rpx; padding: 0 16rpx; font-size: 22rpx; background: #fff; border: 1rpx solid #ddd; color: #333 }
.gu-btn--page { background: #fff; border: 1rpx solid #ddd; color: #333 }
.gu-btn--cancel { background: #f0f0f0; color: #666; flex: 1 }
.gu-btn--confirm { background: #FF6B35; color: #fff; flex: 1 }
.gu-btn.disabled { opacity: .5; pointer-events: none }
.gu-btn.loading { opacity: .7; pointer-events: none }

/* Table */
.gu-table-wrap { margin: 0 30rpx; background: #fff; border-radius: 16rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.06) }
.gu-scroll { width: 100% }
.gu-table { min-width: 1100rpx }
.gu-tr { display: flex; border-bottom: 1rpx solid #f0f0f0 }
.gu-th { background: #fafafa }
.gu-th .gu-td { font-weight: 600; color: #666 }
.gu-td { padding: 16rpx 10rpx; font-size: 24rpx; color: #333; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.gu-loading, .gu-empty { text-align: center; padding: 80rpx 0; color: #999; font-size: 28rpx }

/* Community display */
.gu-community-bound { color: #FF6B35; font-weight: 500 }

/* Pager */
.gu-pager { display: flex; align-items: center; justify-content: center; gap: 24rpx; padding: 30rpx }
.gu-page-info { font-size: 26rpx; color: #666 }

/* Modal */
.gu-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 999 }
.gu-modal { background: #fff; border-radius: 24rpx; width: 90%; max-width: 680rpx; max-height: 85vh; display: flex; flex-direction: column }
.gu-modal--sm { max-width: 560rpx }
.gu-modal-header { display: flex; align-items: center; justify-content: space-between; padding: 36rpx 36rpx 20rpx; border-bottom: 1rpx solid #f0f0f0 }
.gu-modal-title { font-size: 34rpx; font-weight: 700; color: #333 }
.gu-modal-close { font-size: 36rpx; color: #999; padding: 8rpx }
.gu-modal-body { padding: 36rpx; overflow-y: auto; flex: 1 }
.gu-modal-footer { display: flex; gap: 16rpx; padding: 0 36rpx 36rpx; justify-content: center }

/* Form fields (in modals) */
.gu-field { margin-bottom: 24rpx }
.gu-field-label { display: block; font-size: 26rpx; color: #666; margin-bottom: 10rpx }
.gu-field-input { height: 72rpx; line-height: 72rpx; background: #f5f5f5; border-radius: 12rpx; padding: 0 20rpx; font-size: 26rpx; color: #333 }
.gu-field-input.placeholder { color: #999 }
.gu-field-static { display: block; font-size: 28rpx; color: #333; padding: 12rpx 0 }
.gu-current-community { color: #FF6B35; font-weight: 500 }
.gu-required { color: #e53935 }
.gu-highlight { color: #FF6B35; font-weight: 600 }

/* Import button */
.gu-btn--import { background: #1989FA; color: #fff }
.gu-btn--convert { background: #FF6B35; color: #fff }

/* Import modal */
.gu-import-desc { font-size: 28rpx; color: #333; display: block; margin-bottom: 8rpx }
.gu-import-result { font-size: 30rpx; color: #333; font-weight: 600; display: block; margin-top: 12rpx }
.gu-import-error-title { font-size: 30rpx; color: #e53935; font-weight: 600; display: block; margin-top: 8rpx; margin-bottom: 8rpx }
.gu-import-error { font-size: 26rpx; color: #999; display: block; word-break: break-all }
.gu-import-field { margin-bottom: 24rpx }

/* Preview card */
.gu-preview-card { background: #f9fafb; border-radius: 16rpx; padding: 24rpx; margin: 8rpx 0 }
.gu-preview-row { display: flex; justify-content: space-between; align-items: center; padding: 8rpx 0 }
.gu-preview-label { font-size: 26rpx; color: #666 }
.gu-preview-val { font-size: 28rpx; color: #333; font-weight: 500 }
.gu-preview-existing { color: #999 }
</style>
