<template>
  <view class="etb-bar">
    <view
      v-for="item in tabs"
      :key="item.key"
      class="etb-item"
      :class="{ active: activeKey === item.key }"
      @click="onTab(item.key)"
    >
      <text class="etb-icon">{{ item.icon }}</text>
      <text class="etb-label">{{ item.label }}</text>
    </view>
  </view>
</template>

<script setup>
const props = defineProps({
  activeKey: { type: String, default: 'home' }
})

const emit = defineEmits(['switch'])

const tabs = [
  { key: 'ai',      icon: '🤖', label: 'AI助手' },
  { key: 'home',    icon: '🏠', label: '主页' },
  { key: 'pending', icon: '📋', label: '待处理' },
  { key: 'profile', icon: '🧑', label: '我的' }
]

function onTab(key) {
  if (key === props.activeKey) return
  emit('switch', key)
}
</script>

<style scoped>
.etb-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 110rpx;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-around;
  border-top: 1rpx solid #EDEEF2;
  padding-bottom: env(safe-area-inset-bottom);
  z-index: 100;
}
.etb-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  padding: 8rpx 32rpx;
}
.etb-icon { font-size: 40rpx; }
.etb-label {
  font-size: 22rpx;
  color: #86909C;
}
.etb-item.active .etb-label {
  color: #FF6B35;
  font-weight: 600;
}
</style>
