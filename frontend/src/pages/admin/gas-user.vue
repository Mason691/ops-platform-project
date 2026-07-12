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
        <view class="gu-btn gu-btn--assign" @click="openBatchAssign">📋 批量指定小区</view>
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
            <view class="gu-td" style="width:140rpx">{{ d.userName || '-' }}</view>
            <view class="gu-td" style="width:200rpx">{{ d.phone || '-' }}</view>
            <view class="gu-td" style="width:200rpx">{{ d.area || '-' }}</view>
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

    <!-- ═══ Pagination ═══ -->
    <view v-if="total > pageSize" class="gu-pager">
      <view class="gu-btn gu-btn--page" :class="{ disabled: pageNum <= 1 }" @click="goPage(pageNum - 1)">上一页</view>
      <text class="gu-page-info">{{ pageNum }} / {{ totalPages }}</text>
      <view class="gu-btn gu-btn--page" :class="{ disabled: pageNum >= totalPages }" @click="goPage(pageNum + 1)">下一页</view>
    </view>

    <!-- ═══ Batch Assign Modal ═══ -->
    <view v-if="showBatchAssign" class="gu-overlay" @click.self="closeBatchAssign">
      <view class="gu-modal">
        <view class="gu-modal-header">
          <text class="gu-modal-title">批量指定小区</text>
          <text class="gu-modal-close" @click="closeBatchAssign">✕</text>
        </view>
        <view class="gu-modal-body">
          <!-- Area search -->
          <view class="gu-field">
            <text class="gu-field-label">按区域搜索用户</text>
            <view class="gu-search-row">
              <input class="gu-field-input" v-model="assignAreaKeyword" placeholder="输入区域关键字（小区名/街道名）" confirm-type="search" @confirm="searchAssignUsers" />
              <view class="gu-btn gu-btn--sm gu-btn--search" @click="searchAssignUsers">搜索</view>
            </view>
          </view>

          <!-- Area search results -->
          <view v-if="assignUsers.length > 0" class="gu-assign-list">
            <view class="gu-assign-summary">
              <text>搜索结果共 <text class="gu-highlight">{{ assignTotal }}</text> 条</text>
            </view>
            <scroll-view scroll-y class="gu-assign-scroll">
              <view class="gu-tr gu-th">
                <view class="gu-td" style="width:60rpx">
                  <input type="checkbox" :checked="allAssignChecked" @click="toggleAllAssign" />
                </view>
                <view class="gu-td" style="width:140rpx">用户号</view>
                <view class="gu-td" style="width:140rpx">姓名</view>
                <view class="gu-td" style="width:180rpx">区域</view>
                <view class="gu-td" style="width:180rpx">当前小区</view>
              </view>
              <view v-for="u in assignUsers" :key="u.id" class="gu-tr" :class="{ 'gu-tr--bound': u.communityName }">
                <view class="gu-td" style="width:60rpx">
                  <input type="checkbox" :checked="assignSelected.includes(u.id)" @click="toggleOneAssign(u.id)" />
                </view>
                <view class="gu-td" style="width:140rpx">{{ u.userNo }}</view>
                <view class="gu-td" style="width:140rpx">{{ u.userName || '-' }}</view>
                <view class="gu-td" style="width:180rpx">{{ u.area || '-' }}</view>
                <view class="gu-td" style="width:180rpx">
                  <text v-if="u.communityName" class="gu-bound-tag">{{ u.communityName }}</text>
                  <text v-else class="gu-unbound-text">未指定</text>
                </view>
              </view>
            </scroll-view>
            <!-- Search results pagination -->
            <view v-if="assignTotal > assignPageSize" class="gu-assign-pager">
              <view class="gu-btn gu-btn--xs" :class="{ disabled: assignPageNum <= 1 }" @click="goAssignPage(assignPageNum - 1)">上一页</view>
              <text class="gu-page-info-sm">{{ assignPageNum }}/{{ assignTotalPages }}</text>
              <view class="gu-btn gu-btn--xs" :class="{ disabled: assignPageNum >= assignTotalPages }" @click="goAssignPage(assignPageNum + 1)">下一页</view>
            </view>
          </view>

          <!-- No search results -->
          <view v-if="assignSearched && assignUsers.length === 0" class="gu-empty-sm">未找到匹配的用户</view>

          <!-- Target community selector -->
          <view class="gu-field">
            <text class="gu-field-label">目标小区 <text class="gu-required">*</text></text>
            <picker :range="communityPickerRange" range-key="name" :value="assignCommunityIdx" @change="onAssignCommunityChange">
              <view class="gu-field-input" :class="{ placeholder: assignCommunityIdx < 0 }">
                {{ assignCommunityIdx >= 0 ? communityPickerRange[assignCommunityIdx].name : '请选择目标小区' }}
              </view>
            </picker>
          </view>

          <!-- Selected count summary -->
          <view class="gu-assign-footer-summary">
            <text>已选择 <text class="gu-highlight">{{ assignSelected.length }}</text> 个用户</text>
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
            <text class="gu-field-static">{{ singleEditUser?.userName || '-' }}（{{ singleEditUser?.userNo }}）</text>
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
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { api } from '@/api/index'

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
const assignAreaKeyword = ref('')
const assignUsers = ref([])
const assignTotal = ref(0)
const assignPageNum = ref(1)
const assignPageSize = ref(5)
const assignSelected = ref([])
const assignCommunityIdx = ref(-1)
const assigning = ref(false)
const assignSearched = ref(false)

