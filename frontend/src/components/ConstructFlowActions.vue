<template>
  <view v-if="construct" class="construct-card">
    <view class="card-head">
      <text class="card-title">施工流程</text>
      <text class="status-pill" :class="statusTone">{{ construct.bizStatusName || construct.bizStatus }}</text>
    </view>

    <view class="steps">
      <view v-for="(step, index) in steps" :key="step.key" class="step"
            :class="{ on: index <= activeIndex, current: index === activeIndex,
                      danger: index === activeIndex && isRefused }">
        <view class="step-dot">{{ index + 1 }}</view>
        <text class="step-name">{{ step.name }}</text>
        <view v-if="index < steps.length - 1" class="step-line"
              :class="{ on: index < activeIndex }"></view>
      </view>
    </view>

    <view v-if="construct.operateBlockedReason" class="alert">
      <text>{{ construct.operateBlockedReason }}</text>
    </view>
    <view v-else-if="isRefused" class="alert danger">
      <text>客户拒绝确认，请派工人/运营介入；客户同意后可重新签字。</text>
    </view>
    <view v-else-if="isConstructing" class="alert done">
      <text>{{ completion
        ? '施工已完成，请提交验收申请，由订单经办人验收确认。'
        : '订单已开工，施工完成后请提交下方【施工记录】。' }}</text>
    </view>

    <view v-if="hasActions" class="action-row">
      <button v-if="permissions.accept" class="btn primary" :disabled="submitting"
              @click="onAccept">确认接收</button>
      <button v-if="permissions.reject" class="btn ghost-danger" :disabled="submitting"
              @click="openReason('reject')">拒绝接单</button>
      <button v-if="permissions.arrive" class="btn primary" :disabled="submitting"
              @click="arriveVisible = true">上门打卡</button>
      <button v-if="permissions.customerConfirm" class="btn primary" :disabled="submitting"
              @click="signVisible = true">
        {{ isCustomerConfirmed ? '重新签字' : '客户签字确认' }}
      </button>
      <button v-if="permissions.customerRefuse" class="btn ghost-danger" :disabled="submitting"
              @click="openReason('refuse')">客户拒绝</button>
      <button v-if="permissions.start" class="btn primary" :disabled="submitting"
              @click="onStart">确认开工</button>
    </view>

    <view class="timeline-head" @click="showTimeline = !showTimeline">
      <text>施工流转记录（{{ logs.length }}）</text>
      <text class="arrow">{{ showTimeline ? '▲' : '▼' }}</text>
    </view>
    <ConstructTimeline v-if="showTimeline" :logs="logs" />

    <ArriveCheckIn v-model:visible="arriveVisible" :order-id="orderId"
                   :submitting="submitting" @submit="onArrive" />
    <CustomerSign v-model:visible="signVisible" :order-id="orderId"
                  :content="construct.noticePreview" :existing-notice="construct.notice"
                  :resign="isCustomerConfirmed" :submitting="submitting" @submit="onSign" />
    <ConstructReasonDialog v-model:visible="reasonVisible" :title="reasonTitle"
                           :submitting="submitting" @confirm="onReason" />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import ConstructTimeline from '@/components/ConstructTimeline.vue'
import ArriveCheckIn from '@/components/ArriveCheckIn.vue'
import CustomerSign from '@/components/CustomerSign.vue'
import ConstructReasonDialog from '@/components/ConstructReasonDialog.vue'
import { useOrderConstruct } from '@/hooks/useOrderConstruct'

const props = defineProps({
  orderId: { type: [Number, String], required: true },
  construct: { type: Object, default: null },
  /** true=当前是“施工完成”节点，提示改为提交验收申请 */
  completion: { type: Boolean, default: false }
})
const emit = defineEmits(['changed'])

const { submitting, accept, reject, arrive, customerConfirm, customerRefuse, start } =
  useOrderConstruct()

const arriveVisible = ref(false)
const signVisible = ref(false)
const reasonVisible = ref(false)
const reasonAction = ref('reject')
const showTimeline = ref(true)

const steps = [
  { key: 'WAIT_ACCEPT', name: '待接收' },
  { key: 'ACCEPTED', name: '已接收' },
  { key: 'ARRIVED', name: '已上门' },
  { key: 'CUSTOMER_CONFIRMED', name: '客户已确认' },
  { key: 'CONSTRUCTING', name: '施工中' }
]
const status = computed(() => String((props.construct && props.construct.bizStatus) || ''))
const permissions = computed(() => (props.construct && props.construct.permissions) || {})
const logs = computed(() => (props.construct && props.construct.logs) || [])
const isRefused = computed(() => status.value === 'CUSTOMER_REFUSED')
const isCustomerConfirmed = computed(() => status.value === 'CUSTOMER_CONFIRMED')
const isConstructing = computed(() => ['4', '5', '6', '7'].includes(status.value))
const activeIndex = computed(() => {
  if (isRefused.value) return 2
  if (isConstructing.value) return 4
  const index = steps.findIndex(step => step.key === status.value)
  return index < 0 ? -1 : index
})
const hasActions = computed(() => Object.values(permissions.value).some(Boolean))
const statusTone = computed(() => {
  if (isRefused.value) return 'danger'
  if (isConstructing.value) return 'done'
  if (status.value === 'WAIT_ACCEPT' || status.value === 'ACCEPTED') return 'waiting'
  return 'active'
})
const reasonTitle = computed(() => (reasonAction.value === 'refuse'
  ? '客户拒绝确认' : '拒绝接单'))

