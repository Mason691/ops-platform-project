<template>
  <view class="home-link-row">
    <view class="home-link" @click="goHome">
      <text class="home-icon">⌂</text>
      <text>返回主页</text>
    </view>
  </view>
</template>

<script setup>
/**
 * 员工工作台二级页面统一返回主页入口。
 * 使用 reLaunch 清空页面栈，避免从深层页面返回时停留在中间页。
 */
function goHome() {
  uni.reLaunch({ url: '/pages/employee/dashboard' })
}
</script>

<style scoped>
.home-link-row {
  display: flex;
  justify-content: flex-end;
  padding: 12rpx 24rpx 0;
}
.home-link {
  display: flex;
  align-items: center;
  gap: 6rpx;
  padding: 8rpx 18rpx;
  border-radius: 28rpx;
  background: rgba(255, 255, 255, .96);
  color: #FF6B35;
  font-size: 22rpx;
  font-weight: 600;
  box-shadow: 0 2rpx 10rpx rgba(0, 0, 0, .06);
}
.home-link:active { opacity: .75; }
.home-icon { font-size: 25rpx; line-height: 1; }
</style>
