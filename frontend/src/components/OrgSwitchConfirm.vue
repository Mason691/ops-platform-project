<template>
  <view v-if="visible" class="osc-mask" @click.stop="onCancel">
    <view class="osc-box" @click.stop>
      <text class="osc-title">切换组织</text>
      <text class="osc-text">确认切换至【{{ corpName }}】？</text>
      <text class="osc-sub">切换后菜单、权限与数据范围将刷新。</text>
      <view class="osc-btns">
        <view class="osc-btn osc-cancel" @click.stop="onCancel">取消</view>
        <view class="osc-btn osc-ok" @click.stop="onConfirm">确认切换</view>
      </view>
    </view>
  </view>
</template>

<script setup>
/**
 * 组织切换二次确认。
 *
 * H5 的 uni.showModal 固定 z-index: 999，组织选择面板遮罩为 1000，
 * 原生弹窗会被面板盖住；这里用组件内固定层（1200）保证确认框始终浮在最前。
 */
defineProps({
  visible: { type: Boolean, default: false },
  corpName: { type: String, default: '' }
})

const emit = defineEmits(['confirm', 'cancel'])

function onCancel() {
  emit('cancel')
}

function onConfirm() {
  emit('confirm')
}
</script>

<style scoped>
.osc-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 48rpx;
  box-sizing: border-box;
}
.osc-box {
  width: 100%;
  max-width: 580rpx;
  padding: 40rpx 36rpx 32rpx;
  background: #fff;
  border-radius: 24rpx;
  box-sizing: border-box;
}
.osc-title {
  display: block;
  text-align: center;
  font-size: 34rpx;
  font-weight: 700;
  color: #1D2129;
}
.osc-text {
  display: block;
  margin-top: 28rpx;
  text-align: center;
  font-size: 30rpx;
  color: #1D2129;
  line-height: 1.5;
  word-break: break-all;
}
.osc-sub {
  display: block;
  margin-top: 12rpx;
  text-align: center;
  font-size: 24rpx;
  color: #86909C;
  line-height: 1.5;
}
.osc-btns {
  display: flex;
  gap: 24rpx;
  margin-top: 36rpx;
}
.osc-btn {
  flex: 1;
  padding: 24rpx 0;
  text-align: center;
  border-radius: 14rpx;
  font-size: 30rpx;
}
.osc-btn:active {
  opacity: 0.85;
}
.osc-cancel {
  background: #F2F3F5;
  color: #4E5969;
}
.osc-ok {
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  color: #fff;
  font-weight: 600;
}
</style>
