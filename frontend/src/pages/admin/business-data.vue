<template>
  <view class="page">
    <!-- ═══ Header ═══ -->
    <view class="bd-header">
      <text class="bd-title">📊 业务数据管理</text>
      <text class="bd-sub">导入销售记录、计算提成、导出报表</text>
    </view>

    <!-- ═══ Stats ═══ -->
    <view class="bd-stats">
      <view class="bd-stat-item">
        <text class="bd-stat-num">{{ total }}</text>
        <text class="bd-stat-label">总记录</text>
      </view>
      <view class="bd-stat-item">
        <text class="bd-stat-num">{{ totalSale }}</text>
        <text class="bd-stat-label">总销售额</text>
      </view>
      <view class="bd-stat-item">
        <text class="bd-stat-num">{{ totalCommission }}</text>
        <text class="bd-stat-label">总提成</text>
      </view>
    </view>

    <!-- ═══ Filters + Actions ═══ -->
    <view class="bd-toolbar">
      <view class="bd-filters">
        <input class="bd-filter" v-model="filterCompany" placeholder="公司" />
        <input class="bd-filter" v-model="filterBatchNo" placeholder="批次号" />
        <input class="bd-filter" v-model="filterAgent" placeholder="经办人" />
        <view class="bd-btn bd-btn--search" @click="search">搜索</view>
      </view>
      <view class="bd-actions">
        <view class="bd-btn bd-btn--import" @click="showImport = true">📥 导入</view>
        <view class="bd-btn bd-btn--calc" @click="doCalc">🧮 计算提成</view>
        <view class="bd-btn bd-btn--delete" :class="{ disabled: !selected.length }" @click="doDelete">🗑 删除</view>
      </view>
    </view>

    <!-- ═══ Table ═══ -->
    <view v-if="loading" class="bd-loading">加载中...</view>
    <view v-else-if="records.length === 0" class="bd-empty">暂无数据，请导入业务数据</view>
    <view v-else class="bd-table-wrap">
      <view class="bd-table">
        <view class="bd-tr bd-th">
          <view class="bd-td" style="width:60rpx"><input type="checkbox" :checked="allChecked" @click="toggleAll" /></view>
          <view class="bd-td" style="width:80rpx">ID</view>
          <view class="bd-td" style="width:120rpx">公司</view>
          <view class="bd-td" style="width:140rpx">客户</view>
          <view class="bd-td" style="width:100rpx">品牌</view>
          <view class="bd-td" style="width:120rpx">品类</view>
          <view class="bd-td" style="width:120rpx">型号</view>
          <view class="bd-td" style="width:90rpx">销售价</view>
          <view class="bd-td" style="width:100rpx">经办人</view>
          <view class="bd-td" style="width:130rpx">经办人手机</view>
          <view class="bd-td" style="width:90rpx">提成率</view>
          <view class="bd-td" style="width:90rpx">提成额</view>
          <view class="bd-td" style="width:100rpx">状态</view>
        </view>
        <view v-for="d in records" :key="d.id" class="bd-tr">
          <view class="bd-td" style="width:60rpx">
            <input type="checkbox" :checked="selected.includes(d.id)" @click="toggleOne(d.id)" />
          </view>
          <view class="bd-td" style="width:80rpx">{{ d.id }}</view>
          <view class="bd-td" style="width:120rpx">{{ d.company }}</view>
          <view class="bd-td" style="width:140rpx">{{ d.customer }}</view>
          <view class="bd-td" style="width:100rpx">{{ d.brand }}</view>
          <view class="bd-td" style="width:120rpx">{{ categoryName(d.category) }}</view>
          <view class="bd-td" style="width:120rpx">{{ d.model }}</view>
          <view class="bd-td" style="width:90rpx">{{ d.salePrice }}</view>
          <view class="bd-td" style="width:100rpx">{{ d.agent }}</view>
          <view class="bd-td" style="width:130rpx">{{ d.agentPhone || '-' }}</view>
          <view class="bd-td" style="width:90rpx">{{ d.commissionRate || '-' }}</view>
          <view class="bd-td" style="width:90rpx">{{ d.commissionAmount || '-' }}</view>
          <view class="bd-td" style="width:100rpx">{{ d.calcStatus }}</view>
        </view>
      </view>
    </view>

    <!-- ═══ Pagination ═══ -->
    <view v-if="total > pageSize" class="bd-pager">
      <view class="bd-btn bd-btn--page" :class="{ disabled: pageNum <= 1 }" @click="goPage(pageNum - 1)">上一页</view>
      <text class="bd-page-info">{{ pageNum }} / {{ totalPages }}</text>
      <view class="bd-btn bd-btn--page" :class="{ disabled: pageNum >= totalPages }" @click="goPage(pageNum + 1)">下一页</view>
    </view>

    <!-- ═══ Import Modal ═══ -->
    <view v-if="showImport" class="bd-overlay" @click.self="closeImport">
      <view class="bd-modal">
        <view class="bd-modal-header">
          <text class="bd-modal-title">导入业务数据</text>
          <text class="bd-modal-close" @click="closeImport">✕</text>
        </view>

        <view class="bd-modal-body">
          <!-- Step 1: Select file -->
          <view v-if="!importResult" class="bd-import-step">
            <view class="bd-template-link" @click="downloadTemplate">
              📥 下载导入模板（Excel格式）
            </view>

            <view class="bd-file-area" @click="selectFile">
              <text class="bd-file-icon">📁</text>
              <text v-if="!selectedFile" class="bd-file-hint">点击选择Excel文件</text>
              <template v-else>
                <text class="bd-file-name">{{ selectedFile.name }}</text>
                <text class="bd-file-size">{{ formatSize(selectedFile.size) }}</text>
              </template>
            </view>
            <input
              ref="fileInput"
              type="file"
              accept=".xlsx,.xls"
              style="display:none"
              @change="onFileChange"
            />

            <view
              class="bd-btn bd-btn--upload"
              :class="{ loading: importing, disabled: !selectedFile || importing }"
              @click="doImport"
            >
              {{ importing ? '验证中...' : '开始导入' }}
            </view>
          </view>

          <!-- Step 2: Polling -->
          <view v-if="importPolling" class="bd-import-result">
            <text class="bd-result-icon">⏳</text>
            <text class="bd-result-title">正在校验数据，请稍候...</text>
            <text class="bd-result-phase">{{ importPhase }}</text>
          </view>

          <!-- Step 3: Result -->
          <view v-else-if="importResult" class="bd-import-result">
            <!-- Success -->
            <view v-if="importResult.status === 'success'" class="bd-result-success">
              <text class="bd-result-icon">✅</text>
              <text class="bd-result-title">全部验证通过，成功导入{{ importResult.count }}条业务数据</text>
            </view>
            <!-- Fail -->
            <view v-else class="bd-result-fail">
              <text class="bd-result-icon">❌</text>
              <text class="bd-result-title">
                共发现{{ importResult.totalErrors }}行数据不完整，请修正后重新导入，当前未导入任何数据
              </text>
              <view class="bd-error-list">
                <text v-for="(e, i) in importResult.errors" :key="i" class="bd-error-item">{{ e }}</text>
              </view>
            </view>

            <view class="bd-btn bd-btn--retry" @click="resetImport">重新选择文件</view>
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
const records = ref([])
const total = ref(0)
const totalSale = ref('0.00')
const totalCommission = ref('0.00')
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const selected = ref([])

