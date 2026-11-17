<template>
  <view class="page">
    <view class="head">
      <view class="back" @click="goBack"><text>‹ 返回</text></view>
      <text class="title">任务处理</text>
      <EmployeeHomeLink />
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
      <view v-if="isPurchase" class="card">
        <view class="card-title">
          <text>采购订单</text>
          <text class="node">{{ detailNodeName }}</text>
        </view>
        <view class="info-row"><text class="k">单据编号</text><text class="v">{{ order.billNo || '-' }}</text></view>
        <view class="info-row"><text class="k">供应商</text><text class="v">{{ order.supplierName || order.supplierNumber || '-' }}</text></view>
        <view class="info-row"><text class="k">单据日期</text><text class="v">{{ order.billDate || '-' }}</text></view>
        <view class="info-row"><text class="k">金额合计</text><text class="v money">{{ money(order.totalAmount) }}</text></view>
        <view class="info-row"><text class="k">数据来源</text><text class="v">{{ sourceLabel(order.dataSource) }}</text></view>
        <view class="info-row"><text class="k">审批状态</text><text class="v">{{ approvalLabel(order.approvalStatus) }}</text></view>
        <view class="info-row"><text class="k">提交时间</text><text class="v">{{ fmtTime(order.approvalSubmittedAt) }}</text></view>
      </view>

      <view v-if="isMaterialRequisition" class="card">
        <view class="card-title">
          <text>物资领用</text>
          <text class="node">{{ detailNodeName }}</text>
        </view>
        <view class="info-row"><text class="k">领用单号</text><text class="v">{{ order.requisitionNo || '-' }}</text></view>
        <view class="info-row"><text class="k">领用用途</text><text class="v">{{ order.purpose || '-' }}</text></view>
        <view class="info-row"><text class="k">领用人</text><text class="v">{{ order.applicantNames || order.applicantName || '-' }}</text></view>
        <view class="info-row"><text class="k">所属部门</text><text class="v">{{ order.deptName || '未设置部门' }}</text></view>
        <view class="info-row"><text class="k">期望领用日期</text><text class="v">{{ order.expectedDate || '-' }}</text></view>
        <view class="info-row"><text class="k">状态</text><text class="v">{{ materialReqStatus(order.status) }}</text></view>
        <view class="info-row"><text class="k">物料</text><text class="v">{{ order.itemCount || 0 }} 种 · {{ order.totalQty || 0 }} 件</text></view>
      </view>

      <view v-if="isMaterialRequisition && (order.entries || []).length" class="card">
        <text class="card-title">领用明细</text>
        <view v-for="(entry, index) in order.entries" :key="entry.id || index" class="product">
          <view class="product-main">
            <text class="product-name">{{ entry.materialName || entry.materialNumber }}</text>
            <text class="product-meta">
              {{ [entry.category, entry.brand, entry.model].filter(Boolean).join(' · ') }}
              剩余 {{ entry.remainingQty || 0 }} {{ entry.unit || '' }}
            </text>
          </view>
          <text class="product-amount">{{ entry.quantity }} {{ entry.unit || '' }}</text>
        </view>
      </view>

      <view v-if="isMaterialWriteOff" class="card">
        <view class="card-title">
          <text>物资核销</text>
          <text class="node">{{ detailNodeName }}</text>
        </view>
        <view class="info-row"><text class="k">核销单号</text><text class="v">{{ order.writeOffNo || '-' }}</text></view>
        <view class="info-row"><text class="k">关联领用单</text><text class="v">{{ order.requisitionNo || '-' }}</text></view>
        <view class="info-row"><text class="k">领用人</text><text class="v">{{ order.applicantName || '-' }}</text></view>
        <view class="info-row"><text class="k">所属部门</text><text class="v">{{ order.deptName || '未设置部门' }}</text></view>
        <view class="info-row"><text class="k">核销日期</text><text class="v">{{ order.writeOffDate || '-' }}</text></view>
        <view class="info-row"><text class="k">状态</text><text class="v">{{ materialWriteOffStatus(order.status) }}</text></view>
        <view class="info-row"><text class="k">数量</text><text class="v">核销 {{ order.writeOffTotal || 0 }} · 退回 {{ order.returnTotal || 0 }}</text></view>
      </view>

      <view v-if="isMaterialWriteOff && (order.entries || []).length" class="card">
        <text class="card-title">核销明细</text>
        <view v-for="(entry, index) in order.entries" :key="entry.id || index" class="product">
          <view class="product-main">
            <text class="product-name">{{ entry.materialName || entry.materialNumber }}</text>
            <text class="product-meta">
              {{ [entry.category, entry.brand, entry.model].filter(Boolean).join(' · ') }}
              领用 {{ entry.requisitionQty || 0 }} · 余量 {{ entry.remainingQty || 0 }} {{ entry.unit || '' }}
            </text>
          </view>
          <text class="product-amount">核销 {{ entry.writeOffQty || 0 }} / 退回 {{ entry.returnQty || 0 }}</text>
        </view>
      </view>

      <view v-if="isBusinessOrder" class="card">
        <view class="card-title">
          <text>{{ order.bizStatusName || '业务订单' }}</text>
          <text class="node">{{ detailNodeName }}</text>
        </view>
        <view class="info-row"><text class="k">订单编号</text><text class="v">{{ order.orderNo || '-' }}</text></view>
        <view class="info-row"><text class="k">收据号</text><text class="v">{{ order.receiptNo || '-' }}</text></view>
        <view class="info-row"><text class="k">客户</text><text class="v">{{ order.customer || '-' }} {{ order.customerPhone || '' }}</text></view>
        <view class="info-row"><text class="k">小区/地址</text><text class="v">{{ order.communityName || '-' }} {{ order.detailedAddress || '' }}</text></view>
        <view class="info-row"><text class="k">合计金额</text><text class="v money">{{ money(order.totalAmount) }}</text></view>
        <view v-if="order.hasTradeIn" class="info-row">
          <text class="k">抵扣金额</text>
          <text class="v deduction">-{{ money(order.tradeInDeductionAmount) }}</text>
        </view>
        <view v-if="order.hasTradeIn" class="info-row">
          <text class="k">应付合计</text>
          <text class="v money">{{ money(order.payableAmount) }}</text>
        </view>
        <view v-if="order.hasTradeIn" class="info-row">
          <text class="k">旧品采购单</text>
          <text class="v">{{ order.tradeInPurchaseBillNo || '—' }}</text>
        </view>
        <view class="info-row"><text class="k">计算状态</text><text class="v">{{ order.calcStatus || '未计算' }}</text></view>
      </view>

      <view v-if="isBusinessOrder && tradeInInboundDone && isConstructionStage" class="inbound-done">
        <text class="inbound-done-title">✓ 旧品已入库</text>
        <text v-if="order.tradeInPurchaseBillNo" class="inbound-done-sub">
          关联采购单 {{ order.tradeInPurchaseBillNo }}
        </text>
      </view>
      <view v-if="isBusinessOrder && showInboundReminder" class="inbound-alert">
        <text class="inbound-title">⚠️ 本订单含以旧换新旧品，请及时办理旧品入库。</text>
        <text v-if="order.tradeInSummary" class="inbound-line">旧品：{{ order.tradeInSummary }}</text>
        <text v-if="order.tradeInPurchaseBillNo" class="inbound-line">
          关联采购单：{{ order.tradeInPurchaseBillNo }}（未审核）
        </text>
        <text class="inbound-line">入库状态：{{ order.tradeInInboundStatusName || '旧品未入库' }}</text>
        <text v-if="order.tradeInPurchaseStatus === 'FAILED'" class="inbound-line error">
          关联采购单生成失败，请联系经办人在订单详情重试
        </text>
        <text v-if="order.tradeInPurchaseBillNo" class="inbound-hint">
          请在供应链管理 PC 端打开该采购订单办理旧品入库
        </text>
      </view>

      <view v-if="isBusinessOrder && (order.products || []).length" class="card">
        <text class="card-title">商品明细</text>
        <view v-for="(p, i) in order.products" :key="i" class="product">
          <view class="product-main">
            <text class="product-name">{{ p.brand || '' }} {{ p.model || '' }}</text>
            <text class="product-meta">品类 {{ categoryLabel(p.category) }} · 单价 {{ money(p.salePrice) }} · 数量 {{ p.saleQuantity }}</text>
            <text v-if="p.isTradeIn === '是'" class="product-trade">
              以旧换新 · 抵扣 -{{ money(p.deductionAmount) }} · 应付 {{ money(p.payableAmount) }}
            </text>
            <text v-if="p.isTradeIn === '是' && (p.tradeInItems || []).length" class="product-old">
              旧品：{{ tradeInItemsText(p.tradeInItems) }}
            </text>
          </view>
          <text class="product-amount" :class="{ negative: Number(p.receiptAmount) < 0 }">{{ money(p.receiptAmount) }}</text>
        </view>
      </view>

      <ApproverCard v-if="nextApprover" :info="nextApprover"
        @toggle="approverExpanded = !approverExpanded" />

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
          <view v-if="dispatchBlockedReason" class="block-alert">
            <text>{{ dispatchBlockedReason }}</text>
          </view>
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

        <view class="field"><text class="label">处理意见（可选，仅通过时记录）</text>
          <textarea v-model="opinion" class="textarea" maxlength="500" placeholder="通过时的处理意见（可选）" />
        </view>
      </view>

      <view class="actions">
        <button v-if="showGenericReject" class="btn danger" :disabled="submitting" @click="rejectVisible = true">驳回</button>
        <button class="btn primary" :disabled="submitting || !!dispatchBlockedReason" @click="submit">{{ submitting ? '提交中...' : '提交处理' }}</button>
      </view>
    </template>

    <RejectPopup
      v-model:visible="rejectVisible"
      :submitting="submitting"
      @confirm="confirmReject"
    />
  </view>
