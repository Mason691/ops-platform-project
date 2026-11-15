<template>
  <view
    class="org-switcher"
    :class="{ clickable: orgCount > 1 }"
    @click="onClick"
  >
    <text class="org-name">{{ corpName || '当前组织' }}</text>
    <text v-if="orgCount > 1" class="org-arrow">▾</text>
  </view>
</template>

<script setup>
const props = defineProps({
  corpName: { type: String, default: '' },
  orgCount: { type: Number, default: 0 }
})

const emit = defineEmits(['open'])

function onClick() {
  if (props.orgCount > 1) emit('open')
}
</script>

<style scoped>
.org-switcher {
  display: flex;
  align-items: center;
  gap: 6rpx;
  max-width: 300rpx;
  padding: 8rpx 16rpx;
  border-radius: 32rpx;
  background: #F7F8FA;
}
.org-switcher.clickable:active {
  background: #EDEEF2;
}
.org-name {
  font-size: 24rpx;
  color: #1D2129;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.org-arrow {
  font-size: 22rpx;
  color: #86909C;
  flex-shrink: 0;
}
</style>
