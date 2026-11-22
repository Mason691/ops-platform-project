<template>
  <view class="flow-progress">
    <view class="flow-head">
      <text class="flow-title">流程进度</text>
      <text class="flow-status" :class="statusClass">{{ statusText }}</text>
    </view>

    <scroll-view v-if="steps.length" class="flow-steps" scroll-x>
      <view class="flow-steps-inner">
        <view v-for="(step, i) in steps" :key="step.key" class="flow-step" :class="stepClass(i)">
          <view class="flow-dot">
            <text v-if="step.done" class="dot-done">✓</text>
            <text v-else-if="step.current" class="dot-current">●</text>
          </view>
          <text class="flow-step-label">{{ step.label }}</text>
          <view v-if="i < steps.length - 1" class="flow-line" :class="{ done: step.done }" />
        </view>
      </view>
    </scroll-view>

    <view v-if="!hasFlow" class="flow-note">{{ flow.message || '流程未发起' }}</view>
    <template v-else>
      <view class="flow-kv">
        <text class="k">当前状态</text>
        <text class="v current">{{ currentStatusText }}</text>
      </view>
      <view class="flow-kv">
        <text class="k">下一状态</text>
        <text class="v next">{{ flow.nextStatusName || '无下一状态' }}</text>
      </view>
      <view v-if="flow.currentNodeName" class="flow-kv">
        <text class="k">当前节点</text><text class="v">{{ flow.currentNodeName }}</text>
      </view>
      <view v-if="flow.nextNodeName" class="flow-kv">
        <text class="k">下一节点</text><text class="v">{{ flow.nextNodeName }}</text>
      </view>
      <view class="flow-kv">
        <text class="k">{{ approverLabel }}</text>
        <text class="v" :class="{ muted: approverMuted }">{{ approverText }}</text>
      </view>
      <view v-if="flow.startedTime" class="flow-kv">
        <text class="k">流程发起</text>
        <text class="v muted">{{ formatTime(flow.startedTime) }}</text>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 订单流程进度：当前/下一业务状态 + 0~6 步骤条 + 当前/下一处理人。
 * flow 为空/未发起/底座不可达时给出明确文案，不占用订单其它信息区域。
 */
const props = defineProps({
  flow: { type: Object, default: () => ({}) },
  statusOptions: { type: Array, default: () => [] },
  bizStatus: { type: [String, Number], default: '' },
  bizStatusName: { type: String, default: '' }
})

const hasFlow = computed(() => props.flow && props.flow.status
  && props.flow.status !== 'NOT_STARTED' && props.flow.status !== 'UNKNOWN')

const steps = computed(() => {
  // 字典接口已按 item_order 返回；不能再对混合 key（数字 + 新状态字符串）排序，
  // 否则 '4' 会排在 WAIT_ACCEPT 之前导致步骤条错位。
  const options = (props.statusOptions || [])
    // 作废退款是终态分支，不属于线性流程，避免出现在步骤条第一格
    .filter(item => item && item.itemKey != null && String(item.itemKey) !== '-1')
    .map(item => ({ key: String(item.itemKey), label: item.itemValue || item.itemKey }))
  if (!options.length) return []
  const currentKey = String(props.flow.currentBizStatus != null
    ? props.flow.currentBizStatus : props.bizStatus)
  let currentIndex = options.findIndex(item => item.key === currentKey)
  if (currentIndex < 0) currentIndex = 0
  return options.map((item, index) => ({
    ...item,
    done: index < currentIndex,
    current: index === currentIndex
  }))
})

const statusText = computed(() => {
  if (!hasFlow.value) return props.flow.status === 'UNKNOWN' ? '待同步' : '未发起'
  return props.flow.statusName || '进行中'
})

const statusClass = computed(() => {
  const status = props.flow && props.flow.status
  if (status === 'COMPLETED') return 'done'
  if (status === 'REJECTED' || status === 'TERMINATED') return 'stopped'
  if (status === 'NOT_STARTED' || status === 'UNKNOWN') return 'pending'
  return 'running'
})

