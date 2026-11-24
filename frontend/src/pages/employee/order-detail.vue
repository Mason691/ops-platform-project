<template>
  <view class="page">
    <EmployeePageHead title="订单详情" />
    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-error">{{ errorMsg }}</text>
      <view class="retry" @click="load"><text>重试</text></view>
    </view>

    <template v-else-if="order">
      <view v-if="showInboundReminder" class="inbound-alert">
        <text class="inbound-title">⚠️ 本订单含以旧换新旧品，请及时办理旧品入库。</text>
        <text v-if="order.tradeInSummary" class="inbound-line">旧品：{{ order.tradeInSummary }}</text>
        <text v-if="order.tradeInPurchaseBillNo" class="inbound-line">
          关联采购单：{{ order.tradeInPurchaseBillNo }}{{ purchaseAuditLabel }}
        </text>
        <text v-if="order.tradeInAutoApproveError" class="inbound-line error">
          自动审核未通过：{{ order.tradeInAutoApproveError }}
        </text>
        <text v-if="order.tradeInPurchaseSyncStatus === 'PUSH_FAILED'" class="inbound-line error">
          关联采购单推送金蝶失败：{{ order.tradeInPurchasePushError || '请在供应链 PC 端重试推送' }}
        </text>
        <text class="inbound-line">入库状态：{{ order.tradeInInboundStatusName || '旧品未入库' }}</text>
        <text class="inbound-hint">请在供应链管理 PC 端打开该采购订单办理旧品入库</text>
      </view>
      <view v-else-if="tradeInInboundDone && atConstructionStage" class="inbound-done">
        <text class="inbound-done-title">✓ 旧品已入库</text>
        <text v-if="order.tradeInPurchaseBillNo" class="inbound-done-sub">
          关联采购单 {{ order.tradeInPurchaseBillNo }}
        </text>
      </view>

      <view class="card">
        <view class="head-line">
          <text class="order-no">{{ order.orderNo || '未编号订单' }}</text>
          <text class="status" :class="{ void: String(order.bizStatus) === '-1', draft: order.draftFlag }">
            {{ order.draftFlag ? '草稿 · 待重新提交' : (order.bizStatusName || '状态未知') }}
          </text>
          <text v-if="order.refund && order.refund.status === 'PENDING'"
                class="status refunding">作废审批中</text>
        </view>
        <view class="kv"><text class="k">创建时间</text><text class="v">{{ formatTime(order.createTime) || '—' }}</text></view>
        <view class="kv"><text class="k">客户</text><text class="v">{{ order.customer || '—' }}</text></view>
        <view class="kv"><text class="k">小区/地址</text>
          <text class="v">{{ [order.communityName, order.detailedAddress].filter(Boolean).join(' ') || '—' }}</text>
        </view>
        <view class="kv"><text class="k">批次</text><text class="v">{{ order.batchNo || '—' }}</text></view>
        <view class="kv"><text class="k">市场收款日期</text><text class="v">{{ order.receiptDate || '—' }}</text></view>
        <view class="kv"><text class="k">收据号</text><text class="v">{{ order.receiptNo || '—' }}</text></view>
        <view class="kv"><text class="k">经办人</text><text class="v">{{ order.agentNames || '—' }}</text></view>
        <view class="kv">
          <text class="k">{{ order.hasTradeIn ? '销售金额' : '合计金额' }}</text>
          <text class="v amount">¥{{ money(order.totalAmount) }}</text>
        </view>
        <view v-if="order.hasTradeIn" class="kv">
          <text class="k">抵扣金额</text>
          <text class="v deduction">-¥{{ money(order.tradeInDeductionAmount) }}</text>
        </view>
        <view v-if="order.hasTradeIn" class="kv">
          <text class="k">应付合计</text>
          <text class="v amount">¥{{ money(order.payableAmount) }}</text>
        </view>
        <view class="kv"><text class="k">提成合计</text><text class="v commission">¥{{ money(order.totalCommission) }}</text></view>
      </view>

      <view v-if="unavailableReason" class="card action-card">
        <text class="action-hint">{{ unavailableReason }}</text>
      </view>

      <view v-if="showActions" class="card action-card">
        <text class="card-title">订单操作</text>
        <view class="action-row">
          <view v-if="order.canEdit" class="action-btn primary" @click="editDraft">编辑订单</view>
          <view v-if="order.canReturnDraft" class="action-btn ghost" @click="returnDraft">退回草稿</view>
          <view v-if="order.canApplyRefund" class="action-btn danger" @click="openRefund">发起退款申请</view>
          <view v-if="order.canRestartFlow" class="action-btn primary" @click="restartFlow">重新提交审批</view>
        </view>
        <text v-if="order.draftFlag" class="action-hint">
          当前为草稿状态，修改后提交将沿用原订单编号重新进入审批。
        </text>
        <text v-else class="action-hint">
          仅在派工出库前可操作；已出库、已上报集团或已作废订单不可操作。
        </text>
      </view>

      <view v-if="order.refund" class="card refund-card">
        <view class="refund-head">
          <text class="card-title">作废退款</text>
          <text class="refund-status" :class="String(order.refund.status || '').toLowerCase()">
            {{ order.refund.statusName || '—' }}
          </text>
        </view>
        <view class="kv"><text class="k">退款金额</text>
          <text class="v amount">¥{{ money(order.refund.amount) }}</text>
        </view>
        <view class="kv"><text class="k">退款原因</text>
          <text class="v">{{ order.refund.reason || '—' }}</text>
        </view>
        <view v-if="order.refund.remark" class="kv"><text class="k">备注</text>
          <text class="v">{{ order.refund.remark }}</text>
        </view>
        <view v-if="order.refund.rejectReason" class="kv"><text class="k">驳回/终止原因</text>
          <text class="v danger">{{ order.refund.rejectReason }}</text>
        </view>
        <view v-if="order.refund.flow" class="refund-flow">
          <text class="refund-flow-line">
            当前节点：{{ order.refund.flow.currentNodeName || order.refund.currentNodeName || '—' }}
          </text>
          <text v-if="refundApproverText && order.refund.status === 'PENDING'"
                class="refund-flow-line">
            处理人：{{ refundApproverText }}
          </text>
        </view>
        <view v-if="(order.refund.attachmentUrls || []).length" class="refund-files">
          <text v-for="(url, index) in order.refund.attachmentUrls" :key="index"
                class="refund-file" @click="previewRefundFile(url)">
            附件{{ index + 1 }}
          </text>
        </view>
      </view>

      <view v-if="order.hasTradeIn" class="card">
        <view class="trade-head">
          <text class="card-title">以旧换新</text>
          <text class="trade-status">{{ order.tradeInInboundStatusName || '旧品待入库' }}</text>
        </view>
        <view class="kv"><text class="k">抵扣金额</text><text class="v deduction">-¥{{ money(order.tradeInDeductionAmount) }}</text></view>
        <view class="kv"><text class="k">旧品汇总</text><text class="v">{{ order.tradeInSummary || '—' }}</text></view>
        <view class="kv">
          <text class="k">采购订单</text>
          <text class="v">{{ purchaseOrderText }}</text>
        </view>
        <view v-if="showPurchaseRetry" class="trade-error">
          <text class="trade-error-text">
            {{ order.tradeInPurchaseError
              ? '采购订单生成失败：' + order.tradeInPurchaseError
              : '采购订单尚未生成完成，可重试（重复提交不会重复创建）。' }}
          </text>
          <view class="trade-retry" :class="{ disabled: retrying }" @click="retryPurchase">
            <text>{{ retrying ? '重试中...' : '重试生成' }}</text>
          </view>
        </view>
      </view>

      <view class="card">
        <text class="card-title">商品明细</text>
        <view v-for="(p, i) in (order.products || [])" :key="p.productKey || i"
              class="product-row" :class="{ ret: p.isReturnEntry }">
          <view class="product-info">
            <text class="product-name">{{ [p.brand, p.model].filter(Boolean).join(' ') }}</text>
            <text class="product-meta">
              {{ p.categoryName || p.category || '—' }} · ¥{{ money(p.salePrice) }} × {{ p.saleQuantity }}
            </text>
            <text v-if="p.isSpecial === '是'" class="product-special">特批 · 提成 {{ p.commissionRate }}%</text>
            <view v-if="p.isTradeIn === '是'" class="product-trade">
              <text class="trade-tag">以旧换新</text>
              <text>抵扣 -¥{{ money(p.deductionAmount) }} · 应付 ¥{{ money(p.payableAmount) }}</text>
            </view>
            <text v-if="p.isTradeIn === '是' && (p.tradeInItems || []).length" class="product-old">
              旧品：{{ tradeInItemsText(p.tradeInItems) }}
            </text>
          </view>
          <view class="product-money">
            <text class="product-amount">¥{{ money(p.receiptAmount) }}</text>
            <text class="product-commission">提成 ¥{{ money(p.commissionAmount) }}</text>
          </view>
        </view>
        <text v-if="!(order.products || []).length" class="empty">暂无商品明细</text>
      </view>

      <view v-if="attachments.length" class="card">
        <text class="card-title">订单附件</text>
        <view class="attach-list">
          <view v-for="(file, i) in attachments" :key="file.id || file.fileUrl || i"
                class="attach-item" @click="openAttachment(file)">
            <image v-if="isImageFile(file)" class="attach-img" :src="assetUrl(file.fileUrl)"
                   mode="aspectFill" />
            <view v-else class="attach-file">
              <text>{{ isVideoFile(file) ? '🎬' : '📎' }}</text>
            </view>
            <text class="attach-name">
              {{ file.fileType === 'ACCEPTANCE' ? '验收凭证' : (file.fileName || ('附件' + (i + 1))) }}
            </text>
          </view>
        </view>
      </view>

      <view class="card">
        <BusinessFlowProgress :flow="order.flow || {}" :status-options="order.bizStatusOptions || []"
                              :biz-status="order.bizStatus" :biz-status-name="order.bizStatusName" />
      </view>

      <view v-if="construct && (construct.logs || []).length" class="card">
        <text class="card-title">施工流转记录</text>
        <ConstructTimeline :logs="construct.logs" />
        <view v-if="construct.notice" class="notice-block">
          <text class="notice-title">
            用户告知书 · V{{ construct.notice.version }}
            <text class="notice-time">{{ construct.notice.signTime }}</text>
          </text>
          <text class="notice-content">{{ construct.notice.content }}</text>
          <image v-if="construct.notice.signImageUrl" :src="assetUrl(construct.notice.signImageUrl)"
                 class="notice-sign" mode="aspectFit" @click="previewNotice" />
          <view class="notice-foot">
            <text class="notice-hint">点击签字图片可放大查看（SHA-256 已归档）</text>
            <text class="notice-verify" @click="verifyNotice">校验凭证</text>
          </view>
        </view>
      </view>

      <view v-if="flowRecords.length" class="card">
        <text class="card-title">流程处理记录</text>
        <view v-for="(record, i) in flowRecords" :key="record.id || i" class="record">
          <view class="record-dot" />
          <view class="record-body">
            <view class="record-line1">
              <text class="record-node">{{ record.nodeName || '—' }}</text>
              <text class="record-action">{{ actionLabel(record.actionType) }}</text>
              <text class="record-time">{{ formatTime(record.createTime) }}</text>
            </view>
            <text class="record-operator">{{ record.operatorName || '—' }}</text>
            <text v-if="record.opinion" class="record-opinion">{{ record.opinion }}</text>
          </view>
        </view>
      </view>

      <BusinessRefundSheet v-model:visible="refundVisible" :order="order"
                           :order-id="orderId" @submitted="load" />

      <view v-if="videoVisible" class="video-mask" @click="videoVisible = false">
        <video class="video-player" :src="videoUrl" controls autoplay @click.stop />
        <text class="video-close" @click.stop="videoVisible = false">×</text>
      </view>
    </template>
  </view>
