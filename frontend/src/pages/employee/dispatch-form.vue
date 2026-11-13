<template>
  <view class="page">
    <view v-if="loading" class="state">
      <view class="spinner"></view>
      <text class="state-text">加载中...</text>
    </view>

    <view v-else-if="loadError" class="state">
      <text class="state-icon">⚠️</text>
      <text class="state-text">{{ loadError }}</text>
      <view class="retry-btn" @click="loadAll">重新加载</view>
    </view>

    <block v-else>
      <view class="summary">
        <view class="summary-top">
          <text class="order-no">{{ detail.orderNo || ('订单 #' + detail.id) }}</text>
          <text class="tag">{{ detail.financeChecked ? '已财务核对' : '户内美装' }}</text>
        </view>
        <text class="summary-customer">{{ detail.customer || '—' }} {{ detail.customerPhone || '' }}</text>
        <text class="summary-address">{{ addressText }}</text>
        <text v-if="!detail.dispatchable" class="warn">
          该订单暂不可派工{{ detail.activeDispatchNo ? '（已存在进行中派工单 ' + detail.activeDispatchNo + '）' : '' }}
        </text>
      </view>

      <view class="card">
        <text class="section">派工信息</text>
        <view class="field">
          <text class="label">工单类型</text>
          <view class="types">
            <view class="type" :class="{ active: form.dispatchType === 'CONSTRUCTION' }" @click="setType('CONSTRUCTION')">施工</view>
            <view class="type" :class="{ active: form.dispatchType === 'VISIT' }" @click="setType('VISIT')">入户随访</view>
          </view>
        </view>
        <view class="field">
          <text class="label">期望上门</text>
          <view class="time-row">
            <picker class="picker-wrap" mode="date" :value="form.date" :start="today" @change="onDateChange">
              <view class="picker">{{ form.date || '选择日期' }}</view>
            </picker>
            <picker class="picker-wrap" mode="time" :value="form.time" @change="onTimeChange">
              <view class="picker">{{ form.time || '选择时间' }}</view>
            </picker>
          </view>
        </view>
        <view class="field">
          <text class="label">指派员工</text>
          <picker class="picker-wrap" mode="selector" :range="employeeLabels" :value="employeeIndex" @change="onEmployeeChange">
            <view class="picker full">{{ assigneeLabel }}</view>
          </picker>
        </view>
        <text v-if="employeesLoaded && !employees.length" class="hint warn">
          该组织暂无可指派员工，请先在底座维护员工账号
        </text>
      </view>

      <view class="card">
        <text class="section">客户信息（默认取来源订单，可修改）</text>
        <view class="field"><text class="label">客户名称</text><input v-model="form.customerName" class="input" maxlength="100" /></view>
        <view class="field"><text class="label">客户手机</text><input v-model="form.customerPhone" class="input" type="number" maxlength="11" /></view>
        <view class="field"><text class="label">小区</text><input v-model="form.communityName" class="input" maxlength="200" /></view>
        <view class="field"><text class="label">详细地址</text><input v-model="form.detailedAddress" class="input" maxlength="500" /></view>
      </view>

      <DispatchProductList
        v-if="form.dispatchType === 'CONSTRUCTION'"
        v-model="form.selectedKeys"
        :products="products"
      />

      <view class="card">
        <text class="section">{{ form.dispatchType === 'VISIT' ? '随访任务说明（必填）' : '派工备注' }}</text>
        <textarea v-model="form.remark" class="textarea" maxlength="500" placeholder="填写任务说明或备注" />
      </view>

      <view class="submit-bar">
        <view class="submit" :class="{ disabled: submitting || !detail.dispatchable }" @click="submit">
          {{ submitting ? '提交中...' : '确认派工' }}
        </view>
      </view>
    </block>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import DispatchProductList from '@/components/DispatchProductList.vue'

const orderId = ref(null)
const detail = ref({})
const products = ref([])
const employees = ref([])
const employeesLoaded = ref(false)
const loading = ref(true)
const loadError = ref('')
const submitting = ref(false)

