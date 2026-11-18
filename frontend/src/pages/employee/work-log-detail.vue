<template>
  <view class="page">
    <EmployeePageHead title="日志详情" />
    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-error">{{ errorMsg }}</text>
      <view class="retry" @click="load"><text>重试</text></view>
    </view>

    <template v-else-if="detail">
      <view class="card head-card">
        <view class="head-line">
          <text class="biz-type">{{ detail.businessTypeName || '业务流程' }}</text>
          <text class="status" :class="statusClass">{{ detail.instanceStatusName || '—' }}</text>
          <text v-if="detail.deleted" class="deleted">已删除</text>
        </view>
        <text class="biz-summary">{{ detail.businessSummary || detail.businessId || '—' }}</text>
        <text v-if="detail.definitionName" class="biz-def">{{ detail.definitionName }}</text>
      </view>

      <view class="card">
        <text class="card-title">操作信息</text>
        <view class="kv"><text class="k">流程节点</text><text class="v">{{ detail.nodeName || '—' }}</text></view>
        <view class="kv"><text class="k">操作动作</text>
          <text class="v" :class="actionClass">{{ detail.actionName || detail.action || '—' }}</text>
        </view>
        <view class="kv"><text class="k">操作人</text><text class="v">{{ detail.operatorName || detail.initiatorName || '—' }}</text></view>
        <view class="kv"><text class="k">操作时间</text><text class="v">{{ formatTime(detail.createTime) || '—' }}</text></view>
        <view v-if="detail.initiatorName" class="kv">
          <text class="k">发起人</text><text class="v">{{ detail.initiatorName }}</text>
        </view>
        <view v-if="detail.opinion" class="opinion">
          <text class="opinion-label">处理意见</text>
          <text class="opinion-text">{{ detail.opinion }}</text>
        </view>
      </view>

      <view v-if="attachments.length" class="card">
        <text class="card-title">附件</text>
        <view class="attach-list">
          <view v-for="(file, i) in attachments" :key="file.url + i" class="attach-item"
                @click="openAttachment(file)">
            <image v-if="isImage(file.url)" class="attach-img" :src="assetUrl(file.url)"
                   mode="aspectFill" />
            <view v-else class="attach-file"><text>📎</text></view>
            <text class="attach-name">{{ FORM_LABELS[file.name] || file.name || ('附件' + (i + 1)) }}</text>
          </view>
        </view>
      </view>

      <view v-if="formEntries.length" class="card">
        <text class="card-title">表单信息</text>
        <view v-for="(item, i) in formEntries" :key="i" class="kv">
          <text class="k">{{ item.label }}</text>
          <text class="v">{{ item.value }}</text>
        </view>
      </view>

      <view v-if="canOpenBusiness" class="business-link" @click="openBusiness">
        <text>查看业务单据 ›</text>
      </view>
    </template>
  </view>
</template>

<script setup>
import EmployeePageHead from '@/components/EmployeePageHead.vue'
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api'
import { assetUrl } from '@/utils/image'

/**
 * 工作业务日志详情：单据关键信息 + 节点动作 + 意见 + 附件，
 * 并提供跳转业务单据入口（跳转目标按业务类型路由，权限由对应接口校验）。
 */
const key = ref('')
const loading = ref(true)
const errorMsg = ref('')
const detail = ref(null)

onLoad(options => {
  key.value = options && options.key ? decodeURIComponent(options.key) : ''
  if (!key.value) {
    loading.value = false
    errorMsg.value = '缺少日志标识'
    return
  }
  load()
})

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    detail.value = await api.workflowLogDetail(key.value)
  } catch (e) {
    errorMsg.value = (e && e.msg) || '日志加载失败，请重试'
  } finally {
    loading.value = false
  }
}

const statusClass = computed(() => {
  const status = detail.value && detail.value.instanceStatus
  if (status === 'COMPLETED') return 'done'
  if (status === 'REJECTED' || status === 'TERMINATED') return 'stop'
  return 'running'
})

const actionClass = computed(() => {
  const action = detail.value && detail.value.action
  if (action === 'REJECT' || action === 'TERMINATE') return 'stop'
  if (action === 'INITIATE') return 'initiate'
  return 'pass'
})

const attachments = computed(() => {
  const list = detail.value && detail.value.attachments
  return Array.isArray(list) ? list.filter(item => item && item.url) : []
})

const formEntries = computed(() => {
  const form = detail.value && detail.value.detail && detail.value.detail.formData
  if (!form || typeof form !== 'object' || Array.isArray(form)) return []
  return Object.entries(form)
    .filter(([name, value]) => name !== 'attachmentIds'
      && value !== null && value !== undefined && value !== ''
      && !(Array.isArray(value) && value.length === 0))
    .map(([name, value]) => ({
      label: FORM_LABELS[name] || name,
      value: Array.isArray(value) ? value.join('、') : displayValue(name, value)
    }))
})

