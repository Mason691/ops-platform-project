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
      <MaterialRequisitionStepInfo v-if="hasStepErrors(errors, requisitionStepOf, 0)"
                                   :form="form" :meta="meta" :errors="errors" :only-errors="true" />
      <MaterialItemsStep v-if="hasStepErrors(errors, requisitionStepOf, 1)"
                         mode="requisition" :form="form" :meta="meta" :errors="errors"
                         :only-errors="true" />
    </view>

    <view class="summary-card">
      <view class="block-head">
        <text class="block-title">领用信息</text>
        <text class="edit" @click="$emit('edit', 0)">修改</text>
      </view>
      <view class="kv"><text class="k">领用用途</text><text class="v">{{ form.purpose || '—' }}</text></view>
      <view class="kv"><text class="k">期望领用日期</text><text class="v">{{ form.expectedDate || '—' }}</text></view>
      <view class="kv"><text class="k">领用人</text><text class="v">{{ applicantNames || '—' }}</text></view>
      <view class="kv"><text class="k">组织 / 部门</text><text class="v">{{ orgText }}</text></view>
      <view class="kv"><text class="k">备注</text><text class="v">{{ form.remark || '—' }}</text></view>
    </view>

    <view class="summary-card">
      <view class="block-head">
        <text class="block-title">物料明细</text>
        <text class="edit" @click="$emit('edit', 1)">修改</text>
      </view>
      <view v-for="row in filledItems" :key="row.uid" class="product-summary">
        <view class="product-head">
          <text class="product-name">{{ row.name }}</text>
          <text class="product-qty">× {{ row.quantity }} {{ row.unit || '' }}</text>
        </view>
        <text class="product-meta">
          {{ [row.category, row.brand, row.model].filter(Boolean).join(' · ') }}
        </text>
        <text v-if="row.remark" class="product-sub">备注：{{ row.remark }}</text>
      </view>
    </view>

    <view class="totals-card">
      <view class="total-line">
        <text class="total-label">合计</text>
        <text class="total-amount">{{ totals.itemCount }} 种 · {{ totals.totalQty }} 件</text>
      </view>
      <text class="total-meta">领用单号将在提交后自动生成</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import MaterialRequisitionStepInfo from '@/components/MaterialRequisitionStepInfo.vue'
import MaterialItemsStep from '@/components/MaterialItemsStep.vue'
import {
  hasStepErrors, requisitionStepOf, requisitionTotals
} from '@/utils/materialForm'

/**
 * 第 3 步：确认提交。顶部待补充事项就地补填；下方只读汇总，每块可跳回修改。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  missing: { type: Array, default: () => [] }
})
defineEmits(['edit'])

const filledItems = computed(() =>
  (props.form.items || []).filter(row => row && row.materialId))
const totals = computed(() => requisitionTotals(props.form.items))
const applicantNames = computed(() => {
  const ids = new Set((props.form.agentUserIds || []).map(String))
  const employees = props.meta.employees || []
  const names = employees.filter(e => ids.has(String(e.userId))).map(e => e.name)
  if (props.meta.employeeName && ids.has(String(props.meta.employeeUserId))
    && !names.includes(props.meta.employeeName)) {
    names.unshift(props.meta.employeeName)
  }
  return names.join('、')
})
const orgText = computed(() => {
  const org = props.meta.company || props.meta.corpName || '—'
  return `${org} / ${props.meta.deptName || '未设置部门'}`
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
.edit { font-size: 25rpx; color: #FF6B35; padding: 4rpx 8rpx; }
.kv { display: flex; align-items: flex-start; padding: 10rpx 0; }
.k { width: 180rpx; flex-shrink: 0; font-size: 25rpx; color: #999; }
.v { flex: 1; font-size: 26rpx; color: #333; line-height: 1.5; word-break: break-all; }
.product-summary { padding: 14rpx 0; border-top: 1rpx solid #f5f5f5; }
.block-head + .product-summary { border-top: none; }
.product-head { display: flex; align-items: center; justify-content: space-between; }
.product-name { flex: 1; min-width: 0; margin-right: 16rpx; font-size: 26rpx; color: #333; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.product-qty { font-size: 25rpx; color: #FF6B35; font-weight: 700; white-space: nowrap; }
.product-meta { display: block; font-size: 22rpx; color: #999; margin-top: 6rpx; }
.product-sub { display: block; font-size: 21rpx; color: #999; margin-top: 4rpx; }
.totals-card { background: linear-gradient(135deg, #FFF6F1, #FFF); }
.total-line { display: flex; align-items: baseline; justify-content: space-between; }
.total-label { font-size: 27rpx; color: #666; font-weight: 600; }
.total-amount { font-size: 38rpx; font-weight: 700; color: #FF6B35; }
.total-meta { display: block; margin-top: 12rpx; font-size: 23rpx; color: #999; }
</style>
