<template>
  <view class="page">
    <view v-if="loading" class="state"><text>加载中...</text></view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-err">{{ errorMsg }}</text>
      <view class="retry" @click="load"><text>重试</text></view>
    </view>
    <template v-else-if="detail">
      <view class="hero">
        <view class="hero-top">
          <text class="hero-no">{{ detail.writeOffNo }}</text>
          <text class="hero-status">{{ statusLabel(detail.status) }}</text>
        </view>
        <text class="hero-ref">关联领用单 {{ detail.requisitionNo || '—' }}</text>
        <text class="hero-nums">核销 {{ detail.writeOffTotal || 0 }} · 退回 {{ detail.returnTotal || 0 }} · {{ detail.itemCount }} 种</text>
      </view>

      <view class="card">
        <text class="block-title">核销信息</text>
        <view class="kv"><text class="k">领用人</text><text class="v">{{ detail.applicantName || '—' }}</text></view>
        <view class="kv"><text class="k">部门</text><text class="v">{{ detail.deptName || '未设置部门' }}</text></view>
        <view class="kv"><text class="k">核销人</text><text class="v">{{ detail.writeOffUserNames || '—' }}</text></view>
        <view class="kv"><text class="k">核销日期</text><text class="v">{{ detail.writeOffDate || '—' }}</text></view>
        <view class="kv"><text class="k">使用说明</text><text class="v">{{ detail.usageDesc || '—' }}</text></view>
        <view class="kv"><text class="k">备注</text><text class="v">{{ detail.remark || '—' }}</text></view>
      </view>

      <view class="card">
        <text class="block-title">审批信息</text>
        <view class="kv"><text class="k">状态</text><text class="v">{{ statusLabel(detail.status) }}</text></view>
        <view v-if="detail.status === 'PENDING'" class="kv">
          <text class="k">当前审批人</text>
          <text class="v approver">{{ approverText }}</text>
        </view>
        <view v-if="detail.wfNodeName" class="kv"><text class="k">当前节点</text><text class="v">{{ detail.wfNodeName }}</text></view>
        <view v-if="detail.approvalRemark" class="kv"><text class="k">审批意见</text><text class="v">{{ detail.approvalRemark }}</text></view>
        <view v-if="detail.approveTime" class="kv"><text class="k">审批时间</text><text class="v">{{ formatTime(detail.approveTime) }}</text></view>
      </view>

      <view class="card">
        <text class="block-title">核销明细</text>
        <view v-for="entry in detail.entries" :key="entry.id" class="entry">
          <view class="entry-top">
            <text class="entry-name">{{ entry.materialName }}</text>
            <text class="entry-nums">核销 {{ entry.writeOffQty }} / 退回 {{ entry.returnQty }}</text>
          </view>
          <text class="entry-meta">{{ [entry.category, entry.brand, entry.model].filter(Boolean).join(' · ') }}</text>
          <text class="entry-meta">领用 {{ entry.requisitionQty }} {{ entry.unit || '' }} · 可核销余量 {{ entry.remainingQty }}</text>
          <text v-if="entry.remark" class="entry-sub">说明：{{ entry.remark }}</text>
        </view>
      </view>

      <view v-if="detail.voucherUrls && detail.voucherUrls.length" class="card">
        <text class="block-title">使用凭证</text>
        <view class="voucher-grid">
          <view v-for="(url, index) in detail.voucherUrls" :key="url + index" class="voucher-item"
                @click="preview(url)">
            <image v-if="isImage(url)" class="voucher-img" :src="assetUrl(url)" mode="aspectFill" />
            <view v-else class="pdf-thumb"><text>PDF</text></view>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { api } from '@/api/index'
import { WRITE_OFF_STATUS, formatTime } from '@/utils/materialForm'
import { assetUrl } from '@/utils/image'

/** 物资核销详情：核销信息 + 审批信息 + 明细 + 使用凭证。 */
const id = ref(null)
const detail = ref(null)
const loading = ref(true)
const errorMsg = ref('')

