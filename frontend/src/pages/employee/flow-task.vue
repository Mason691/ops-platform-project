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

      <view v-if="isScmPick" class="card">
        <view class="card-title">
          <text>物料领用</text>
          <text class="node">{{ detailNodeName }}</text>
        </view>
        <view class="info-row"><text class="k">领用单号</text><text class="v">{{ order.billNo || '-' }}</text></view>
        <view class="info-row"><text class="k">领用事由</text><text class="v">{{ pickReasonLabel(order.pickReason) }}</text></view>
        <view class="info-row"><text class="k">领用人</text><text class="v">{{ order.pickerName || '-' }}</text></view>
        <view class="info-row"><text class="k">领用仓库</text><text class="v">{{ order.stockNumber || '未指定' }}</text></view>
        <view v-if="order.taskOrderNo" class="info-row">
          <text class="k">关联工单</text><text class="v">{{ order.taskOrderNo }}</text>
        </view>
        <view class="info-row"><text class="k">提交时间</text><text class="v">{{ fmtTime(order.approvalSubmittedAt) }}</text></view>
      </view>

      <view v-if="isScmPick && (order.entries || []).length" class="card">
        <text class="card-title">领用明细</text>
        <view v-for="(entry, index) in order.entries" :key="entry.id || index" class="product">
          <view class="product-main">
            <text class="product-name">{{ entry.materialName || entry.materialNumber }}</text>
            <text class="product-meta">
              {{ [entry.materialNumber, entry.spec,
                  entry.batchNo ? '批次 ' + entry.batchNo : ''].filter(Boolean).join(' · ') }}
            </text>
          </view>
          <text class="product-amount">{{ entry.pickQty }} {{ entry.unitName || '' }}</text>
        </view>
      </view>

      <view v-if="isScmWriteOff" class="card">
        <view class="card-title">
          <text>物料核销</text>
          <text class="node">{{ detailNodeName }}</text>
        </view>
        <view class="info-row"><text class="k">核销单号</text><text class="v">{{ order.billNo || '-' }}</text></view>
        <view class="info-row"><text class="k">关联领用单</text><text class="v">{{ order.pickBillNo || '-' }}</text></view>
        <view class="info-row"><text class="k">核销人</text><text class="v">{{ order.writeOffByName || '-' }}</text></view>
        <view class="info-row"><text class="k">提交时间</text><text class="v">{{ fmtTime(order.approvalSubmittedAt) }}</text></view>
      </view>

      <view v-if="isScmWriteOff && (order.entries || []).length" class="card">
        <text class="card-title">核销明细</text>
        <view v-for="(entry, index) in order.entries" :key="entry.id || index" class="product">
          <view class="product-main">
            <text class="product-name">{{ entry.materialName || entry.materialNumber }}</text>
            <text class="product-meta">
              {{ [entry.materialNumber, entry.spec,
                  entry.batchNo ? '批次 ' + entry.batchNo : ''].filter(Boolean).join(' · ') }}
              领用 {{ entry.pickQty || 0 }}
            </text>
          </view>
          <text class="product-amount">
            核销 {{ entry.writeOffQty || 0 }} / 退回 {{ entry.returnedQty || 0 }} {{ entry.unitName || '' }}
          </text>
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

      <ConstructFlowActions v-if="isBusinessOrder && actionType === 'OPS_CONSTRUCTION_RECORD'"
                            :order-id="order.orderId" :construct="order.construct"
                            @changed="onConstructChanged" />

      <view v-if="isBusinessOrder && order.refund" class="card">
        <view class="card-title">
          <text>作废退款申请</text>
          <text class="node">{{ order.refund.statusName || '' }}</text>
        </view>
        <view class="info-row"><text class="k">退款金额</text>
          <text class="v money">¥{{ money(order.refund.amount) }}</text>
        </view>
        <view class="info-row"><text class="k">退款原因</text>
          <text class="v">{{ order.refund.reason || '-' }}</text>
        </view>
        <view v-if="order.refund.remark" class="info-row"><text class="k">备注</text>
          <text class="v">{{ order.refund.remark }}</text>
        </view>
        <view v-if="order.refund.applicantName" class="info-row"><text class="k">申请人</text>
          <text class="v">{{ order.refund.applicantName }} {{ order.refund.applicantPhone || '' }}</text>
        </view>
        <view v-if="order.refund.flow && order.refund.flow.currentNodeName" class="info-row">
          <text class="k">当前节点</text>
          <text class="v">{{ order.refund.flow.currentNodeName }}</text>
        </view>
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
          关联采购单：{{ order.tradeInPurchaseBillNo }}{{ purchaseAuditLabel }}
        </text>
        <text v-if="order.tradeInAutoApproveError" class="inbound-line error">
          自动审核未通过：{{ order.tradeInAutoApproveError }}
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

      <view v-if="!taskPending" class="card">
        <text class="card-title">任务状态</text>
        <view class="no-action"><text>{{ taskStatusHint }}</text></view>
      </view>

      <view v-if="taskPending" class="card">
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
            <DispatchWorkerPicker
              v-model="form.assigneeUserId"
              :order-id="order.orderId"
              :corp-name="order.company || order.corpName"
              :role-name="form.dispatchType === 'CONSTRUCTION' ? '施工员' : ''"
              @selected="onWorkerSelected"
            />
          </view>
          <view class="field"><text class="label">期望上门 <text class="req">*</text></text>
            <view class="row2">
              <picker mode="date" :value="expectDate" @change="onExpectDate">
                <view class="picker">{{ expectDate || '日期' }}</view>
              </picker>
              <view class="period-chips">
                <view class="chip period-chip" :class="{ on: expectPeriod === '上午' }"
                      @click="choosePeriod('上午')">上午</view>
                <view class="chip period-chip" :class="{ on: expectPeriod === '下午' }"
                      @click="choosePeriod('下午')">下午</view>
              </view>
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
          <DispatchMaterialList v-if="form.dispatchType === 'CONSTRUCTION'"
                                v-model="dispatchMaterials"
                                v-model:stock-number="dispatchMaterialStockNumber" />
          <view v-if="form.dispatchType === 'VISIT'" class="field"><text class="label">随访任务说明 <text class="req">*</text></text>
            <textarea v-model="form.visitNote" class="textarea" maxlength="500" placeholder="请填写随访任务说明" />
          </view>
        </template>

        <template v-else-if="actionType === 'OPS_CONSTRUCTION_RECORD'">
          <view v-if="constructionBlockedReason" class="block-alert">
            <text>{{ constructionBlockedReason }}</text>
          </view>
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
          <view class="field"><text class="label">验收意见 <text class="req">*</text></text>
            <textarea v-model="form.acceptanceNote" class="textarea" maxlength="500"
                      placeholder="请填写验收意见（5-500字），通过/不通过均必填" />
          </view>
          <view class="field">
            <text class="label">验收凭证 <text class="req">*</text></text>
            <AcceptanceEvidence v-model="form.acceptanceEvidence" />
          </view>
        </template>

        <view v-else class="no-action"><text>当前节点为审批节点，确认信息后提交即可。</text></view>

        <view class="field"><text class="label">处理意见（可选，仅通过时记录）</text>
          <textarea v-model="opinion" class="textarea" maxlength="500" placeholder="通过时的处理意见（可选）" />
        </view>
      </view>

      <view v-if="taskPending" class="actions">
        <button v-if="showGenericReject" class="btn danger" :disabled="submitting" @click="rejectVisible = true">驳回</button>
        <button class="btn primary"
                :disabled="submitting || !!dispatchBlockedReason || !!constructionBlockedReason"
                @click="submit">{{ submitting ? '提交中...' : '提交处理' }}</button>
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
import { computed, reactive, ref, watch } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'
import { REQUISITION_STATUS, WRITE_OFF_STATUS } from '@/utils/materialForm'
import ApproverCard from '@/components/ApproverCard.vue'
import RejectPopup from '@/components/RejectPopup.vue'
import DispatchMaterialList from '@/components/DispatchMaterialList.vue'
import AcceptanceEvidence from '@/components/AcceptanceEvidence.vue'
import DispatchWorkerPicker from '@/components/DispatchWorkerPicker.vue'
import ConstructFlowActions from '@/components/ConstructFlowActions.vue'