</template>

<script setup>
import EmployeeHomeLink from '@/components/EmployeeHomeLink.vue'
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'
import { REQUISITION_STATUS, WRITE_OFF_STATUS } from '@/utils/materialForm'
import ApproverCard from '@/components/ApproverCard.vue'
import RejectPopup from '@/components/RejectPopup.vue'

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
const rejectVisible = ref(false)
const categoryMap = ref({})
const approverExpanded = ref(false)

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
/** 派工准入：is_full_payment=1 且已财务核对；定金未收齐或未核对时禁止提交。 */
const dispatchBlockedReason = computed(() => {
  if (actionType.value !== 'OPS_DISPATCH_OUTBOUND') return ''
  const o = order.value || {}
  const full = Number(o.isFullPayment) === 1
  const finance = Number(o.bizStatus || 0) >= 2 || !!o.financeCheckTime
    || String(o.financeCheckConclusion || '') === 'PASS'
  const balance = (Number(o.balanceAmount) || 0).toFixed(2)
  if (!full && !finance) {
    return `该订单为定金支付，尚有 ¥${balance} 余款未收，请先在【货款补录】完成收款；且该订单尚未财务核对，暂不能派工出库`
  }
  if (!full) return `该订单为定金支付，尚有 ¥${balance} 余款未收，请先在【货款补录】完成收款`
  if (!finance) return '该订单尚未财务核对，暂不能派工出库'
  return ''
})
const detailNodeName = computed(() => (detail.value && detail.value.task && detail.value.task.nodeName) || '')
/** 同一底座工作流 H5 页面按 businessType 分支展示与审批 */
const businessType = computed(() => {
  const instance = detail.value && detail.value.instance
  return (instance && instance.businessType) || ''
})
const isPurchase = computed(() => businessType.value === 'PUR_ORDER')
/** 物资领用/核销：底座待办只返回“当前用户是审批人”的任务，这里补详情展示 */
const isMaterialRequisition = computed(() => businessType.value === 'OPS_MATERIAL_REQUISITION')
const isMaterialWriteOff = computed(() => businessType.value === 'OPS_MATERIAL_WRITE_OFF')
const isBusinessOrder = computed(() => !isPurchase.value
  && !isMaterialRequisition.value && !isMaterialWriteOff.value)
