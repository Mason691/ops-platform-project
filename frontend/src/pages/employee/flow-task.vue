<template>
  <view class="page">
    <view class="head">
      <view class="back" @click="goBack"><text>‹ 返回</text></view>
      <text class="title">任务处理</text>
      <view class="back placeholder" />
    </view>

    <view v-if="missing" class="state">
      <text class="empty-icon">🔗</text>
      <text class="state-title">链接缺少任务信息</text>
      <text class="state-sub">请返回待办列表重新进入，或从工作台打开。</text>
      <view class="btn primary" @click="goTodo"><text>返回待办列表</text></view>
      <view class="btn ghost" @click="goDashboard"><text>返回工作台</text></view>
    </view>

    <view v-else-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-title">无法打开任务</text>
      <text class="state-sub">{{ errorMsg }}</text>
      <view class="btn primary" @click="loadData"><text>重试</text></view>
      <view class="btn ghost" @click="goTodo"><text>返回待办列表</text></view>
    </view>

    <template v-else>
      <view class="card">
        <view class="card-title">
          <text>{{ order.bizStatusName || '业务订单' }}</text>
          <text class="node">{{ task.nodeName || detailNodeName }}</text>
        </view>
        <view class="info-row"><text class="k">订单编号</text><text class="v">{{ order.orderNo || '-' }}</text></view>
        <view class="info-row"><text class="k">收据号</text><text class="v">{{ order.receiptNo || '-' }}</text></view>
        <view class="info-row"><text class="k">客户</text><text class="v">{{ order.customer || '-' }} {{ order.customerPhone || '' }}</text></view>
        <view class="info-row"><text class="k">小区/地址</text><text class="v">{{ order.communityName || '-' }} {{ order.detailedAddress || '' }}</text></view>
        <view class="info-row"><text class="k">合计金额</text><text class="v money">{{ money(order.totalAmount) }}</text></view>
        <view class="info-row"><text class="k">计算状态</text><text class="v">{{ order.calcStatus || '未计算' }}</text></view>
      </view>

      <view v-if="(order.products || []).length" class="card">
        <text class="card-title">商品明细</text>
        <view v-for="(p, i) in order.products" :key="i" class="product">
          <view class="product-main">
            <text class="product-name">{{ p.brand || '' }} {{ p.model || '' }}</text>
            <text class="product-meta">品类 {{ categoryLabel(p.category) }} · 单价 {{ money(p.salePrice) }} · 数量 {{ p.saleQuantity }}</text>
          </view>
          <text class="product-amount" :class="{ negative: Number(p.receiptAmount) < 0 }">{{ money(p.receiptAmount) }}</text>
        </view>
      </view>

      <view class="card">
        <text class="card-title">{{ actionTitle }}</text>

        <template v-if="actionType === 'OPS_FINANCE_CHECK'">
          <view class="field"><text class="label">收据号 <text class="req">*</text></text>
            <input v-model="form.receiptNo" class="input" maxlength="50" placeholder="必填" />
          </view>
          <view class="field"><text class="label">财务收款日期 <text class="req">*</text></text>
            <picker mode="date" :value="form.financeReceiptDate" @change="onDate('financeReceiptDate', $event)">
              <view class="picker">{{ form.financeReceiptDate || '请选择日期' }}</view>
            </picker>
          </view>
          <view class="field"><text class="label">资金动态表横排数</text>
            <input v-model="form.fundDynamicRows" class="input" type="number" placeholder="可空，非负整数" />
          </view>
          <view class="field"><text class="label">是否收款 <text class="req">*</text></text>
            <view class="chips">
              <view v-for="opt in yesNo" :key="opt" class="chip" :class="{ on: form.isReceived === opt }" @click="form.isReceived = opt">{{ opt }}</view>
            </view>
          </view>
          <view class="field"><text class="label">核对结论 <text class="req">*</text></text>
            <view class="chips">
              <view v-for="opt in conclusions" :key="opt.value" class="chip" :class="{ on: form.checkConclusion === opt.value }" @click="form.checkConclusion = opt.value">{{ opt.label }}</view>
            </view>
          </view>
          <view class="field"><text class="label">核对备注</text>
            <textarea v-model="form.checkRemark" class="textarea" maxlength="500" placeholder="不通过时必须填写原因（≥5字）" />
          </view>
        </template>

        <template v-else-if="actionType === 'OPS_DISPATCH_OUTBOUND'">
          <view class="field"><text class="label">工单类型 <text class="req">*</text></text>
            <view class="chips">
              <view class="chip" :class="{ on: form.dispatchType === 'CONSTRUCTION' }" @click="form.dispatchType = 'CONSTRUCTION'">施工</view>
              <view class="chip" :class="{ on: form.dispatchType === 'VISIT' }" @click="form.dispatchType = 'VISIT'">入户随访</view>
            </view>
          </view>
          <view class="field"><text class="label">上门员工 <text class="req">*</text></text>
            <picker mode="selector" :range="assigneeNames" :value="assigneeIndex" @change="onAssignee">
              <view class="picker">{{ assigneeName || '请选择上门员工' }}</view>
            </picker>
          </view>
          <view class="field"><text class="label">期望上门 <text class="req">*</text></text>
            <view class="row2">
              <picker mode="date" :value="expectDate" @change="expectDate = $event.detail.value; syncExpect()">
                <view class="picker">{{ expectDate || '日期' }}</view>
              </picker>
              <picker mode="time" :value="expectClock" @change="expectClock = $event.detail.value; syncExpect()">
                <view class="picker">{{ expectClock || '时间' }}</view>
              </picker>
            </view>
          </view>
          <view v-if="form.dispatchType === 'CONSTRUCTION'" class="field"><text class="label">施工商品</text>
            <checkbox-group @change="onProducts">
              <label v-for="p in dispatchProducts" :key="p.productKey" class="check-row">
                <checkbox :value="p.productKey" :checked="selectedKeys.includes(p.productKey)" color="#FF6B35" />
                <text>{{ p.brand || '' }} {{ p.model || '' }} ×{{ p.saleQuantity || 1 }}</text>
              </label>
            </checkbox-group>
            <text class="hint">不勾选表示订单全部正数商品</text>
          </view>
          <view v-if="form.dispatchType === 'VISIT'" class="field"><text class="label">随访任务说明 <text class="req">*</text></text>
            <textarea v-model="form.visitNote" class="textarea" maxlength="500" placeholder="请填写随访任务说明" />
          </view>
        </template>

        <template v-else-if="actionType === 'OPS_CONSTRUCTION_RECORD'">
          <view class="field"><text class="label">施工说明</text>
            <textarea v-model="form.constructionNote" class="textarea" maxlength="1000" placeholder="现场施工情况" />
          </view>
          <view class="field"><text class="label">完工说明</text>
            <textarea v-model="form.completionNote" class="textarea" maxlength="1000" placeholder="完工情况、更换配件等" />
          </view>
        </template>

        <template v-else-if="actionType === 'OPS_ACCEPTANCE'">
          <view class="field"><text class="label">验收结论 <text class="req">*</text></text>
            <view class="chips">
              <view class="chip" :class="{ on: form.acceptanceConclusion === 'PASS' }" @click="form.acceptanceConclusion = 'PASS'">通过</view>
              <view class="chip" :class="{ on: form.acceptanceConclusion === 'REJECT' }" @click="form.acceptanceConclusion = 'REJECT'">不通过</view>
            </view>
          </view>
          <view class="field"><text class="label">验收说明</text>
            <textarea v-model="form.acceptanceNote" class="textarea" maxlength="1000" placeholder="验收说明/不通过原因（≥5字）" />
          </view>
        </template>

        <template v-else-if="actionType === 'OPS_AFTER_SALES'">
          <view class="field"><text class="label">售后内容</text>
            <textarea v-model="form.serviceNote" class="textarea" maxlength="1000" placeholder="售后维护内容" />
          </view>
          <view class="field"><text class="label">回访结果</text>
            <textarea v-model="form.visitResult" class="textarea" maxlength="1000" placeholder="回访结果" />
          </view>
        </template>

        <view v-else class="no-action"><text>当前节点为审批节点，确认信息后提交即可。</text></view>

        <view class="field"><text class="label">处理意见</text>
          <textarea v-model="opinion" class="textarea" maxlength="500" placeholder="可选" />
        </view>

        <view v-if="rejecting" class="field reject-box">
          <text class="label">驳回意见 <text class="req">*</text></text>
          <textarea v-model="rejectNote" class="textarea" maxlength="500" placeholder="请填写驳回原因（不少于5个字）" />
          <view class="actions">
            <button class="btn ghost-line" @click="rejecting = false">取消</button>
            <button class="btn danger" :disabled="submitting" @click="confirmReject">确认驳回</button>
          </view>
        </view>
      </view>

      <view class="actions">
        <button v-if="showGenericReject" class="btn danger" :disabled="submitting" @click="rejecting = !rejecting">驳回</button>
        <button class="btn primary" :disabled="submitting" @click="submit">{{ submitting ? '提交中...' : '提交处理' }}</button>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