const loading = ref(true)
const submitting = ref(false)
const missing = ref(false)
const errorMsg = ref('')
const taskId = ref('')
const instanceId = ref('')
const detail = ref(null)
const order = ref({})
const selectedWorker = ref(null)
const selectedKeys = ref([])
/** 派工出库物料清单（需求预填；为空不生成领用单） */
const dispatchMaterials = ref([])
const dispatchMaterialStockNumber = ref(null)
const expectDate = ref('')
const expectPeriod = ref('')
const opinion = ref('')
const rejectVisible = ref(false)
const categoryMap = ref({})
const approverExpanded = ref(false)

const form = reactive({
  receiptNo: '', financeReceiptDate: '', fundDynamicRows: null,
  isReceived: '是', checkConclusion: 'PASS', checkRemark: '',
  dispatchType: 'CONSTRUCTION', assigneeUserId: null, assignSource: 'SELECTED',
  expectTime: '', visitNote: '',
  constructionNote: '', completionNote: '',
  acceptanceConclusion: 'PASS', acceptanceNote: '', acceptanceEvidence: []
})

/** 验收凭证草稿：刷新/返回后恢复已上传附件，避免重复上传与孤儿文件。 */
function evidenceDraftKey() {
  return taskId.value ? 'acceptance_evidence_' + taskId.value : ''
}
function restoreEvidenceDraft() {
  const key = evidenceDraftKey()
  if (!key) return
  try {
    const list = uni.getStorageSync(key)
    if (Array.isArray(list)) form.acceptanceEvidence = list
  } catch (e) { /* ignore */ }
}
function clearEvidenceDraft() {
  const key = evidenceDraftKey()
  if (!key) return
  try { uni.removeStorageSync(key) } catch (e) { /* ignore */ }
}
watch(() => form.acceptanceEvidence, list => {
  const key = evidenceDraftKey()
  if (!key) return
  try { uni.setStorageSync(key, list || []) } catch (e) { /* ignore */ }
}, { deep: true })

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
/** SCM 供应链物料领用/核销：详情走 SCM，审批动作仍代理底座工作流 */
const isScmPick = computed(() => businessType.value === 'MATERIAL_PICK')
const isScmWriteOff = computed(() => businessType.value === 'MATERIAL_WRITE_OFF')
const isBusinessOrder = computed(() => !isPurchase.value
  && !isMaterialRequisition.value && !isMaterialWriteOff.value
  && !isScmPick.value && !isScmWriteOff.value)
