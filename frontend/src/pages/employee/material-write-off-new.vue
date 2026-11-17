<template>
  <view class="page">
    <view v-if="loading" class="state"><text class="state-text">加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-error">{{ errorMsg }}</text>
      <view class="retry-btn" @click="loadOptions"><text>重试</text></view>
    </view>

    <MaterialFlowSuccess v-else-if="success" :result="success" kind="write-off"
                         @continue="continueOrdering" @view-records="viewRecords" />

    <template v-else>
      <view class="context">
        <text class="context-title">物资核销</text>
        <text class="context-sub">{{ meta.company || meta.corpName }} · {{ meta.employeeName }} · 核销已领用物资</text>
      </view>

      <BusinessWizardSteps :steps="steps" :current="step" :max-visited="maxVisited" @select="goStep" />

      <view v-if="banner" class="error-banner" :class="bannerType">
        <text class="banner-dot">{{ bannerType === 'success' ? '✓' : bannerType === 'info' ? 'i' : '!' }}</text>
        <text class="banner-text">{{ banner }}</text>
      </view>

      <view class="step-body" :class="animClass">
        <MaterialWriteOffStepInfo v-if="step === 0" :form="form" :meta="meta" :errors="errors"
                                  :requisition="selectedRequisition" @select-requisition="onSelectRequisition" />
        <MaterialItemsStep v-else-if="step === 1" mode="writeoff" :form="form" :meta="meta" :errors="errors" />
        <MaterialWriteOffConfirm v-else :form="form" :meta="meta" :errors="errors" :missing="missing"
                                 :requisition="selectedRequisition" @edit="goStep"
                                 @select-requisition="onSelectRequisition" />
      </view>

      <view class="footer-space" :class="{ tall: step === 1 }"></view>
      <view class="action-bar">
        <view v-if="step === 1" class="footer-total">
          <text class="footer-total-label">合计</text>
          <view class="footer-total-value">
            <text>{{ totals.itemCount }} 种 · 核销</text>
            <AnimatedAmount :value="totals.writeOffTotal" prefix="" :decimals="0" />
            <text>· 退回</text>
            <AnimatedAmount :value="totals.returnTotal" prefix="" :decimals="0" />
          </view>
        </view>
        <view class="btn-row">
          <view v-if="step > 0" class="btn ghost" @click="back"><text>上一步</text></view>
          <view v-if="step < steps.length - 1" class="btn primary" @click="next"><text>下一步</text></view>
          <view v-else class="btn primary" :class="{ disabled: submitting }" @click="submit">
            <text>{{ submitting ? '提交中...' : '提交核销' }}</text>
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
import { computed, onMounted, ref, watch } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { useMaterialWriteOffWizard } from '@/hooks/useMaterialWriteOffWizard'
import { writeOffTotals } from '@/utils/materialForm'
import BusinessWizardSteps from '@/components/BusinessWizardSteps.vue'
import MaterialWriteOffStepInfo from '@/components/MaterialWriteOffStepInfo.vue'
import MaterialItemsStep from '@/components/MaterialItemsStep.vue'
import MaterialWriteOffConfirm from '@/components/MaterialWriteOffConfirm.vue'
import MaterialFlowSuccess from '@/components/MaterialFlowSuccess.vue'
import AnimatedAmount from '@/components/AnimatedAmount.vue'

/** 物资核销制单：3 步向导（核销信息 → 物料核销 → 确认提交）。 */
const {
  loading, errorMsg, submitting, step, maxVisited, success, banner, bannerType,
  meta, form, errors, missing, steps, selectedRequisition,
  loadOptions, selectRequisition, next, back, goStep, submit, continueOrdering, viewRecords
} = useMaterialWriteOffWizard()

const animClass = ref('')
const presetRequisitionId = ref(null)
const totals = computed(() => writeOffTotals(form.items))
let animTimer = null

watch(step, (current, previous) => {
  animClass.value = current > previous ? 'enter-forward' : 'enter-back'
  clearTimeout(animTimer)
  animTimer = setTimeout(() => { animClass.value = '' }, 240)
})

onLoad((options) => {
  presetRequisitionId.value = options && options.requisitionId ? Number(options.requisitionId) : null
})

onMounted(async () => {
  if (!uni.getStorageSync('ops_employee_token')) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  await loadOptions()
  if (presetRequisitionId.value && !form.requisitionId) {
    try {
      await selectRequisition({ id: presetRequisitionId.value })
    } catch (e) {
      // 错误已由 hook 展示
    }
  }
})

