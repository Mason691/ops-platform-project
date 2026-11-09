<template>
  <view class="card">
    <view class="card-title">
      <text class="bar"></text>经办人
      <text class="count">（已选 {{ selectedCount }} 人）</text>
    </view>

    <view v-if="selfEmployee" class="self-row">
      <view class="avatar"><text>{{ initial(selfEmployee.name) }}</text></view>
      <view class="info">
        <text class="name">{{ selfEmployee.name }}</text>
        <text class="phone">{{ selfEmployee.phone }}</text>
      </view>
      <text class="fixed-tag">本人 · 不可移除</text>
    </view>

    <text class="hint">可添加当前组织内的其他员工共同参与；提成按现有规则均分。</text>

    <scroll-view v-if="others.length" scroll-y class="emp-scroll"
                 :style="{ height: others.length > 6 ? '520rpx' : 'auto' }">
      <view v-for="employee in others" :key="employee.userId"
            class="emp-row" :class="{ on: isSelected(employee) }"
            @click="toggle(employee)">
        <view class="checkbox" :class="{ checked: isSelected(employee) }">
          <text v-if="isSelected(employee)" class="check-mark">✓</text>
        </view>
        <view class="info">
          <text class="name">{{ employee.name }}</text>
          <text class="phone">{{ employee.phone }}</text>
        </view>
      </view>
    </scroll-view>
    <text v-else class="empty-hint">当前组织暂无可添加的其他员工</text>

    <text v-if="errors.agentUserIds" class="ferr">{{ errors.agentUserIds }}</text>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { MAX_AGENTS } from '@/utils/businessForm'

const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, required: true }
})

const selfEmployee = computed(() => {
  const found = (props.meta.employees || []).find(item => item && item.isSelf)
  if (found) return found
  if (!props.meta.employeeUserId) return null
  return {
    userId: props.meta.employeeUserId,
    name: props.meta.employeeName || '',
    phone: props.meta.employeePhone || '',
    isSelf: true
  }
})

const others = computed(() =>
  (props.meta.employees || []).filter(item => item && !item.isSelf))

const selectedIds = computed(() =>
  new Set((props.form.agentUserIds || []).map(id => String(id))))

const selectedCount = computed(() => {
  const self = selfEmployee.value ? 1 : 0
  return self + others.value.filter(item => isSelected(item)).length
})

function isSelected(employee) {
  return selectedIds.value.has(String(employee.userId))
}

function toggle(employee) {
  if (!Array.isArray(props.form.agentUserIds)) props.form.agentUserIds = []
  const list = props.form.agentUserIds
  const target = String(employee.userId)
  const index = list.findIndex(id => String(id) === target)
  if (index >= 0) {
    list.splice(index, 1)
    return
  }
  if (list.length >= MAX_AGENTS) {
    uni.showToast({ title: `单笔业务最多选择${MAX_AGENTS}名经办人`, icon: 'none' })
    return
  }
  list.push(Number(employee.userId))
}

function initial(name) {
  const text = String(name || '').trim()
  return text ? text.slice(0, 1) : '员'
}
</script>

<style scoped>
.card {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  margin-bottom: 20rpx; box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.card-title {
  display: flex; align-items: center; font-size: 30rpx;
  font-weight: 600; color: #333; margin-bottom: 20rpx;
}
.bar {
  width: 8rpx; height: 30rpx; background: #FF6B35;
  border-radius: 4rpx; margin-right: 12rpx;
}
.count { font-size: 24rpx; color: #999; font-weight: 400; }
.self-row, .emp-row {
  display: flex; align-items: center; padding: 16rpx 0;
  border-bottom: 1rpx solid #f5f5f5;
}
.self-row { background: #FFF8F4; border-radius: 10rpx; padding: 16rpx; }
.avatar {
  width: 64rpx; height: 64rpx; border-radius: 50%; background: #FF6B35;
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-size: 28rpx; margin-right: 16rpx; flex-shrink: 0;
}
.info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.name {
  font-size: 28rpx; color: #333; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.phone { font-size: 22rpx; color: #999; margin-top: 4rpx; }
.fixed-tag { font-size: 22rpx; color: #FF6B35; white-space: nowrap; }
.hint {
  display: block; font-size: 22rpx; color: #999;
  margin: 16rpx 0 8rpx; line-height: 1.5;
}
.emp-scroll { max-height: 520rpx; }
.emp-row.on .name { color: #FF6B35; font-weight: 600; }
.checkbox {
  width: 36rpx; height: 36rpx; border-radius: 50%; border: 2rpx solid #ccc;
  margin-right: 16rpx; display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.checkbox.checked { background: #FF6B35; border-color: #FF6B35; }
.check-mark { color: #fff; font-size: 24rpx; }
.empty-hint { display: block; font-size: 24rpx; color: #999; padding: 12rpx 0; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
</style>
