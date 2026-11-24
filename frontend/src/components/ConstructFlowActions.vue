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

    <view v-if="construct.isWorker && construct.operateBlockedReason" class="alert">
      <text>{{ construct.operateBlockedReason }}</text>
    </view>
    <view v-else-if="isRefused" class="alert danger">
      <text>客户拒绝确认，请派工人/运营介入；客户同意后可重新签字。</text>
    </view>
    <view v-else-if="isConstructing" class="alert done">
      <text>{{ constructingHint }}</text>
    </view>

    <view v-if="hasRecord" class="record-block">
      <view class="record-head">
        <text>施工记录</text>
        <text v-if="construct.acceptanceSubmittedTime" class="record-time">
          {{ construct.acceptanceSubmittedTime }} 已提交验收
        </text>
      </view>
      <view v-if="construct.constructionNote" class="record-row">
        <text class="record-label">施工说明</text>
        <text class="record-text">{{ construct.constructionNote }}</text>
      </view>
      <view v-if="construct.completionNote" class="record-row">
        <text class="record-label">完工说明</text>
        <text class="record-text">{{ construct.completionNote }}</text>
      </view>
      <view v-if="photos.length" class="record-photos">
        <image v-for="file in photos" :key="file.id" :src="assetUrl(file.url)"
               class="record-photo" mode="aspectFill" @click="previewPhotos(file)" />
      </view>
    </view>

    <view v-if="hasActions" class="action-row">
      <button v-if="permissions.accept" class="btn primary" :disabled="submitting"
              @click="onAccept">确认接受</button>
      <button v-if="permissions.reject" class="btn ghost-danger" :disabled="submitting"
              @click="openReason('reject')">拒绝接受</button>
      <button v-if="permissions.arrive" class="btn primary" :disabled="submitting"
              @click="arriveVisible = true">上门打卡</button>
      <button v-if="permissions.customerConfirm" class="btn primary" :disabled="submitting"
              @click="signVisible = true">
        {{ isCustomerConfirmed ? '重新签字' : '客户签字确认' }}
      </button>
      <button v-if="permissions.customerRefuse" class="btn ghost-danger" :disabled="submitting"
              @click="openReason('refuse')">客户拒绝</button>
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
import { assetUrl } from '@/utils/image'

const props = defineProps({
  orderId: { type: [Number, String], required: true },
  construct: { type: Object, default: null }
})
const emit = defineEmits(['changed'])

const { submitting, accept, reject, arrive, customerConfirm, customerRefuse } =
  useOrderConstruct()

const arriveVisible = ref(false)
const signVisible = ref(false)
const reasonVisible = ref(false)
const reasonAction = ref('reject')
const showTimeline = ref(true)

const steps = [
  { key: 'WAIT_ACCEPT', name: '待接受' },
  { key: 'ACCEPTED', name: '已接受' },
  { key: 'ARRIVED', name: '已上门' },
  { key: 'CUSTOMER_CONFIRMED', name: '客户已确认' },
  { key: 'CONSTRUCTING', name: '施工中' }
]
const status = computed(() => String((props.construct && props.construct.bizStatus) || ''))
const permissions = computed(() => (props.construct && props.construct.permissions) || {})
const logs = computed(() => (props.construct && props.construct.logs) || [])
const photos = computed(() => (props.construct && props.construct.constructionPhotos) || [])
const hasRecord = computed(() => !!(props.construct && (props.construct.constructionNote
  || props.construct.completionNote || photos.value.length)))
const isRefused = computed(() => status.value === 'CUSTOMER_REFUSED')
const isCustomerConfirmed = computed(() => status.value === 'CUSTOMER_CONFIRMED')
const isConstructing = computed(() => ['4', '5', '6', '7'].includes(status.value))
const constructingHint = computed(() => {
  if (status.value === '7') return '订单已完成验收。'
  if (status.value === '6') return '订单已提交验收，请核对施工记录后填写验收结论。'
  if (status.value === '5') return '施工记录已提交，等待验收确认；如被退回，请修改后重新提交。'
  return '施工已开始，完成后请在下方填写施工记录并提交验收申请。'
})
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
  ? '客户拒绝确认' : '拒绝接受任务'))

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
  await run(() => accept(props.orderId), '确认接受成功')
}

async function onArrive(payload) {
  const ok = await run(() => arrive(props.orderId, payload), '打卡成功')
  if (ok) arriveVisible.value = false
}

function onSign(payload) {
  // 签字后自动进入施工中且告知书不可修改，提交前再做一次明确确认
  uni.showModal({
    title: '确认客户签字',
    content: '客户签字确认后，任务将自动进入施工中，告知书内容不可修改。请确认客户已阅读并同意。',
    confirmText: '确认签字',
    success: (res) => { if (res.confirm) doSign(payload) }
  })
}

async function doSign(payload) {
  const ok = await run(() => customerConfirm(props.orderId, payload),
    '客户已签字确认，任务已进入施工中')
  if (!ok) return
  signVisible.value = false
  uni.showModal({
    title: '客户已签字确认',
    content: '任务已进入施工中，请完成施工后填写施工记录并提交验收申请。',
    showCancel: false
  })
}

async function onReason(reason) {
  const refuse = reasonAction.value === 'refuse'
  const ok = refuse
    ? await run(() => customerRefuse(props.orderId, reason), '已记录客户拒绝')
    : await run(() => reject(props.orderId, reason), '已拒绝接受', { rejected: true })
  if (ok) reasonVisible.value = false
}

function previewPhotos(current) {
  const urls = photos.value.map(file => assetUrl(file.url))
  uni.previewImage({ urls, current: assetUrl(current.url) })
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
.record-block {
  border-top: 1rpx dashed #E5E6EB; padding-top: 14rpx;
  display: flex; flex-direction: column; gap: 8rpx;
}
.record-head {
  display: flex; align-items: baseline; justify-content: space-between; gap: 12rpx;
  font-size: 25rpx; font-weight: 600; color: #1D2129;
}
.record-time { font-size: 20rpx; color: #86909C; font-weight: 400; }
.record-row { display: flex; flex-direction: column; gap: 2rpx; }
.record-label { font-size: 21rpx; color: #86909C; }
.record-text { font-size: 23rpx; color: #4E5969; line-height: 1.6; white-space: pre-wrap; }
.record-photos { display: flex; flex-wrap: wrap; gap: 10rpx; }
.record-photo { width: 120rpx; height: 120rpx; border-radius: 8rpx; background: #F2F3F5; }
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
