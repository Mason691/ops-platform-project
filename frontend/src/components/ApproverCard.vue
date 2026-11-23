<template>
  <view v-if="info" class="card approver-card">
    <view class="card-title">
      <text>下一步审批人</text>
      <text v-if="info.ruleText" class="node">{{ info.ruleText }}</text>
    </view>
    <text v-if="info.display" class="approver-main" :class="{ tappable: info.expandable }"
      @click="$emit('toggle')">{{ info.display }}</text>
    <text v-if="info.nodeName" class="approver-sub">审批节点：{{ info.nodeName }}</text>
    <text v-if="info.message" class="approver-hint">{{ info.message }}</text>
  </view>
</template>

<script setup>
/**
 * 下一步/当前审批人卡片（H5 通用）。
 *
 * info 结构来自后端 NextApproverInfo 的展示视图：
 * { display, nodeName, ruleText, message, expandable }
 * 多审批人折叠、角色前缀、兜底文案由父组件组装，本组件只负责渲染。
 */
defineProps({
  info: { type: Object, default: null }
})
defineEmits(['toggle'])
</script>

<style scoped>
.approver-card { background: linear-gradient(135deg, #fff8f4, #fff); border: 1rpx solid #ffe2d6; }
.card-title { display: flex; justify-content: space-between; align-items: center; font-size: 30rpx; font-weight: 600; color: #1D2129; margin-bottom: 18rpx; }
.node { font-size: 24rpx; color: #FF6B35; font-weight: 500; }
.approver-main {
  display: block; font-size: 30rpx; font-weight: 700; color: #1D2129;
  line-height: 1.6; word-break: break-all;
}
.approver-main.tappable { color: #FF6B35; }
.approver-sub { display: block; margin-top: 6rpx; font-size: 23rpx; color: #86909C; }
.approver-hint { display: block; margin-top: 8rpx; font-size: 23rpx; color: #FF7D00; line-height: 1.5; }
</style>