// Filters
const filterCompany = ref('')
const filterBatchNo = ref('')
const filterAgent = ref('')

// Import
const showImport = ref(false)
const selectedFile = ref(null)
const importing = ref(false)
const importResult = ref(null)
const importPolling = ref(false)
const importPhase = ref('')
let pollTimer = null
const fileInput = ref(null)

// ── Computed ──
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const allChecked = computed(() => records.value.length > 0 && records.value.every(d => selected.value.includes(d.id)))

// ── Init ──
onMounted(async () => {
  await ensureLogin()
  await loadData()
})

const CATEGORY_MAP = { '1':'燃气灶','2':'抽油烟机','3':'热水器','4':'壁挂炉','5':'壁挂炉以旧换新销售','6':'户内美装开发','7':'定尺波纹管','8':'户内美装工程','9':'户内美装运维' }
function categoryName(key) { return CATEGORY_MAP[key] || key || '-' }

async function ensureLogin() {
  const token = uni.getStorageSync('ops_token')
  if (token) return
  try {
    const res = await new Promise((resolve, reject) => {
      uni.request({
        url: 'https://test.jdzn99.com/api/ops/auth/login',
        method: 'POST',
        data: { phone: '13900000001', password: 'admin123' },
        header: { 'Content-Type': 'application/json' },
        success(r) { if (r.data?.ok) resolve(r.data.data); else reject(r.data) },
        fail: reject,
      })
    })
    uni.setStorageSync('ops_token', res.token)
    uni.setStorageSync('ops_member', JSON.stringify(res))
  } catch (e) { console.warn('[business-data] mock login failed:', e) }
}

