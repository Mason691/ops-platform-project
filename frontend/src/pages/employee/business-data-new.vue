<template>
  <view class="page">
    <EmployeePageHead :title="pageTitle" />
    <view v-if="loading" class="state">
      <text class="state-text">加载中...</text>
    </view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-error">{{ errorMsg }}</text>
      <view class="retry-btn" @click="loadOptions"><text>重试</text></view>
    </view>

    <BusinessSuccess v-else-if="success" :result="success" :edit-mode="!!success.editMode"
                     @continue="continueOrdering" @view-order="viewOrder" />

    <template v-else>
      <view class="context">
        <text class="context-sub">{{ meta.company }} · {{ meta.employeeName }} · {{ meta.employeePhone }}</text>
      </view>

      <BusinessWizardSteps :steps="steps" :current="step" :max-visited="maxVisited"
                           @select="goStep" />

      <view v-if="banner" class="error-banner" :class="bannerType">
        <text class="banner-dot">{{ bannerType === 'success' ? '✓' : bannerType === 'info' ? 'i' : '!' }}</text>
        <text class="banner-text">{{ banner }}</text>
      </view>

      <view v-if="meta.productError" class="warn">
        <text>⚠️ {{ meta.productError }}</text>
      </view>

      <view class="step-body" :class="animClass">
        <BusinessStepBasic v-if="step === 0" :form="form" :meta="meta" :errors="errors"
                           :querying="querying" :member-status="memberStatus"
                           @query-member="queryMember" />
        <BusinessStepProducts v-else-if="step === 1" :form="form" :meta="meta" :errors="errors" />
        <BusinessStepPayment v-else-if="step === 2" :form="form" :meta="meta" :errors="errors" />
        <BusinessStepVoucher v-else-if="step === 3" :form="form" :meta="meta" :errors="errors" />
        <BusinessStepConfirm v-else :form="form" :meta="meta" :errors="errors" :missing="missing"
                             :querying="querying" :member-status="memberStatus"
                             @edit="goStep" @query-member="queryMember" />
      </view>

      <view class="footer-space" :class="{ tall: step === 1 }"></view>
      <view class="action-bar">
        <view v-if="step === 1" class="footer-total">
          <view class="footer-total-line">
            <text class="footer-total-label">商品合计</text>
            <AnimatedAmount class="footer-total-sub" :value="productsTotal" />
          </view>
          <view v-if="deductionTotal > 0" class="footer-total-line">
            <text class="footer-total-label">抵扣合计</text>
            <AnimatedAmount class="footer-total-sub deduction" :value="deductionTotal" prefix="-¥" />
          </view>
          <view class="footer-total-line payable">
            <text class="footer-total-label">应付合计</text>
            <AnimatedAmount class="footer-total-value" :value="payableTotal" />
          </view>
        </view>
        <view class="btn-row">
          <view v-if="step > 0" class="btn ghost" @click="back"><text>上一步</text></view>
          <view v-if="step < 4" class="btn primary" @click="next"><text>下一步</text></view>
          <view v-else class="btn primary" :class="{ disabled: submitting }" @click="submit">
            <text>{{ submitting ? '提交中...' : (reviseMode ? '重新提交审批' : '提交制单') }}</text>
          </view>
        </view>
      </view>

      <view v-if="submitting" class="submit-mask">
        <view class="mask-box">
          <text class="mask-spinner"></text>
          <text class="mask-text">正在提交，请稍候...</text>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import EmployeePageHead from '@/components/EmployeePageHead.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { useBusinessWizard } from '@/hooks/useBusinessWizard'
import { computeProductsDeductionTotal, computeProductsPayableTotal, computeProductsTotal } from '@/utils/businessForm'
import BusinessWizardSteps from '@/components/BusinessWizardSteps.vue'
import BusinessStepBasic from '@/components/BusinessStepBasic.vue'
import BusinessStepProducts from '@/components/BusinessStepProducts.vue'
import BusinessStepPayment from '@/components/BusinessStepPayment.vue'
import BusinessStepVoucher from '@/components/BusinessStepVoucher.vue'
import BusinessStepConfirm from '@/components/BusinessStepConfirm.vue'
import BusinessSuccess from '@/components/BusinessSuccess.vue'
import AnimatedAmount from '@/components/AnimatedAmount.vue'

const {
  loading, errorMsg, querying, submitting, step, maxVisited, success, banner,
  bannerType, memberStatus, meta, form, errors, steps, missing, loadOptions, queryMember,
  next, back, goStep, submit, continueOrdering, viewOrder, editOrderId, setEditOrderId,
  reviseMode, setReviseMode
} = useBusinessWizard()

const pageTitle = computed(() => reviseMode.value
  ? '修改并重新提交'
  : (editOrderId.value ? '编辑业务订单' : '新增业务订单'))

const animClass = ref('')
const productsTotal = computed(() => computeProductsTotal(form.products))
const deductionTotal = computed(() => computeProductsDeductionTotal(form.products))
const payableTotal = computed(() => computeProductsPayableTotal(form.products))

let animTimer = null

// 进入右滑、返回左滑；仅做轻量位移+淡入，避免叠加动画。
watch(step, (current, previous) => {
  animClass.value = current > previous ? 'enter-forward' : 'enter-back'
  clearTimeout(animTimer)
  animTimer = setTimeout(() => { animClass.value = '' }, 240)
})

