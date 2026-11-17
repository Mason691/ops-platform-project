<template>
  <view class="ti" :class="'ti-' + level" @click="open">
    <view class="ti-bar" />
    <view class="ti-main">
      <view class="ti-top">
        <text class="ti-type">{{ typeLabel }}</text>
        <text v-if="level === 'overdue'" class="ti-tag ti-tag-overdue">已超时</text>
        <text v-else-if="level === 'urgent'" class="ti-tag ti-tag-urgent">临期</text>
        <text class="ti-remain" :class="'ti-remain-' + level">{{ remainText }}</text>
      </view>
      <text class="ti-title">{{ title }}</text>
      <view class="ti-meta">
        <text class="ti-node">{{ nodeName }}</text>
        <text class="ti-dot">·</text>
        <text class="ti-initiator">{{ initiator }}</text>
        <text class="ti-arrive">{{ arriveText }}</text>
      </view>
    </view>
    <text class="ti-arrow">›</text>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import {
  formatArriveTime, formatRemain, urgencyLevel
} from '@/utils/formatRemainTime'
import { businessTypeLabel } from '@/utils/workflowTodo'
import { track } from '@/utils/track'

/**
 * 单条待办：业务类型 / 标题 / 当前节点 / 发起人 / 到达时间 / 剩余时限。
 * 剩余时限基于加载时固化的 __deadlineAt，由父级 now 每分钟驱动重算，
 * 不额外请求接口。
 */
const props = defineProps({
  item: { type: Object, required: true },
  now: { type: Number, default: () => Date.now() }
})

const remainMs = computed(() => (Number.isFinite(props.item.__deadlineAt)
  ? props.item.__deadlineAt - props.now
  : NaN))
const level = computed(() => urgencyLevel(remainMs.value))
const remainText = computed(() => formatRemain(remainMs.value))
const typeLabel = computed(() => businessTypeLabel(props.item.businessType)
  || props.item.definitionName || '待办')
const title = computed(() => props.item.businessSummary || props.item.businessId || '—')
const nodeName = computed(() => (props.item.task && props.item.task.nodeName)
  || props.item.currentNodeName || '待处理')
const initiator = computed(() => props.item.initiatorName || '—')
const arriveText = computed(() => formatArriveTime((props.item.task && props.item.task.createTime)
  || props.item.submitTime))

function open() {
  const task = props.item.task || {}
  if (!task.id) {
    uni.showToast({ title: '任务信息不完整，请返回重试', icon: 'none' })
    return
  }
  track('todo_item_click', {
    taskId: task.id,
    businessType: props.item.businessType,
    urgent: level.value
  })
  uni.navigateTo({
    url: `/pages/employee/flow-task?taskId=${task.id}&instanceId=${task.instanceId || ''}`
  })
}
</script>

<style scoped>
.ti {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 22rpx 0 22rpx 20rpx;
  border-bottom: 1rpx solid #F2F3F5;
}
.ti:last-child { border-bottom: none; }
.ti:active { background: #FAFAFB; }
.ti-bar {
  position: absolute;
  left: 0;
  top: 24rpx;
  bottom: 24rpx;
  width: 6rpx;
  border-radius: 3rpx;
  background: transparent;
}
.ti-overdue .ti-bar { background: #F53F3F; }
.ti-urgent .ti-bar { background: #FF7D00; }
.ti-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.ti-top { display: flex; align-items: center; gap: 8rpx; }
.ti-type {
  font-size: 20rpx;
  color: #4E5969;
  font-weight: 600;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  background: #F2F3F5;
}
.ti-tag { font-size: 19rpx; padding: 2rpx 8rpx; border-radius: 4rpx; font-weight: 600; }
.ti-tag-overdue { background: #FFF1F0; color: #C62828; }
.ti-tag-urgent { background: #FFF5E8; color: #C75A00; }
.ti-remain { margin-left: auto; font-size: 21rpx; font-weight: 600; }
.ti-remain-overdue { color: #F53F3F; }
.ti-remain-urgent { color: #FF7D00; }
.ti-remain-normal, .ti-remain-unknown { color: #86909C; }
.ti-title {
  font-size: 27rpx;
  font-weight: 600;
  color: #1D2129;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ti-meta {
  display: flex;
  align-items: center;
  gap: 6rpx;
  font-size: 21rpx;
  color: #86909C;
  min-width: 0;
}
.ti-node { color: #4E5969; font-weight: 500; flex-shrink: 0; }
.ti-dot { color: #C9CDD4; }
.ti-initiator { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ti-arrive { margin-left: auto; flex-shrink: 0; color: #86909C; }
.ti-arrow { font-size: 30rpx; color: #C9CDD4; flex-shrink: 0; }
</style>
