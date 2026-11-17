<template>
  <view class="page">
    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
      <view class="retry" @click="load"><text>重试</text></view>
    </view>
    <template v-else-if="detail">
      <view class="hero">
        <view class="hero-top">
          <text class="hero-no">{{ detail.requisitionNo }}</text>
          <text class="hero-status" :class="statusClass(detail.status)">{{ statusLabel(detail.status) }}</text>
        </view>
        <text class="hero-purpose">{{ detail.purpose }}</text>
        <text class="hero-progress">{{ progressLabel(detail.writeOffStatus) }}</text>
      </view>

      <view class="card">
        <text class="block-title">领用信息</text>
        <view class="kv"><text class="k">期望领用日期</text><text class="v">{{ detail.expectedDate || '—' }}</text></view>
        <view class="kv"><text class="k">领用人</text><text class="v">{{ detail.applicantNames || detail.applicantName || '—' }}</text></view>
        <view class="kv"><text class="k">所属部门</text><text class="v">{{ detail.deptName || '未设置部门' }}</text></view>
        <view class="kv"><text class="k">提交时间</text><text class="v">{{ formatTime(detail.submitTime) }}</text></view>
        <view class="kv"><text class="k">备注</text><text class="v">{{ detail.remark || '—' }}</text></view>
      </view>

      <view class="card">
        <text class="block-title">审批信息</text>
        <view class="kv"><text class="k">状态</text><text class="v">{{ statusLabel(detail.status) }}</text></view>
        <view class="kv"><text class="k">发放状态</text><text class="v">{{ issueStatusText }}</text></view>
        <view v-if="detail.status === 'PENDING'" class="kv">
          <text class="k">当前审批人</text>
          <text class="v approver">{{ approverText }}</text>
        </view>
        <view v-if="detail.wfNodeName" class="kv"><text class="k">当前节点</text><text class="v">{{ detail.wfNodeName }}</text></view>
        <view v-if="detail.approvalRemark" class="kv"><text class="k">审批意见</text><text class="v">{{ detail.approvalRemark }}</text></view>
        <view v-if="detail.approveTime" class="kv"><text class="k">审批时间</text><text class="v">{{ formatTime(detail.approveTime) }}</text></view>
      </view>

      <view class="card">
        <view class="block-head">
          <text class="block-title">物料明细</text>
          <text class="total">{{ detail.itemCount }} 种 · {{ detail.totalQty }} 件</text>
        </view>
        <view v-for="entry in detail.entries" :key="entry.id" class="entry">
          <view class="entry-top">
            <text class="entry-name">{{ entry.materialName }}</text>
            <text class="entry-qty">{{ entry.quantity }} {{ entry.unit || '' }}</text>
          </view>
          <text class="entry-meta">{{ [entry.category, entry.brand, entry.model].filter(Boolean).join(' · ') }}</text>
          <view class="entry-foot">
            <text class="entry-sub">已核销 {{ entry.writtenOffQty || 0 }}</text>
            <text class="entry-sub">已退回 {{ entry.returnedQty || 0 }}</text>
            <text class="entry-remain">剩余 {{ entry.remainingQty || 0 }}</text>
          </view>
        </view>
      </view>

      <view v-if="canWriteOff" class="footer">
        <view class="primary-btn" @click="goWriteOff"><text>发起核销</text></view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { REQUISITION_STATUS, WRITE_OFF_PROGRESS, formatTime } from '@/utils/materialForm'

/** 物资领用详情：领用信息 + 审批信息 + 明细余量；可继续发起核销。 */
const id = ref(null)
const detail = ref(null)
const loading = ref(true)
const errorMsg = ref('')

