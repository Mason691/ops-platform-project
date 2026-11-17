<template>
  <view class="page-head">
    <text class="page-head-title">{{ title }}</text>
    <EmployeeHomeLink />
  </view>
</template>

<script setup>
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'

/** 无独立标题头的二级页：统一标题 + 最右侧返回主页链接。 */
defineProps({
  title: { type: String, required: true }
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.page-head-title {
  font-size: 34rpx;
  font-weight: 700;
  color: #1D2129;
  line-height: 1.3;
}
</style>