</template>

<script setup>
import EmployeePageHead from '@/components/EmployeePageHead.vue'
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api'
import BusinessFlowProgress from '@/components/BusinessFlowProgress.vue'
import BusinessRefundSheet from '@/components/BusinessRefundSheet.vue'
import ConstructTimeline from '@/components/ConstructTimeline.vue'
import { assetUrl } from '@/utils/image'

/**
 * 员工端业务订单详情：本人参与的商品明细 + 流程进度 + 节点处理留痕。
 * 接口按 当前组织 + 当前经办人 校验，非本人参与订单返回无权查看。
 */
const orderId = ref('')
const loading = ref(true)
const errorMsg = ref('')
const order = ref(null)
const videoVisible = ref(false)
const videoUrl = ref('')
const retrying = ref(false)
const refundVisible = ref(false)

onLoad(options => {
  orderId.value = options && options.orderId ? decodeURIComponent(options.orderId) : ''
  if (!orderId.value) {
    loading.value = false
    errorMsg.value = '缺少订单ID'
    return
  }
  load()
})

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    order.value = await api.employeeOrderDetail(orderId.value)
  } catch (e) {
    errorMsg.value = (e && e.msg) || '订单加载失败，请重试'
  } finally {
    loading.value = false
  }
}

const construct = computed(() => (order.value && order.value.construct) || null)