/** 任务是否仍待处理：或签被他人处理后状态为 SKIPPED，不能再提交 */
const taskPending = computed(() => {
  const task = detail.value && detail.value.task
  return !task || !task.status || task.status === 'PENDING'
})
const taskStatusHint = computed(() => ({
  APPROVED: '该任务已审批通过，无需重复处理。',
  REJECTED: '该任务已驳回，无需重复处理。',
  SKIPPED: '该任务已由其他审批人处理，无需重复处理。'
}[detail.value?.task?.status] || '该任务已处理，无需重复处理。'))
/** 旧品入库完成（SCM inboundStatus=ALL）后提醒消失，改为已入库标识。 */
const tradeInInboundDone = computed(() => {
  const o = order.value || {}
  return !!o.hasTradeIn && (o.tradeInInboundStatus === 'ALL'
    || o.tradeInInboundStatusName === '旧品已入库')
})

/** 采购单审核状态文案：自动审核 / 人工已审核 / 待完善。 */
const purchaseAuditLabel = computed(() => {
  const o = order.value || {}
  if (o.tradeInAutoApproveTrigger) return '（已审核·财务核对自动）'
  if (o.tradeInAuditStatus === 'AUDITED') return '（已审核）'
  if (o.tradeInAuditStatus === 'CLOSED') return '（已关闭）'
  if (o.tradeInAutoApproveError) return '（自动审核未通过）'
  return '（待财务核对后自动审核）'
})
/**
 * 施工完成节点（施工记录/施工验收）处理页必须提示旧品入库；
 * 其余节点在业务状态到达 5 施工完成后同样提示，直到入库完成。
 */
const isConstructionStage = computed(() => {
  if (['OPS_CONSTRUCTION_RECORD', 'OPS_ACCEPTANCE'].includes(actionType.value)) return true
  const o = order.value || {}
  const match = String(o.bizStatus == null ? '' : o.bizStatus).match(/\d+/)
  if (match) return [5, 6].includes(Number(match[0]))
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
    // 优先使用底座统一生成的“角色 + 姓名”，前端不再拼接后续节点角色
    const displayText = preview.approval && preview.approval.displayText
      && (preview.approval.groups || []).length ? preview.approval.displayText : ''
    return buildApprover(preview.nodeName, names, roles, preview.approveMode,
      names.length ? '' : (preview.resolveMessage || '待分配审批人，请联系管理员'), displayText)
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
  if (isScmPick.value) return '物料领用审批'
  if (isScmWriteOff.value) return '物料核销审批'
  return {
    OPS_FINANCE_CHECK: '财务核对',
    OPS_DISPATCH_OUTBOUND: '派工出库',
    OPS_CONSTRUCTION_RECORD: '施工记录',
    OPS_ACCEPTANCE: '完工验收'
  }[actionType.value] || '节点处理'
})
/** 施工记录节点的接收/打卡/签字/开工由业务状态链承担，不再提供工作流驳回入口 */
const showGenericReject = computed(() => !['OPS_FINANCE_CHECK', 'OPS_ACCEPTANCE',
  'OPS_CONSTRUCTION_RECORD'].includes(actionType.value))
