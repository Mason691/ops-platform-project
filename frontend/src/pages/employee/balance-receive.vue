<template>
  <view class="page">
    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
      <view class="retry" @click="load"><text>重试</text></view>
    </view>
    <template v-else-if="detail">
      <view class="hero">
        <text class="hero-title">{{ detail.orderNo || '—' }}</text>
        <text class="hero-sub">{{ detail.customer || '—' }} {{ detail.customerPhone || '' }}</text>
        <view class="due-row">
          <text class="due-label">应补余款</text>
          <text class="due-value">¥{{ money(detail.balanceAmount) }}</text>
        </view>
      </view>

      <view class="card">
        <text class="block-title">订单信息</text>
        <view class="kv"><text class="k">销售价合计</text><text class="v">¥{{ money(detail.totalAmount) }}</text></view>
        <view class="kv"><text class="k">定金金额</text><text class="v">¥{{ money(detail.depositAmount) }}</text></view>
        <view class="kv"><text class="k">领用经办人</text><text class="v">{{ detail.agentNames || '—' }}</text></view>
        <view class="kv"><text class="k">制单时间</text><text class="v">{{ detail.createTime || '—' }}</text></view>
      </view>

      <view v-if="detail.alreadyReceived" class="done-card">
        <text class="done-icon">✓</text>
        <text class="done-title">该订单已完成货款补录</text>
        <text class="done-sub">订单已标记为全款，无需重复补录</text>
        <view class="done-btn" @click="back"><text>返回列表</text></view>
      </view>

      <template v-else>
        <view class="card">
          <text class="block-title">收款信息</text>
          <view class="field">
            <text class="label">实收余款 <text class="req">*</text></text>
            <view class="amount-input">
              <text class="currency">¥</text>
              <input v-model="form.receivedAmount" type="digit" class="input"
                     placeholder="请输入实收余款" />
            </view>
            <text class="hint">实收余款必须等于应补余款 ¥{{ money(detail.balanceAmount) }}</text>
            <text v-if="amountError" class="ferr">{{ amountError }}</text>
          </view>
          <view class="field">
            <text class="label">收款凭证 <text class="req">*</text></text>
            <BusinessSpecialFileUploader v-model="form.voucher" label="收款凭证"
                                         upload-title="上传收款凭证"
                                         hint-text="支持 PDF / JPG / PNG，单个不超过 10MB"
                                         :error="voucherError" />
          </view>
          <view class="field">
            <text class="label">收款备注</text>
            <textarea v-model="form.remark" class="textarea" maxlength="500"
                      placeholder="选填，最多500字" />
          </view>
        </view>

        <view class="footer-space"></view>
        <view class="footer">
          <view class="primary-btn" :class="{ disabled: submitting }" @click="submit">
            <text>{{ submitting ? '提交中...' : '确认收款并标记全款' }}</text>
          </view>
        </view>
      </template>
    </template>
  </view>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import BusinessSpecialFileUploader from '@/components/BusinessSpecialFileUploader.vue'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

/** 货款补录：应补余款只读；实收余款默认等于应补余款且必须相等；凭证必填。 */
const orderId = ref(null)
const detail = ref(null)
const loading = ref(true)
const errorMsg = ref('')
const submitting = ref(false)
const amountError = ref('')
const voucherError = ref('')

const form = reactive({ receivedAmount: '', voucher: null, remark: '' })

onLoad((options) => {
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent(
        '/pages/employee/balance-receive?orderId=' + ((options && options.orderId) || ''))
    })
    return
  }
  orderId.value = Number(options && options.orderId)
  load()
})

async function load() {
  if (!orderId.value) { errorMsg.value = '缺少订单ID'; loading.value = false; return }
  loading.value = true
  errorMsg.value = ''
  try {
    detail.value = await api.depositPendingDetail(orderId.value)
    if (!detail.value.alreadyReceived) {
      form.receivedAmount = money(detail.value.balanceAmount)
    }
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败'
  } finally {
    loading.value = false
  }
}

function money(value) {
  return (Number(value) || 0).toFixed(2)
}

function validate() {
  amountError.value = ''
  voucherError.value = ''
  const raw = String(form.receivedAmount || '').trim()
  const received = Number(raw)
  const due = Number(detail.value.balanceAmount) || 0
  if (!raw || !Number.isFinite(received) || received <= 0) {
    amountError.value = '请输入大于0的实收余款'
    return false
  }
  if (Math.round(received * 100) !== Math.round(due * 100)) {
    amountError.value = `实收余款必须等于应补余款 ¥${money(due)}`
    return false
  }
  if (!form.voucher || !form.voucher.url) {
    voucherError.value = '请上传收款凭证'
    return false
  }
  return true
}