const loading = ref(true)
const submitting = ref(false)
const missing = ref(false)
const errorMsg = ref('')
const taskId = ref('')
const instanceId = ref('')
const detail = ref(null)
const order = ref({})
const assignees = ref([])
const selectedKeys = ref([])
const expectDate = ref('')
const expectClock = ref('')
const opinion = ref('')
const rejecting = ref(false)
const rejectNote = ref('')
const categoryMap = ref({})

const form = reactive({
  receiptNo: '', financeReceiptDate: '', fundDynamicRows: null,
  isReceived: '是', checkConclusion: 'PASS', checkRemark: '',
  dispatchType: 'CONSTRUCTION', assigneeUserId: null, expectTime: '', visitNote: '',
  constructionNote: '', completionNote: '',
  acceptanceConclusion: 'PASS', acceptanceNote: '',
  serviceNote: '', visitResult: ''
})

const yesNo = ['是', '否']
const conclusions = [
  { label: '通过', value: 'PASS' },
  { label: '不通过', value: 'REJECT' },
  { label: '暂缓', value: 'HOLD' }
]

const actionType = computed(() => (detail.value && detail.value.node && detail.value.node.actionType) || '')
const detailNodeName = computed(() => (detail.value && detail.value.task && detail.value.task.nodeName) || '')
const actionTitle = computed(() => ({
  OPS_FINANCE_CHECK: '财务核对',
  OPS_DISPATCH_OUTBOUND: '派工出库',
  OPS_CONSTRUCTION_RECORD: '施工记录',
  OPS_ACCEPTANCE: '施工完成验收',
  OPS_AFTER_SALES: '售后维护'
}[actionType.value] || '节点处理'))
const showGenericReject = computed(() => !['OPS_FINANCE_CHECK', 'OPS_ACCEPTANCE'].includes(actionType.value))
const dispatchProducts = computed(() => (order.value.products || [])
  .filter(p => !p.isReturnEntry && Number(p.saleQuantity || 1) > 0))
