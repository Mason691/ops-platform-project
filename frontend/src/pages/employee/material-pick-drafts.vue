<template>
  <view class="page">
    <view class="head">
      <view class="head-main">
        <text class="title">物料领用单</text>
        <text class="sub">派工自动生成；施工员接受任务后自动确认，修改明细需重新提交部长审批</text>
      </view>
      <view class="head-actions">
        <view class="create-btn" @click="createNew"><text>＋ 新建领用</text></view>
        <EmployeeHomeLink />
      </view>
    </view>

    <view class="filter-card">
      <FieldChips :options="statusOptions" :model-value="status" compact
                  @update:model-value="onStatusChange" />
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="!rows.length" class="state"><text>暂无领用单</text></view>
    <view v-else class="list">
      <view v-for="item in rows" :key="item.id" class="card" @click="openDetail(item)">
        <view class="card-head">
          <text class="card-no">{{ item.billNo || '草稿未编号' }}</text>
          <text class="status" :class="materialPickStatusClass(item.status)">
            {{ item.statusName || materialPickStatusLabel(item.status) }}
          </text>
        </view>
        <text class="card-meta">
          {{ reasonLabel(item.pickReason) }}
          <text v-if="item.taskOrder" class="order-link" @click.stop="openTaskOrder(item)">
            · 工单 {{ item.taskOrder.orderNo || item.taskOrderNo || '已删除' }}
            <text v-if="item.taskOrder.customerName"> · {{ item.taskOrder.customerName }}</text>
            <text v-if="item.taskOrder.deleted">（已删除/作废）</text>
            <text v-else-if="item.taskOrder.readable === false">（无权查看）</text>
          </text>
          <text v-else-if="item.taskOrderNo"> · 工单 {{ item.taskOrderNo }}</text>
        </text>
        <text class="card-summary">
          {{ (item.entries || []).length }} 种 ·
          {{ totalQty(item) }} 件
          <text v-if="item.stockNumber"> · 仓库 {{ item.stockNumber }}</text>
          <text v-else class="warn"> · 待选仓库</text>
        </text>
        <text v-if="item.inApproval" class="card-approval">
          部长审核中 · {{ item.currentApproverDisplay || '审批人待分配' }}
        </text>
        <text v-else-if="item.approvedByName" class="card-approval">
          部长已审核：{{ item.approvedByName }}
        </text>
        <text v-else-if="item.approvalRemark" class="card-approval warn">
          审批意见：{{ item.approvalRemark }}
        </text>
        <text v-if="item.printTime" class="card-print">
          库管已打印：{{ item.printByName || '库管' }} {{ formatTime(item.printTime) }}
          （{{ item.printCount || 0 }} 次）
        </text>
        <text v-if="item.status === '4'" class="card-print">
          金蝶其他出库：{{ materialPickJdyStatusLabel(item.jdySyncStatus) }}
        </text>
        <view class="card-foot">
          <text class="card-date">创建 {{ formatTime(item.createTime) }}</text>
          <view class="card-actions" @click.stop>
            <template v-if="item.status === '0' && !item.inApproval">
              <text class="act primary" @click="submitDraft(item)">提交部长审批</text>
              <text v-if="!item.sourceDispatchId" class="act danger" @click="deleteDraft(item)">删除</text>
            </template>
            <template v-else-if="item.status === '0' && item.inApproval">
              <text class="act muted">部长审核中</text>
            </template>
            <template v-else-if="item.status === '1'">
              <text class="act primary" @click="openDetail(item)">修改明细</text>
            </template>
            <template v-else-if="item.status === '2'">
              <text class="act primary" @click="applyWriteOff(item)">申请核销</text>
            </template>
            <template v-else>
              <text class="act muted">{{ item.status === '3' ? '核销审批中' : '核销已完成' }}</text>
            </template>
          </view>
        </view>
      </view>
    </view>

    <Pager v-model:page-num="pageNum" v-model:page-size="pageSize" :total="total"
           :page-size-options="[10, 20, 50]" @change="load" />
  </view>
</template>

<script setup>
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import FieldChips from '@/components/FieldChips.vue'
import Pager from '@/components/Pager.vue'
import { api } from '@/api/index'
import { formatTime } from '@/utils/materialForm'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'
import {
  MATERIAL_PICK_STATUS_OPTIONS,
  materialPickStatusLabel,
  materialPickStatusClass,
  materialPickJdyStatusLabel
} from '@/utils/materialPickStatus'

/** 我的物料领用单：派工自动生成；施工员接受后自动确认，修改后需部长重新审批。 */
const statusOptions = MATERIAL_PICK_STATUS_OPTIONS
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const status = ref('')

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})

onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect='
        + encodeURIComponent('/pages/employee/material-pick-drafts')
    })
    return
  }
  load()
})

function onStatusChange(value) {
  status.value = value || ''
  pageNum.value = 1
  load()
}

