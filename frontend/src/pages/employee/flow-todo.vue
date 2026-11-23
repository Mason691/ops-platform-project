<template>
  <view class="page">
    <view class="head">
      <text class="title">待办任务</text>
      <text class="sub">工作流审批与业务处理</text>
    </view>

    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
      <view class="btn ghost" @click="loadData"><text>重试</text></view>
    </view>
    <view v-else-if="!list.length" class="state">
      <text class="empty-icon">✅</text>
      <text>暂无待办任务</text>
    </view>
    <view v-else class="list">
      <view v-for="item in list" :key="item.task.id" class="card" @click="goTask(item)">
        <view class="card-top">
          <view class="node-wrap">
            <text class="node">{{ item.task.nodeName || item.currentNodeName || '待处理' }}</text>
            <text v-if="payTagOf(item)" class="pay-tag"
                  :class="payTagOf(item) === '待收款' ? 'pay-tag-wait' : 'pay-tag-ready'">
              {{ payTagOf(item) }}
            </text>
          </view>
          <text class="time">{{ item.submitTime || '' }}</text>
        </view>
        <text class="summary">{{ item.businessSummary || item.businessId }}</text>
        <view class="card-bottom">
          <text class="initiator">发起人：{{ item.initiatorName || '—' }}</text>
          <text class="go">去处理 ›</text>
        </view>
      </view>
    </view>

    <EmployeeTabBar active-key="todo" @switch="onTabSwitch" />
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'
import {
  TODO_PAGE_SIZE, decorateTodo, filterIneligibleConstruct, filterDispatchTodoVisibility,
  sortTodoByCreateTime
} from '@/utils/workflowTodo'
import EmployeeTabBar from '@/components/EmployeeTabBar.vue'

const loading = ref(true)
const errorMsg = ref('')
const list = ref([])

onShow(() => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent('/pages/employee/flow-todo')
    })
    return
  }
  loadData()
})

async function loadData() {
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await api.workflowTodo({ pageNum: 1, pageSize: TODO_PAGE_SIZE })
    const records = Array.isArray(data && data.records) ? data.records : []
    const filtered = await filterIneligibleConstruct(
      await filterDispatchTodoVisibility(records))
    // 与工作台首页共用同一套过滤/排序口径（任务创建时间倒序），保证两处顺序一致
    const receivedAt = Date.now()
    list.value = sortTodoByCreateTime(decorateTodo(filtered, receivedAt))
  } catch (e) {
    errorMsg.value = e && e.msg ? e.msg : '加载待办失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function payTagOf(item) {
  return (item && item.dispatchInfo && item.dispatchInfo.payTag) || ''
}

function goTask(item) {
  const taskId = item && item.task ? item.task.id : ''
  const instanceId = item && item.task ? item.task.instanceId : ''
  if (!taskId) {
    uni.showToast({ title: '任务信息不完整，请返回重试', icon: 'none' })
    return
  }
  uni.navigateTo({
    url: '/pages/employee/flow-task?taskId=' + taskId + '&instanceId=' + (instanceId || '')
  })
}

function onTabSwitch(key) {
  const routes = {
    ai: '/pages/employee/ai-chat',
    home: '/pages/employee/dashboard',
    todo: '/pages/employee/flow-todo',
    profile: '/pages/employee/profile'
  }
  if (routes[key] && key !== 'todo') uni.redirectTo({ url: routes[key] })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f6f8; padding-bottom: 140rpx; }
.head { padding: 36rpx 32rpx 20rpx; background: linear-gradient(135deg, #FF6B35, #E85D2C); }
.title { display: block; font-size: 40rpx; font-weight: 700; color: #fff; }
.sub { display: block; margin-top: 8rpx; font-size: 24rpx; color: rgba(255,255,255,.85); }
.state { padding: 120rpx 40rpx; display: flex; flex-direction: column; align-items: center; color: #86909C; font-size: 28rpx; }
.state-err { color: #F53F3F; margin-bottom: 24rpx; text-align: center; }
.empty-icon { font-size: 72rpx; margin-bottom: 16rpx; }
.btn { margin-top: 12rpx; padding: 16rpx 48rpx; border-radius: 40rpx; background: #FF6B35; color: #fff; font-size: 26rpx; }
.btn.ghost { background: #fff; color: #FF6B35; border: 1rpx solid #FF6B35; }
.list { padding: 24rpx; }
.card { background: #fff; border-radius: 20rpx; padding: 26rpx; margin-bottom: 20rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.04); }
.card-top { display: flex; justify-content: space-between; align-items: center; gap: 12rpx; }
.node-wrap { display: flex; align-items: center; gap: 10rpx; min-width: 0; }
.node { font-size: 30rpx; font-weight: 600; color: #1D2129; }
.pay-tag { font-size: 20rpx; padding: 3rpx 10rpx; border-radius: 6rpx; font-weight: 600; flex-shrink: 0; }
.pay-tag-wait { background: #FFF5E8; color: #C75A00; }
.pay-tag-ready { background: #E8FFEA; color: #0B7A42; }
.time { font-size: 22rpx; color: #86909C; }
.summary { display: block; margin: 16rpx 0; font-size: 26rpx; color: #4E5969; line-height: 1.6; }
.card-bottom { display: flex; justify-content: space-between; align-items: center; }
.initiator { font-size: 23rpx; color: #86909C; }
.go { font-size: 25rpx; color: #FF6B35; font-weight: 600; }
</style>