/** 旧品入库完成（SCM inboundStatus=ALL）后提醒消失，改为已入库标识。 */
const tradeInInboundDone = computed(() => {
  const o = order.value || {}
  return !!o.hasTradeIn && (o.tradeInInboundStatus === 'ALL'
    || o.tradeInInboundStatusName === '旧品已入库')
})
/**
 * 施工完成节点（施工记录/施工验收）处理页必须提示旧品入库；
 * 其余节点在业务状态到达 5 施工完成后同样提示，直到入库完成。
 */
const isConstructionStage = computed(() => {
  if (['OPS_CONSTRUCTION_RECORD', 'OPS_ACCEPTANCE'].includes(actionType.value)) return true
  const o = order.value || {}
  const match = String(o.bizStatus == null ? '' : o.bizStatus).match(/\d+/)
  if (match) return Number(match[0]) >= 5
  return /施工完成|验收|完成/.test(String(o.bizStatusName || ''))
})
const showInboundReminder = computed(() => {
  const o = order.value || {}
  return !!o.hasTradeIn && isConstructionStage.value && !tradeInInboundDone.value
})

function tradeInItemsText(items) {
  return (items || [])
    .map(item => `${item.productName || item.brand || '旧品'} ×${item.quantity || 0}`)
    .join('、')
}
/** 当前节点处理完后的下一步审批人（底座 instance.nextNodePreview 已含姓名/规则） */
const nextApprover = computed(() => {
  const instance = detail.value && detail.value.instance
  if (!instance) return null
  const preview = instance.nextNodePreview
  if (preview) {
    const names = (preview.approvers || []).map(a => a.userName).filter(Boolean)
    const roles = [...new Set((preview.approvers || []).map(a => a.roleName).filter(Boolean))]
    return buildApprover(preview.nodeName, names, roles, preview.approveMode,
      names.length ? '' : (preview.resolveMessage || '待分配审批人，请联系管理员'))
  }
  if (detail.value?.node?.lastNode) {
    return buildApprover('', [], [], '', '当前为最后一个审批节点，提交后流程完成')
  }
  if (instance.status && instance.status !== 'RUNNING') {
    return buildApprover('', [], [], '', statusText(instance.status))
  }
  return buildApprover('', [], [], '', '暂无法获取下一审批人，提交后可在详情页查看')
})
const actionTitle = computed(() => {
  if (isMaterialRequisition.value) return '物资领用审批'
  if (isMaterialWriteOff.value) return '物资核销审批'
  return {
    OPS_FINANCE_CHECK: '财务核对',
    OPS_DISPATCH_OUTBOUND: '派工出库',
    OPS_CONSTRUCTION_RECORD: '施工记录',
    OPS_ACCEPTANCE: '施工完成验收',
    OPS_AFTER_SALES: '售后维护'
  }[actionType.value] || '节点处理'
})
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
    if (isPurchase.value) {
      // 采购审批：订单详情走 SCM，审批动作走 SCM 代理（回写审批人后由底座回调驱动）
      order.value = await api.scmPurOrderDetail(businessId)
      return
    }
    if (isMaterialRequisition.value) {
      // 审批人视角详情：后端用 taskId 再校验当前用户是本任务审批人，避免越权读他人单据
      order.value = await api.materialRequisitionApprovalDetail(businessId, taskId.value)
      return
    }
    if (isMaterialWriteOff.value) {
      order.value = await api.materialWriteOffApprovalDetail(businessId, taskId.value)
      return
    }
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
/** 采购订单审批页展示用（不依赖字典，避免 H5 额外请求） */
function sourceLabel(v) {
  return {
    DIRECT: '直接采购', BUSINESS_DATA: '业务数据', MALL_ORDER: '商城订单',
    BUSINESS_ORDER_TRADE_IN: '业务订单以旧换新'
  }[v] || v || '-'
}
function approvalLabel(v) {
  return { NONE: '无需审批', IN_APPROVAL: '审批中', APPROVED: '已通过', REJECTED: '已驳回' }[v] || v || '-'
}
function fmtTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
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
  if (dispatchBlockedReason.value) return dispatchBlockedReason.value
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
  // 暂缓/驳回不向下一节点流转，不需要展示下一审批人
  if (actionType.value === 'OPS_FINANCE_CHECK' && form.checkConclusion === 'HOLD') {
    return doSubmit()
  }
  const willReject = (actionType.value === 'OPS_FINANCE_CHECK' && form.checkConclusion === 'REJECT')
    || (actionType.value === 'OPS_ACCEPTANCE' && form.acceptanceConclusion === 'REJECT')
  if (willReject) {
    return doSubmit()
  }
  const info = nextApprover.value
  const content = info && info.display
    ? `确认提交给 ${info.display}${info.nodeName ? '（' + info.nodeName + '）' : ''} 审批？`
    : (info && info.message ? info.message : '提交后将进入下一步，审批人信息请在提交后查看。')
  uni.showModal({
    title: '确认提交',
    content,
    confirmText: '确认提交',
    success: (res) => { if (res.confirm) doSubmit() }
  })
}

