<template>
  <view class="page">
    <EmployeeHomeLink />
    <view class="head">
      <text class="title">货款补录</text>
      <text class="sub">定金支付订单收齐余款后标记为全款，方可派工出库</text>
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
      <view class="btn ghost" @click="load"><text>重试</text></view>
    </view>
    <view v-else-if="!list.length" class="state">
      <text class="empty-icon">✅</text>
      <text>暂无需补录的定金订单</text>
    </view>
    <view v-else class="list">
      <view v-for="item in list" :key="item.orderId" class="card" @click="openReceive(item)">
        <view class="card-top">
          <text class="order-no">{{ item.orderNo || '—' }}</text>
          <text class="balance">应补 ¥{{ money(item.balanceAmount) }}</text>
        </view>
        <text class="customer">{{ item.customer || '—' }} {{ item.customerPhone || '' }}</text>
        <view class="meta">
          <text>销售价 ¥{{ money(item.totalAmount) }}</text>
          <text>定金 ¥{{ money(item.depositAmount) }}</text>
        </view>
        <view class="card-bottom">
          <text class="time">{{ formatTime(item.createTime) }}</text>
          <text class="go">收款补录 ›</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'
import { ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { formatTime } from '@/utils/materialForm'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

/** 货款补录列表：当前经办人名下定金支付且未全款的业务订单。 */
const loading = ref(true)
const errorMsg = ref('')
const list = ref([])

onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent('/pages/employee/balance-pending')
    })
    return
  }
  load()
})

onPullDownRefresh(async () => {
  await load()
  uni.stopPullDownRefresh()
})

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    list.value = await api.depositPending() || []
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function money(value) {
  return (Number(value) || 0).toFixed(2)
}

function openReceive(item) {
  uni.navigateTo({
    url: '/pages/employee/balance-receive?orderId=' + item.orderId
  })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 40rpx; box-sizing: border-box; }
.head { padding: 8rpx 4rpx 20rpx; display: flex; flex-direction: column; gap: 6rpx; }
.title { font-size: 36rpx; font-weight: 700; color: #1D2129; }
.sub { font-size: 23rpx; color: #86909C; line-height: 1.5; }
.list { display: flex; flex-direction: column; gap: 14rpx; }
.card { background: #fff; border-radius: 16rpx; padding: 24rpx; }
.card:active { background: #FFF8F4; }
.card-top { display: flex; align-items: center; justify-content: space-between; }
.order-no { font-size: 29rpx; font-weight: 700; color: #1D2129; }
.balance { font-size: 25rpx; color: #F53F3F; font-weight: 700; }
.customer { display: block; margin-top: 10rpx; font-size: 26rpx; color: #4E5969; }
.meta { display: flex; gap: 24rpx; margin-top: 8rpx; font-size: 23rpx; color: #86909C; }
.card-bottom { display: flex; justify-content: space-between; align-items: center; margin-top: 14rpx; }
.time { font-size: 21rpx; color: #C9CDD4; }
.go { font-size: 25rpx; color: #FF6B35; font-weight: 600; }
.state { padding: 160rpx 40rpx; display: flex; flex-direction: column; align-items: center; gap: 16rpx; color: #86909C; font-size: 26rpx; }
.state-err { color: #F53F3F; text-align: center; }
.empty-icon { font-size: 72rpx; }
.btn { padding: 14rpx 44rpx; border-radius: 40rpx; font-size: 25rpx; }
.btn.ghost { background: #fff; color: #FF6B35; border: 1rpx solid #FF6B35; }
</style>