const attachments = computed(() => {
  const list = (order.value && order.value.attachments) || []
  return list.filter(file => file && (file.fileUrl || file.url))
})

function previewNotice() {
  const url = construct.value && construct.value.notice
    ? construct.value.notice.signImageUrl : ''
  if (url) uni.previewImage({ urls: [url] })
}

async function verifyNotice() {
  try {
    const result = await api.constructVerifyNotice(orderId.value)
    uni.showModal({
      title: result && result.consistent ? '凭证校验通过' : '凭证校验不一致',
      content: result && result.consistent
        ? `告知书 V${result.version} 签字图片与归档哈希一致，未被篡改。`
        : '签字图片哈希与归档记录不一致，请联系管理员核查。',
      showCancel: false
    })
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '校验失败，请稍后重试', icon: 'none' })
  }
}

function isImageFile(file) {
  return String(file.contentType || '').startsWith('image/')
    || /\.(jpe?g|png)(\?|$)/i.test(file.fileUrl || file.url || '')
}

function isVideoFile(file) {
  return String(file.contentType || '').startsWith('video/')
    || /\.(mp4|mov)(\?|$)/i.test(file.fileUrl || file.url || '')
}

function openAttachment(file) {
  const url = assetUrl(file.fileUrl || file.url)
  if (!url) return
  if (isImageFile(file)) {
    const urls = attachments.value.filter(isImageFile)
      .map(item => assetUrl(item.fileUrl || item.url))
    uni.previewImage({ urls, current: url })
    return
  }
  if (isVideoFile(file)) {
    videoUrl.value = url
    videoVisible.value = true
    return
  }
  // #ifdef H5
  window.open(url, '_blank')
  // #endif
  // #ifndef H5
  uni.downloadFile({
    url,
    success: res => {
      if (res.statusCode === 200 && res.tempFilePath) {
        uni.openDocument({
          filePath: res.tempFilePath,
          showMenu: true,
          fail: () => uni.showToast({ title: '附件打开失败', icon: 'none' })
        })
      } else {
        uni.showToast({ title: '附件下载失败', icon: 'none' })
      }
    },
    fail: () => uni.showToast({ title: '附件下载失败', icon: 'none' })
  })
  // #endif
}

