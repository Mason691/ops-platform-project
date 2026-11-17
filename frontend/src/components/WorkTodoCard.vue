<template>
  <view class="todo-card">
    <view class="todo-head">
      <view class="todo-head-left">
        <text class="todo-title">待办任务</text>
        <text v-if="total > 0" class="todo-count">{{ total }}</text>
      </view>
    </view>

    <view v-if="loading" class="todo-state">
      <text class="todo-state-text">加载中...</text>
    </view>

    <view v-else-if="error" class="todo-state">
      <text class="todo-error">{{ error }}</text>
      <view class="todo-retry" @click="reload()"><text>重试</text></view>
    </view>

    <view v-else-if="!items.length" class="todo-empty">
      <text class="todo-empty-icon">🗂️</text>
      <text class="todo-empty-text">暂无待办任务，去开展业务吧</text>
      <view v-if="addable" class="todo-empty-btn" @click="onAdd">
        <text>＋ 新增业务</text>
      </view>
    </view>

    <view v-else class="todo-list">
      <WorkTodoItem
        v-for="(item, index) in items"
        :key="(item.task && item.task.id) || item.businessId || index"
        :item="item"
        :now="nowMs"
      />
    </view>

    <view v-if="!loading && !error && hasMore" class="todo-more" @click="goAll">
      <text>查看更多（共 {{ total }} 条）</text>
      <text class="todo-more-arrow">›</text>
    </view>
  </view>
</template>

<script setup>
import { onMounted } from 'vue'
import WorkTodoItem from '@/components/WorkTodoItem.vue'
import { useWorkTodo } from '@/hooks/useWorkTodo'
import { track } from '@/utils/track'

/**
 * 工作台「待办任务」主卡片：最多展示 5 条，超出显示「查看更多（共 N 条）」。
 * 数据口径与完整待办列表页一致（同一接口 + 同一过滤排序工具）。
 */
defineProps({
  addable: { type: Boolean, default: true }
})
const emit = defineEmits(['add-business'])

const { items, total, hasMore, loading, error, nowMs, load, startTicker } = useWorkTodo()

onMounted(() => {
  load()
  startTicker()
})

function goAll() {
  track('todo_more_click', { total: total.value })
  // 与底部「待办」Tab 一致用 redirectTo：flow-todo 是 Tab 级页面，
  // 若用 navigateTo，配合其「主页」Tab 的 redirectTo 会让页面栈每轮 +1，
  // 达到 uni-app 10 页上限后跳转会静默失败。
  uni.redirectTo({ url: '/pages/employee/flow-todo' })
}

function onAdd() {
  // 只记录空态入口来源；真正的 add_business_click 由 AddBusinessButton 统一上报，
  // 避免同一次点击产生两条同名事件。
  track('todo_empty_add_click', {})
  emit('add-business')
}

function reload(options) {
  load(options)
}

defineExpose({ reload })
</script>

<style scoped>
.todo-card {
  margin: 24rpx 24rpx 0;
  background: #fff;
  border-radius: 20rpx;
  padding: 24rpx;
  box-shadow: 0 2rpx 16rpx rgba(0, 0, 0, .04);
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.todo-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8rpx;
}
.todo-head-left { display: flex; align-items: center; gap: 10rpx; }
.todo-title { font-size: 28rpx; font-weight: 700; color: #1D2129; }
.todo-count {
  min-width: 34rpx;
  height: 34rpx;
  line-height: 34rpx;
  padding: 0 10rpx;
  border-radius: 17rpx;
  background: #FFF1E8;
  color: #FF6B35;
  font-size: 21rpx;
  font-weight: 700;
  text-align: center;
  box-sizing: border-box;
}
.todo-state {
  padding: 32rpx 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
}
.todo-state-text { font-size: 23rpx; color: #A9AEB8; }
.todo-error { font-size: 23rpx; color: #F53F3F; text-align: center; }
.todo-retry {
  padding: 10rpx 36rpx;
  border: 1rpx solid #FF6B35;
  border-radius: 30rpx;
  font-size: 23rpx;
  color: #FF6B35;
}
.todo-empty {
  padding: 36rpx 0 24rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14rpx;
}
.todo-empty-icon { font-size: 64rpx; }
.todo-empty-text { font-size: 24rpx; color: #86909C; }
.todo-empty-btn {
  margin-top: 8rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 88rpx;
  padding: 0 44rpx;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #FF8C5A, #FF6B35);
  box-shadow: 0 6rpx 16rpx rgba(255, 107, 53, .24);
  font-size: 25rpx;
  font-weight: 600;
  color: #fff;
}
.todo-list { display: flex; flex-direction: column; }
.todo-more {
  margin-top: 4rpx;
  min-height: 88rpx;
  padding: 0;
  border-top: 1rpx solid #F2F3F5;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  font-size: 24rpx;
  color: #FF6B35;
}
.todo-more-arrow { font-size: 28rpx; font-weight: 600; }
</style>