const form = reactive({
  dispatchType: 'CONSTRUCTION',
  date: '',
  time: '',
  assigneeUserId: null,
  customerName: '',
  customerPhone: '',
  communityName: '',
  detailedAddress: '',
  remark: '',
  selectedKeys: []
})

const today = localDate(new Date())
const addressText = computed(() => {
  const text = [detail.value.communityName, detail.value.detailedAddress].filter(Boolean).join(' ')
  return text || '—'
})
const employeeLabels = computed(() => employees.value.map(e => e.name + '（' + e.phone + '）'))
const employeeIndex = computed(() => {
  const index = employees.value.findIndex(e => e.userId === form.assigneeUserId)
  return index < 0 ? 0 : index
})
const assigneeLabel = computed(() => {
  const employee = employees.value.find(e => e.userId === form.assigneeUserId)
  return employee ? employee.name + '（' + employee.phone + '）' : '请选择员工'
})

onLoad((options) => {
  if (!uni.getStorageSync('ops_employee_token')) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  orderId.value = options.orderId
  loadAll()
})

async function loadAll() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await api.employeeDispatchableOrder(orderId.value)
    detail.value = data || {}
    products.value = data.products || []
    form.customerName = data.customer || ''
    form.customerPhone = data.customerPhone || ''
    form.communityName = data.communityName || ''
    form.detailedAddress = data.detailedAddress || ''
    form.selectedKeys = products.value.map(p => p.productKey)
    if (!products.value.length) form.dispatchType = 'VISIT'
    const tomorrow = new Date(Date.now() + 86400000)
    form.date = localDate(tomorrow)
    form.time = '09:00'
  } catch (e) {
    loadError.value = (e && e.msg) || '订单加载失败'
    if (e && e.code === 401) uni.redirectTo({ url: '/pages/employee/login' })
    loading.value = false
    return
  }
  try {
    employees.value = await api.employeeDispatchAssignees() || []
  } catch (_) {
    employees.value = []
  }
  employeesLoaded.value = true
  loading.value = false
}

function setType(type) {
  form.dispatchType = type
  form.selectedKeys = type === 'CONSTRUCTION'
    ? products.value.map(p => p.productKey)
    : []
}

function onDateChange(e) { form.date = e.detail.value }
function onTimeChange(e) { form.time = e.detail.value }

function onEmployeeChange(e) {
  const employee = employees.value[Number(e.detail.value)]
  form.assigneeUserId = employee ? employee.userId : null
}

function toast(title) {
  uni.showToast({ title, icon: 'none' })
}

