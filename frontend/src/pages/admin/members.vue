<template>
  <view class="page">
    <!-- ═══ Header ═══ -->
    <view class="bd-header">
      <text class="bd-title">👥 会员管理</text>
      <text class="bd-sub">查看和管理注册会员</text>
    </view>

    <!-- ═══ Stats ═══ -->
    <view class="bd-stats">
      <view class="bd-stat-item">
        <text class="bd-stat-num">{{ total }}</text>
        <text class="bd-stat-label">会员总数</text>
      </view>
    </view>

    <!-- ═══ Filters ═══ -->
    <view class="bd-toolbar">
      <view class="bd-filters">
        <input class="bd-filter" v-model="filterPhone" placeholder="手机号" />
        <input class="bd-filter" v-model="filterStartDate" placeholder="起始日期" />
        <input class="bd-filter" v-model="filterEndDate" placeholder="结束日期" />
        <picker class="bd-filter bd-filter--picker" mode="selector" :range="statusOptions" range-key="label" @change="onStatusChange">
          <view class="bd-picker-val">{{ statusLabel }}</view>
        </picker>
        <view class="bd-btn bd-btn--search" @click="search">搜索</view>
      </view>
    </view>

    <!-- ═══ Table ═══ -->
    <view v-if="loading" class="bd-loading">加载中...</view>
    <view v-else-if="records.length === 0" class="bd-empty">暂无会员数据</view>
    <view v-else class="bd-table-wrap">
      <view class="bd-table">
        <view class="bd-tr bd-th">
          <view class="bd-td" style="width:80rpx">ID</view>
          <view class="bd-td" style="width:170rpx">手机号</view>
          <view class="bd-td" style="width:150rpx">昵称</view>
          <view class="bd-td" style="width:100rpx">积分</view>
          <view class="bd-td" style="width:100rpx">状态</view>
          <view class="bd-td" style="width:180rpx">最后登录</view>
          <view class="bd-td" style="width:180rpx">操作</view>
        </view>
        <view v-for="d in records" :key="d.id" class="bd-tr">
          <view class="bd-td" style="width:80rpx">{{ d.id }}</view>
          <view class="bd-td" style="width:170rpx">{{ d.phone }}</view>
          <view class="bd-td" style="width:150rpx">{{ d.nickname || '燃气用户' }}</view>
          <view class="bd-td" style="width:100rpx">{{ d.pointsBalance || 0 }}</view>
          <view class="bd-td" style="width:100rpx">
            <text :class="d.status === 1 ? 'badge badge--on' : 'badge badge--off'">
              {{ d.status === 1 ? '正常' : '禁用' }}
            </text>
          </view>
          <view class="bd-td" style="width:180rpx">{{ d.lastLoginTime || '-' }}</view>
          <view class="bd-td" style="width:180rpx">
            <view class="row-actions">
              <view class="bd-btn bd-btn--sm" @click="showMemberDetail(d.id)">详情</view>
              <view
                v-if="canToggleStatus"
                :class="['bd-btn', 'bd-btn--sm', d.status === 1 ? 'bd-btn--warn' : 'bd-btn--ok']"
                @click="toggleStatus(d)"
              >{{ d.status === 1 ? '禁用' : '启用' }}</view>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- ═══ Pagination ═══ -->
    <view v-if="total > pageSize" class="bd-pager">
      <view class="bd-btn bd-btn--page" :class="{ disabled: pageNum <= 1 }" @click="goPage(pageNum - 1)">上一页</view>
      <text class="bd-page-info">{{ pageNum }} / {{ totalPages }}</text>
      <view class="bd-btn bd-btn--page" :class="{ disabled: pageNum >= totalPages }" @click="goPage(pageNum + 1)">下一页</view>
    </view>

    <!-- ═══ Detail Modal ═══ -->
    <view v-if="showDetail && detail" class="bd-overlay" @click.self="showDetail = false">
      <view class="bd-modal">
        <view class="bd-modal-header">
          <text class="bd-modal-title">会员详情</text>
          <text class="bd-modal-close" @click="showDetail = false">✕</text>
        </view>
        <view class="bd-modal-body">
          <view class="detail-grid">
            <view class="detail-row"><text class="detail-key">ID</text><text class="detail-val">{{ detail.id }}</text></view>
            <view class="detail-row"><text class="detail-key">手机号</text><text class="detail-val">{{ detail.phone }}</text></view>
            <view class="detail-row"><text class="detail-key">昵称</text><text class="detail-val">{{ detail.nickname || '-' }}</text></view>
            <view class="detail-row"><text class="detail-key">积分余额</text><text class="detail-val">{{ detail.pointsBalance || 0 }}</text></view>
            <view class="detail-row"><text class="detail-key">状态</text>
              <text :class="detail.status === 1 ? 'badge badge--on' : 'badge badge--off'">
                {{ detail.status === 1 ? '正常' : '禁用' }}
              </text>
            </view>
            <view class="detail-row"><text class="detail-key">平台会员ID</text><text class="detail-val">{{ detail.platformMemberId || '-' }}</text></view>
            <view class="detail-row"><text class="detail-key">最后登录</text><text class="detail-val">{{ detail.lastLoginTime || '-' }}</text></view>
            <view class="detail-row"><text class="detail-key">注册时间</text><text class="detail-val">{{ detail.createTime || '-' }}</text></view>
          </view>
          <view v-if="detail.platformMember" class="detail-platform">
            <text class="detail-section-title">底座平台会员信息</text>
            <view class="detail-row"><text class="detail-key">统一会员ID</text><text class="detail-val">{{ detail.platformMember.memberId || '-' }}</text></view>
            <view class="detail-row"><text class="detail-key">平台昵称</text><text class="detail-val">{{ detail.platformMember.nickname || '-' }}</text></view>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/index'

