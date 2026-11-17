<template>
  <view class="success-view">
    <view class="icon-wrap">
      <view class="success-icon"><text>✓</text></view>
    </view>
    <text class="title">制单成功</text>
    <text class="subtitle">业务订单已提交，等待后续审批流转</text>

    <view class="result-card">
      <view class="result-row">
        <text class="k">订单编号</text>
        <text class="v order-no">{{ result.orderNo || '—' }}</text>
      </view>
      <view class="result-row">
        <text class="k">下一审批人</text>
        <text class="v approver" :class="{ fallback: !result.currentApproverName }">{{ approverText }}</text>
      </view>
      <view class="result-row">
        <text class="k">批次</text>
        <text class="v">{{ result.batchNo || '—' }}</text>
      </view>
      <view class="result-row">
        <text class="k">合计金额</text>
        <text class="v amount">¥{{ amount }}</text>
      </view>
      <view class="result-row">
        <text class="k">付款方式</text>
        <text class="v">{{ isDeposit ? '定金支付' : '全款支付' }}</text>
      </view>
      <view v-if="isDeposit" class="result-row">
        <text class="k">定金金额</text>
        <text class="v">¥{{ money(result.depositAmount) }}</text>
      </view>
      <view v-if="isDeposit" class="result-row">
        <text class="k">应补余款</text>
        <text class="v amount">¥{{ money(result.balanceAmount) }}</text>
      </view>
      <view class="result-row">
        <text class="k">生成明细</text>
        <text class="v">{{ detailCount }} 条（{{ productCount }} 个产品 × {{ agentCount }} 名经办人）</text>
      </view>
      <view v-if="result.memberCreated" class="result-row">
        <text class="k">会员</text>
        <text class="v">已自动创建平台会员</text>
      </view>
    </view>

    <view class="actions">
      <view class="btn ghost" @click="$emit('continue')"><text>继续制单</text></view>
      <view class="btn primary" @click="$emit('view-order')"><text>查看订单</text></view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

/** 提交成功页：展示订单编号/金额/明细条数，支持继续制单或跳转订单列表。 */
const props = defineProps({
  result: { type: Object, default: () => ({}) }
})
defineEmits(['continue', 'view-order'])

const amount = computed(() => {
  const value = Number(props.result && props.result.receiptAmount)
  return Number.isFinite(value) ? value.toFixed(2) : '0.00'
})
const isDeposit = computed(() => (props.result && props.result.paymentType) === 'DEPOSIT')
const money = value => (Number(value) || 0).toFixed(2)
const detailCount = computed(() => Number(props.result && props.result.detailCount) || 0)
const productCount = computed(() => Number(props.result && props.result.productCount) || 0)
const agentCount = computed(() => Number(props.result && props.result.agentCount) || 1)
/** 审批人展示：有姓名显示“已提交给 角色：姓名 审批”；否则显示明确兜底文案。 */
const approverText = computed(() => {
  const result = props.result || {}
  if (result.currentApproverName) {
    const who = result.currentApproverRole
      ? `${result.currentApproverRole}：${result.currentApproverName}`
      : result.currentApproverName
    return `已提交给 ${who} 审批`
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
.k { width: 150rpx; flex-shrink: 0; font-size: 25rpx; color: #999; }
.v { flex: 1; font-size: 27rpx; color: #333; line-height: 1.5; }
.order-no { font-weight: 700; color: #FF6B35; letter-spacing: 1rpx; }
.amount { font-size: 32rpx; font-weight: 700; color: #FF6B35; }
.approver { color: #07C160; font-weight: 600; }
.approver.fallback { color: #FF7D00; font-weight: 400; line-height: 1.5; }
.actions { display: flex; gap: 20rpx; width: 100%; margin-top: 48rpx; }
.btn {
  flex: 1; height: 88rpx; line-height: 88rpx; text-align: center;
  border-radius: 44rpx; font-size: 29rpx; font-weight: 600;
}
.btn.ghost { background: #fff; color: #FF6B35; border: 2rpx solid #FF6B35; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn:active { opacity: .85; }
@keyframes popIn {
  from { transform: scale(.6); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}
</style>