function submit() {
  if (submitting.value) return
  if (!detail.value.dispatchable) return toast('该订单暂不可派工')
  if (!form.date || !form.time) return toast('请选择期望上门时间')
  const expectTime = form.date + ' ' + form.time + ':00'
  if (new Date(expectTime.replace(/-/g, '/')).getTime() < Date.now() - 60000) {
    return toast('期望上门时间不能早于当前时间')
  }
  if (!form.assigneeUserId) return toast('请选择被指派员工')
  const name = (form.customerName || '').trim()
  if (!name) return toast('客户名称不能为空')
  const phone = (form.customerPhone || '').trim()
  if (!/^1[3-9]\d{9}$/.test(phone)) return toast('客户手机号格式不正确')
  if (form.dispatchType === 'CONSTRUCTION' && !form.selectedKeys.length) {
    return toast('请至少选择一个施工商品')
  }
  if (form.dispatchType === 'VISIT' && !(form.remark || '').trim()) {
    return toast('入户随访任务说明不能为空')
  }
  const selected = products.value.filter(p => form.selectedKeys.includes(p.productKey))
  const sourceItemIds = form.dispatchType === 'CONSTRUCTION'
    ? selected.flatMap(p => p.itemIds || [])
    : []
  submitting.value = true
  api.employeeCreateDispatch({
    sourceOrderId: Number(orderId.value),
    dispatchType: form.dispatchType,
    expectTime,
    assigneeUserId: form.assigneeUserId,
    customerName: name,
    customerPhone: phone,
    communityId: detail.value.communityId || null,
    communityName: (form.communityName || '').trim(),
    detailedAddress: (form.detailedAddress || '').trim(),
    remark: (form.remark || '').trim(),
    sourceItemIds
  }).then(() => {
    uni.showToast({ title: '派工单已创建', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 900)
  }).catch((e) => {
    toast((e && e.msg) || '提交失败，请稍后重试')
  }).finally(() => {
    submitting.value = false
  })
}

function localDate(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 20rpx 24rpx 160rpx; box-sizing: border-box; }

.state { padding: 120rpx 48rpx; display: flex; flex-direction: column; align-items: center; gap: 18rpx; }
.state-icon { font-size: 72rpx; }
.state-text { font-size: 28rpx; color: #4E5969; text-align: center; }
.spinner {
  width: 40rpx; height: 40rpx; border-radius: 50%;
  border: 4rpx solid #FFD8C7; border-top-color: #FF6B35;
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.retry-btn { margin-top: 8rpx; padding: 14rpx 40rpx; border-radius: 32rpx; background: #FF6B35; color: #fff; font-size: 26rpx; }

.summary { background: #fff; border-radius: 20rpx; padding: 24rpx; display: flex; flex-direction: column; gap: 8rpx; }
.summary-top { display: flex; align-items: center; justify-content: space-between; }
.order-no { font-size: 30rpx; font-weight: 700; color: #1D2129; }
.tag { font-size: 22rpx; color: #00B42A; background: #E8F8E8; padding: 4rpx 14rpx; border-radius: 8rpx; }
.summary-customer { font-size: 26rpx; color: #1D2129; }
.summary-address { font-size: 24rpx; color: #86909C; }
.warn { font-size: 24rpx; color: #FF7D00; margin-top: 8rpx; }

.card { background: #fff; border-radius: 20rpx; padding: 24rpx; margin-top: 20rpx; }
.section { display: block; font-size: 26rpx; color: #4E5969; font-weight: 600; margin-bottom: 12rpx; }
.field { display: flex; align-items: center; gap: 16rpx; padding: 12rpx 0; }
.label { width: 148rpx; flex-shrink: 0; font-size: 26rpx; color: #4E5969; }
.picker-wrap { flex: 1; }
.hint { display: block; font-size: 22rpx; color: #86909C; padding-left: 164rpx; line-height: 1.6; }
.hint.warn { color: #FF7D00; }
.input, .picker {
  flex: 1; height: 68rpx; padding: 0 20rpx; border-radius: 12rpx;
  background: #F7F8FA; font-size: 26rpx; color: #1D2129; box-sizing: border-box;
  display: flex; align-items: center;
}
.picker.full { flex: 1; }
.types { flex: 1; display: flex; gap: 16rpx; }
.type {
  flex: 1; text-align: center; padding: 16rpx 0; border-radius: 12rpx;
  background: #F7F8FA; color: #4E5969; font-size: 26rpx;
}
.type.active { background: #FFF0EB; color: #FF6B35; font-weight: 600; }
.time-row { flex: 1; display: flex; gap: 16rpx; }
.time-row .picker { flex: 1; }
.textarea {
  width: 100%; height: 140rpx; padding: 16rpx 20rpx; border-radius: 12rpx;
  background: #F7F8FA; font-size: 26rpx; box-sizing: border-box;
}
.submit-bar { position: fixed; left: 0; right: 0; bottom: 0; padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom)); background: #fff; box-shadow: 0 -2rpx 12rpx rgba(0,0,0,.04); }
.submit { height: 84rpx; border-radius: 42rpx; background: #FF6B35; color: #fff; font-size: 30rpx; font-weight: 600; display: flex; align-items: center; justify-content: center; }
.submit.disabled { background: #FFC4AC; }
</style>