async function submit() {
  if (submitting.value) return
  if (!validate()) return
  submitting.value = true
  try {
    const result = await api.receiveBalance(orderId.value, {
      receivedAmount: Number(form.receivedAmount),
      voucherUrl: form.voucher.url,
      remark: String(form.remark || '').trim() || null
    })
    if (result && result.alreadyReceived) {
      uni.showModal({
        title: '已完成补录',
        content: result.message || '该订单已完成货款补录',
        showCancel: false,
        success: () => back()
      })
      return
    }
    uni.showModal({
      title: '货款补录成功',
      content: '订单已标记为全款，可发起派工出库',
      showCancel: false,
      success: () => back()
    })
  } catch (e) {
    if (e && e.msg) {
      if (String(e.msg).includes('实收余款')) amountError.value = e.msg
      else if (String(e.msg).includes('凭证')) voucherError.value = e.msg
    }
    uni.showToast({ title: (e && e.msg) || '补录失败，请稍后重试', icon: 'none', duration: 2500 })
  } finally {
    submitting.value = false
  }
}

function back() {
  uni.navigateBack()
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 40rpx; box-sizing: border-box; }
.state { padding: 160rpx 0; text-align: center; color: #86909C; font-size: 25rpx; }
.state-err { display: block; color: #F53F3F; margin-bottom: 20rpx; }
.retry { display: inline-block; padding: 12rpx 40rpx; border-radius: 999rpx; background: #FF6B35; color: #fff; font-size: 24rpx; }
.hero { background: linear-gradient(135deg, #FF6B35, #FF8C5A); border-radius: 18rpx; padding: 28rpx; color: #fff; }
.hero-title { display: block; font-size: 32rpx; font-weight: 700; }
.hero-sub { display: block; margin-top: 8rpx; font-size: 24rpx; opacity: .9; }
.due-row { display: flex; align-items: baseline; justify-content: space-between; margin-top: 20rpx; }
.due-label { font-size: 26rpx; }
.due-value { font-size: 46rpx; font-weight: 700; }
.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-top: 16rpx; }
.block-title { display: block; font-size: 29rpx; font-weight: 700; color: #1D2129; margin-bottom: 14rpx; }
.kv { display: flex; padding: 8rpx 0; }
.k { width: 180rpx; flex-shrink: 0; font-size: 24rpx; color: #86909C; }
.v { flex: 1; font-size: 26rpx; color: #1D2129; }
.field { margin-top: 18rpx; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 10rpx; }
.req { color: #e53935; }
.amount-input {
  display: flex; align-items: center; background: #f7f7f7; border-radius: 12rpx;
  padding: 0 20rpx; height: 88rpx;
}
.currency { font-size: 32rpx; color: #FF6B35; font-weight: 700; margin-right: 8rpx; }
.input { flex: 1; height: 88rpx !important; min-height: 88rpx !important; font-size: 34rpx; color: #1D2129; }
.textarea {
  width: 100%; height: 140rpx !important; min-height: 140rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 16rpx 20rpx;
  font-size: 27rpx; color: #333; box-sizing: border-box;
}
.hint { display: block; font-size: 21rpx; color: #999; margin-top: 8rpx; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 6rpx; }
.done-card {
  margin-top: 16rpx; padding: 40rpx 24rpx; background: #fff; border-radius: 16rpx;
  display: flex; flex-direction: column; align-items: center; gap: 10rpx;
}
.done-icon {
  width: 80rpx; height: 80rpx; line-height: 80rpx; text-align: center; border-radius: 50%;
  background: #07C160; color: #fff; font-size: 42rpx; font-weight: 700;
}
.done-title { font-size: 30rpx; font-weight: 700; color: #1D2129; }
.done-sub { font-size: 23rpx; color: #86909C; }
.done-btn { margin-top: 16rpx; padding: 14rpx 48rpx; border-radius: 999rpx; background: #FF6B35; color: #fff; font-size: 25rpx; }
.footer-space { height: 160rpx; }
.footer { position: fixed; left: 0; right: 0; bottom: 0; padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom)); background: #F5F6FA; }
.primary-btn { height: 88rpx; line-height: 88rpx; text-align: center; border-radius: 44rpx; background: #FF6B35; color: #fff; font-size: 29rpx; font-weight: 600; }
.primary-btn.disabled { opacity: .6; }
</style>