const flowRecords = computed(() => {
  const list = order.value && order.value.flowRecords
  return Array.isArray(list) ? list : []
})

const showActions = computed(() => {
  const o = order.value || {}
  return !!(o.canEdit || o.canReturnDraft || o.canApplyRefund || o.canRestartFlow)
})

/**
 * 需求六.1：操作不可用时需说明原因。操作卡只在可操作时展示，
 * 已到派工出库及之后、作废、审批中的订单在这里给出只读原因。
 */
const unavailableReason = computed(() => {
  const o = order.value || {}
  if (o.refund && o.refund.status === 'PENDING') {
    return '作废退款审批中，暂不可退回草稿或重复发起退款'
  }
  if (String(o.bizStatus) === '-1') return '订单已作废退款，不可再操作'
  if (o.draftFlag) return '' // 草稿态可由 canEdit 展示操作卡
  const status = Number(o.bizStatus)
  if (Number.isFinite(status) && status >= 3) {
    return '业务状态已到派工出库及之后，不可退回草稿或发起退款'
  }
  // 新施工状态链是字符串 key（WAIT_ACCEPT/ACCEPTED/ARRIVED/客户确认等）
  if (!Number.isFinite(status) && String(o.bizStatus || '').trim() !== '') {
    return '业务状态已到施工阶段，不可退回草稿或发起退款'
  }
  return ''
})