async function loadData() {
  loading.value = true
  try {
    const data = await api.businessDataList({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      company: filterCompany.value || undefined,
      batchNo: filterBatchNo.value || undefined,
      agent: filterAgent.value || undefined,
    })
    records.value = data.records || []
    total.value = data.total || 0
    totalSale.value = data.totalSale || '0.00'
    totalCommission.value = data.totalCommission || '0.00'
  } catch (e) { console.error('[business-data] load failed:', e) }
  finally { loading.value = false }
}

// ── Pagination & filters ──
function search() { pageNum.value = 1; loadData() }
function goPage(n) { if (n < 1 || n > totalPages.value) return; pageNum.value = n; loadData() }

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

// ── Delete ──
async function doDelete() {
  if (!selected.value.length) return
  const ok = await new Promise(resolve => {
    uni.showModal({
      title: '确认删除',
      content: `确定要删除选中的${selected.value.length}条数据吗？此操作不可恢复。`,
      success: r => resolve(r.confirm),
    })
  })
  if (!ok) return
  try {
    await api.businessDataDelete(selected.value)
    selected.value = []
    uni.showToast({ title: '删除成功', icon: 'success' })
    loadData()
  } catch (e) { uni.showToast({ title: '删除失败', icon: 'none' }) }
}

// ── Calc commission ──
async function doCalc() {
  try {
    const r = await api.businessDataCalcCommission()
    uni.showToast({ title: `计算完成：${r.total}条`, icon: 'success' })
    loadData()
  } catch (e) { uni.showToast({ title: '计算失败', icon: 'none' }) }
}

// ── Import (Async) ──
function selectFile() { fileInput.value?.click() }
function onFileChange(e) {
  const file = e.target?.files?.[0]
  if (file) selectedFile.value = file
}
function formatSize(bytes) {
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / 1048576).toFixed(1) + 'MB'
}
function downloadTemplate() {
  const url = api.businessDataTemplateUrl()
  window.open(url, '_blank')
}

async function doImport() {
  if (!selectedFile.value || importing.value) return
  importing.value = true
  importResult.value = null
  importPolling.value = false
  try {
    // Submit async import task
    const submit = await api.businessDataImportSubmit(selectedFile.value)
    const taskId = submit.taskId
    if (!taskId) {
      // Fallback to sync result
      importResult.value = submit
      return
    }
    // Start polling
    importPolling.value = true
    importPhase.value = '已提交，等待处理...'
    pollTimer = setInterval(async () => {
      try {
        const status = await api.businessDataImportStatus(taskId)
        importPhase.value = status.phase || status.status
        if (status.status === 'DONE') {
          clearInterval(pollTimer)
          importResult.value = { status: 'success', count: status.successRows }
          setTimeout(() => {
            showImport.value = false
            importResult.value = null
            selectedFile.value = null
            importPolling.value = false
            loadData()
          }, 2000)
        } else if (status.status === 'FAILED') {
          clearInterval(pollTimer)
          importPolling.value = false
          const errs = status.errorMsg ? status.errorMsg.split('; ') : []
          importResult.value = { status: 'fail', totalErrors: status.failRows, errors: errs, phase: status.phase }
        }
      } catch (e) { /* keep polling */ }
    }, 2000)
  } catch (e) {
    importResult.value = { status: 'fail', totalErrors: 1, errors: [e.msg || '提交失败'] }
  } finally { importing.value = false }
}

function resetImport() {
  if (pollTimer) clearInterval(pollTimer)
  importResult.value = null
  importPolling.value = false
  importPhase.value = ''
  selectedFile.value = null
}

function closeImport() {
  if (importing.value) return
  if (pollTimer) clearInterval(pollTimer)
  showImport.value = false
  importResult.value = null
  importPolling.value = false
  importPhase.value = ''
  selectedFile.value = null
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding-bottom: 40rpx }

/* Header */
.bd-header {
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  padding: 60rpx 40rpx 40rpx; color: #fff;
}
.bd-title { display: block; font-size: 40rpx; font-weight: 700 }
.bd-sub { display: block; font-size: 26rpx; opacity: .8; margin-top: 8rpx }