const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const orgRoleType = ref('')

const filterPhone = ref('')
const filterStartDate = ref('')
const filterEndDate = ref('')
const filterStatus = ref('')
const statusOptions = [{ label: '全部', value: '' }, { label: '正常', value: '1' }, { label: '禁用', value: '0' }]

const showDetail = ref(false)
const detail = ref(null)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const canToggleStatus = computed(() => orgRoleType.value !== 'partner')
const statusLabel = computed(() => {
  const opt = statusOptions.find(o => o.value === filterStatus.value)
  return opt ? opt.label : '全部'
})

onMounted(async () => {
  await ensureLogin()
  await loadPermission()
  await loadData()
})

async function ensureLogin() {
  const token = uni.getStorageSync('ops_token')
  if (token) return
  // dev: mock login for testing
  try {
    const r = await api.login('13900000001', 'admin123')
    uni.setStorageSync('ops_token', r.token)
    uni.setStorageSync('corp_id', String(r.corpId))
    uni.setStorageSync('ops_member', JSON.stringify(r))
  } catch (e) {
    uni.showToast({ title: '请先登录', icon: 'none' })
  }
}

async function loadPermission() {
  try {
    const prof = await api.permissionProfile()
    orgRoleType.value = prof.orgRoleType || ''
  } catch { orgRoleType.value = '' }
}

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (filterPhone.value) params.phone = filterPhone.value
    if (filterStartDate.value) params.startDate = filterStartDate.value
    if (filterEndDate.value) params.endDate = filterEndDate.value
    if (filterStatus.value) params.status = Number(filterStatus.value)
    const data = await api.adminMembers(params)
    records.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    uni.showToast({ title: e?.msg || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function search() {
  pageNum.value = 1
  loadData()
}

function goPage(n) {
  if (n < 1 || n > totalPages.value) return
  pageNum.value = n
  loadData()
}

function onStatusChange(e) {
  const idx = Number(e.detail.value)
  filterStatus.value = statusOptions[idx].value
}

async function showMemberDetail(id) {
  try {
    detail.value = await api.adminMemberDetail(id)
    showDetail.value = true
  } catch (e) {
    uni.showToast({ title: e?.msg || '加载详情失败', icon: 'none' })
  }
}

async function toggleStatus(member) {
  const newStatus = member.status === 1 ? 0 : 1
  const label = newStatus === 1 ? '启用' : '禁用'
  const { confirm } = await uni.showModal({
    title: '确认操作',
    content: `确定要${label}会员「${member.nickname || member.phone}」吗？`
  })
  if (!confirm) return
  try {
    await api.adminMemberStatus(member.id, newStatus)
    uni.showToast({ title: `已${label}`, icon: 'success' })
    await loadData()
  } catch (e) {
    uni.showToast({ title: e?.msg || '操作失败', icon: 'none' })
  }
}

// H5 file input helpers (not used for member page, for browser reference)
</script>

<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding-bottom:48rpx}
.bd-header{padding:32rpx 24rpx 16rpx;background:linear-gradient(135deg,#FF6B35,#FF8C5A)}
.bd-title{font-size:38rpx;color:#fff;font-weight:700;display:block}
.bd-sub{font-size:24rpx;color:rgba(255,255,255,.75);margin-top:8rpx;display:block}
.bd-stats{display:flex;margin:-20rpx 24rpx 20rpx;background:#fff;border-radius:16rpx;padding:24rpx;box-shadow:0 2rpx 12rpx rgba(0,0,0,.06)}
.bd-stat-item{flex:1;text-align:center}
.bd-stat-num{font-size:44rpx;color:#FF6B35;font-weight:700;display:block}
.bd-stat-label{font-size:22rpx;color:#999;margin-top:4rpx;display:block}
.bd-toolbar{margin:0 24rpx 20rpx}
.bd-filters{display:flex;flex-wrap:wrap;gap:12rpx;align-items:center}
.bd-filter{flex:1;min-width:140rpx;height:64rpx;border:1rpx solid #ddd;border-radius:10rpx;padding:0 16rpx;font-size:26rpx;background:#fff;box-sizing:border-box}
.bd-filter--picker{display:flex;align-items:center}
.bd-picker-val{font-size:26rpx;color:#333}
.bd-btn{display:inline-flex;align-items:center;justify-content:center;height:60rpx;padding:0 24rpx;border-radius:10rpx;font-size:26rpx;font-weight:500;white-space:nowrap}
.bd-btn--search{background:#FF6B35;color:#fff}
.bd-btn--page{background:#fff;border:1rpx solid #ddd;color:#333;min-width:120rpx}
.bd-btn--page.disabled{opacity:.4;pointer-events:none}
.bd-btn--sm{height:48rpx;padding:0 20rpx;font-size:24rpx;background:#f0f0f0;color:#333;border-radius:8rpx}
.bd-btn--warn{background:#ff4d4f;color:#fff}
.bd-btn--ok{background:#52c41a;color:#fff}
.bd-loading,.bd-empty{padding:80rpx;text-align:center;color:#999;font-size:28rpx}
.bd-table-wrap{overflow-x:auto;margin:0 24rpx;background:#fff;border-radius:12rpx}
.bd-table{min-width:960rpx}
.bd-tr{display:flex;border-bottom:1rpx solid #f0f0f0}
.bd-tr:last-child{border-bottom:none}
.bd-th .bd-td{font-weight:600;color:#555;font-size:24rpx;background:#fafafa;padding:16rpx 8rpx}
.bd-td{padding:14rpx 8rpx;font-size:24rpx;color:#333;display:flex;align-items:center;word-break:break-all}
.badge{font-size:22rpx;padding:4rpx 14rpx;border-radius:6rpx;display:inline-block}
.badge--on{background:#e6f7e6;color:#52c41a}
.badge--off{background:#fff1f0;color:#ff4d4f}
.row-actions{display:flex;gap:10rpx}
.bd-pager{display:flex;align-items:center;justify-content:center;gap:20rpx;margin:24rpx 0}
.bd-page-info{font-size:26rpx;color:#666}
.bd-overlay{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.45);z-index:999;display:flex;align-items:center;justify-content:center}
.bd-modal{background:#fff;border-radius:16rpx;width:88%;max-width:640rpx;max-height:80vh;overflow-y:auto}
.bd-modal-header{display:flex;justify-content:space-between;align-items:center;padding:28rpx 28rpx 16rpx}
.bd-modal-title{font-size:32rpx;font-weight:600;color:#333}
.bd-modal-close{font-size:36rpx;color:#999;padding:8rpx}
.bd-modal-body{padding:0 28rpx 28rpx}
.detail-grid{margin-bottom:16rpx}
.detail-row{display:flex;justify-content:space-between;padding:14rpx 0;border-bottom:1rpx solid #f5f5f5;font-size:26rpx}
.detail-key{color:#999}
.detail-val{color:#333;font-weight:500}
.detail-platform{margin-top:16rpx;padding-top:16rpx;border-top:1rpx solid #eee}
.detail-section-title{font-size:26rpx;color:#FF6B35;font-weight:600;margin-bottom:8rpx;display:block}
</style>