onLoad(options => {
  const id = options && options.orderId ? decodeURIComponent(options.orderId) : ''
  if (id) setEditOrderId(id)
  setReviseMode(!!(options && options.revise))
})

onMounted(() => {
  if (!uni.getStorageSync('ops_employee_token')) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  loadOptions()
})
</script>

<style scoped>
.page {
  min-height: 100vh; background: #f5f5f5;
  padding: 20rpx 24rpx 0; box-sizing: border-box;
}
.state { padding: 200rpx 32rpx; text-align: center; }
.state-text { font-size: 28rpx; color: #999; }
.state-error { display: block; font-size: 28rpx; color: #e53935; margin-bottom: 24rpx; line-height: 1.5; }
.retry-btn {
  display: inline-block; padding: 16rpx 48rpx; background: #FF6B35;
  color: #fff; border-radius: 32rpx; font-size: 28rpx;
}
.context {
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  border-radius: 16rpx; padding: 26rpx 24rpx; margin-bottom: 18rpx;
}
.context-sub { display: block; font-size: 22rpx; color: rgba(255, 255, 255, .88); }
.error-banner {
  position: fixed; left: 24rpx; right: 24rpx;
  top: calc(var(--window-top, 0px) + 16rpx); z-index: 80;
  display: flex; align-items: center; gap: 12rpx;
  background: #FFF1F0; border: 2rpx solid #FFD1CC; border-radius: 12rpx;
  padding: 18rpx 22rpx; box-shadow: 0 8rpx 24rpx rgba(229, 57, 53, .12);
  animation: bannerIn .22s ease-out;
}
.banner-dot {
  width: 32rpx; height: 32rpx; line-height: 30rpx; text-align: center;
  border-radius: 50%; background: #e53935; color: #fff;
  font-size: 22rpx; font-weight: 700; flex-shrink: 0;
}
.banner-text { flex: 1; font-size: 25rpx; color: #c62828; line-height: 1.4; }
.error-banner.success { background: #F0FBF4; border-color: #BFE9CF; }
.error-banner.success .banner-dot { background: #07C160; }
.error-banner.success .banner-text { color: #0B7A42; }
.error-banner.info { background: #F2F7FF; border-color: #C9DBFF; }
.error-banner.info .banner-dot { background: #4A7DF0; }
.error-banner.info .banner-text { color: #2F5BB7; }
.warn {
  background: #FFF7E6; color: #B26A00; border-radius: 12rpx;
  padding: 18rpx 22rpx; font-size: 23rpx; line-height: 1.5; margin-bottom: 16rpx;
}
.step-body { padding-top: 18rpx; }
.step-body.enter-forward { animation: slideFromRight .22s ease-out; }
.step-body.enter-back { animation: slideFromLeft .22s ease-out; }
.footer-space { height: 160rpx; }
.footer-space.tall { height: 260rpx; }
.action-bar {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 30;
  background: #fff; border-top: 1rpx solid #f0f0f0;
  padding: 14rpx 24rpx calc(14rpx + env(safe-area-inset-bottom));
}
.footer-total {
  display: flex; flex-direction: column; gap: 4rpx;
  padding: 4rpx 4rpx 12rpx;
}
.footer-total-line { display: flex; align-items: center; justify-content: space-between; }
.footer-total-line.payable { border-top: 1rpx solid #F2F3F5; padding-top: 6rpx; }
.footer-total-label { font-size: 25rpx; color: #666; }
.footer-total-sub { font-size: 25rpx; color: #1D2129; font-weight: 600; }
.footer-total-sub.deduction { color: #C62828; }
.footer-total-value { font-size: 38rpx; font-weight: 700; color: #FF6B35; }
.btn-row { display: flex; gap: 20rpx; }
.btn {
  height: 84rpx; line-height: 84rpx; text-align: center;
  border-radius: 42rpx; font-size: 29rpx; font-weight: 600;
  flex: 1; box-sizing: border-box;
}
.btn.ghost { background: #fff; color: #FF6B35; border: 2rpx solid #FF6B35; flex: 0 0 34%; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn.primary.disabled { opacity: .6; }
.btn:active { opacity: .85; }
.submit-mask {
  position: fixed; inset: 0; z-index: 200; background: rgba(0, 0, 0, .35);
  display: flex; align-items: center; justify-content: center;
}
.mask-box {
  background: rgba(255, 255, 255, .96); border-radius: 16rpx;
  padding: 36rpx 48rpx; display: flex; flex-direction: column;
  align-items: center; gap: 16rpx;
}
.mask-spinner {
  width: 44rpx; height: 44rpx; border-radius: 50%;
  border: 4rpx solid #FFD9C8; border-top-color: #FF6B35;
  animation: spin .7s linear infinite;
}
.mask-text { font-size: 26rpx; color: #555; }
@keyframes slideFromRight {
  from { opacity: .3; transform: translateX(28rpx); }
  to { opacity: 1; transform: translateX(0); }
}
@keyframes slideFromLeft {
  from { opacity: .3; transform: translateX(-28rpx); }
  to { opacity: 1; transform: translateX(0); }
}
@keyframes bannerIn {
  from { opacity: 0; transform: translateY(-16rpx); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