const refundApproverText = computed(() => {
  const flow = (order.value && order.value.refund && order.value.refund.flow) || {}
  const approval = flow.currentApproval
  const display = approval && (approval.groups || []).length && approval.displayText
  if (display) return display
  const list = flow.currentApprovers || []
  // 旧响应兜底：仅当前节点，按角色分组
  const groups = new Map()
  list.forEach(item => {
    const name = item.userName || item.approverName
    if (!name) return
    const role = item.roleName || '审批人'
    if (!groups.has(role)) groups.set(role, [])
    groups.get(role).push(name)
  })
  return [...groups.entries()].map(([role, names]) => `${role}（${names.join('、')}）`).join('、')
})

function editDraft() {
  uni.navigateTo({
    url: '/pages/employee/business-data-new?orderId=' + encodeURIComponent(orderId.value)
  })
}

function returnDraft() {
  uni.showModal({
    title: '退回草稿',
    content: '退回后订单将回到草稿状态，需重新提交审批',
    confirmText: '确认退回',
    success: async modal => {
      if (!modal.confirm) return
      try {
        await api.employeeReturnDraft(orderId.value)
        uni.showToast({ title: '已退回草稿', icon: 'success' })
        load()
      } catch (e) {
        uni.showToast({ title: (e && e.msg) || '退回失败，请重试', icon: 'none' })
      }
    }
  })
}

function openRefund() {
  refundVisible.value = true
}

function restartFlow() {
  uni.showModal({
    title: '重新提交审批',
    content: '确认重新提交后进入部长审核？',
    success: async modal => {
      if (!modal.confirm) return
      try {
        await api.employeeRestartFlow(orderId.value)
        uni.showToast({ title: '已重新提交', icon: 'success' })
        load()
      } catch (e) {
        uni.showToast({ title: (e && e.msg) || '提交失败，请重试', icon: 'none' })
      }
    }
  })
}

function previewRefundFile(url) {
  if (!url) return
  const full = assetUrl(url)
  if (/\.pdf(\?|$)/i.test(String(url))) {
    // #ifdef H5
    if (typeof window !== 'undefined') window.open(full, '_blank')
    // #endif
    // #ifndef H5
    uni.downloadFile({
      url: full,
      success: res => {
        if (res.statusCode === 200) {
          uni.openDocument({ filePath: res.tempFilePath, fileType: 'pdf', showMenu: true })
        } else {
          uni.showToast({ title: '文件下载失败', icon: 'none' })
        }
      },
      fail: () => uni.showToast({ title: '文件下载失败', icon: 'none' })
    })
    // #endif
    return
  }
  const urls = (order.value && order.value.refund && order.value.refund.attachmentUrls
    ? order.value.refund.attachmentUrls : []).map(assetUrl)
  uni.previewImage({ urls, current: full })
}

/** 旧品入库完成（SCM inboundStatus=ALL）后提醒消失，改为已入库标识。 */
const tradeInInboundDone = computed(() => {
  const o = order.value || {}
  return o.hasTradeIn && (o.tradeInInboundStatus === 'ALL'
    || o.tradeInInboundStatusName === '旧品已入库')
})

/**
 * 施工完成（业务状态 5）起持续提示旧品入库，直到入库完成；
 * 字典 key 非数字时退回状态名称判断。
 */
const atConstructionStage = computed(() => {
  const o = order.value || {}
  if (!o.hasTradeIn) return false
  const match = String(o.bizStatus == null ? '' : o.bizStatus).match(/\d+/)
  if (match) return [5, 6].includes(Number(match[0]))
  return /施工完成|验收|完成/.test(String(o.bizStatusName || ''))
})
const showInboundReminder = computed(() =>
  atConstructionStage.value && !tradeInInboundDone.value)

