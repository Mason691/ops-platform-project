<template>
  <view v-if="visible" class="overlay">
    <view class="sheet">
      <view class="sheet-head">
        <text class="title">{{ resign ? '重新签署告知书' : '客户签字确认' }}</text>
        <text class="close" @click="close">×</text>
      </view>

      <scroll-view scroll-y class="sheet-body">
        <view class="notice">
          <text class="notice-title">施工用户告知书</text>
          <text class="notice-content">{{ content || '告知书内容加载中...' }}</text>
        </view>

        <view v-if="resign && existingNotice && existingNotice.signImageUrl" class="existing">
          <text class="label">上一次签字（提交后作废，历史版本保留）</text>
          <image :src="assetUrl(existingNotice.signImageUrl)" class="existing-img" mode="aspectFit" />
        </view>

        <view class="sign-area">
          <view class="sign-head">
            <text class="label">客户手写签名</text>
            <text class="clear" @click="clearSign">清除重写</text>
          </view>
          <view class="canvas-wrap">
            <canvas class="sign-canvas" canvas-id="customer-sign" id="customer-sign"
                    disable-scroll="true"
                    @touchstart="sign.start"
                    @touchmove.stop.prevent="sign.move"
                    @touchend="sign.end" />
            <text v-if="!sign.hasInk.value" class="sign-placeholder">请客户在此签名</text>
          </view>
          <text class="sign-hint">
            请客户在施工员手机上用手指签名。提交后生成告知书凭证（含内容快照与签字图片哈希），
            开工后不可修改。
          </text>
        </view>
      </scroll-view>

      <view class="sheet-foot">
        <button class="btn ghost" :disabled="submitting" @click="close">取消</button>
        <button class="btn primary" :disabled="submitting || !contentReady" @click="submit">
          {{ submitting ? '提交中...' : '客户确认签字' }}
        </button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, nextTick, watch } from 'vue'
import { useSignature } from '@/hooks/useSignature'
import { assetUrl } from '@/utils/image'

const props = defineProps({
  visible: { type: Boolean, default: false },
  orderId: { type: [Number, String], default: '' },
  content: { type: String, default: '' },
  existingNotice: { type: Object, default: null },
  resign: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false }
})
const emit = defineEmits(['update:visible', 'submit'])

const sign = useSignature('customer-sign')
/** 告知书未加载成功时禁止签字，避免归档空白凭证 */
const contentReady = computed(() => String(props.content || '').trim().length > 0)

watch(() => props.visible, open => {
  if (open) nextTick(() => sign.attach())
})

function clearSign() {
  sign.clear()
}

function close() {
  if (props.submitting) return
  emit('update:visible', false)
}

async function submit() {
  let image
  try {
    image = await sign.toBase64()
  } catch (e) {
    uni.showToast({ title: (e && e.message) || '请客户完成手写签字', icon: 'none' })
    return
  }
  emit('submit', { signImageBase64: image })
}
</script>

<style scoped>
.overlay {
  position: fixed; inset: 0; z-index: 950; background: rgba(0, 0, 0, .45);
  display: flex; align-items: flex-end; justify-content: center;
}
.sheet {
  width: 100%; max-width: 560px; max-height: 92vh; background: #fff;
  border-radius: 24rpx 24rpx 0 0; display: flex; flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom);
}
.sheet-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 26rpx 30rpx; border-bottom: 1rpx solid #F2F3F5;
}
.title { font-size: 32rpx; font-weight: 700; color: #1D2129; }
.close { font-size: 44rpx; color: #A9AEB8; line-height: 1; padding: 0 8rpx; }
.sheet-body { flex: 1; min-height: 0; padding: 24rpx 30rpx; box-sizing: border-box; }
.notice { background: #FAFAFA; border: 1rpx solid #EEEFF2; border-radius: 12rpx; padding: 24rpx; }
.notice-title {
  display: block; text-align: center; font-size: 30rpx; font-weight: 700;
  color: #1D2129; margin-bottom: 16rpx;
}
.notice-content {
  display: block; white-space: pre-wrap; font-size: 24rpx; line-height: 1.9; color: #4E5969;
  word-break: break-all;
}
.existing { margin-top: 22rpx; }
.label { display: block; font-size: 25rpx; font-weight: 600; color: #1D2129; }
.existing-img {
  margin-top: 10rpx; width: 100%; height: 200rpx; background: #fff;
  border: 1rpx solid #EEEFF2; border-radius: 10rpx;
}
.sign-area { margin-top: 22rpx; }
.sign-head { display: flex; align-items: center; justify-content: space-between; }
.clear { font-size: 23rpx; color: #2F6FEB; padding: 6rpx 0; }
.canvas-wrap {
  position: relative; margin-top: 10rpx; background: #fff;
  border: 2rpx dashed #C9CDD4; border-radius: 12rpx; overflow: hidden;
  height: 340rpx;
}
.sign-canvas { width: 100%; height: 340rpx; }
.sign-placeholder {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  font-size: 26rpx; color: #C9CDD4; pointer-events: none;
}
.sign-hint { display: block; margin-top: 10rpx; font-size: 21rpx; color: #A9AEB8; line-height: 1.6; }
.sheet-foot {
  display: flex; gap: 16rpx; padding: 20rpx 30rpx 26rpx;
  border-top: 1rpx solid #F2F3F5;
}
.btn {
  flex: 1; height: 84rpx; line-height: 84rpx; border-radius: 42rpx;
  font-size: 28rpx; text-align: center; border: none;
}
.btn.primary { background: #FF6B35; color: #fff; }
.btn.primary[disabled] { opacity: .55; }
.btn.ghost { background: #F2F3F5; color: #4E5969; }
</style>
