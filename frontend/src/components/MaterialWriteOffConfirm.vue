<template>
  <view class="confirm">
    <view v-if="missing.length" class="missing-card">
      <view class="missing-head">
        <text class="missing-title">待补充事项</text>
        <text class="missing-count">{{ missing.length }} 项</text>
      </view>
      <view class="missing-labels">
        <text v-for="item in missing" :key="item.key" class="missing-chip">{{ item.label }}</text>
      </view>
      <text class="missing-hint">可直接在下方补填后提交，无需返回上一步</text>
      <MaterialWriteOffStepInfo v-if="hasStepErrors(errors, writeOffStepOf, 0)"
                                :form="form" :meta="meta" :errors="errors"
                                :requisition="requisition" :only-errors="true"
                                @select-requisition="payload => $emit('select-requisition', payload)" />
      <MaterialItemsStep v-if="hasStepErrors(errors, writeOffStepOf, 1)"
                         mode="writeoff" :form="form" :meta="meta" :errors="errors"
                         :only-errors="true" />
    </view>

    <view class="summary-card">
      <view class="block-head">
        <text class="block-title">核销信息</text>
        <text class="edit" @click="$emit('edit', 0)">修改</text>
      </view>
      <view class="kv"><text class="k">领用单号</text><text class="v">{{ form.requisitionNo || '—' }}</text></view>
      <view class="kv"><text class="k">核销单号</text><text class="v muted">提交后生成</text></view>
      <view class="kv"><text class="k">领用人</text><text class="v">{{ form.applicantName || '—' }}</text></view>
      <view class="kv"><text class="k">部门</text><text class="v">{{ form.deptName || '未设置部门' }}</text></view>
      <view class="kv"><text class="k">核销人</text><text class="v">{{ writeOffUserNames || '—' }}</text></view>
      <view class="kv"><text class="k">核销日期</text><text class="v">{{ form.writeOffDate || '—' }}</text></view>
      <view class="kv"><text class="k">使用说明</text><text class="v">{{ form.usageDesc || '—' }}</text></view>
      <view class="kv"><text class="k">备注</text><text class="v">{{ form.remark || '—' }}</text></view>
    </view>

    <view class="summary-card">
      <view class="block-head">
        <text class="block-title">物料核销</text>
        <text class="edit" @click="$emit('edit', 1)">修改</text>
      </view>
      <view v-for="row in form.items" :key="row.uid" class="product-summary">
        <view class="product-head">
          <text class="product-name">{{ row.materialName }}</text>
          <text class="product-qty">核销 {{ row.writeOffQty }} / 退回 {{ row.returnQty }}</text>
        </view>
        <text class="product-meta">
          {{ [row.category, row.brand, row.model].filter(Boolean).join(' · ') }}
          · 领用 {{ row.requisitionQty }} {{ row.unit || '' }}
        </text>
        <text v-if="row.remark" class="product-sub">说明：{{ row.remark }}</text>
      </view>
    </view>

    <view id="field-voucherUrls" class="summary-card">
      <view class="block-head">
        <text class="block-title">使用凭证 <text class="req">*</text></text>
      </view>
      <MaterialVoucherUploader v-model="form.voucherUrls" :max="5" :error="errors.voucherUrls" />
      <text class="voucher-hint">支持 JPG / PNG / WEBP / PDF，单个不超过 10MB，至少上传 1 个</text>
    </view>

    <view class="totals-card">
      <view class="total-line">
        <text class="total-label">合计</text>
        <text class="total-amount">{{ totals.itemCount }} 种 · 核销 {{ totals.writeOffTotal }} · 退回 {{ totals.returnTotal }}</text>
      </view>
      <text class="total-meta">核销单号将在提交后自动生成</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import MaterialWriteOffStepInfo from '@/components/MaterialWriteOffStepInfo.vue'
import MaterialItemsStep from '@/components/MaterialItemsStep.vue'
import MaterialVoucherUploader from '@/components/MaterialVoucherUploader.vue'
import { hasStepErrors, writeOffStepOf, writeOffTotals } from '@/utils/materialForm'

/**
 * 第 3 步：确认提交。待补充事项就地补填；凭证在本页上传并参与全局校验。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  missing: { type: Array, default: () => [] },
  requisition: { type: Object, default: null }
})
defineEmits(['edit', 'select-requisition'])

const totals = computed(() => writeOffTotals(props.form.items))
const writeOffUserNames = computed(() => {
  const ids = new Set((props.form.agentUserIds || []).map(String))
  const employees = props.meta.employees || []
  const names = employees.filter(e => ids.has(String(e.userId))).map(e => e.name)
  if (props.meta.employeeName && ids.has(String(props.meta.employeeUserId))
    && !names.includes(props.meta.employeeName)) {
    names.unshift(props.meta.employeeName)
  }
  return names.join('、')
})
</script>

<style scoped>
.confirm { display: flex; flex-direction: column; gap: 20rpx; }
.missing-card {
  background: #FFF8F4; border: 2rpx solid #FFD9C8; border-radius: 16rpx;
  padding: 22rpx 20rpx; display: flex; flex-direction: column; gap: 16rpx;
}
.missing-head { display: flex; align-items: center; justify-content: space-between; }
.missing-title { font-size: 30rpx; font-weight: 700; color: #C2410C; }
.missing-count { font-size: 23rpx; color: #C2410C; background: #FFE8DC; border-radius: 20rpx; padding: 4rpx 18rpx; }
.missing-labels { display: flex; flex-wrap: wrap; gap: 10rpx; }
.missing-chip { font-size: 22rpx; color: #C2410C; background: #fff; border-radius: 8rpx; padding: 6rpx 14rpx; }
.missing-hint { font-size: 22rpx; color: #9a6a55; line-height: 1.5; }
.summary-card, .totals-card { background: #fff; border-radius: 16rpx; padding: 24rpx; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04); }
.block-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16rpx; }
.block-title { font-size: 29rpx; font-weight: 700; color: #333; }
.req { color: #e53935; }
.edit { font-size: 25rpx; color: #FF6B35; padding: 4rpx 8rpx; }
.kv { display: flex; align-items: flex-start; padding: 10rpx 0; }
.k { width: 180rpx; flex-shrink: 0; font-size: 25rpx; color: #999; }
.v { flex: 1; font-size: 26rpx; color: #333; line-height: 1.5; word-break: break-all; }
.muted { color: #999; }
.product-summary { padding: 14rpx 0; border-top: 1rpx solid #f5f5f5; }
.block-head + .product-summary { border-top: none; }
.product-head { display: flex; align-items: center; justify-content: space-between; }
.product-name { flex: 1; min-width: 0; margin-right: 16rpx; font-size: 26rpx; color: #333; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.product-qty { font-size: 24rpx; color: #FF6B35; font-weight: 700; white-space: nowrap; }
.product-meta { display: block; font-size: 22rpx; color: #999; margin-top: 6rpx; }
.product-sub { display: block; font-size: 21rpx; color: #999; margin-top: 4rpx; }
.voucher-hint { display: block; font-size: 21rpx; color: #999; margin-top: 12rpx; }
.totals-card { background: linear-gradient(135deg, #FFF6F1, #FFF); }
.total-line { display: flex; align-items: baseline; justify-content: space-between; }
.total-label { font-size: 27rpx; color: #666; font-weight: 600; }
.total-amount { font-size: 34rpx; font-weight: 700; color: #FF6B35; }
.total-meta { display: block; margin-top: 12rpx; font-size: 23rpx; color: #999; }
</style>