const assigneeNames = computed(() => assignees.value.map(u => (u.name || '') + '（' + (u.phone || '') + '）'))
const assigneeIndex = computed(() => {
  const idx = assignees.value.findIndex(u => String(u.userId) === String(form.assigneeUserId))
  return idx < 0 ? 0 : idx
})
const assigneeName = computed(() => {
  const u = assignees.value.find(x => String(x.userId) === String(form.assigneeUserId))
  return u ? (u.name || '') + '（' + (u.phone || '') + '）' : ''
})

onLoad((options) => {
  taskId.value = (options && options.taskId) || ''
  instanceId.value = (options && options.instanceId) || ''
  if (!taskId.value) {
    missing.value = true
    loading.value = false
    return
  }
  if (!isEmployeeLoggedIn()) {
    uni.redirectTo({
      url: '/pages/employee/login?redirect=' + encodeURIComponent(
        '/pages/employee/flow-task?taskId=' + taskId.value + '&instanceId=' + instanceId.value)
    })
    return
  }
  loadData()
})

async function loadData() {
  loading.value = true
  errorMsg.value = ''
  try {
    detail.value = await api.workflowTaskDetail(taskId.value)
    const businessId = detail.value && detail.value.instance ? detail.value.instance.businessId : ''
    if (!businessId) throw { msg: '任务未关联业务单据' }
    order.value = await api.businessOrderDetail(businessId)
    const r = order.value || {}
    form.receiptNo = r.receiptNo || ''
    form.financeReceiptDate = normalizeDate(r.financeReceiptDate || r.receiptDate)
    form.fundDynamicRows = r.fundDynamicRows == null ? null : r.fundDynamicRows
    form.isReceived = r.isReceived || '是'
    if (actionType.value === 'OPS_DISPATCH_OUTBOUND') {
      try { assignees.value = await api.businessFlowAssignees() || [] } catch (_) { assignees.value = [] }
      selectedKeys.value = dispatchProducts.value.map(p => p.productKey)
    }
    loadCategoryDict()
  } catch (e) {
    if (e && e.code === 401) {
      uni.redirectTo({
        url: '/pages/employee/login?redirect=' + encodeURIComponent(
          '/pages/employee/flow-task?taskId=' + taskId.value + '&instanceId=' + instanceId.value)
      })
      return
    }
    errorMsg.value = (e && e.msg) || '加载任务失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function loadCategoryDict() {
  try {
    const data = await api.getDictData('PRODUCT_CATEGORY')
    const map = {}
    ;(data || []).forEach(i => { map[String(i.itemKey)] = i.itemValue })
    categoryMap.value = map
  } catch (_) { /* 品类展示失败不阻断 */ }
}

function normalizeDate(raw) {
  const text = String(raw || '')
  const m = text.match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/)
  if (!m) return ''
  return m[1] + '-' + String(m[2]).padStart(2, '0') + '-' + String(m[3]).padStart(2, '0')
}

