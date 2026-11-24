<template>
  <view class="page">
    <view class="head">
      <view class="head-title-row">
        <text class="title">向上采购申请</text>
        <EmployeeHomeLink />
      </view>
      <text class="sub">部长审核勾选的采购商品；财务核对通过后可发起采购申请</text>
    </view>

    <view v-if="!forbidden" class="pending-summary">
      <view class="pending-main">
        <text class="pending-label">待发起采购申请</text>
        <text class="pending-hint">已核对 · 已勾选 · 未发起</text>
      </view>
      <text class="pending-value">{{ pendingCount }} 项</text>
    </view>

    <view v-if="!forbidden" class="status-filter">
      <view v-for="option in statusOptions" :key="option.value" class="filter-chip"
            :class="{ on: status === option.value }" @click="setStatus(option.value)">
        {{ option.label }}
      </view>
    </view>

    <view v-if="forbidden" class="state">
      <text class="empty-icon">🔒</text>
      <text class="state-err">无权限访问向上采购申请</text>
      <text class="state-sub">{{ forbiddenText }}</text>
      <view class="btn ghost" @click="goHome"><text>返回工作台</text></view>
    </view>
    <view v-else-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
      <view class="btn ghost" @click="load"><text>重试</text></view>
    </view>
    <view v-else-if="!groups.length" class="state">
      <text class="empty-icon">📋</text>
      <text>暂无向上采购申请</text>
      <text class="state-sub">部长审核勾选商品且财务核对通过后，会显示在这里</text>
    </view>
    <view v-else class="list">
      <UpwardPurchaseCard v-for="group in groups" :key="group.key" :group="group"
                          :submitting="submittingKey === group.key"
                          @initiate="initiate" />
      <view v-if="hasMore" class="load-more" @click="loadMore">
        <text>{{ loadingMore ? '加载中...' : '加载更多' }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'
import UpwardPurchaseCard from '@/components/UpwardPurchaseCard.vue'
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { useUpwardPurchase } from '@/hooks/useUpwardPurchase'
import {
  isEmployeeLoggedIn, hasEmployeePermission, isExternalUser, PERM_VERIFIED_BUSINESS
} from '@/utils/employeeSession'

const forbidden = ref(false)
const forbiddenText = ref('该功能仅对具备采购 H5 制单权限的角色开放')
const {
  loading, loadingMore, errorMsg, pendingCount, status, submittingKey,
  groups, hasMore, statusOptions, load, loadMore, setStatus, initiate
} = useUpwardPurchase()

onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent('/pages/employee/verified-business')
    })
    return
  }
  if (isExternalUser()) {
    forbidden.value = true
    forbiddenText.value = '外部用户无权限访问向上采购申请'
    loading.value = false
    return
  }
  if (!hasEmployeePermission(PERM_VERIFIED_BUSINESS)) {
    forbiddenText.value = '该功能仅对具备采购 H5 制单权限的角色开放'
    forbidden.value = true
    loading.value = false
    return
  }
  forbidden.value = false
  load()
})

onPullDownRefresh(async () => {
  if (!forbidden.value) {
    await load()
  }
  uni.stopPullDownRefresh()
})

function goHome() {
  uni.reLaunch({ url: '/pages/employee/dashboard' })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f6f8; padding-bottom: 40rpx; }
.head { padding: 36rpx 32rpx 20rpx; background: linear-gradient(135deg, #FF6B35, #E85D2C); }
.head-title-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.title { display: block; font-size: 40rpx; font-weight: 700; color: #fff; }
.sub { display: block; margin-top: 8rpx; font-size: 24rpx; color: rgba(255,255,255,.85); line-height: 1.5; }
.pending-summary {
  display: flex; align-items: center; justify-content: space-between;
  margin: 20rpx 24rpx 0; padding: 22rpx 24rpx; border-radius: 16rpx;
  background: #fff; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.04);
}
.pending-main { display: flex; flex-direction: column; gap: 4rpx; }
.pending-label { font-size: 25rpx; color: #4E5969; font-weight: 600; }
.pending-hint { font-size: 20rpx; color: #86909C; }
.pending-value { font-size: 38rpx; line-height: 1; font-weight: 700; color: #F53F3F; }
.status-filter { display: flex; gap: 14rpx; padding: 20rpx 24rpx 0; }
.filter-chip { padding: 12rpx 24rpx; border-radius: 32rpx; background: #fff; font-size: 23rpx; color: #4E5969; border: 1rpx solid #E5E6EB; }
.filter-chip.on { background: #FFF1E8; color: #FF6B35; border-color: #FF6B35; font-weight: 600; }
.state { padding: 120rpx 40rpx; display: flex; flex-direction: column; align-items: center; color: #86909C; font-size: 28rpx; }
.state-err { color: #F53F3F; margin-bottom: 12rpx; text-align: center; }
.state-sub { color: #86909C; font-size: 24rpx; text-align: center; margin-top: 10rpx; margin-bottom: 24rpx; line-height: 1.5; }
.empty-icon { font-size: 72rpx; margin-bottom: 16rpx; }
.list { padding: 24rpx; }
.load-more { padding: 20rpx 0 8rpx; text-align: center; font-size: 24rpx; color: #FF6B35; }
.btn.ghost { padding: 16rpx 40rpx; border-radius: 36rpx; background: #fff; color: #FF6B35; border: 1rpx solid #FF6B35; font-size: 26rpx; }
</style>
