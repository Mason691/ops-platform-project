<template>
  <view class="agent-picker" :class="{ 'picker-error': errors.agentUserIds }">
    <view class="selected-row">
      <view class="selected-item">
        <view class="avatar"><text>{{ initial(selfName) }}</text></view>
        <view class="info">
          <text class="name">{{ selfName }}<text class="self-tag">本人</text></text>
          <text class="phone">{{ meta.employeePhone || '' }}</text>
        </view>
        <text class="fixed-tag">不可移除</text>
      </view>
      <text class="count">已选 {{ selectedCount }} 人</text>
    </view>

    <view v-if="others.length > 4" class="search-row">
      <input class="search-input" v-model="keyword" placeholder="搜索员工姓名/手机号" />
      <text v-if="keyword" class="search-clear" @click="keyword = ''">清空</text>
    </view>

    <scroll-view v-if="others.length" scroll-y class="emp-scroll"
                 :style="{ maxHeight: others.length > 6 ? '520rpx' : 'auto' }">
      <view v-for="employee in filteredOthers" :key="employee.userId"
            class="emp-card" :class="{ on: isSelected(employee) }"
            @click="toggle(employee)">
        <view class="avatar" :class="{ muted: !isSelected(employee) }">
          <text>{{ initial(employee.name) }}</text>
        </view>
        <view class="info">
          <text class="name">{{ employee.name }}</text>
          <text class="phone">{{ employee.phone || '' }}</text>
        </view>
        <view class="checkbox" :class="{ checked: isSelected(employee) }">
          <text v-if="isSelected(employee)" class="check-mark">✓</text>
        </view>
      </view>
      <view v-if="!filteredOthers.length" class="emp-empty">
        <text>没有匹配的员工</text>
      </view>
    </scroll-view>
    <text v-else class="emp-empty">当前组织暂无可添加的其他员工</text>

    <text v-if="errors.agentUserIds" class="ferr">{{ errors.agentUserIds }}</text>
    <text v-else class="hint">{{ hint }}</text>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { MAX_AGENTS } from '@/utils/businessForm'

/**
 * 经办人多选：本人固定不可移除，其他员工以头像卡片直接勾选，
 * 人数较多时提供搜索，避免下拉框。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  hint: { type: String, default: '可添加当前组织内的其他员工共同参与，提成按现有规则均分' }
})

const keyword = ref('')

const isSelfOf = item => !!(item && (item.isSelf === true || item.self === true))
const selfName = computed(() => props.meta.employeeName
  || (props.meta.employees || []).find(isSelfOf)?.name || '本人')

const others = computed(() =>
  (props.meta.employees || []).filter(item => item && !isSelfOf(item)))

const filteredOthers = computed(() => {
  const word = keyword.value.trim().toLowerCase()
  if (!word) return others.value
  return others.value.filter(item =>
    String(item.name || '').toLowerCase().includes(word)
    || String(item.phone || '').includes(word))
})

const selectedIds = computed(() =>
  new Set((props.form.agentUserIds || []).map(id => String(id))))

const selectedCount = computed(() => {
  const self = props.meta.employeeUserId ? 1 : 0
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
.agent-picker { width: 100%; }
.picker-error {
  border: 2rpx solid #e53935; border-radius: 12rpx;
  padding: 12rpx; background: #FFF8F8; box-sizing: border-box;
}
.selected-row {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12rpx;
}
.selected-item {
  display: flex; align-items: center; flex: 1; min-width: 0;
  background: #FFF8F4; border-radius: 12rpx; padding: 16rpx;
}
.count { font-size: 24rpx; color: #999; margin-left: 16rpx; white-space: nowrap; }
.avatar {
  width: 64rpx; height: 64rpx; border-radius: 50%; background: #FF6B35;
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-size: 28rpx; margin-right: 16rpx; flex-shrink: 0;
}
.avatar.muted { background: #d9d9d9; }
.info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.name {
  font-size: 27rpx; color: #333; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.self-tag {
  font-size: 20rpx; color: #FF6B35; background: #FFF0EB;
  border-radius: 6rpx; padding: 2rpx 8rpx; margin-left: 10rpx;
}
.phone { font-size: 22rpx; color: #999; margin-top: 4rpx; }
.fixed-tag { font-size: 22rpx; color: #FF6B35; white-space: nowrap; }
.search-row {
  display: flex; align-items: center; background: #f5f5f5;
  border-radius: 10rpx; padding: 0 18rpx; margin: 12rpx 0;
}
.search-input { flex: 1; height: 68rpx !important; min-height: 68rpx !important; font-size: 26rpx; color: #333; }
.search-clear { font-size: 23rpx; color: #999; padding-left: 12rpx; }
.emp-scroll { margin-top: 8rpx; }
.emp-card {
  display: flex; align-items: center; padding: 16rpx 12rpx;
  border: 2rpx solid transparent; border-radius: 12rpx; margin-bottom: 10rpx;
  background: #fcfcfc;
}
.emp-card.on { border-color: #FF6B35; background: #FFF8F4; }
.emp-card.on .name { color: #FF6B35; font-weight: 600; }
.checkbox {
  width: 38rpx; height: 38rpx; border-radius: 50%; border: 2rpx solid #ccc;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.checkbox.checked { background: #FF6B35; border-color: #FF6B35; }
.check-mark { color: #fff; font-size: 24rpx; }
.emp-empty {
  display: block; font-size: 24rpx; color: #999;
  padding: 20rpx 0; text-align: center;
}
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.hint { display: block; font-size: 22rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
</style>