function categoryLabel(key) { return categoryMap.value[String(key)] || key }
function money(v) {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  return Number.isFinite(n) ? '¥' + n.toFixed(2) : String(v)
}

function onDate(field, e) { form[field] = e.detail.value }
function onAssignee(e) {
  const idx = Number(e.detail.value)
  const u = assignees.value[idx]
  if (u) form.assigneeUserId = u.userId
}
function onProducts(e) { selectedKeys.value = e.detail.value || [] }
function syncExpect() {
  if (expectDate.value && expectClock.value) {
    form.expectTime = expectDate.value + ' ' + expectClock.value + ':00'
  }
}

function buildFormData() {
  const data = { ...form }
  if (actionType.value === 'OPS_DISPATCH_OUTBOUND') {
    const selected = new Set(selectedKeys.value)
    data.selectedItemIds = dispatchProducts.value
      .filter(p => selected.has(p.productKey))
      .flatMap(p => p.rowIds || [])
  }
  return data
}

function validate() {
  if (actionType.value === 'OPS_FINANCE_CHECK') {
    if (!String(form.receiptNo || '').trim()) return '请填写收据号'
    if (!form.financeReceiptDate) return '请选择财务收款日期'
    if (!form.checkConclusion) return '请选择核对结论'
    if (form.checkConclusion === 'REJECT' && String(form.checkRemark || '').trim().length < 5) {
      return '核对不通过原因不少于5个字'
    }
  }
  if (actionType.value === 'OPS_DISPATCH_OUTBOUND') {
    if (!form.assigneeUserId) return '请选择上门员工'
    if (!form.expectTime) return '请选择期望上门时间'
    if (form.dispatchType === 'VISIT' && !String(form.visitNote || '').trim()) return '请填写随访任务说明'
  }
  if (actionType.value === 'OPS_ACCEPTANCE') {
    if (!form.acceptanceConclusion) return '请选择验收结论'
    if (form.acceptanceConclusion === 'REJECT' && String(form.acceptanceNote || '').trim().length < 5) {
      return '验收不通过原因不少于5个字'
    }
  }
  return ''
}

async function submit() {
  const error = validate()
  if (error) return toast(error)
  submitting.value = true
  try {
    const formData = buildFormData()
    if (actionType.value === 'OPS_FINANCE_CHECK' && form.checkConclusion === 'HOLD') {
      await api.businessHoldFinance(order.value.orderId, formData)
      toast('已暂缓，流程停留在财务核对节点')
      return
    }
    if (actionType.value === 'OPS_FINANCE_CHECK' && form.checkConclusion === 'REJECT') {
      await api.workflowReject({
        taskId: Number(taskId.value),
        opinion: String(form.checkRemark || '财务核对不通过').trim(),
        formData
      })
      toast('已退回经办人')
      goTodo()
      return
    }
    if (actionType.value === 'OPS_ACCEPTANCE' && form.acceptanceConclusion === 'REJECT') {
      await api.workflowReject({
        taskId: Number(taskId.value),
        opinion: String(form.acceptanceNote || '验收不通过退回施工中').trim(),
        formData
      })
      toast('已退回施工中')
      goTodo()
      return
    }
    await api.workflowApprove({
      taskId: Number(taskId.value),
      opinion: opinion.value,
      formData
    })
    toast('处理成功，流程已推进')
    goTodo()
  } catch (e) {
    toast((e && e.msg) || '处理失败，请重试')
  } finally {
    submitting.value = false
  }
}

async function confirmReject() {
  if (submitting.value) return
  const text = String(rejectNote.value || '').trim()
  if (text.length < 5) return toast('驳回意见至少5个字')
  submitting.value = true
  try {
    await api.workflowReject({ taskId: Number(taskId.value), opinion: text })
    toast('已驳回')
    goTodo()
  } catch (e) {
    toast((e && e.msg) || '驳回失败，请重试')
  } finally {
    submitting.value = false
  }
}

