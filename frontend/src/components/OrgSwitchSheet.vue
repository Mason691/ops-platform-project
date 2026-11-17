<template>
  <view v-if="visible" class="org-sheet-mask" @click="onMask">
    <view class="org-sheet" @click.stop>
      <view class="sheet-head">
        <view>
          <text class="sheet-title">切换组织</text>
          <text class="sheet-sub">选择本次进入的组织，菜单与数据范围将同步刷新</text>
        </view>
        <text class="sheet-close" @click="close">✕</text>
      </view>

      <scroll-view scroll-y class="org-list">
        <view
          v-for="org in orgs"
          :key="org.corpId"
          class="org-item"
          :class="{ current: isCurrent(org), disabled: loading }"
          @click="choose(org)"
        >
          <view class="org-main">
            <view class="org-title-row">
              <text class="org-title">{{ org.corpName }}</text>
              <text v-if="isCurrent(org)" class="tag tag-current">当前</text>
              <text v-else-if="org.corpId === recentCorpId" class="tag tag-recent">最近登录</text>
              <text v-if="org.isDefault && !isCurrent(org)" class="tag tag-default">常用</text>
            </view>
            <view class="org-roles">
              <text v-for="role in org.roleNames" :key="role" class="role-tag">{{ role }}</text>
              <text v-if="!org.roleNames || !org.roleNames.length" class="role-empty">无组织角色</text>
            </view>
            <text v-if="org.lastLoginTime" class="org-time">
              最近登录 {{ formatLastLogin(org.lastLoginTime) }}
            </text>
          </view>
          <text v-if="isCurrent(org)" class="org-check">✓</text>
          <text v-else class="org-arrow">›</text>
        </view>
      </scroll-view>

      <view v-if="loading" class="sheet-loading">
        <view class="spinner"></view>
        <text>正在切换组织…</text>
      </view>

      <view class="sheet-safe" />
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { formatLastLogin } from '@/utils/orgSwitch'

const props = defineProps({
  visible: { type: Boolean, default: false },
  orgs: { type: Array, default: () => [] },
  currentCorpId: { type: Number, default: 0 },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'select'])

/** 最近登录组织：用于列表展示，不参与默认选中逻辑。 */
const recentCorpId = computed(() => {
  let target = null
  let latest = ''
  for (const org of props.orgs) {
    const time = org.lastLoginTime || ''
    if (time && time > latest) {
      latest = time
      target = org.corpId
    }
  }
  return target
})

function close() {
  if (!props.loading) emit('update:visible', false)
}

function onMask() {
  close()
}

function isCurrent(org) {
  return !!org && (org.isCurrent || Number(org.corpId) === Number(props.currentCorpId))
}

function choose(org) {
  if (props.loading || !org || isCurrent(org)) return
  uni.showModal({
    title: '切换组织',
    content: `确认切换至【${org.corpName}】？切换后菜单、权限与数据范围将刷新。`,
    confirmText: '确认切换',
    success(res) {
      if (res.confirm) emit('select', org)
    }
  })
}
</script>

<style scoped>
.org-sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: flex-end;
}
.org-sheet {
  width: 100%;
  max-height: 78vh;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 32rpx 28rpx 0;
  box-sizing: border-box;
}
.sheet-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding-bottom: 20rpx;
}
.sheet-title {
  display: block;
  font-size: 34rpx;
  font-weight: 700;
  color: #1D2129;
}
.sheet-sub {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #86909C;
}
.sheet-close {
  padding: 8rpx 12rpx;
  color: #86909C;
  font-size: 32rpx;
}
.org-list {
  max-height: 56vh;
}
.org-item {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 24rpx 20rpx;
  margin-bottom: 16rpx;
  border: 2rpx solid #E5E6EB;
  border-radius: 18rpx;
  background: #fff;
}
.org-item.current {
  border-color: #FF6B35;
  background: #FFF7F2;
}
.org-item.disabled {
  opacity: 0.6;
}
.org-item:active:not(.current) {
  background: #F7F8FA;
}
.org-main {
  flex: 1;
  min-width: 0;
}
.org-title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8rpx;
}
.org-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #1D2129;
}
.tag {
  font-size: 20rpx;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
}
.tag-current {
  color: #FF6B35;
  background: #FFE8DA;
}
.tag-recent {
  color: #1D7BFF;
  background: #E8F1FF;
}
.tag-default {
  color: #86909C;
  background: #F2F3F5;
}
.org-roles {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 10rpx;
}
.role-tag {
  font-size: 22rpx;
  color: #4E5969;
  background: #F2F3F5;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
}
.role-empty {
  font-size: 22rpx;
  color: #C9CDD4;
}
.org-time {
  display: block;
  margin-top: 8rpx;
  font-size: 21rpx;
  color: #86909C;
}
.org-check {
  color: #FF6B35;
  font-size: 34rpx;
  font-weight: 700;
}
.org-arrow {
  color: #C9CDD4;
  font-size: 34rpx;
}
.sheet-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  padding: 12rpx 0 4rpx;
  color: #FF6B35;
  font-size: 24rpx;
}
.spinner {
  width: 28rpx;
  height: 28rpx;
  border: 3rpx solid #FFE3D8;
  border-top-color: #FF6B35;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.sheet-safe {
  height: calc(24rpx + env(safe-area-inset-bottom));
}
</style>