async function load() {
  loading.value = true
  try {
    const res = await api.scmMaterialPickMyPicks({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      status: status.value || undefined
    })
    rows.value = (res && res.records) || []
    total.value = Number(res && res.total) || 0
  } catch (e) {
    rows.value = []
    total.value = 0
    uni.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function openDetail(item) {
  uni.navigateTo({ url: '/pages/employee/material-pick-draft?id=' + item.id })
}

function applyWriteOff(item) {
  uni.navigateTo({
    url: '/pages/employee/material-write-off?pickId=' + encodeURIComponent(item.id)
  })
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
    url: '/pages/employee/material-task-order?pickId=' + encodeURIComponent(item.id)
  })
}

function createNew() {
  uni.navigateTo({ url: '/pages/employee/material-pick' })
}

async function submitDraft(item) {
  // 提交前预览本组织部长；预览失败不阻塞提交
  let content = `确认提交领用单 ${item.billNo || ''} 给本组织部长审批？提交后需等待审核。`
  try {
    const preview = await api.scmMaterialPickApprovalPreview(item.id)
    if (preview && preview.displayText) {
      content = `您将把物料领用单 ${item.billNo || ''} 提交给 ${preview.displayText} 审批，确认提交？`
    } else if (preview && preview.fallbackMessage) {
      content = preview.fallbackMessage
    }
  } catch (e) {
    // 预览失败使用兜底文案
  }
  uni.showModal({
    title: '提交部长审批',
    content,
    success: async (res) => {
      if (!res.confirm) return
      try {
        const result = await api.scmMaterialPickSubmit(item.id)
        const display = result && result.currentApproverDisplay
        uni.showModal({
          title: '已提交审批',
          content: display ? '已提交成功，当前审批：' + display : '已提交成功，待分配审批人，请联系管理员',
          showCancel: false
        })
        load()
      } catch (e) {
        uni.showToast({ title: (e && e.msg) || '提交失败', icon: 'none', duration: 2500 })
      }
    }
  })
}

function deleteDraft(item) {
  uni.showModal({
    title: '删除领用单',
    content: '删除后不可恢复，确认删除该领用单？',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await api.scmMaterialPickDelete(item.id)
        uni.showToast({ title: '已删除', icon: 'success' })
        load()
      } catch (e) {
        uni.showToast({ title: (e && e.msg) || '删除失败', icon: 'none' })
      }
    }
  })
}

function reasonLabel(value) {
  return { DISPATCH: '派工施工', ACTIVITY: '活动物料', OTHER: '其它事宜' }[value] || value || '—'
}
function totalQty(item) {
  return (item.entries || []).reduce((sum, row) => sum + (Number(row.pickQty) || 0), 0)
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 60rpx; }
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx; padding: 8rpx 8rpx 20rpx; }
.head-main { display: flex; flex-direction: column; gap: 8rpx; }
.head-actions { display: flex; align-items: center; gap: 12rpx; flex-shrink: 0; }
.title { font-size: 36rpx; font-weight: 700; color: #1D2129; }
.sub { font-size: 22rpx; color: #86909C; }
.create-btn { padding: 8rpx 18rpx; border-radius: 28rpx; background: #FFF0EB; color: #FF6B35; font-size: 22rpx; font-weight: 600; }
.filter-card { background: #fff; border-radius: 16rpx; padding: 18rpx 20rpx; }
.state { padding: 80rpx 0; text-align: center; font-size: 24rpx; color: #86909C; }
.list { margin-top: 16rpx; display: flex; flex-direction: column; gap: 16rpx; }
.card { background: #fff; border-radius: 16rpx; padding: 24rpx; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.card-no { font-size: 28rpx; font-weight: 700; color: #1D2129; }
.status { font-size: 22rpx; padding: 4rpx 12rpx; border-radius: 8rpx; }
.st-draft { background: #FFF3E8; color: #FF7D00; }
.st-pending { background: #E8F3FF; color: #165DFF; }
.st-ok { background: #E8F8E8; color: #00B42A; }
.st-reject { background: #FFECE8; color: #F53F3F; }
.card-meta { display: block; margin-top: 10rpx; font-size: 24rpx; color: #4E5969; }
.order-link { color: #FF6B35; }
.card-summary { display: block; margin-top: 6rpx; font-size: 24rpx; color: #86909C; }
.card-approval { display: block; margin-top: 6rpx; font-size: 22rpx; color: #165DFF; }
.card-print { display: block; margin-top: 6rpx; font-size: 22rpx; color: #00B42A; word-break: break-all; }
.warn { color: #FF7D00; }
.card-foot { margin-top: 16rpx; display: flex; align-items: center; justify-content: space-between; }
.card-date { font-size: 22rpx; color: #86909C; }
.card-actions { display: flex; gap: 20rpx; }
.act { font-size: 24rpx; font-weight: 600; }
.act.primary { color: #FF6B35; }
.act.danger { color: #F53F3F; }
.act.muted { color: #86909C; font-weight: 400; }
</style>