async function onSelectRequisition(summary) {
  try {
    await selectRequisition(summary)
  } catch (e) {
    // 错误已由 hook 展示
  }
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; padding: 20rpx 24rpx 0; box-sizing: border-box; }
.state { padding: 200rpx 32rpx; text-align: center; }
.state-text { font-size: 28rpx; color: #999; }
.state-error { display: block; font-size: 28rpx; color: #e53935; margin-bottom: 24rpx; line-height: 1.5; }
.retry-btn { display: inline-block; padding: 16rpx 48rpx; background: #FF6B35; color: #fff; border-radius: 32rpx; font-size: 28rpx; }
.context { background: linear-gradient(135deg, #FF6B35, #FF8C5A); border-radius: 16rpx; padding: 26rpx 24rpx; margin-bottom: 18rpx; }
.context-title { display: block; font-size: 34rpx; font-weight: 700; color: #fff; }
.context-sub { display: block; font-size: 22rpx; color: rgba(255, 255, 255, .88); margin-top: 8rpx; }
.error-banner {
  position: fixed; left: 24rpx; right: 24rpx; top: calc(var(--window-top, 0px) + 16rpx);
  z-index: 80; display: flex; align-items: center; gap: 12rpx;
  background: #FFF1F0; border: 2rpx solid #FFD1CC; border-radius: 12rpx;
  padding: 18rpx 22rpx; box-shadow: 0 8rpx 24rpx rgba(229, 57, 53, .12);
  animation: bannerIn .22s ease-out;
}
.banner-dot { width: 32rpx; height: 32rpx; line-height: 30rpx; text-align: center; border-radius: 50%; background: #e53935; color: #fff; font-size: 22rpx; font-weight: 700; flex-shrink: 0; }
.banner-text { flex: 1; font-size: 25rpx; color: #c62828; line-height: 1.4; }
.error-banner.success { background: #F0FBF4; border-color: #BFE9CF; }
.error-banner.success .banner-dot { background: #07C160; }
.error-banner.success .banner-text { color: #0B7A42; }
.error-banner.info { background: #F2F7FF; border-color: #C9DBFF; }
.error-banner.info .banner-dot { background: #4A7DF0; }
.error-banner.info .banner-text { color: #2F5BB7; }
.step-body { padding-top: 18rpx; }
.step-body.enter-forward { animation: slideFromRight .22s ease-out; }
.step-body.enter-back { animation: slideFromLeft .22s ease-out; }
.footer-space { height: 160rpx; }
.footer-space.tall { height: 230rpx; }
.action-bar { position: fixed; left: 0; right: 0; bottom: 0; z-index: 30; background: #fff; border-top: 1rpx solid #f0f0f0; padding: 14rpx 24rpx calc(14rpx + env(safe-area-inset-bottom)); }
.footer-total { display: flex; align-items: center; justify-content: space-between; padding: 4rpx 4rpx 12rpx; }
.footer-total-label { font-size: 25rpx; color: #666; }
.footer-total-value { display: flex; align-items: baseline; gap: 6rpx; font-size: 30rpx; font-weight: 700; color: #FF6B35; }
.btn-row { display: flex; gap: 20rpx; }
.btn { height: 84rpx; line-height: 84rpx; text-align: center; border-radius: 42rpx; font-size: 29rpx; font-weight: 600; flex: 1; box-sizing: border-box; }
.btn.ghost { background: #fff; color: #FF6B35; border: 2rpx solid #FF6B35; flex: 0 0 34%; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn.primary.disabled { opacity: .6; }
.btn:active { opacity: .85; }
.submit-mask { position: fixed; inset: 0; z-index: 200; background: rgba(0, 0, 0, .35); display: flex; align-items: center; justify-content: center; }
.mask-box { background: rgba(255, 255, 255, .96); border-radius: 16rpx; padding: 36rpx 48rpx; display: flex; flex-direction: column; align-items: center; gap: 16rpx; }
.mask-spinner { width: 44rpx; height: 44rpx; border-radius: 50%; border: 4rpx solid #FFD9C8; border-top-color: #FF6B35; animation: spin .7s linear infinite; }
.mask-text { font-size: 26rpx; color: #555; }
@keyframes slideFromRight { from { opacity: .3; transform: translateX(28rpx); } to { opacity: 1; transform: translateX(0); } }
@keyframes slideFromLeft { from { opacity: .3; transform: translateX(-28rpx); } to { opacity: 1; transform: translateX(0); } }
@keyframes bannerIn { from { opacity: 0; transform: translateY(-16rpx); } to { opacity: 1; transform: translateY(0); } }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