const FORM_LABELS = {
  receiptNo: '收据号', financeReceiptDate: '财务收款日期', fundDynamicRows: '资金动态表横排数',
  isReceived: '是否收款', checkConclusion: '核对结论', checkRemark: '核对备注',
  extraVouchers: '补充凭证', bankReceiptNote: '银行收款流水/备注', bankReceiptUrls: '银行回单',
  dispatchType: '工单类型', assigneeUserId: '上门员工', expectTime: '期望上门时间',
  visitNote: '随访说明', selectedItemIds: '施工商品', constructionNote: '施工记录',
  completionNote: '完工记录', photos: '现场照片', acceptanceConclusion: '验收结论',
  acceptanceNote: '验收意见', acceptanceEvidenceUrls: '验收凭证',
  serviceNote: '售后记录（历史）', visitResult: '随访结果（历史）'
}

function displayValue(name, value) {
  if (name === 'acceptanceConclusion') {
    return { PASS: '通过', REJECT: '不通过' }[value] || String(value)
  }
  return String(value)
}

const canOpenBusiness = computed(() => {
  const type = detail.value && detail.value.businessType
  const id = detail.value && detail.value.businessId
  return !!id && ['OPS_BUSINESS_ORDER', 'OPS_MATERIAL_REQUISITION',
    'OPS_MATERIAL_WRITE_OFF'].includes(type)
})

function openBusiness() {
  const type = detail.value && detail.value.businessType
  const id = detail.value && detail.value.businessId
  if (!id) return
  const routes = {
    OPS_BUSINESS_ORDER: '/pages/employee/order-detail?orderId=',
    OPS_MATERIAL_REQUISITION: '/pages/employee/material-requisition-detail?id=',
    OPS_MATERIAL_WRITE_OFF: '/pages/employee/material-write-off-detail?id='
  }
  const prefix = routes[type]
  if (!prefix) {
    uni.showToast({ title: '暂不支持跳转该单据', icon: 'none' })
    return
  }
  uni.navigateTo({ url: prefix + encodeURIComponent(id) })
}

function isImage(url) {
  return /\.(png|jpe?g|gif|webp|bmp)(\?|$)/i.test(String(url || ''))
}

function openAttachment(file) {
  const url = assetUrl(file && file.url)
  if (!url) return
  if (isImage(url)) {
    uni.previewImage({ urls: [url], current: url })
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

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 19)
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 20rpx 24rpx 40rpx; box-sizing: border-box; }
.card {
  background: #fff; border-radius: 16rpx; padding: 24rpx;
  display: flex; flex-direction: column; gap: 12rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .03); margin-bottom: 16rpx;
}
.head-card { gap: 8rpx; }
.head-line { display: flex; align-items: center; gap: 10rpx; flex-wrap: wrap; }
.biz-type { font-size: 25rpx; font-weight: 700; color: #1D2129; }
.status, .deleted { font-size: 20rpx; padding: 1rpx 10rpx; border-radius: 4rpx; }
.status.running { background: #E8F3FF; color: #2F6FEB; }
.status.done { background: #E8F8E8; color: #0B7A42; }
.status.stop, .deleted { background: #FFF1F0; color: #C62828; }
.biz-summary { font-size: 26rpx; color: #1D2129; line-height: 1.5; word-break: break-all; }
.biz-def { font-size: 21rpx; color: #86909C; }
.card-title { font-size: 24rpx; font-weight: 600; color: #4E5969; margin-bottom: 4rpx; }
.kv { display: flex; align-items: flex-start; gap: 16rpx; font-size: 23rpx; line-height: 1.5; }
.kv .k { flex: 0 0 150rpx; color: #86909C; }
.kv .v { flex: 1; min-width: 0; color: #1D2129; word-break: break-all; }
.kv .v.pass { color: #0B7A42; }
.kv .v.stop { color: #C62828; }
.kv .v.initiate { color: #2F6FEB; }
.opinion {
  margin-top: 8rpx; background: #F7F9FC; border-radius: 10rpx; padding: 14rpx 16rpx;
  display: flex; flex-direction: column; gap: 6rpx;
}
.opinion-label { font-size: 21rpx; color: #86909C; }
.opinion-text { font-size: 23rpx; color: #1D2129; line-height: 1.6; word-break: break-all; }
.attach-list { display: flex; flex-wrap: wrap; gap: 14rpx; }
.attach-item {
  width: 160rpx; display: flex; flex-direction: column; align-items: center; gap: 6rpx;
}
.attach-img { width: 160rpx; height: 160rpx; border-radius: 8rpx; background: #F2F3F5; }
.attach-file {
  width: 160rpx; height: 160rpx; border-radius: 8rpx; background: #F2F3F5;
  display: flex; align-items: center; justify-content: center; font-size: 46rpx;
}
.attach-name {
  width: 100%; font-size: 20rpx; color: #4E5969; text-align: center;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.business-link {
  text-align: center; padding: 24rpx; background: #fff; border-radius: 16rpx;
  color: #FF6B35; font-size: 26rpx; font-weight: 600;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .03);
}
.state { text-align: center; padding: 160rpx 32rpx; font-size: 25rpx; color: #86909C; }
.state-error { display: block; color: #C62828; margin-bottom: 20rpx; line-height: 1.5; }
.retry {
  display: inline-block; padding: 12rpx 36rpx; background: #FF6B35;
  color: #fff; border-radius: 28rpx; font-size: 24rpx;
}
</style>