function openReason(action) {
  reasonAction.value = action
  reasonVisible.value = true
}

async function run(action, successText, meta) {
  try {
    const result = await action()
    uni.showToast({ title: (result && result.message) || successText, icon: 'none' })
    emit('changed', meta || {})
    return true
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '操作失败，请重试', icon: 'none' })
    return false
  }
}

async function onAccept() {
  await run(() => accept(props.orderId), '确认接收成功')
}

async function onArrive(payload) {
  const ok = await run(() => arrive(props.orderId, payload), '打卡成功')
  if (ok) arriveVisible.value = false
}

async function onSign(payload) {
  const ok = await run(() => customerConfirm(props.orderId, payload), '客户已确认')
  if (ok) signVisible.value = false
}

async function onReason(reason) {
  const refuse = reasonAction.value === 'refuse'
  const ok = refuse
    ? await run(() => customerRefuse(props.orderId, reason), '已记录客户拒绝')
    : await run(() => reject(props.orderId, reason), '已拒绝接单', { rejected: true })
  if (ok) reasonVisible.value = false
}

function onStart() {
  uni.showModal({
    title: '确认开工',
    content: '确认客户已签字并开始施工？开工后告知书不可修改。',
    confirmText: '确认开工',
    success: res => { if (res.confirm) run(() => start(props.orderId), '已确认开工') }
  })
}
</script>

<style scoped>
.construct-card {
  margin: 20rpx 24rpx; background: #fff; border-radius: 20rpx; padding: 26rpx;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, .04);
  display: flex; flex-direction: column; gap: 18rpx;
}
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 29rpx; font-weight: 700; color: #1D2129; }
.status-pill { font-size: 21rpx; padding: 3rpx 12rpx; border-radius: 6rpx; background: #E8F3FF; color: #2F6FEB; }
.status-pill.waiting { background: #FFF7E6; color: #B26A00; }
.status-pill.done { background: #E8F8E8; color: #0B7A42; }
.status-pill.danger { background: #FFF1F0; color: #C62828; }
.steps { display: flex; align-items: flex-start; justify-content: space-between; }
.step { flex: 1; display: flex; flex-direction: column; align-items: center; position: relative; min-width: 0; }
.step-dot {
  width: 40rpx; height: 40rpx; border-radius: 50%; background: #F2F3F5;
  color: #A9AEB8; font-size: 22rpx; line-height: 40rpx; text-align: center;
  position: relative; z-index: 2;
}
.step.on .step-dot { background: #FF6B35; color: #fff; }
.step.current .step-dot { box-shadow: 0 0 0 8rpx rgba(255, 107, 53, .16); }
.step.danger .step-dot { background: #C62828; box-shadow: 0 0 0 8rpx rgba(198, 40, 40, .14); }
.step-name { margin-top: 8rpx; font-size: 20rpx; color: #86909C; white-space: nowrap; }
.step.on .step-name { color: #1D2129; font-weight: 600; }
.step-line {
  position: absolute; top: 20rpx; left: 50%; width: 100%; height: 2rpx;
  background: #F2F3F5; z-index: 1;
}
.step-line.on { background: #FFC7AE; }
.alert { font-size: 23rpx; color: #4E5969; background: #F7F8FA; border-radius: 8rpx; padding: 12rpx 16rpx; line-height: 1.6; }
.alert.danger { color: #C62828; background: #FFF1F0; }
.alert.done { color: #0B7A42; background: #F0F9F2; }
.action-row { display: flex; flex-wrap: wrap; gap: 14rpx; }
.btn {
  flex: 1; min-width: 200rpx; height: 78rpx; line-height: 78rpx; border-radius: 39rpx;
  font-size: 26rpx; text-align: center; border: none;
}
.btn.primary { background: #FF6B35; color: #fff; }
.btn.primary[disabled] { opacity: .55; }
.btn.ghost-danger { background: #FFF1F0; color: #C62828; }
.timeline-head {
  display: flex; align-items: center; justify-content: space-between;
  padding-top: 12rpx; border-top: 1rpx solid #F2F3F5;
  font-size: 25rpx; color: #4E5969; font-weight: 600;
}
.arrow { font-size: 20rpx; color: #A9AEB8; }
</style>