const currentStatusText = computed(() => {
  const name = props.flow.currentBizStatusName || props.bizStatusName
  if (name) return name
  const current = steps.value.find(step => step.current)
  return current ? current.label : '—'
})

function pending(list) {
  return (Array.isArray(list) ? list : []).filter(item => {
    const status = item && item.status
    return !status || String(status).toUpperCase() === 'PENDING'
  })
}

const chosenApprovers = computed(() => {
  const current = pending(props.flow.currentApprovers)
  return current.length ? current : pending(props.flow.nextApprovers)
})

const approverLabel = computed(() => {
  const current = pending(props.flow.currentApprovers)
  return current.length ? '当前处理人' : '下一节点处理人'
})

const approverText = computed(() => {
  const list = chosenApprovers.value
  if (!list.length) return props.flow.resolveMessage || '待分配'
  const names = [...new Set(list.map(item => item.userName || item.approverName).filter(Boolean))]
  if (names.length) {
    return names.length > 1 ? `${names.join('、')}（多人可处理）` : names[0]
  }
  const roles = [...new Set(list.map(item => item.roleName).filter(Boolean))]
  return roles.length ? `${roles.join('、')}（待认领）` : '待分配'
})

const approverMuted = computed(() => !chosenApprovers.value.length)

function stepClass(index) {
  const step = steps.value[index]
  if (!step) return ''
  return step.done ? 'done' : step.current ? 'current' : 'future'
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.flow-progress {
  background: #F7F9FC; border: 1rpx solid #E5EAF2; border-radius: 14rpx;
  padding: 20rpx; display: flex; flex-direction: column; gap: 10rpx;
}
.flow-head { display: flex; align-items: center; justify-content: space-between; }
.flow-title { font-size: 25rpx; font-weight: 600; color: #1D2129; }
.flow-status {
  font-size: 21rpx; padding: 2rpx 12rpx; border-radius: 20rpx;
  background: #E8F3FF; color: #2F6FEB;
}
.flow-status.done { background: #E8F8E8; color: #0B7A42; }
.flow-status.stopped { background: #FFF1F0; color: #C62828; }
.flow-status.pending { background: #F2F3F5; color: #86909C; }
.flow-steps { width: 100%; }
.flow-steps-inner { display: flex; align-items: flex-start; padding: 12rpx 2rpx 4rpx; min-width: 100%; }
.flow-step { position: relative; flex: 0 0 auto; width: 96rpx; display: flex; flex-direction: column; align-items: center; }
.flow-dot {
  width: 28rpx; height: 28rpx; border-radius: 50%; background: #E5E6EB;
  display: flex; align-items: center; justify-content: center; z-index: 1;
}
.flow-step.done .flow-dot { background: #07C160; }
.flow-step.current .flow-dot { background: #FF6B35; box-shadow: 0 0 0 6rpx rgba(255, 107, 53, .15); }
.dot-done, .dot-current { color: #fff; font-size: 18rpx; line-height: 1; }
.flow-step-label { margin-top: 8rpx; font-size: 20rpx; color: #86909C; text-align: center; line-height: 1.3; }
.flow-step.current .flow-step-label { color: #FF6B35; font-weight: 600; }
.flow-step.done .flow-step-label { color: #0B7A42; }
.flow-line {
  position: absolute; top: 13rpx; left: 62rpx; width: 68rpx; height: 2rpx;
  background: #E5E6EB;
}
.flow-line.done { background: #07C160; }
.flow-kv { display: flex; align-items: flex-start; gap: 16rpx; font-size: 23rpx; line-height: 1.5; }
.flow-kv .k { flex: 0 0 132rpx; color: #86909C; }
.flow-kv .v { flex: 1; min-width: 0; color: #1D2129; word-break: break-all; }
.flow-kv .v.current { color: #FF6B35; font-weight: 600; }
.flow-kv .v.next { color: #2F6FEB; font-weight: 600; }
.flow-kv .v.muted { color: #86909C; }
.flow-note { font-size: 23rpx; color: #86909C; line-height: 1.5; padding: 4rpx 0; }
</style>