/** 采购单审核状态文案：自动审核 / 人工已审核 / 待完善，避免误导为「未审核」。 */
const purchaseAuditLabel = computed(() => {
  const o = order.value || {}
  if (o.tradeInAutoApproveTrigger) return '（已审核·财务核对自动）'
  if (o.tradeInAuditStatus === 'AUDITED') return '（已审核）'
  if (o.tradeInAuditStatus === 'CLOSED') return '（已关闭）'
  if (o.tradeInAutoApproveError) return '（自动审核未通过）'
  return '（待财务核对后自动审核）'
})

const purchaseOrderText = computed(() => {
  const o = order.value || {}
  const status = o.tradeInPurchaseStatus
  if (status === 'SUCCESS') {
    return o.tradeInPurchaseBillNo
      ? `${o.tradeInPurchaseBillNo}${purchaseAuditLabel.value} · ${o.tradeInInboundStatusName || '旧品未入库'}`
      : '已生成（单号待同步）'
  }
  if (status === 'PENDING') return '生成中'
  if (status === 'FAILED') return '生成失败'
  return '—'
})

/** 采购单未成功生成（含 PENDING 残留/历史空状态）时提供幂等重试入口。 */
const showPurchaseRetry = computed(() => {
  const o = order.value || {}
  if (!o.hasTradeIn || o.tradeInPurchaseStatus === 'SUCCESS') return false
  return ['FAILED', 'PENDING'].includes(o.tradeInPurchaseStatus) || !o.tradeInPurchaseStatus
})

function tradeInItemsText(items) {
  return (items || [])
    .map(item => `${item.productName || item.brand || '旧品'} ×${item.quantity || 0}`)
    .join('、')
}

async function retryPurchase() {
  if (retrying.value) return
  retrying.value = true
  try {
    const result = await api.employeeRetryTradeInPurchase(orderId.value)
    if (result && result.status === 'SUCCESS') {
      order.value.tradeInPurchaseStatus = 'SUCCESS'
      order.value.tradeInPurchaseOrderId = result.purchaseOrderId
      order.value.tradeInPurchaseBillNo = result.billNo
      order.value.tradeInPurchaseError = ''
      uni.showToast({ title: '采购订单已生成', icon: 'success' })
      // 重新拉取详情：SCM 会实时补充审核状态/自动审核原因/推送状态
      await load()
    } else {
      order.value.tradeInPurchaseStatus = 'FAILED'
      order.value.tradeInPurchaseError = (result && result.error) || '生成失败'
      uni.showToast({
        title: order.value.tradeInPurchaseError, icon: 'none', duration: 3000
      })
    }
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '重试失败，请稍后再试', icon: 'none' })
  } finally {
    retrying.value = false
  }
}

function actionLabel(type) {
  return {
    OPS_FINANCE_CHECK: '财务核对', OPS_DISPATCH_OUTBOUND: '派工出库',
    OPS_CONSTRUCTION_RECORD: '施工记录', OPS_ACCEPTANCE: '完工验收',
    OPS_AFTER_SALES: '售后维护（历史）', MIGRATE_STATUS: '历史状态迁移'
  }[type] || type || '处理'
}

