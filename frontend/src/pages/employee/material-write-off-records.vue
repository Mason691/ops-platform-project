<template>
  <view class="page">
    <view class="head">
      <view class="head-main">
        <text class="title">物资核销记录</text>
        <text class="sub">查看核销单审批状态与核销/退回数量</text>
      </view>
      <view class="head-actions">
        <view class="create-btn" @click="createNew"><text>＋ 新建核销</text></view>
        <EmployeeHomeLink />
      </view>
    </view>

    <view class="filter-card">
      <FieldChips :options="statusOptions" :model-value="status" compact
                  @update:model-value="onStatusChange" />
      <view class="search-row">
        <input v-model="keyword" class="search-input" placeholder="搜索核销单号 / 领用单号" />
        <text v-if="keyword" class="search-clear" @click="keyword = ''">清空</text>
      </view>
      <view class="date-row">
        <picker mode="date" :value="dateFrom" @change="e => { dateFrom = e.detail.value; search() }">
          <view class="date-trigger">{{ dateFrom || '开始日期' }}</view>
        </picker>
        <text class="date-sep">至</text>
        <picker mode="date" :value="dateTo" @change="e => { dateTo = e.detail.value; search() }">
          <view class="date-trigger">{{ dateTo || '结束日期' }}</view>
        </picker>
        <text v-if="dateFrom || dateTo" class="date-clear" @click="clearDates">清空</text>
      </view>
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="!rows.length" class="state"><text>暂无核销记录</text></view>
    <view v-else class="list">
      <view v-for="item in rows" :key="item.id" class="card" @click="openDetail(item.id)">
        <view class="card-head">
          <text class="card-no">{{ item.writeOffNo }}</text>
          <text class="status" :class="statusClass(item.status)">{{ statusLabel(item.status) }}</text>
        </view>
        <text class="card-ref">关联领用单 {{ item.requisitionNo || '—' }}</text>
        <text class="card-meta">{{ item.materialSummary }} · {{ item.itemCount }}种</text>
        <view class="card-foot">
          <text class="card-date">核销日期 {{ item.writeOffDate || '—' }}</text>
          <text class="card-nums">核销 {{ item.writeOffTotal || 0 }} · 退回 {{ item.returnTotal || 0 }}</text>
        </view>
        <text class="card-date">提交 {{ formatTime(item.submitTime) }}</text>
      </view>
    </view>

    <Pager v-model:page-num="pageNum" v-model:page-size="pageSize" :total="total"
           :page-size-options="[10, 20, 50]" @change="load" />
  </view>
</template>

<script setup>
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'
import { ref, watch } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import FieldChips from '@/components/FieldChips.vue'
import Pager from '@/components/Pager.vue'
import { api } from '@/api/index'
import { WRITE_OFF_STATUS, formatTime } from '@/utils/materialForm'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

const statusOptions = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' }
]
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const status = ref('')
const keyword = ref('')
const dateFrom = ref('')
const dateTo = ref('')
let keywordTimer = null

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})

onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent('/pages/employee/material-write-off-records')
    })
    return
  }
  load()
})

watch(keyword, () => {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(search, 300)
})

async function load() {
  loading.value = true
  try {
    const res = await api.materialWriteOffPage({
      status: status.value || undefined,
      keyword: String(keyword.value || '').trim() || undefined,
      dateFrom: dateFrom.value || undefined,
      dateTo: dateTo.value || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
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

function search() {
  pageNum.value = 1
  load()
}
function onStatusChange(value) {
  status.value = value || ''
  search()
}
function clearDates() {
  dateFrom.value = ''
  dateTo.value = ''
  search()
}
function statusLabel(value) {
  return WRITE_OFF_STATUS[value] || value || ''
}
function statusClass(value) {
  if (value === 'APPROVED') return 'ok'
  if (value === 'REJECTED') return 'bad'
  return 'pending'
}
function createNew() {
  uni.navigateTo({ url: '/pages/employee/material-write-off-new' })
}
function openDetail(id) {
  uni.navigateTo({ url: '/pages/employee/material-write-off-detail?id=' + id })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 40rpx; box-sizing: border-box; }
.head { display: flex; align-items: center; justify-content: space-between; padding: 8rpx 4rpx 20rpx; }
.head-main { display: flex; flex-direction: column; gap: 6rpx; min-width: 0; }
.title { font-size: 36rpx; font-weight: 700; color: #1D2129; }
.sub { font-size: 23rpx; color: #86909C; }
.head-actions { display: flex; align-items: center; gap: 12rpx; flex-shrink: 0; }
.create-btn { padding: 14rpx 24rpx; border-radius: 999rpx; background: #FF6B35; color: #fff; font-size: 24rpx; font-weight: 600; }
.filter-card { background: #fff; border-radius: 16rpx; padding: 20rpx; display: flex; flex-direction: column; gap: 14rpx; }
.search-row { display: flex; align-items: center; background: #f5f5f5; border-radius: 10rpx; padding: 0 18rpx; }
.search-input { flex: 1; height: 68rpx !important; min-height: 68rpx !important; font-size: 26rpx; color: #333; }
.search-clear { font-size: 23rpx; color: #999; padding-left: 12rpx; }
.date-row { display: flex; align-items: center; gap: 12rpx; }
.date-trigger { padding: 10rpx 18rpx; background: #f5f5f5; border-radius: 10rpx; font-size: 23rpx; color: #4E5969; }
.date-sep { font-size: 23rpx; color: #86909C; }
.date-clear { font-size: 23rpx; color: #FF6B35; margin-left: auto; }
.list { margin-top: 16rpx; }
.card { background: #fff; border-radius: 16rpx; padding: 22rpx; margin-bottom: 12rpx; }
.card:active { background: #FFF8F4; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-no { font-size: 28rpx; font-weight: 700; color: #1D2129; }
.status { font-size: 22rpx; border-radius: 6rpx; padding: 3rpx 12rpx; }
.status.pending { color: #FF7D00; background: #FFF7E6; }
.status.ok { color: #00B42A; background: #F0FBF4; }
.status.bad { color: #F53F3F; background: #FFF1F0; }
.card-ref { display: block; margin-top: 10rpx; font-size: 23rpx; color: #4E5969; }
.card-meta { display: block; margin-top: 4rpx; font-size: 22rpx; color: #86909C; }
.card-foot { margin-top: 12rpx; display: flex; justify-content: space-between; }
.card-date, .card-nums { font-size: 21rpx; color: #C9CDD4; }
.state { padding: 100rpx 0; text-align: center; font-size: 25rpx; color: #86909C; }
</style>