async function doSubmit() {
  submitting.value = true
  try {
    if (isPurchase.value) {
      const result = await api.scmPurOrderApprove({
        taskId: Number(taskId.value), opinion: opinion.value
      })
      showSubmitResult(result, '审批已通过')
      return
    }
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
    const result = await api.workflowApprove({
      taskId: Number(taskId.value),
      opinion: opinion.value,
      formData
    })
    showSubmitResult(result, '处理成功，流程已推进')
  } catch (e) {
    toast((e && e.msg) || '处理失败，请重试')
  } finally {
    submitting.value = false
  }
}

/** 提交结果：显示实际下一审批人；取不到时展示兜底文案，不静默 */
function showSubmitResult(result, fallback) {
  const info = normalizeResult(result)
  if (info.names.length) {
    const names = info.names.join('、')
    const who = info.roles.length ? `${info.roles.join('、')}：${names}` : names
    uni.showModal({
      title: '已提交',
      content: `已提交给 ${who}${info.nodeName ? '（' + info.nodeName + '）' : ''} 审批`,
      showCancel: false,
      success: goTodo
    })
    return
  }
  if (info.message) {
    uni.showModal({ title: '处理成功', content: info.message, showCancel: false, success: goTodo })
    return
  }
  toast(fallback)
  goTodo()
}