const approverText = computed(() => {
  if (detail.value.currentApproverName) {
    const who = detail.value.currentApproverRole
      ? `${detail.value.currentApproverRole}：${detail.value.currentApproverName}`
      : detail.value.currentApproverName
    return `待 ${who} 审批`
  }
  return detail.value.approverMessage || '审批人待分配，请联系管理员'
})
const issueStatusText = computed(() => {
  const status = detail.value && detail.value.status
  if (status === 'ISSUED') return '已发放'
  if (status === 'APPROVED') return '待发放'
  return '—'
})
const canWriteOff = computed(() => {
  const value = detail.value
  return value && ['APPROVED', 'ISSUED'].includes(value.status)
    && value.writeOffStatus !== 'COMPLETED'
})

onLoad((options) => {
  id.value = Number(options && options.id)
  load()
})

async function load() {
  if (!id.value) { errorMsg.value = '缺少领用单ID'; loading.value = false; return }
  loading.value = true
  errorMsg.value = ''
  try {
    detail.value = await api.materialRequisitionDetail(id.value)
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败'
  } finally {
    loading.value = false
  }
}

function statusLabel(value) {
  return REQUISITION_STATUS[value] || value || ''
}
function statusClass(value) {
  if (value === 'APPROVED' || value === 'ISSUED') return 'ok'
  if (value === 'REJECTED') return 'bad'
  return 'pending'
}
function progressLabel(value) {
  return WRITE_OFF_PROGRESS[value] || '未核销'
}
function goWriteOff() {
  uni.navigateTo({ url: '/pages/employee/material-write-off-new?requisitionId=' + id.value })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 180rpx; box-sizing: border-box; }
.state { padding: 160rpx 0; text-align: center; font-size: 25rpx; color: #86909C; }
.state-err { display: block; color: #F53F3F; margin-bottom: 20rpx; }
.retry { display: inline-block; padding: 12rpx 40rpx; border-radius: 999rpx; background: #FF6B35; color: #fff; font-size: 24rpx; }
.hero { background: linear-gradient(135deg, #FF6B35, #FF8C5A); border-radius: 18rpx; padding: 26rpx; color: #fff; }
.hero-top { display: flex; align-items: center; justify-content: space-between; }
.hero-no { font-size: 32rpx; font-weight: 700; }
.hero-status { font-size: 22rpx; background: rgba(255,255,255,.22); border-radius: 8rpx; padding: 4rpx 14rpx; }
.hero-purpose { display: block; margin-top: 12rpx; font-size: 26rpx; line-height: 1.5; }
.hero-progress { display: block; margin-top: 8rpx; font-size: 22rpx; opacity: .85; }
.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-top: 16rpx; }
.block-title { display: block; font-size: 29rpx; font-weight: 700; color: #1D2129; margin-bottom: 14rpx; }
.block-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14rpx; }
.block-head .block-title { margin-bottom: 0; }
.total { font-size: 24rpx; color: #FF6B35; font-weight: 600; }
.kv { display: flex; padding: 8rpx 0; }
.k { width: 180rpx; flex-shrink: 0; font-size: 24rpx; color: #86909C; }
.v { flex: 1; font-size: 26rpx; color: #1D2129; line-height: 1.5; word-break: break-all; }
.approver { color: #FF7D00; }
.entry { padding: 16rpx 0; border-top: 1rpx solid #F2F3F5; }
.entry:first-of-type { border-top: none; }
.entry-top { display: flex; justify-content: space-between; }
.entry-name { font-size: 27rpx; font-weight: 600; color: #1D2129; }
.entry-qty { font-size: 25rpx; color: #FF6B35; font-weight: 700; }
.entry-meta { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.entry-foot { display: flex; gap: 20rpx; margin-top: 8rpx; }
.entry-sub { font-size: 21rpx; color: #86909C; }
.entry-remain { font-size: 21rpx; color: #00B42A; margin-left: auto; }
.footer { position: fixed; left: 0; right: 0; bottom: 0; padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom)); background: #F5F6FA; }
.primary-btn { height: 88rpx; line-height: 88rpx; text-align: center; border-radius: 44rpx; background: #FF6B35; color: #fff; font-size: 29rpx; font-weight: 600; }
</style>