function money(value) {
  return (Number(value) || 0).toFixed(2)
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 20rpx 24rpx 40rpx; box-sizing: border-box; }
.card {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  display: flex; flex-direction: column; gap: 10rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .03); margin-bottom: 16rpx;
}
.head-line { display: flex; align-items: center; gap: 12rpx; flex-wrap: wrap; }
.order-no { font-size: 30rpx; font-weight: 700; color: #1D2129; }
.status { font-size: 20rpx; padding: 2rpx 12rpx; border-radius: 6rpx; background: #E8F3FF; color: #2F6FEB; }
.status.void { background: #FFF1F0; color: #C62828; }
.status.draft { background: #F2F3F5; color: #86909C; }
.status.refunding { background: #FFF7E6; color: #B26A00; }
.kv { display: flex; align-items: flex-start; gap: 16rpx; font-size: 23rpx; line-height: 1.5; }
.kv .k { flex: 0 0 168rpx; color: #86909C; }
.kv .v { flex: 1; min-width: 0; color: #1D2129; word-break: break-all; }
.kv .v.amount { color: #FF6B35; font-weight: 700; }
.kv .v.commission { color: #0B7A42; font-weight: 600; }
.kv .v.deduction { color: #C62828; font-weight: 700; }
.kv .v.danger { color: #C62828; }
.inbound-alert {
  background: #FFF7E6; border: 2rpx solid #FFB74D; border-radius: 16rpx;
  padding: 22rpx 24rpx; margin-bottom: 16rpx;
  display: flex; flex-direction: column; gap: 8rpx;
}
.inbound-title { font-size: 26rpx; font-weight: 700; color: #B26A00; line-height: 1.5; }
.inbound-line { font-size: 23rpx; color: #8C5A00; line-height: 1.5; word-break: break-all; }
.inbound-line.error { color: #D93025; }
.inbound-hint { font-size: 21rpx; color: #A9812F; margin-top: 2rpx; }
.inbound-done {
  background: #F0FBF4; border: 2rpx solid #BFE9CF; border-radius: 16rpx;
  padding: 20rpx 24rpx; margin-bottom: 16rpx;
  display: flex; align-items: center; justify-content: space-between; gap: 12rpx;
}
.inbound-done-title { font-size: 25rpx; font-weight: 700; color: #0B7A42; }
.inbound-done-sub { font-size: 21rpx; color: #3D8B62; }
.trade-head { display: flex; align-items: center; justify-content: space-between; }
.trade-status {
  font-size: 20rpx; padding: 2rpx 12rpx; border-radius: 6rpx;
  background: #FFF7E6; color: #B26A00;
}
.trade-error {
  margin-top: 6rpx; padding: 16rpx 18rpx; border-radius: 10rpx;
  background: #FFF1F0; display: flex; flex-direction: column; gap: 12rpx;
}
.trade-error-text { font-size: 21rpx; color: #C62828; line-height: 1.5; word-break: break-all; }
.trade-retry {
  align-self: flex-start; padding: 8rpx 28rpx; border-radius: 24rpx;
  background: #FF6B35; color: #fff; font-size: 22rpx;
}
.trade-retry.disabled { opacity: .55; }
.product-trade { display: flex; align-items: center; flex-wrap: wrap; gap: 8rpx; font-size: 20rpx; color: #B26A00; }
.trade-tag {
  font-size: 18rpx; padding: 1rpx 8rpx; border-radius: 4rpx;
  background: #FFF0E8; color: #C2410C;
}
.product-old { font-size: 20rpx; color: #A9812F; line-height: 1.5; }
.card-title { font-size: 24rpx; font-weight: 600; color: #4E5969; margin-bottom: 4rpx; }
.action-card { gap: 16rpx; }
.action-row { display: flex; flex-wrap: wrap; gap: 14rpx; }
.action-btn {
  min-width: 190rpx; height: 72rpx; line-height: 72rpx; padding: 0 26rpx;
  border-radius: 36rpx; font-size: 25rpx; font-weight: 600;
  text-align: center; box-sizing: border-box;
}
.action-btn.primary { background: #FF6B35; color: #fff; }
.action-btn.ghost { background: #fff; color: #FF6B35; border: 2rpx solid #FF6B35; }
.action-btn.danger { background: #FFF1F0; color: #C62828; border: 2rpx solid #FFC9C4; }
.action-hint { font-size: 22rpx; color: #86909C; line-height: 1.5; }
.refund-head { display: flex; align-items: center; justify-content: space-between; }
.refund-status {
  font-size: 20rpx; padding: 2rpx 12rpx; border-radius: 6rpx;
  background: #F2F3F5; color: #86909C;
}
.refund-status.pending { background: #FFF7E6; color: #B26A00; }
.refund-status.approved { background: #FFF1F0; color: #C62828; }
.refund-status.rejected, .refund-status.terminated { background: #F2F3F5; color: #86909C; }
.refund-flow { display: flex; flex-direction: column; gap: 4rpx; margin-top: 4rpx; }
.refund-flow-line { font-size: 22rpx; color: #4E5969; line-height: 1.5; }
.refund-files { display: flex; flex-wrap: wrap; gap: 10rpx; margin-top: 4rpx; }
.refund-file {
  font-size: 22rpx; color: #FF6B35; background: #FFF3ED;
  border-radius: 6rpx; padding: 4rpx 12rpx;
}
.product-row {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16rpx;
  padding: 14rpx 0; border-bottom: 1rpx solid #F7F8FA;
}
.product-row:last-child { border-bottom: none; }
.product-row.ret .product-amount, .product-row.ret .product-commission { color: #C62828; }
.product-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.product-name { font-size: 26rpx; color: #1D2129; font-weight: 500; }
.product-meta { font-size: 21rpx; color: #86909C; }
.product-special { font-size: 20rpx; color: #C2410C; background: #FFF0E8; border-radius: 4rpx; padding: 1rpx 8rpx; align-self: flex-start; }
.product-money { flex-shrink: 0; display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; }
.product-amount { font-size: 26rpx; font-weight: 600; color: #1D2129; }
.product-commission { font-size: 20rpx; color: #0B7A42; }
.empty { font-size: 23rpx; color: #A9AEB8; }
.record { display: flex; gap: 14rpx; padding: 10rpx 0; }
.record-dot {
  width: 14rpx; height: 14rpx; border-radius: 50%; background: #FF6B35;
  margin-top: 10rpx; flex-shrink: 0;
}
.record-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.record-line1 { display: flex; align-items: center; gap: 10rpx; flex-wrap: wrap; }
.record-node { font-size: 24rpx; font-weight: 600; color: #1D2129; }
.record-action { font-size: 20rpx; padding: 1rpx 8rpx; border-radius: 4rpx; background: #E8F8E8; color: #0B7A42; }
.record-time { margin-left: auto; font-size: 20rpx; color: #A9AEB8; }
.record-operator { font-size: 21rpx; color: #86909C; }
.record-opinion { font-size: 21rpx; color: #4E5969; line-height: 1.5; }
.state { text-align: center; padding: 160rpx 32rpx; font-size: 25rpx; color: #86909C; }
.state-error { display: block; color: #C62828; margin-bottom: 20rpx; line-height: 1.5; }
.retry {
  display: inline-block; padding: 12rpx 36rpx; background: #FF6B35;
  color: #fff; border-radius: 28rpx; font-size: 24rpx;
}
.attach-list { display: flex; flex-wrap: wrap; gap: 16rpx; }
.attach-item { width: 160rpx; display: flex; flex-direction: column; gap: 6rpx; }
.attach-img { width: 160rpx; height: 160rpx; border-radius: 12rpx; background: #f2f3f5; }
.attach-file {
  width: 160rpx; height: 160rpx; border-radius: 12rpx; background: #f2f3f5;
  display: flex; align-items: center; justify-content: center; font-size: 48rpx;
}
.attach-name { font-size: 20rpx; color: #86909C; text-align: center;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.video-mask { position: fixed; inset: 0; z-index: 999; background: rgba(0,0,0,.88);
  display: flex; align-items: center; justify-content: center; }
.video-player { width: 92vw; max-height: 70vh; }
.video-close { position: absolute; top: 80rpx; right: 40rpx; color: #fff; font-size: 64rpx; }
.notice-block {
  margin-top: 8rpx; padding: 20rpx; background: #FAFAFA;
  border: 1rpx solid #EEEFF2; border-radius: 12rpx;
}
.notice-title { display: block; font-size: 26rpx; font-weight: 700; color: #1D2129; }
.notice-time { font-size: 20rpx; color: #A9AEB8; font-weight: 400; margin-left: 8rpx; }
.notice-content {
  display: block; margin-top: 12rpx; white-space: pre-wrap;
  font-size: 22rpx; line-height: 1.8; color: #4E5969; word-break: break-all;
}
.notice-sign {
  margin-top: 14rpx; width: 100%; height: 220rpx; background: #fff;
  border: 1rpx solid #EEEFF2; border-radius: 10rpx;
}
.notice-foot { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; margin-top: 6rpx; }
.notice-hint { font-size: 19rpx; color: #A9AEB8; }
.notice-verify { flex-shrink: 0; font-size: 21rpx; color: #2F6FEB; padding: 4rpx 0; }
</style>
