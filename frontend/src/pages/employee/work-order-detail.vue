<template>
  <view class="page">
    <view v-if="loading" class="state">
      <view class="spinner"></view>
      <text class="state-text">加载中...</text>
    </view>

    <view v-else-if="errorMsg" class="state">
      <text class="state-icon">⚠️</text>
      <text class="state-text">{{ errorMsg }}</text>
      <view class="retry-btn" @click="load">重新加载</view>
    </view>

    <template v-else-if="detail">
      <view class="hero">
        <view class="hero-top">
          <text class="type-tag">{{ typeName(detail.dispatchType) }}</text>
          <text class="hero-status">{{ statusName(detail.status) }}</text>
        </view>
        <text class="dispatch-no">{{ detail.dispatchNo }}</text>
        <text class="hero-sub">派发人 {{ detail.dispatchByName || '—' }} · {{ detail.dispatchTime || '—' }}</text>
      </view>

      <view class="card">
        <view class="section-title"><text class="bar"></text>客户信息</view>
        <view class="row">
          <text class="label">客户名称</text>
          <text class="value strong">{{ detail.customerName || '—' }}</text>
        </view>
        <view class="row" @click="callPhone">
          <text class="label">客户手机</text>
          <text class="value phone">{{ detail.customerPhone || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">小区</text>
          <text class="value">{{ detail.communityName || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">详细地址</text>
          <text class="value">{{ detail.detailedAddress || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">期望上门</text>
          <text class="value strong">{{ detail.expectTime || '—' }}</text>
        </view>
      </view>

      <view class="card">
        <view class="section-title"><text class="bar"></text>来源信息</view>
        <view class="row">
          <text class="label">来源订单</text>
          <text class="value">{{ detail.sourceOrderNo || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">订单经办人</text>
          <text class="value">{{ detail.sourceAgentNames || '—' }}</text>
        </view>
        <view class="row">
          <text class="label">收据号</text>
          <text class="value">{{ detail.receiptNo || '—' }}</text>
        </view>
      </view>

      <view v-if="detail.dispatchType === 'CONSTRUCTION'" class="card">
        <view class="section-title"><text class="bar"></text>施工商品</view>
        <view v-for="(item, index) in (detail.items || [])" :key="item.id || index" class="product">
          <view class="product-main">
            <text class="product-name">{{ item.productName || ((item.brand || '') + ' ' + (item.model || '')) }}</text>
            <text class="product-qty">×{{ item.saleQuantity || 1 }}</text>
          </view>
          <text class="product-meta">{{ categoryLabel(item.category) }} · 单位 {{ item.unit || '台' }}</text>
        </view>
        <view v-if="!detail.items || !detail.items.length" class="empty-line">暂无施工商品</view>
      </view>

      <view v-else class="card">
        <view class="section-title"><text class="bar"></text>随访说明</view>
        <text class="remark">{{ detail.remark || '—' }}</text>
      </view>

      <view v-if="detail.stockOutNo" class="card">
        <view class="section-title"><text class="bar"></text>销售出库单</view>
        <view class="stock-row">
          <text class="stock-no">{{ detail.stockOutNo }}</text>
          <text class="stock-status">未审核</text>
        </view>
        <text class="stock-hint">出库单已进入供应链管理，由相关角色审核。</text>
      </view>

      <view v-if="detail.status === 'REJECTED' && detail.rejectReason" class="card">
        <view class="section-title"><text class="bar"></text>退回原因</view>
        <text class="remark">{{ detail.rejectReason }}</text>
      </view>

      <view class="footer-space"></view>

      <view v-if="actions.length" class="action-bar">
        <button
          v-for="action in actions"
          :key="action.key"
          class="action-btn"
          :class="{ primary: action.primary, secondary: !action.primary }"
          :disabled="submitting"
          @click="onAction(action.key)"
        >
          {{ action.label }}
        </button>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { setLoginRedirect } from '@/utils/employeeSession'

const CATEGORY_LABEL = {
  '1': '燃气灶', '2': '抽油烟机', '3': '热水器', '4': '壁挂炉',
  '5': '壁挂炉以旧换新销售', '6': '户内美装销售', '7': '定尺波纹管',
  '8': '户内美装工程', '9': '户内美装运维', '10': '保险',
  '11': '美装管道整改', '12': '美装安全评估', '13': '厨具',
  '15': '商业报警器', qimi: '安全评估技术服务'
}

const id = ref(null)
const detail = ref(null)
const loading = ref(true)
const errorMsg = ref('')
const submitting = ref(false)

const actions = computed(() => {
  const status = detail.value && detail.value.status
  if (status === 'PENDING') {
    return [
      { key: 'accept', label: '接受任务', primary: true },
      { key: 'reject', label: '退回', primary: false }
    ]
  }
  if (status === 'ACCEPTED') return [{ key: 'start', label: '开始处理', primary: true }]
  if (status === 'PROCESSING') return [{ key: 'complete', label: '完成工单', primary: true }]
  return []
})

onLoad((options) => {
  id.value = options && options.id ? Number(options.id) : null
  if (!uni.getStorageSync('ops_employee_token')) {
    const redirect = '/pages/employee/work-order-detail?id=' + (id.value || '')
    setLoginRedirect(redirect)
    uni.redirectTo({ url: '/pages/employee/login?redirect=' + encodeURIComponent(redirect) })
    return
  }
  if (!id.value) {
    errorMsg.value = '工单参数缺失'
    loading.value = false
    return
  }
  load()
})

async function load() {
  if (!id.value) return
  loading.value = true
  errorMsg.value = ''
  try {
    detail.value = await api.employeeDispatchDetail(id.value)
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败，请稍后重试'
    if (e && e.code === 401) {
      const redirect = '/pages/employee/work-order-detail?id=' + id.value
      setLoginRedirect(redirect)
      uni.redirectTo({ url: '/pages/employee/login?redirect=' + encodeURIComponent(redirect) })
    }
  } finally {
    loading.value = false
  }
}

async function onAction(key) {
  if (submitting.value) return
  if (key === 'accept') return acceptTask()
  if (key === 'reject') return rejectTask()
  if (key === 'start') return startTask()
  if (key === 'complete') return completeTask()
}

async function acceptTask() {
  const confirmed = await confirm('接受任务', '确认接受该任务？施工类型将自动生成一张未审核的销售出库单。')
  if (!confirmed) return
  submitting.value = true
  try {
    const result = await api.employeeAcceptDispatch(id.value)
    await load()
    if (result && result.stockOutNo) {
      uni.showModal({
        title: '接单成功',
        content: '已生成销售出库单 ' + result.stockOutNo + '（未审核），请按计划上门服务。',
        showCancel: false
      })
    } else {
      toast('接单成功')
    }
  } catch (e) {
    toast((e && e.msg) || '接单失败，请重试')
  } finally {
    submitting.value = false
  }
}

async function rejectTask() {
  const res = await new Promise((resolve) => {
    uni.showModal({
      title: '退回工单',
      editable: true,
      placeholderText: '请填写退回原因',
      success: resolve,
      fail: () => resolve({ confirm: false })
    })
  })
  if (!res.confirm) return
  const reason = (res.content || '').trim()
  if (!reason) {
    toast('请填写退回原因')
    return
  }
  submitting.value = true
  try {
    await api.employeeRejectDispatch(id.value, { reason })
    await load()
    toast('已退回')
  } catch (e) {
    toast((e && e.msg) || '退回失败，请重试')
  } finally {
    submitting.value = false
  }
}

async function startTask() {
  const confirmed = await confirm('开始处理', '确认已到达现场并开始处理该工单？')
  if (!confirmed) return
  submitting.value = true
  try {
    await api.employeeStartDispatch(id.value)
    await load()
    toast('已开始处理')
  } catch (e) {
    toast((e && e.msg) || '操作失败，请重试')
  } finally {
    submitting.value = false
  }
}

async function completeTask() {
  const confirmed = await confirm('完成工单', '确认该工单已处理完成？')
  if (!confirmed) return
  submitting.value = true
  try {
    await api.employeeCompleteDispatch(id.value)
    await load()
    toast('工单已完成')
  } catch (e) {
    toast((e && e.msg) || '操作失败，请重试')
  } finally {
    submitting.value = false
  }
}

function callPhone() {
  const phone = detail.value && detail.value.customerPhone
  if (!phone) return
  uni.makePhoneCall({ phoneNumber: String(phone) })
}

function confirm(title, content) {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
}

function toast(title) {
  uni.showToast({ title, icon: 'none', duration: 2200 })
}

function typeName(type) {
  return type === 'VISIT' ? '入户随访' : '施工'
}

function statusName(status) {
  return {
    PENDING: '待接单',
    ACCEPTING: '接单处理中',
    ACCEPTED: '已接单',
    PROCESSING: '处理中',
    COMPLETED: '已完成',
    REJECTED: '已退回',
    CANCELLED: '已取消'
  }[status] || status
}

function categoryLabel(category) {
  return CATEGORY_LABEL[category] || category || '—'
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f5f5;
}

.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 140rpx 40rpx;
}
.state-icon { font-size: 64rpx; margin-bottom: 16rpx; }
.state-text { font-size: 28rpx; color: #86909C; }
.retry-btn {
  margin-top: 28rpx;
  padding: 14rpx 40rpx;
  border-radius: 30rpx;
  background: #FF6B35;
  color: #fff;
  font-size: 26rpx;
}
.spinner {
  width: 44rpx;
  height: 44rpx;
  border: 4rpx solid #eee;
  border-top-color: #FF6B35;
  border-radius: 50%;
  animation: spin .8s linear infinite;
  margin-bottom: 16rpx;
}
@keyframes spin { to { transform: rotate(360deg); } }

.hero {
  background: linear-gradient(135deg, #FF8C5A, #FF6B35);
  padding: 36rpx 32rpx 40rpx;
  color: #fff;
}
.hero-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18rpx;
}
.type-tag {
  font-size: 22rpx;
  padding: 6rpx 16rpx;
  border-radius: 8rpx;
  background: rgba(255,255,255,.22);
  color: #fff;
}
.hero-status {
  font-size: 24rpx;
  font-weight: 600;
}
.dispatch-no {
  display: block;
  font-size: 38rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
}
.hero-sub {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: rgba(255,255,255,.82);
}

.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 26rpx 24rpx;
  margin: 20rpx 24rpx 0;
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04);
}
.section-title {
  display: flex;
  align-items: center;
  font-size: 29rpx;
  font-weight: 600;
  color: #1D2129;
  margin-bottom: 18rpx;
}
.section-title .bar {
  width: 8rpx;
  height: 30rpx;
  border-radius: 4rpx;
  background: #FF6B35;
  margin-right: 14rpx;
}

.row {
  display: flex;
  align-items: flex-start;
  margin-top: 14rpx;
  font-size: 26rpx;
}
.label { width: 148rpx; color: #86909C; flex-shrink: 0; }
.value { flex: 1; color: #4E5969; word-break: break-all; }
.value.strong { color: #1D2129; font-weight: 500; }
.value.phone { color: #FF6B35; text-decoration: underline; }

.product {
  padding: 16rpx 0;
  border-top: 1rpx solid #f5f5f5;
}
.product:first-of-type { border-top: none; }
.product-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.product-name { font-size: 27rpx; color: #1D2129; font-weight: 500; flex: 1; }
.product-qty { font-size: 27rpx; color: #FF6B35; font-weight: 600; margin-left: 16rpx; }
.product-meta { display: block; margin-top: 8rpx; font-size: 23rpx; color: #86909C; }

.remark { font-size: 26rpx; color: #4E5969; line-height: 1.7; }
.empty-line { font-size: 25rpx; color: #C9CDD4; padding: 16rpx 0; }

.stock-row { display: flex; align-items: center; justify-content: space-between; }
.stock-no { font-size: 28rpx; color: #1D2129; font-weight: 600; }
.stock-status {
  font-size: 22rpx;
  color: #E85D2C;
  background: #FFF3E8;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
}
.stock-hint { display: block; margin-top: 12rpx; font-size: 23rpx; color: #86909C; }

.footer-space { height: 170rpx; }
.action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  background: #fff;
  border-top: 1rpx solid #f0f0f0;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  gap: 20rpx;
}
.action-btn {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  text-align: center;
  border-radius: 42rpx;
  font-size: 29rpx;
  font-weight: 600;
  margin: 0;
  border: none;
}
.action-btn::after { border: none; }
.action-btn.primary { background: #FF6B35; color: #fff; }
.action-btn.secondary { background: #F5F6FA; color: #4E5969; }
.action-btn[disabled] { opacity: .6; }
</style>
