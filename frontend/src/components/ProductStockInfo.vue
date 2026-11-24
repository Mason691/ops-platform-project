<template>
  <view v-if="available" class="stock-info" :class="{ compact }">
    <view v-if="loading && !data" class="stock-loading">库存查询中...</view>
    <template v-else-if="data">
      <view class="stock-line">
        <text class="stock-label">本组织库存</text>
        <text class="stock-value" :class="{ warning: lowStock }">{{ stockText }}</text>
        <text v-if="data.unit" class="stock-unit">{{ data.unit }}</text>
        <text v-if="showOthersLink" class="stock-link" @click.stop="toggle">
          {{ expanded ? '收起其他组织库存' : '查看其他城市合伙人库存' }}
          <text class="stock-arrow">{{ expanded ? '▲' : '▼' }}</text>
        </text>
        <text class="stock-refresh" @click.stop="load(true)">刷新</text>
      </view>
      <text v-if="noStock" class="stock-hint">本组织暂无库存，可选择其他组织库存参考</text>
      <view v-if="expanded" class="stock-others">
        <view v-for="item in others" :key="item.corpId" class="stock-other-row">
          <text class="stock-other-name">{{ item.corpName }}</text>
          <text class="stock-other-value" :class="{ zero: Number(item.stock) <= 0 }">
            {{ formatQty(item.stock) }}{{ data.unit || '' }}
          </text>
        </view>
        <text v-if="!others.length" class="stock-empty">同运营商下暂无其他城市合伙人</text>
        <text class="stock-cache-hint">库存数据 30 秒内缓存，仅供制单/审核参考</text>
      </view>
    </template>
    <view v-else-if="error" class="stock-error" @click.stop="load(true)">
      库存暂不可用，点此重试
    </view>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '@/api/index'

/**
 * 型号库存只读展示：本组织可用库存 + 同运营商其他城市合伙人库存。
 *
 * 数据源为 SCM scm_inventory_cache（金蝶同步 + 领用/核销流水），
 * 前端按「组织 + 商品」缓存 30 秒，避免同一商品多行重复请求；
 * 库存不足/为 0 不参与任何校验，仅作提示。
 */
const props = defineProps({
  materialId: { type: [Number, String], default: null },
  brand: { type: String, default: '' },
  category: { type: String, default: '' },
  model: { type: String, default: '' },
  quantity: { type: [Number, String], default: null },
  compact: { type: Boolean, default: false }
})

const CACHE_TTL_MS = 30 * 1000
const cache = new Map()

const loading = ref(false)
const error = ref(false)
const data = ref(null)
const expanded = ref(false)
let requestToken = 0

function corpKey() {
  try { return String(uni.getStorageSync('corp_id') || '') } catch (e) { return '' }
}

function cacheKey() {
  const id = props.materialId ? 'id:' + props.materialId
    : [props.brand, props.category, props.model].map(v => String(v || '')).join('|')
  return corpKey() + '#' + id
}

function params() {
  if (props.materialId) return { materialId: props.materialId }
  return {
    brand: props.brand || undefined,
    category: props.category || undefined,
    model: props.model || undefined
  }
}

const available = computed(() => !!(props.materialId
  || (props.model && (props.category || props.brand))))

const currentStock = computed(() => Number((data.value && data.value.currentStock) || 0))
const others = computed(() => (data.value && data.value.others) || [])
const lowStock = computed(() => {
  const qty = Number(props.quantity)
  return Number.isFinite(qty) && qty > 0 ? currentStock.value < qty : currentStock.value <= 0
})
const noStock = computed(() => currentStock.value <= 0)
const showOthersLink = computed(() => lowStock.value || noStock.value)
const stockText = computed(() => formatQty(currentStock.value))

function formatQty(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '0'
  return Number.isInteger(number) ? String(number) : number.toFixed(2)
}

async function load(force = false) {
  if (!available.value) return
  const key = cacheKey()
  if (!force) {
    const cached = cache.get(key)
    if (cached && Date.now() - cached.ts < CACHE_TTL_MS) {
      data.value = cached.data
      error.value = false
      return
    }
  }
  const token = ++requestToken
  loading.value = true
  error.value = false
  try {
    const result = await api.employeeModelStock(params())
    if (token !== requestToken) return
    data.value = result || null
    if (result) cache.set(key, { data: result, ts: Date.now() })
  } catch (e) {
    if (token === requestToken) error.value = !data.value
  } finally {
    if (token === requestToken) loading.value = false
  }
}

function toggle() {
  expanded.value = !expanded.value
  if (expanded.value && !data.value) load()
}

watch(() => [props.materialId, props.brand, props.category, props.model], () => {
  expanded.value = false
  data.value = null
  load()
}, { immediate: true })
</script>

<style scoped>
.stock-info {
  margin-top: 8rpx; padding: 12rpx 16rpx; border-radius: 10rpx;
  background: #F7F9FC; border: 1rpx solid #E8EEF5;
}
.stock-info.compact { margin-top: 6rpx; padding: 8rpx 12rpx; }
.stock-line { display: flex; align-items: center; flex-wrap: wrap; gap: 8rpx; }
.stock-label { font-size: 22rpx; color: #86909C; }
.stock-value { font-size: 25rpx; font-weight: 700; color: #0B7A42; }
.stock-value.warning { color: #D25F00; }
.stock-unit { font-size: 21rpx; color: #86909C; }
.stock-link { font-size: 21rpx; color: #2F6FEB; margin-left: auto; }
.stock-arrow { font-size: 18rpx; margin-left: 4rpx; }
.stock-refresh { font-size: 20rpx; color: #A9AEB8; margin-left: 12rpx; text-decoration: underline; }
.stock-hint { display: block; font-size: 21rpx; color: #D25F00; margin-top: 6rpx; line-height: 1.5; }
.stock-others {
  margin-top: 8rpx; padding-top: 8rpx; border-top: 1rpx dashed #DCE3EC;
  display: flex; flex-direction: column; gap: 6rpx;
}
.stock-other-row { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.stock-other-name { font-size: 22rpx; color: #4E5969; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.stock-other-value { font-size: 22rpx; font-weight: 600; color: #1D2129; flex-shrink: 0; }
.stock-other-value.zero { color: #A9AEB8; font-weight: 400; }
.stock-empty, .stock-cache-hint { font-size: 20rpx; color: #A9AEB8; line-height: 1.5; }
.stock-loading, .stock-error { font-size: 21rpx; color: #86909C; }
.stock-error { color: #2F6FEB; text-decoration: underline; }
</style>