// ── Single edit state ──
const showSingleEdit = ref(false)
const singleEditUser = ref(null)
const singleEditCommunityIdx = ref(-1)
const savingSingle = ref(false)

// ── Computed ──
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const allChecked = computed(() => records.value.length > 0 && records.value.every(d => selected.value.includes(d.id)))
const assignTotalPages = computed(() => Math.max(1, Math.ceil(assignTotal.value / assignPageSize.value)))
const allAssignChecked = computed(() => assignUsers.value.length > 0 && assignUsers.value.every(u => assignSelected.value.includes(u.id)))

// ── Lifecycle ──
onMounted(async () => {
  await loadCommunities()
  await loadData()
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
    if (filterCommunityId.value) params.communityId = Number(filterCommunityId.value)
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
function goPage(n) { if (n < 1 || n > totalPages.value) return; pageNum.value = n; loadData() }

function onCommunityFilterChange(e) {
  const idx = Number(e.detail.value)
  filterCommunityId.value = idx <= 0 ? '' : String(communityPickerRange.value[idx]?.id || '')
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
  assignAreaKeyword.value = ''
  assignUsers.value = []
  assignTotal.value = 0
  assignPageNum.value = 1
  assignSearched.value = false
  assignSelected.value = []
  assignCommunityIdx.value = -1
  showBatchAssign.value = true
}

function closeBatchAssign() {
  showBatchAssign.value = false
  assigning.value = false
}

async function searchAssignUsers() {
  if (!assignAreaKeyword.value || !assignAreaKeyword.value.trim()) {
    uni.showToast({ title: '请输入区域关键字', icon: 'none' })
    return
  }
  assignPageNum.value = 1
  assignSearched.value = true
  await loadAssignUsers()
}

async function loadAssignUsers() {
  try {
    const data = await api.gasUserSearchByArea({
      pageNum: assignPageNum.value,
      pageSize: assignPageSize.value,
      keyword: assignAreaKeyword.value,
    })
    assignUsers.value = data.records || []
    assignTotal.value = data.total || 0
  } catch (e) {
    uni.showToast({ title: e?.msg || '搜索失败', icon: 'none' })
  }
}

function goAssignPage(n) {
  if (n < 1 || n > assignTotalPages.value) return
  assignPageNum.value = n
  loadAssignUsers()
}

function toggleOneAssign(id) {
  const idx = assignSelected.value.indexOf(id)
  if (idx >= 0) assignSelected.value.splice(idx, 1)
  else assignSelected.value.push(id)
}
function toggleAllAssign() {
  if (allAssignChecked.value) assignSelected.value = []
  else assignSelected.value = assignUsers.value.map(u => u.id)
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
  if (!assignSelected.value.length) {
    uni.showToast({ title: '请选择要绑定的用户', icon: 'none' })
    return
  }
  assigning.value = true
  try {
    const result = await api.gasUserBatchAssignCommunity({
      userIds: assignSelected.value,
      communityId: communityId,
    })
    uni.showToast({ title: `成功为 ${result.updated || assignSelected.value.length} 个用户指定小区`, icon: 'success' })
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
.gu-search-row { display: flex; gap: 12rpx }
.gu-search-row .gu-field-input { flex: 1 }
.gu-required { color: #e53935 }

/* Batch assign list */
.gu-assign-list { margin-bottom: 24rpx }
.gu-assign-summary { padding: 12rpx 0; font-size: 24rpx; color: #666 }
.gu-assign-scroll { max-height: 360rpx; border: 1rpx solid #f0f0f0; border-radius: 12rpx }
.gu-highlight { color: #FF6B35; font-weight: 600 }
.gu-tr--bound { background: #fffbf0 }
.gu-bound-tag { display: inline-block; padding: 2rpx 12rpx; background: #fff3e0; color: #e67e00; border-radius: 6rpx; font-size: 22rpx }
.gu-unbound-text { color: #999 }
.gu-assign-pager { display: flex; align-items: center; justify-content: center; gap: 16rpx; padding: 16rpx 0 }
.gu-page-info-sm { font-size: 22rpx; color: #999 }
.gu-empty-sm { text-align: center; padding: 40rpx 0; color: #999; font-size: 26rpx }
.gu-assign-footer-summary { padding: 8rpx 0; font-size: 26rpx; color: #666; text-align: right }
</style>