function toast(title) { uni.showToast({ title, icon: 'none', duration: 2500 }) }
function goTodo() { uni.redirectTo({ url: '/pages/employee/flow-todo' }) }
function goDashboard() { uni.redirectTo({ url: '/pages/employee/dashboard' }) }
function goBack() {
  const pages = getCurrentPages()
  if (pages && pages.length > 1) uni.navigateBack()
  else goTodo()
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f6f8; padding-bottom: 40rpx; }
.head { display: flex; align-items: center; justify-content: space-between; padding: 26rpx 28rpx; background: linear-gradient(135deg, #FF6B35, #E85D2C); }
.back { min-width: 120rpx; font-size: 26rpx; color: #fff; }
.back.placeholder { visibility: hidden; }
.title { font-size: 34rpx; font-weight: 700; color: #fff; }
.state { padding: 120rpx 48rpx; display: flex; flex-direction: column; align-items: center; }
.empty-icon { font-size: 72rpx; margin-bottom: 20rpx; }
.state-title { font-size: 32rpx; font-weight: 600; color: #1D2129; margin-bottom: 12rpx; }
.state-sub { font-size: 26rpx; color: #86909C; text-align: center; line-height: 1.6; margin-bottom: 28rpx; }
.card { margin: 20rpx 24rpx; background: #fff; border-radius: 20rpx; padding: 26rpx; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.04); }
.card-title { display: flex; justify-content: space-between; align-items: center; font-size: 30rpx; font-weight: 600; color: #1D2129; margin-bottom: 18rpx; }
.node { font-size: 24rpx; color: #FF6B35; font-weight: 500; }
.info-row { display: flex; justify-content: space-between; padding: 9rpx 0; font-size: 26rpx; }
.k { color: #86909C; flex: 0 0 150rpx; }
.v { color: #1D2129; text-align: right; flex: 1; word-break: break-all; }
.money { color: #FF6B35; font-weight: 600; }
.product { display: flex; justify-content: space-between; padding: 14rpx 0; border-top: 1rpx solid #F2F3F5; }
.product:first-of-type { border-top: none; }
.product-main { flex: 1; padding-right: 12rpx; }
.product-name { display: block; font-size: 26rpx; color: #1D2129; }
.product-meta { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.product-amount { font-size: 26rpx; color: #1D2129; font-weight: 600; }
.negative { color: #F53F3F; }
.field { margin-top: 22rpx; }
.label { display: block; font-size: 25rpx; color: #4E5969; margin-bottom: 10rpx; }
.req { color: #F53F3F; }
.input, .picker, .textarea { width: 100%; box-sizing: border-box; background: #F7F8FA; border-radius: 12rpx; padding: 20rpx 22rpx; font-size: 26rpx; color: #1D2129; }
.textarea { min-height: 150rpx; }
.picker { display: flex; align-items: center; }
.row2 { display: flex; gap: 16rpx; }
.row2 > * { flex: 1; }
.chips { display: flex; flex-wrap: wrap; gap: 14rpx; }
.chip { padding: 14rpx 30rpx; border-radius: 40rpx; background: #F2F3F5; color: #4E5969; font-size: 25rpx; }
.chip.on { background: #FFF0EB; color: #FF6B35; border: 1rpx solid #FF6B35; font-weight: 600; }
.check-row { display: flex; align-items: center; gap: 12rpx; padding: 12rpx 0; font-size: 26rpx; color: #1D2129; }
.hint { display: block; margin-top: 8rpx; font-size: 22rpx; color: #86909C; }
.no-action { padding: 20rpx 0; font-size: 26rpx; color: #86909C; }
.actions { display: flex; gap: 20rpx; padding: 24rpx 28rpx 0; }
.btn { flex: 1; height: 88rpx; line-height: 88rpx; border-radius: 44rpx; font-size: 30rpx; border: none; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn.danger { background: #fff; color: #F53F3F; border: 1rpx solid #F53F3F; }
.btn.ghost { background: #fff; color: #FF6B35; border: 1rpx solid #FF6B35; margin-top: 16rpx; width: 100%; }
.btn.ghost-line { background: #fff; color: #4E5969; border: 1rpx solid #C9CDD4; }
.btn[disabled] { opacity: .6; }
.reject-box { background: #FFF7F5; border-radius: 12rpx; padding: 18rpx; }
</style>