/** 施工记录必须在确认开工后提交（施工中=4，验收退回返工=5） */
const constructionBlockedReason = computed(() => {
  if (actionType.value !== 'OPS_CONSTRUCTION_RECORD') return ''
  const status = String((order.value && order.value.bizStatus) || '')
  if (['4', '5'].includes(status)) return ''
  return '请先在【施工流程】完成确认接收、上门打卡、客户签字与确认开工'
})
const dispatchProducts = computed(() => (order.value.products || [])
  .filter(p => !p.isReturnEntry && Number(p.saleQuantity || 1) > 0))
function onWorkerSelected(worker) {
  selectedWorker.value = worker || null
  form.assignSource = worker?.createdOnSite ? 'CREATED_ON_SITE' : 'SELECTED'
}

/** 施工工单只允许施工员：切换工单类型时清掉不符合角色的已选人员 */
watch(() => form.dispatchType, type => {
  if (type !== 'CONSTRUCTION' || !selectedWorker.value) return
  if (selectedWorker.value.workerRole === false) {
    selectedWorker.value = null
    form.assigneeUserId = null
  }
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
    if (isScmPick.value) {
      // 任务归属已由底座 /api/wf/task/{taskId} 按 JWT 校验（发起人或审批人）；
      // SCM 详情按单据组织可见性返回，运营商审批人可读下辖组织单据
      order.value = await api.scmMaterialPickDetail(businessId)
      return
    }
    if (isScmWriteOff.value) {
      order.value = await api.scmMaterialWriteOffDetail(businessId)
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
    // 任务处理人可能是外部施工员/外部验收人，且不一定是订单经办人；
    // 统一走按 taskId 鉴权的员工端接口，避免 /api/ops/admin 对外部用户 403。
    order.value = await api.businessTaskOrderDetail(taskId.value)
    restoreEvidenceDraft()
    const r = order.value || {}
    form.receiptNo = r.receiptNo || ''
    form.financeReceiptDate = normalizeDate(r.financeReceiptDate || r.receiptDate)
    form.fundDynamicRows = r.fundDynamicRows == null ? null : r.fundDynamicRows
    form.isReceived = r.isReceived || '是'
    if (actionType.value === 'OPS_DISPATCH_OUTBOUND') {
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

/** 施工链操作成功后刷新订单/待办：状态、按钮、施工记录提交权限同步更新 */
async function onConstructChanged(meta) {
  // 拒绝接单后工作流已退回派工，当前任务不再是待办，详情接口会 403
  if (meta && meta.rejected) {
    uni.showModal({
      title: '已拒绝接单',
      content: '订单已退回派工，请等待派工人重新指派',
      showCancel: false,
      success: goTodo
    })
    return
  }
  await loadData()
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
  return {
    NONE: '无需审批', IN_APPROVAL: '审批中', APPROVED: '已通过', REJECTED: '已驳回'
  }[v] || v || '-'
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
function onProducts(e) { selectedKeys.value = e.detail.value || [] }
function onExpectDate(e) {
  expectDate.value = e.detail.value
  syncExpect()
}
function choosePeriod(period) {
  expectPeriod.value = period
  syncExpect()
}
function syncExpect() {
  // 只精确到上午/下午，不再采集时分秒
  form.expectTime = expectDate.value && expectPeriod.value
    ? expectDate.value + ' ' + expectPeriod.value
    : ''
}

function buildFormData() {
  const data = { ...form }
  if (actionType.value === 'OPS_DISPATCH_OUTBOUND') {
    const selected = new Set(selectedKeys.value)
    data.selectedItemIds = dispatchProducts.value
      .filter(p => selected.has(p.productKey))
      .flatMap(p => p.rowIds || [])
    // 物料清单只随施工派工传递；入户随访无物料
    data.materialList = form.dispatchType === 'CONSTRUCTION'
      ? dispatchMaterials.value.map(row => ({
          materialNumber: row.materialNumber,
          qty: Number(row.qty)
        }))
      : []
    // 领用仓库随派工传入 SCM，草稿直接带入，避免员工二次选择
    data.materialStockNumber = form.dispatchType === 'CONSTRUCTION'
      ? (dispatchMaterialStockNumber.value || null) : null
  }
  if (actionType.value === 'OPS_ACCEPTANCE') {
    data.attachmentIds = (form.acceptanceEvidence || [])
      .map(file => file.id).filter(Boolean)
    data.acceptanceEvidenceUrls = (form.acceptanceEvidence || [])
      .map(file => file.url).filter(Boolean)
    delete data.acceptanceEvidence
  }
  return data
}

function validate() {
  if (dispatchBlockedReason.value) return dispatchBlockedReason.value
  if (constructionBlockedReason.value) return constructionBlockedReason.value
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
    if (form.dispatchType === 'CONSTRUCTION'
        && selectedWorker.value && selectedWorker.value.workerRole === false) {
      return '上门员工必须是施工员角色'
    }
    if (!form.expectTime) return '请选择期望上门日期和上午/下午'
    if (form.dispatchType === 'VISIT' && !String(form.visitNote || '').trim()) return '请填写随访任务说明'
    if (form.dispatchType === 'CONSTRUCTION') {
      if (dispatchMaterials.value.length && !dispatchMaterialStockNumber.value) {
        return '请选择物料领用仓库'
      }
      const invalid = dispatchMaterials.value.find(row =>
        !row.materialNumber || !(Number(row.qty) > 0))
      if (invalid) return '请填写完整的物料清单（商品与数量）'
      const over = dispatchMaterials.value.find(row =>
        row.available !== null && row.available !== undefined
        && Number(row.qty) > Number(row.available))
      if (over) {
        return `物料 ${over.name || over.materialNumber} 数量不能大于库存 ${over.available}`
      }
    }
  }
  if (actionType.value === 'OPS_ACCEPTANCE') {
    if (!form.acceptanceConclusion) return '请选择验收结论'
    if (String(form.acceptanceNote || '').trim().length < 5) {
      return '验收意见不少于5个字'
    }
    if (!(form.acceptanceEvidence || []).length) {
      return '请至少上传 1 张图片或 1 段视频作为验收凭证'
    }
  }
  return ''
}

async function submit() {
  if (!taskPending.value) return toast(taskStatusHint.value)
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
      const result = await api.workflowReject({
        taskId: Number(taskId.value),
        opinion: String(form.checkRemark || '财务核对不通过').trim(),
        formData
      })
      showSubmitResult(result, '已退回经办人')
      return
    }
    if (actionType.value === 'OPS_ACCEPTANCE' && form.acceptanceConclusion === 'REJECT') {
      const result = await api.workflowReject({
        taskId: Number(taskId.value),
        opinion: String(form.acceptanceNote || '验收不通过退回施工完成').trim(),
        formData
      })
      clearEvidenceDraft()
      showSubmitResult(result, '已退回施工完成')
      return
    }
    const result = await api.workflowApprove({
      taskId: Number(taskId.value),
      // 完工验收的验收意见就是本次处理意见，写入 wf_task/wf_log 供详情与审计回看
      opinion: actionType.value === 'OPS_ACCEPTANCE'
        ? String(form.acceptanceNote || '').trim()
        : opinion.value,
      formData
    })
    if (actionType.value === 'OPS_ACCEPTANCE') clearEvidenceDraft()
    // 派工出库带物料清单时，SCM 已同事务生成领用草稿：按派工记录（=当前 taskId）查回单号提示
    if (actionType.value === 'OPS_DISPATCH_OUTBOUND'
        && form.dispatchType === 'CONSTRUCTION' && dispatchMaterials.value.length) {
      const pick = await api.scmMaterialPickByDispatch(Number(taskId.value)).catch(() => null)
      if (pick && pick.billNo) {
        showDispatchMaterialResult(pick)
        return
      }
    }
    if (actionType.value === 'OPS_DISPATCH_OUTBOUND'
        && selectedWorker.value
        && selectedWorker.value.wechatBound === false) {
      toast('该施工员未绑定微信，可能无法接收任务通知')
    }
    showSubmitResult(result, '处理成功，流程已推进')
  } catch (e) {
    toast((e && e.msg) || '处理失败，请重试')
  } finally {
    submitting.value = false
  }
}

/** 提交结果：显示实际下一审批人；取不到时展示兜底文案，不静默 */
/** 派工成功 + 已生成物料领用草稿：引导员工补充仓库/批次并提交。 */
function showDispatchMaterialResult(pick) {
  uni.showModal({
    title: '派工出库成功',
    content: `已生成物料领用单 ${pick.billNo}，请前往【常用功能 → 物料领用】补充仓库、批次并提交审批`,
    confirmText: '去处理',
    cancelText: '稍后',
    success: (res) => {
      if (res.confirm) {
        uni.navigateTo({ url: '/pages/employee/material-pick-drafts' })
      } else {
        goTodo()
      }
    }
  })
}

function showSubmitResult(result, fallback) {
  const info = normalizeResult(result)
  if (showTerminalResult(info)) return
  if (info.displayText) {
    showActiveResult(info, fallback)
    return
  }
  if (info.message) {
    uni.showModal({ title: '处理成功', content: info.message, showCancel: false, success: goTodo })
    return
  }
  toast(fallback)
  goTodo()
}

function showTerminalResult(info) {
  if (info.status === 'COMPLETED') {
    const cc = info.ccNames.length ? `；抄送：${info.ccNames.join('、')}` : ''
    uni.showModal({
      title: '流程已完成',
      content: `流程已完成${cc}`,
      showCancel: false,
      success: goTodo
    })
    return true
  }
  if (info.status === 'REJECTED') {
    uni.showModal({
      title: '已驳回',
      content: `退回至：${info.displayText || info.message || '已退回'}`,
      showCancel: false,
      success: goTodo
    })
    return true
  }
  if (info.status === 'TERMINATED') {
    uni.showModal({
      title: '流程已终止',
      content: '流程已终止，无需继续审批',
      showCancel: false,
      success: goTodo
    })
    return true
  }
  return false
}

function showActiveResult(info, fallback) {
  const waiting = info.status === 'WAITING_SIBLINGS'
  uni.showModal({
    title: waiting ? '已提交，等待会签' : (fallback || '处理成功'),
    content: waiting ? info.displayText : `已同意，当前审批：${info.displayText}`,
    showCancel: false,
    success: goTodo
  })
}

/**
 * 兼容底座 FlowResult、SCM NextApproverInfo 两种返回结构。
 * 优先使用后端 displayText；旧响应才按“当前/下一节点”的审批人分组兜底，
 * 绝不遍历后续节点。
 */
function normalizeResult(result) {
  const ccNames = ((result && result.ccUsers) || [])
    .map(a => a.userName || '')
    .filter(Boolean)
  if (!result) return emptyResult(ccNames)

  const approval = result.currentApproval
    || (result.nextNodePreview && result.nextNodePreview.approval)
    || null
  if (approval && approval.displayText && (approval.groups || []).length) {
    return fromApproval(result, approval, ccNames)
  }
  // SCM NextApproverInfo：displayText 已由服务端生成（只含当前/下一节点）
  if (result.displayText) {
    return fromScmDisplay(result, ccNames)
  }
  // 旧版 SCM：只取第一个有审批人的节点
  if (Array.isArray(result.nodes)) {
    return fromScmNodes(result, ccNames)
  }
  // 旧版底座 FlowResult：会签未完成优先当前节点剩余待办，否则下一节点
  return fromLegacyFlow(result, ccNames)
}

function emptyResult(ccNames) {
  return { status: '', displayText: '', names: [], roles: [], nodeName: '', message: '', ccNames }
}

function fromApproval(result, approval, ccNames) {
  const groups = approval.groups || []
  return {
    status: result.status || '',
    displayText: approval.displayText,
    names: groups.flatMap(g => (g.approvers || []).map(a => a.userName)).filter(Boolean),
    roles: groups.map(g => g.roleName).filter(Boolean),
    nodeName: approval.nodeName || '',
    message: approval.resolveMessage || result.resolveMessage || '',
    ccNames
  }
}

function fromScmDisplay(result, ccNames) {
  const node = (result.nodes || []).find(n => (n.approvers || []).length)
  const approvers = (node && node.approvers) || []
  return {
    status: '',
    displayText: result.displayText,
    names: [...new Set(approvers.map(a => a.userName).filter(Boolean))],
    roles: [...new Set(approvers.map(a => a.roleName).filter(Boolean))],
    nodeName: (node && node.nodeName) || '',
    message: result.fallbackMessage || '',
    ccNames
  }
}

function fromScmNodes(result, ccNames) {
  const node = result.nodes.find(n => (n.approvers || []).length)
  const approvers = (node && node.approvers) || []
  return {
    status: '',
    displayText: displayFromApprovers(approvers, node && node.rule),
    names: [...new Set(approvers.map(a => a.userName).filter(Boolean))],
    roles: [...new Set(approvers.map(a => a.roleName).filter(Boolean))],
    nodeName: (node && node.nodeName) || '',
    message: result.fallbackMessage || '',
    ccNames
  }
}

function fromLegacyFlow(result, ccNames) {
  const pending = result.pendingApprovers || []
  const source = pending.length ? pending : (result.nextApprovers || [])
  const names = [...new Set(source.map(a => a.userName).filter(Boolean))]
  const roles = [...new Set(source.map(a => a.roleName).filter(Boolean))]
  const nodeName = pending.length
    ? (result.currentNode?.nodeName || '')
    : (result.nextNodeName || '')
  const remaining = Math.max(0, (result.totalApprovers || 0) - (result.approvedCount || 0))
  const displayText = result.status === 'WAITING_SIBLINGS'
    ? `本节点还需 ${remaining || source.length} 人审批：${names.join('、')}`
    : displayFromApprovers(source, result.approverMode)
  return {
    status: result.status || '', displayText, names, roles, nodeName,
    message: result.resolveMessage || '', ccNames
  }
}

/** 旧响应兜底：按角色分组，仅用于当前节点/下一节点，不跨节点聚合。 */
function displayFromApprovers(approvers, mode) {
  const groups = new Map()
  ;(approvers || []).forEach(a => {
    if (!a || !a.userName) return
    const role = a.roleName || '审批人'
    if (!groups.has(role)) groups.set(role, [])
    if (!groups.get(role).includes(a.userName)) groups.get(role).push(a.userName)
  })
  const modeText = mode === 'ALL' ? '，会签' : mode === 'ANY' ? '，或签' : ''
  return [...groups.entries()].map(([role, names]) => {
    const folded = names.length > 3
      ? `${names.slice(0, 2).join('、')}等 ${names.length} 人`
      : names.join('、')
    return `${role}（${folded}${names.length > 1 ? modeText : ''}）`
  }).join('、')
}

function buildApprover(nodeName, names, roles, rule, message, displayText) {
  const collapsed = names.length > 2 && !approverExpanded.value
  const shown = collapsed ? names.slice(0, 1) : names
  const namesText = names.length
    ? shown.map(shortName).join('、') + (collapsed ? ` 等 ${names.length} 人` : '')
    : ''
  const roleText = (roles || []).join('、')
  // 折叠时用后端统一文案（含“等 N 人”）；展开后展示完整姓名。
  const expandedFull = displayText && names.length > 2 && !collapsed
  const fallback = names.length
    ? (roleText ? `${roleText}：${namesText}` : namesText)
    : ''
  const display = expandedFull ? fallback : (displayText || fallback)
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

function pickReasonLabel(value) {
  return { DISPATCH: '派工施工', ACTIVITY: '活动物料', OTHER: '其它事宜' }[value] || value || '-'
}

function materialReqStatus(value) {
  return REQUISITION_STATUS[value] || value || '-'
}

function materialWriteOffStatus(value) {
  return WRITE_OFF_STATUS[value] || value || '-'
}

async function confirmReject(opinionText) {
  if (submitting.value) return
  if (!taskPending.value) {
    rejectVisible.value = false
    return toast(taskStatusHint.value)
  }
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
.textarea {
  /* App.vue 全局 uni-textarea{min-height:auto!important} 会把宿主高度压塌，
     内层 textarea 绝对定位高度随之为 0，导致无法聚焦录入；按其他页面同口径显式覆盖。 */
  height: 150rpx !important;
  min-height: 150rpx !important;
}
.picker { display: flex; align-items: center; }
.row2 { display: flex; gap: 16rpx; }
.row2 > * { flex: 1; }
.period-chips { display: flex; gap: 12rpx; }
.period-chip { flex: 1; text-align: center; padding-left: 0; padding-right: 0; }
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
