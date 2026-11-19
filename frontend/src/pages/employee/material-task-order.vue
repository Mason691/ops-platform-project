<template>
  <view class="page">
    <view class="head">
      <view class="head-title-row">
        <text class="title">关联工单</text>
        <EmployeeHomeLink />
      </view>
      <text class="sub">物料领用单对应的业务数据订单</text>
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
    </view>
    <view v-else-if="info" class="card">
      <view class="kv"><text class="k">工单编号</text><text class="v">{{ info.orderNo || '—' }}</text></view>
      <view class="kv"><text class="k">订单状态</text><text class="v">{{ info.statusLabel || '—' }}</text></view>
      <view class="kv"><text class="k">客户名称</text><text class="v">{{ info.customerName || '—' }}</text></view>
      <view class="kv"><text class="k">客户手机</text><text class="v">{{ info.customerPhoneMasked || '—' }}</text></view>
      <view class="kv"><text class="k">订单类型</text><text class="v">{{ info.orderType || '—' }}</text></view>
      <view class="kv"><text class="k">派工员工</text><text class="v">{{ info.assigneeName || '—' }}</text></view>
      <view class="kv"><text class="k">派工时间</text><text class="v">{{ info.dispatchTime || '—' }}</text></view>
    </view>

    <view class="footer">
      <view class="btn" @click="goBack"><text>返回领用单</text></view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

const pickId = ref(null)
const info = ref(null)
const loading = ref(true)
const errorMsg = ref('')

onLoad(options => {
  pickId.value = options && options.pickId ? decodeURIComponent(options.pickId) : ''
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect='
        + encodeURIComponent('/pages/employee/material-task-order?pickId=' + (pickId.value || ''))
    })
    return
  }
  load()
})

async function load() {
  if (!pickId.value) {
    errorMsg.value = '缺少领用单ID，无法查看关联工单'
    loading.value = false
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await api.scmMaterialPickTaskOrder(pickId.value)
    info.value = data || null
    if (!info.value) {
      errorMsg.value = '该领用单没有关联工单'
    } else if (info.value.deleted) {
      errorMsg.value = '该工单已删除/作废'
    } else if (info.value.readable === false) {
      errorMsg.value = '无权查看该工单'
    }
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载关联工单失败'
  } finally {
    loading.value = false
  }
}

function goBack() {
  const pages = getCurrentPages()
  if (pages.length > 1) {
    uni.navigateBack()
  } else {
    uni.redirectTo({ url: '/pages/employee/material-pick-drafts' })
  }
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 200rpx; }
.head { padding: 8rpx 8rpx 20rpx; display: flex; flex-direction: column; gap: 8rpx; }
.head-title-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.title { font-size: 36rpx; font-weight: 700; color: #1D2129; }
.sub { font-size: 22rpx; color: #86909C; }
.state { padding: 80rpx 0; text-align: center; font-size: 24rpx; color: #86909C; }
.state-err { color: #F53F3F; }
.card { background: #fff; border-radius: 16rpx; padding: 8rpx 24rpx; }
.kv { display: flex; align-items: center; min-height: 76rpx; border-bottom: 1rpx solid #F2F3F5; }
.k { width: 180rpx; font-size: 26rpx; color: #4E5969; }
.v { flex: 1; font-size: 26rpx; color: #1D2129; word-break: break-all; }
.footer { position: fixed; left: 0; right: 0; bottom: 0; padding: 20rpx 24rpx 40rpx; background: #fff; }
.btn { height: 88rpx; line-height: 88rpx; text-align: center; border-radius: 12rpx; background: #FF6B35; color: #fff; font-size: 30rpx; }
</style>
