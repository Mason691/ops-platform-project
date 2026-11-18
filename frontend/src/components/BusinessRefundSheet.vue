<template>
  <view v-if="visible" class="refund-mask" @click="close">
    <view class="refund-sheet" @click.stop>
      <view class="sheet-head">
        <text class="sheet-title">发起退款申请</text>
        <text class="sheet-close" @click="close">×</text>
      </view>

      <scroll-view scroll-y class="sheet-body">
        <view class="field">
          <text class="label">退款金额 <text class="req">*</text></text>
          <view class="amount-box">
            <text class="currency">¥</text>
            <input class="amount-input" type="digit" :value="form.amount"
                   placeholder="请输入退款金额" @input="onAmount" />
          </view>
          <text class="hint">默认订单应付合计 ¥{{ money(payable) }}，不得超过</text>
          <text v-if="amountError" class="ferr">{{ amountError }}</text>
        </view>

        <view class="field">
          <text class="label">退款原因 <text class="req">*</text></text>
          <textarea class="textarea" :value="form.reason" maxlength="500"
                    placeholder="请填写退款原因（必填，最多500字）" @input="onReason" />
          <view class="field-foot">
            <text v-if="reasonError" class="ferr">{{ reasonError }}</text>
            <text class="counter">{{ (form.reason || '').length }}/500</text>
          </view>
        </view>

        <view class="field">
          <text class="label">备注</text>
          <textarea class="textarea" :value="form.remark" maxlength="500"
                    placeholder="选填，最多500字" @input="onRemark" />
        </view>

        <view class="field">
          <text class="label">补充附件</text>
          <BusinessAttachmentUploader v-model="form.attachments" file-type="REFUND"
                                      :max="5" accept="image-pdf" add-text="上传附件"
                                      hint-text="支持 JPG、PNG、PDF；最多5份，单个不超过10MB" />
        </view>
      </scroll-view>

      <view class="sheet-foot">
        <view class="btn ghost" @click="close"><text>取消</text></view>
        <view class="btn primary" :class="{ disabled: submitting }" @click="submit">
          <text>{{ submitting ? '提交中...' : '提交退款申请' }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import BusinessAttachmentUploader from '@/components/BusinessAttachmentUploader.vue'
import { api } from '@/api/index'

/**
 * 员工发起作废退款：金额默认订单应付合计、原因必填、可上传补充附件。
 * 提交后订单进入部长→财务→总经办审批；审批期间经办人不可重复发起/退回草稿。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  order: { type: Object, default: null },
  orderId: { type: [String, Number], default: '' }
})
const emit = defineEmits(['update:visible', 'submitted'])

const submitting = ref(false)
const amountError = ref('')
const reasonError = ref('')
const form = reactive({ amount: '', reason: '', remark: '', attachments: [] })

const payable = computed(() => {
  const value = Number(props.order && props.order.payableAmount)
  return Number.isFinite(value) && value >= 0 ? value : 0
})

watch(() => props.visible, value => {
  if (value) reset()
})

function reset() {
  form.amount = payable.value > 0 ? payable.value.toFixed(2) : ''
  form.reason = ''
  form.remark = ''
  form.attachments = []
  amountError.value = ''
  reasonError.value = ''
}

function onAmount(event) {
  form.amount = String(event.detail.value ?? '')
  amountError.value = ''
}

function onReason(event) {
  form.reason = String(event.detail.value ?? '')
  reasonError.value = ''
}

function onRemark(event) {
  form.remark = String(event.detail.value ?? '')
}

async function submit() {
  if (submitting.value) return
  const amount = Number(form.amount)
  const reason = String(form.reason || '').trim()
  amountError.value = ''
  reasonError.value = ''
  let invalid = false
  if (!reason) {
    reasonError.value = '请填写退款原因'
    invalid = true
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    amountError.value = '请输入大于0的退款金额'
    invalid = true
  } else if (amount > payable.value + 0.005) {
    amountError.value = `退款金额不能超过应付合计 ¥${money(payable.value)}`
    invalid = true
  }
  if (invalid) return
  submitting.value = true
  try {
    await api.employeeApplyRefund(props.orderId, {
      amount: Math.round(amount * 100) / 100,
      reason,
      remark: String(form.remark || '').trim(),
      attachmentIds: (form.attachments || []).map(file => file && file.id).filter(Boolean)
    })
    uni.showToast({ title: '退款申请已提交', icon: 'success' })
    emit('submitted')
    close()
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '提交失败，请重试', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
}

function money(value) {
  return (Number(value) || 0).toFixed(2)
}
</script>

<style scoped>
.refund-mask {
  position: fixed; inset: 0; z-index: 200;
  background: rgba(0, 0, 0, .42);
  display: flex; align-items: flex-end;
}
.refund-sheet {
  width: 100%; max-height: 86vh; background: #fff;
  border-radius: 24rpx 24rpx 0 0; display: flex; flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom);
}
.sheet-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 26rpx 28rpx 18rpx; border-bottom: 1rpx solid #f2f2f2;
}
.sheet-title { font-size: 31rpx; font-weight: 700; color: #1D2129; }
.sheet-close { font-size: 44rpx; color: #999; line-height: 1; padding: 0 8rpx; }
.sheet-body { flex: 1; min-height: 0; padding: 20rpx 28rpx 10rpx; box-sizing: border-box; }
.field { margin-bottom: 24rpx; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 10rpx; }
.req { color: #e53935; }
.amount-box {
  display: flex; align-items: center; gap: 8rpx;
  height: 84rpx; background: #f7f7f7; border-radius: 12rpx; padding: 0 20rpx;
}
.currency { font-size: 30rpx; font-weight: 700; color: #FF6B35; }
.amount-input { flex: 1; height: 84rpx !important; min-height: 84rpx !important; font-size: 32rpx; color: #1D2129; }
.textarea {
  width: 100%; height: 160rpx !important; min-height: 160rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 16rpx 20rpx;
  font-size: 27rpx; color: #333; box-sizing: border-box; line-height: 1.5;
}
.field-foot { display: flex; align-items: flex-start; justify-content: space-between; }
.counter { font-size: 21rpx; color: #bbb; margin-left: 16rpx; }
.hint { display: block; font-size: 21rpx; color: #999; margin-top: 8rpx; line-height: 1.5; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.sheet-foot {
  display: flex; gap: 18rpx; padding: 16rpx 28rpx 22rpx;
  border-top: 1rpx solid #f2f2f2;
}
.btn {
  flex: 1; height: 82rpx; line-height: 82rpx; text-align: center;
  border-radius: 41rpx; font-size: 28rpx; font-weight: 600;
}
.btn.ghost { background: #fff; color: #FF6B35; border: 2rpx solid #FF6B35; flex: 0 0 34%; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn.primary.disabled { opacity: .6; }
</style>