/* Stats */
.bd-stats { display: flex; margin: -20rpx 30rpx 20rpx; }
.bd-stat-item {
  flex: 1; background: #fff; border-radius: 16rpx; padding: 24rpx;
  margin: 0 10rpx; text-align: center; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.06);
}
.bd-stat-num { display: block; font-size: 36rpx; font-weight: 700; color: #FF6B35 }
.bd-stat-label { display: block; font-size: 22rpx; color: #999; margin-top: 4rpx }

/* Toolbar */
.bd-toolbar { padding: 20rpx 30rpx; display: flex; flex-direction: column; gap: 16rpx }
.bd-filters { display: flex; gap: 12rpx; flex-wrap: wrap }
.bd-filter { flex: 1; min-width: 160rpx; height: 64rpx; background: #fff; border-radius: 12rpx; padding: 0 16rpx; font-size: 26rpx; border: 1rpx solid #e0e0e0 }
.bd-actions { display: flex; gap: 12rpx; flex-wrap: wrap; justify-content: flex-end }

/* Buttons */
.bd-btn { height: 64rpx; line-height: 64rpx; padding: 0 24rpx; border-radius: 12rpx; font-size: 26rpx; text-align: center }
.bd-btn--search { background: #FF6B35; color: #fff }
.bd-btn--import { background: #07C160; color: #fff }
.bd-btn--calc { background: #FF6B35; color: #fff }
.bd-btn--delete { background: #e53935; color: #fff }
.bd-btn--page { background: #fff; border: 1rpx solid #ddd; color: #333 }
.bd-btn--upload { background: #FF6B35; color: #fff; margin-top: 24rpx; height: 80rpx; line-height: 80rpx }
.bd-btn.disabled { opacity: .5; pointer-events: none }
.bd-btn.loading { opacity: .7; pointer-events: none }
.bd-btn--retry { background: #f0f0f0; color: #333; margin-top: 24rpx }

/* Table */
.bd-table-wrap { margin: 0 30rpx; overflow-x: auto; background: #fff; border-radius: 16rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.06) }
.bd-table { min-width: 1400rpx }
.bd-tr { display: flex; border-bottom: 1rpx solid #f0f0f0 }
.bd-th { background: #fafafa }
.bd-th .bd-td { font-weight: 600; color: #666 }
.bd-td { padding: 16rpx 10rpx; font-size: 24rpx; color: #333; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.bd-loading, .bd-empty { text-align: center; padding: 80rpx 0; color: #999; font-size: 28rpx }

/* Pager */
.bd-pager { display: flex; align-items: center; justify-content: center; gap: 24rpx; padding: 30rpx }
.bd-page-info { font-size: 26rpx; color: #666 }

/* Modal */
.bd-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 999 }
.bd-modal { background: #fff; border-radius: 24rpx; width: 90%; max-width: 640rpx; max-height: 80vh; display: flex; flex-direction: column }
.bd-modal-header { display: flex; align-items: center; justify-content: space-between; padding: 36rpx 36rpx 20rpx; border-bottom: 1rpx solid #f0f0f0 }
.bd-modal-title { font-size: 34rpx; font-weight: 700; color: #333 }
.bd-modal-close { font-size: 36rpx; color: #999; padding: 8rpx }
.bd-modal-body { padding: 36rpx; overflow-y: auto; flex: 1 }

/* Import step */
.bd-import-step { display: flex; flex-direction: column }
.bd-template-link { font-size: 26rpx; color: #FF6B35; text-decoration: underline; padding: 16rpx 0; margin-bottom: 20rpx }
.bd-file-area { background: #f9f9f9; border: 2rpx dashed #ddd; border-radius: 16rpx; padding: 48rpx 24rpx; text-align: center }
.bd-file-icon { font-size: 48rpx; display: block }
.bd-file-hint { font-size: 26rpx; color: #999 }
.bd-file-name { display: block; font-size: 28rpx; color: #333; font-weight: 500; word-break: break-all }
.bd-file-size { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx }

/* Import result */
.bd-import-result { text-align: center }
.bd-result-icon { font-size: 56rpx; display: block; margin-bottom: 16rpx }
.bd-result-title { font-size: 28rpx; color: #333; line-height: 1.6 }
.bd-result-success .bd-result-title { color: #07C160 }
.bd-result-fail .bd-result-title { color: #e53935 }
.bd-result-phase { font-size: 24rpx; color: #999; margin-top: 8rpx }
.bd-error-list { max-height: 300rpx; overflow-y: auto; margin-top: 20rpx; text-align: left; background: #fff5f5; border-radius: 12rpx; padding: 16rpx 20rpx }
.bd-error-item { display: block; font-size: 24rpx; color: #e53935; line-height: 1.8 }
</style>
