<template>
  <view class="success-view">
    <view class="icon-wrap">
      <view class="success-icon"><text>✓</text></view>
    </view>
    <text class="title">{{ requisition ? '领用申请已提交' : '核销申请已提交' }}</text>
    <text class="subtitle">等待审批流转，可在记录中查看进度</text>

    <view class="result-card">
      <view class="result-row">
        <text class="k">{{ requisition ? '领用单号' : '核销单号' }}</text>
        <text class="v order-no">{{ requisition ? (result.requisitionNo || '—') : (result.writeOffNo || '—') }}</text>
      </view>
      <view v-if="!requisition" class="result-row">
        <text class="k">关联领用单</text>
        <text class="v">{{ result.requisitionNo || '—' }}</text>
      </view>
      <view class="result-row">
        <text class="k">当前审批人</text>
        <text class="v approver"
          :class="{ fallback: !result.currentApprovalDisplay && !result.currentApproverName }">{{ approverText }}</text>
      </view>
      <view class="result-row">
        <text class="k">物料</text>
        <text class="v">{{ result.itemCount || 0 }} 种</text>
      </view>
      <view v-if="requisition" class="result-row">
        <text class="k">领用件数</text>
        <text class="v amount">{{ result.totalQty || 0 }} 件</text>
      </view>
      <template v-else>
        <view class="result-row">
          <text class="k">核销件数</text>
          <text class="v amount">{{ result.writeOffTotal || 0 }} 件</text>
        </view>
        <view class="result-row">
          <text class="k">退回件数</text>
          <text class="v">{{ result.returnTotal || 0 }} 件</text>
        </view>
      </template>
    </view>

    <view class="actions">
      <view class="btn ghost" @click="$emit('continue')"><text>{{ requisition ? '继续领用' : '继续核销' }}</text></view>
      <view class="btn primary" @click="$emit('view-records')"><text>{{ requisition ? '查看领用记录' : '查看核销记录' }}</text></view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

/** 物资领用/核销提交成功页：单号、下一审批人、数量汇总与两个后续动作。 */
const props = defineProps({
  result: { type: Object, default: () => ({}) },
  kind: { type: String, default: 'requisition' }
})
defineEmits(['continue', 'view-records'])

const requisition = computed(() => props.kind !== 'write-off')
const approverText = computed(() => {
  const result = props.result || {}
  if (result.currentApprovalDisplay) {
    return result.currentApprovalDisplay
  }
  if (result.currentApproverName) {
    return result.currentApproverRole
      ? `${result.currentApproverRole}（${result.currentApproverName}）`
      : result.currentApproverName
  }
  return result.approverMessage || '审批人信息加载中，请稍后查看'
})
</script>

<style scoped>
.success-view {
  min-height: 100vh; background: #f5f5f5; box-sizing: border-box;
  padding: 80rpx 32rpx calc(48rpx + env(safe-area-inset-bottom));
  display: flex; flex-direction: column; align-items: center;
}
.icon-wrap { margin-bottom: 24rpx; }
.success-icon {
  width: 128rpx; height: 128rpx; border-radius: 50%;
  background: linear-gradient(135deg, #07C160, #35D07F);
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 72rpx; font-weight: 700;
  box-shadow: 0 12rpx 32rpx rgba(7, 193, 96, .25);
  animation: popIn .32s cubic-bezier(.22, 1.2, .36, 1);
}
.title { font-size: 40rpx; font-weight: 700; color: #222; }
.subtitle { font-size: 24rpx; color: #999; margin-top: 10rpx; }
.result-card {
  width: 100%; background: #fff; border-radius: 18rpx;
  padding: 28rpx 26rpx; margin-top: 36rpx; box-sizing: border-box;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.result-row { display: flex; align-items: flex-start; padding: 14rpx 0; }
.result-row + .result-row { border-top: 1rpx solid #f7f7f7; }
.k { width: 160rpx; flex-shrink: 0; font-size: 25rpx; color: #999; }
.v { flex: 1; font-size: 27rpx; color: #333; line-height: 1.5; word-break: break-all; }
.order-no { font-weight: 700; color: #FF6B35; letter-spacing: 1rpx; }
.amount { font-size: 32rpx; font-weight: 700; color: #FF6B35; }
.approver { color: #07C160; font-weight: 600; }
.approver.fallback { color: #FF7D00; font-weight: 400; line-height: 1.5; }
.actions { display: flex; gap: 20rpx; width: 100%; margin-top: 48rpx; }
.btn { flex: 1; height: 88rpx; line-height: 88rpx; text-align: center; border-radius: 44rpx; font-size: 29rpx; font-weight: 600; }
.btn.ghost { background: #fff; color: #FF6B35; border: 2rpx solid #FF6B35; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn:active { opacity: .85; }
@keyframes popIn {
  from { transform: scale(.6); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}
</style>
