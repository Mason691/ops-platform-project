<template>
  <view v-if="total > 0" class="pager">
    <!-- Page-size picker -->
    <picker class="pager-size-picker" mode="selector" :range="sizeLabels" :value="sizeIdx" @change="onSizeChange">
      <view class="pager-size-val">{{ pageSize }}条/页 ▾</view>
    </picker>
    <text class="pager-total">共 {{ total }} 条</text>
    <!-- Page navigation -->
    <view class="pager-nav">
      <view class="pager-btn" :class="{ disabled: pageNum <= 1 }" @click="goPage(pageNum - 1)">上一页</view>
      <text class="pager-info">{{ pageNum }} / {{ totalPages }}</text>
      <view class="pager-btn" :class="{ disabled: pageNum >= totalPages }" @click="goPage(pageNum + 1)">下一页</view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { PAGE_SIZES } from '@/constants/pagination'

const props = defineProps({
  pageNum: { type: Number, required: true },
  pageSize: { type: Number, required: true },
  total: { type: Number, required: true },
  pageSizeOptions: { type: Array, default: () => [...PAGE_SIZES] },
})

const emit = defineEmits(['update:pageNum', 'update:pageSize', 'change'])

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const sizeLabels = computed(() => props.pageSizeOptions.map(String))
const sizeIdx = computed(() => props.pageSizeOptions.indexOf(props.pageSize))

function onSizeChange(e) {
  const idx = Number(e.detail.value)
  const newSize = props.pageSizeOptions[idx]
  if (newSize && newSize !== props.pageSize) {
    emit('update:pageSize', newSize)
    emit('update:pageNum', 1)
    emit('change')
  }
}

function goPage(n) {
  if (n < 1 || n > totalPages.value) return
  emit('update:pageNum', n)
  emit('change')
}
</script>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20rpx;
  padding: 24rpx 20rpx;
  flex-wrap: wrap;
}

.pager-size-picker { flex-shrink: 0 }
.pager-size-val {
  height: 56rpx; line-height: 56rpx;
  padding: 0 16rpx;
  background: #fff;
  border: 1rpx solid #ddd;
  border-radius: 10rpx;
  font-size: 24rpx; color: #666;
}

.pager-total { font-size: 24rpx; color: #999; flex-shrink: 0 }

.pager-nav { display: flex; align-items: center; gap: 16rpx }
.pager-btn {
  height: 56rpx; line-height: 56rpx;
  padding: 0 20rpx;
  background: #fff;
  border: 1rpx solid #ddd;
  border-radius: 10rpx;
  font-size: 24rpx; color: #333;
}
.pager-btn.disabled { opacity: .4; pointer-events: none }
.pager-info { font-size: 24rpx; color: #666; min-width: 100rpx; text-align: center }
</style>
