<template>
  <view class="page">
    <view class="head">
      <view class="head-main">
        <text class="title">物料领用单</text>
        <text class="sub">派工自动生成的领用草稿，补充仓库/批次后提交审批</text>
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
      <view v-for="item in rows" :key="item.id" class="card" @click="openDraft(item)">
        <view class="card-head">
          <text class="card-no">{{ item.billNo || '草稿未编号' }}</text>
          <text class="status" :class="statusClass(item.approvalStatus)">
            {{ statusLabel(item.approvalStatus) }}
          </text>
        </view>
        <text class="card-meta">
          {{ reasonLabel(item.pickReason) }}
          <text v-if="item.taskOrderNo"> · 工单 {{ item.taskOrderNo }}</text>
        </text>
        <text class="card-summary">
          {{ (item.entries || []).length }} 种 ·
          {{ totalQty(item) }} 件
          <text v-if="item.stockNumber"> · 仓库 {{ item.stockNumber }}</text>
          <text v-else class="warn"> · 待选仓库</text>
        </text>
        <view class="card-foot">
          <text class="card-date">创建 {{ formatTime(item.createTime) }}</text>
          <view v-if="item.approvalStatus === 'DRAFT'" class="card-actions" @click.stop>
            <text class="act primary" @click="submitDraft(item)">提交</text>
            <text class="act danger" @click="deleteDraft(item)">删除</text>
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

/** 我的物料领用单：默认 DRAFT（派工自动生成待补充），可切换审批中/已通过/已驳回。 */
const statusOptions = [
  { value: 'DRAFT', label: '待提交' },
  { value: 'IN_APPROVAL', label: '审批中' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' }
]
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const status = ref('DRAFT')

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
  status.value = value || 'DRAFT'
  pageNum.value = 1
  load()
}

async function load() {
  loading.value = true
  try {
    const res = await api.scmMaterialPickMyDrafts({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      approvalStatus: status.value
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

function openDraft(item) {
  uni.navigateTo({ url: '/pages/employee/material-pick-draft?id=' + item.id })
}

function createNew() {
  uni.navigateTo({ url: '/pages/employee/material-pick' })
}

function submitDraft(item) {
  uni.showModal({
    title: '提交审批',
    content: `确认提交领用单 ${item.billNo || ''} 给财务审批？提交后不可修改。`,
    success: async (res) => {
      if (!res.confirm) return
      try {
        const result = await api.scmMaterialPickSubmit(item.id)
        const display = result && result.currentApproverDisplay
        uni.showModal({
          title: '已提交审批',
          content: '已提交给财务审批' + (display ? '\n' + display : ''),
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
    title: '删除草稿',
    content: '删除后不可恢复，确认删除该领用草稿？',
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

function statusLabel(value) {
  return { DRAFT: '待提交', IN_APPROVAL: '审批中', APPROVED: '已通过', REJECTED: '已驳回' }[value] || value || '—'
}
function statusClass(value) {
  return {
    DRAFT: 'st-draft', IN_APPROVAL: 'st-pending',
    APPROVED: 'st-ok', REJECTED: 'st-reject'
  }[value] || ''
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
.card-summary { display: block; margin-top: 6rpx; font-size: 24rpx; color: #86909C; }
.warn { color: #FF7D00; }
.card-foot { margin-top: 16rpx; display: flex; align-items: center; justify-content: space-between; }
.card-date { font-size: 22rpx; color: #86909C; }
.card-actions { display: flex; gap: 20rpx; }
.act { font-size: 24rpx; font-weight: 600; }
.act.primary { color: #FF6B35; }
.act.danger { color: #F53F3F; }
</style>