const approverText = computed(() => {
  if (detail.value.currentApproverName) {
    const who = detail.value.currentApproverRole
      ? `${detail.value.currentApproverRole}：${detail.value.currentApproverName}`
      : detail.value.currentApproverName
    return `待 ${who} 审批`
  }
  return detail.value.approverMessage || '审批人待分配，请联系管理员'
})

onLoad((options) => {
  id.value = Number(options && options.id)
  load()
})

async function load() {
  if (!id.value) { errorMsg.value = '缺少核销单ID'; loading.value = false; return }
  loading.value = true
  errorMsg.value = ''
  try {
    detail.value = await api.materialWriteOffDetail(id.value)
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败'
  } finally {
    loading.value = false
  }
}

function statusLabel(value) {
  return WRITE_OFF_STATUS[value] || value || ''
}
function isImage(url) {
  return /\.(jpe?g|png|webp)$/i.test(String(url || ''))
}
function preview(url) {
  const full = assetUrl(url)
  if (isImage(full)) {
    uni.previewImage({ urls: (detail.value.voucherUrls || []).filter(isImage).map(assetUrl), current: full })
    return
  }
  // #ifdef H5
  if (typeof window !== 'undefined') window.open(full, '_blank')
  // #endif
  // #ifndef H5
  uni.openDocument({ filePath: full, showMenu: true })
  // #endif
}
</script>

<style scoped>
.page { min-height: 100vh; background: #F5F6FA; padding: 24rpx 24rpx 40rpx; box-sizing: border-box; }
.state { padding: 160rpx 0; text-align: center; font-size: 25rpx; color: #86909C; }
.state-err { display: block; color: #F53F3F; margin-bottom: 20rpx; }
.retry { display: inline-block; padding: 12rpx 40rpx; border-radius: 999rpx; background: #FF6B35; color: #fff; font-size: 24rpx; }
.hero { background: linear-gradient(135deg, #FF6B35, #FF8C5A); border-radius: 18rpx; padding: 26rpx; color: #fff; }
.hero-top { display: flex; align-items: center; justify-content: space-between; }
.hero-no { font-size: 32rpx; font-weight: 700; }
.hero-status { font-size: 22rpx; background: rgba(255,255,255,.22); border-radius: 8rpx; padding: 4rpx 14rpx; }
.hero-ref { display: block; margin-top: 12rpx; font-size: 24rpx; opacity: .9; }
.hero-nums { display: block; margin-top: 6rpx; font-size: 23rpx; opacity: .9; }
.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-top: 16rpx; }
.block-title { display: block; font-size: 29rpx; font-weight: 700; color: #1D2129; margin-bottom: 14rpx; }
.kv { display: flex; padding: 8rpx 0; }
.k { width: 180rpx; flex-shrink: 0; font-size: 24rpx; color: #86909C; }
.v { flex: 1; font-size: 26rpx; color: #1D2129; line-height: 1.5; word-break: break-all; }
.approver { color: #FF7D00; }
.entry { padding: 16rpx 0; border-top: 1rpx solid #F2F3F5; }
.entry:first-of-type { border-top: none; }
.entry-top { display: flex; justify-content: space-between; }
.entry-name { font-size: 27rpx; font-weight: 600; color: #1D2129; }
.entry-nums { font-size: 23rpx; color: #FF6B35; font-weight: 600; }
.entry-meta { display: block; margin-top: 6rpx; font-size: 21rpx; color: #86909C; }
.entry-sub { display: block; margin-top: 6rpx; font-size: 21rpx; color: #86909C; }
.voucher-grid { display: flex; flex-wrap: wrap; gap: 14rpx; }
.voucher-item { width: 150rpx; height: 150rpx; border-radius: 10rpx; overflow: hidden; background: #f5f5f5; }
.voucher-img { width: 100%; height: 100%; }
.pdf-thumb { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; background: #FFF1F0; color: #e53935; font-size: 26rpx; font-weight: 700; }
</style>