/** 兼容 SCM NextApproverInfo 与底座 FlowResult 两种返回结构 */
function normalizeResult(result) {
  if (!result) return { names: [], roles: [], nodeName: '', message: '' }
  if (Array.isArray(result.nodes)) {
    const names = []
    const roles = []
    result.nodes.forEach(node => (node.approvers || []).forEach(a => {
      if (a.userName && !names.includes(a.userName)) names.push(a.userName)
      if (a.roleName && !roles.includes(a.roleName)) roles.push(a.roleName)
    }))
    const node = result.nodes.find(n => (n.approvers || []).length)
    return {
      names, roles, nodeName: node ? node.nodeName : '',
      message: result.fallbackMessage || ''
    }
  }
  // 会签未完成时 pendingApprovers 是当前节点剩余待办人，优先于下一节点
  const pending = result.pendingApprovers || []
  const source = pending.length ? pending : (result.nextApprovers || [])
  const names = source.map(a => a.userName).filter(Boolean)
  const roles = [...new Set(source.map(a => a.roleName).filter(Boolean))]
  const nodeName = pending.length
    ? (result.currentNode?.nodeName || '')
    : (result.nextNodeName || '')
  return { names, roles, nodeName, message: result.resolveMessage || '' }
}

function buildApprover(nodeName, names, roles, rule, message) {
  const collapsed = names.length > 2 && !approverExpanded.value
  const shown = collapsed ? names.slice(0, 1) : names
  const namesText = names.length
    ? shown.map(shortName).join('、') + (collapsed ? ` 等 ${names.length} 人` : '')
    : ''
  const roleText = (roles || []).join('、')
  // 角色配置时展示“总经办：张三、李四”；无法展开时至少保留角色名
  const display = names.length
    ? (roleText ? `${roleText}：${namesText}` : namesText)
    : ''
  return {
    nodeName, display, message, roleText,
    ruleText: ruleLabel(rule), expandable: names.length > 2
  }
}

/** 移动端空间有限：超长姓名省略中间字，但姓名始终可见 */
function shortName(name) {
  const text = String(name || '')
  return text.length > 4 ? text.charAt(0) + '*' + text.charAt(text.length - 1) : text
}

function ruleLabel(rule) {
  return { ALL: '会签 · 全部通过', ANY: '或签 · 任一通过', SEQUENTIAL: '依次审批' }[rule] || ''
}

function statusText(status) {
  return { COMPLETED: '审批已完成', REJECTED: '审批已驳回', TERMINATED: '审批已终止' }[status]
    || '审批已结束'
}

function materialReqStatus(value) {
  return REQUISITION_STATUS[value] || value || '-'
}

function materialWriteOffStatus(value) {
  return WRITE_OFF_STATUS[value] || value || '-'
}

async function confirmReject(opinionText) {
  if (submitting.value) return
  const text = String(opinionText || '').trim()
  if (text.length < 5) return toast('请填写驳回意见（不少于 5 个字）')
  submitting.value = true
  try {
    const payload = { taskId: Number(taskId.value), opinion: text }
    if (isPurchase.value) {
      await api.scmPurOrderReject(payload)
    } else {
      await api.workflowReject(payload)
    }
    rejectVisible.value = false
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
.title { flex: 1; text-align: center; font-size: 34rpx; font-weight: 700; color: #fff; }
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
.deduction { color: #C62828; font-weight: 600; }
.product { display: flex; justify-content: space-between; padding: 14rpx 0; border-top: 1rpx solid #F2F3F5; }
.product:first-of-type { border-top: none; }
.product-main { flex: 1; padding-right: 12rpx; }
.product-name { display: block; font-size: 26rpx; color: #1D2129; }
.product-meta { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.product-amount { font-size: 26rpx; color: #1D2129; font-weight: 600; }
.negative { color: #F53F3F; }
.inbound-alert {
  margin: 20rpx 24rpx 0; padding: 22rpx 24rpx; border-radius: 16rpx;
  background: #FFF7E6; border: 2rpx solid #FFB74D;
  display: flex; flex-direction: column; gap: 8rpx;
}
.inbound-title { font-size: 26rpx; font-weight: 700; color: #B26A00; line-height: 1.5; }
.inbound-line { font-size: 23rpx; color: #8C5A00; line-height: 1.5; word-break: break-all; }
.inbound-line.error { color: #C62828; font-weight: 600; }
.inbound-hint { font-size: 21rpx; color: #A9812F; }
.inbound-done {
  margin: 20rpx 24rpx 0; padding: 18rpx 24rpx; border-radius: 16rpx;
  background: #F0FBF4; border: 2rpx solid #BFE9CF;
  display: flex; align-items: center; justify-content: space-between; gap: 12rpx;
}
.inbound-done-title { font-size: 25rpx; font-weight: 700; color: #0B7A42; }
.inbound-done-sub { font-size: 21rpx; color: #3D8B62; }
.product-trade { display: block; margin-top: 6rpx; font-size: 21rpx; color: #B26A00; }
.product-old { display: block; margin-top: 4rpx; font-size: 20rpx; color: #A9812F; line-height: 1.5; }
.block-alert {
  margin: 0 0 16rpx; padding: 18rpx 20rpx; border-radius: 12rpx;
  background: #FFF7E6; border: 2rpx solid #FFD591; color: #B26A00;
  font-size: 24rpx; line-height: 1.5;
}
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
.btn[disabled] { opacity: .6; }
</style>
