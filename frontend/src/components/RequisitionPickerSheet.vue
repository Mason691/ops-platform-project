<template>
  <view v-if="visible" class="overlay" @click.self="close">
    <view class="sheet">
      <view class="sheet-head">
        <text class="sheet-title">选择领用单</text>
        <text class="close" @click="close">关闭</text>
      </view>

      <view class="search-row">
        <input v-model="keyword" class="search-input" placeholder="搜索领用单号 / 用途 / 物料名称" />
        <text v-if="keyword" class="search-clear" @click="keyword = ''">清空</text>
      </view>
      <view class="date-row">
        <picker mode="date" :value="dateFrom" @change="e => { dateFrom = e.detail.value; load() }">
          <view class="date-trigger">{{ dateFrom || '开始日期' }}</view>
        </picker>
        <text class="date-sep">至</text>
        <picker mode="date" :value="dateTo" @change="e => { dateTo = e.detail.value; load() }">
          <view class="date-trigger">{{ dateTo || '结束日期' }}</view>
        </picker>
        <text v-if="dateFrom || dateTo" class="date-clear" @click="clearDates">清空</text>
      </view>

      <view v-if="loading" class="state"><text>加载中...</text></view>
      <view v-else-if="!rows.length" class="state"><text>没有可核销的领用单</text></view>
      <scroll-view v-else scroll-y class="list">
        <view v-for="item in rows" :key="item.id" class="card" @click="pick(item)">
          <view class="card-head">
            <text class="card-no">{{ item.requisitionNo }}</text>
            <text class="card-status">{{ statusLabel(item.status) }}</text>
          </view>
          <text class="card-purpose">{{ item.purpose }}</text>
          <text class="card-meta">{{ item.materialSummary }} · {{ item.itemCount }}种 {{ item.totalQty }}件</text>
          <view class="card-foot">
            <text class="card-date">领用日期 {{ item.expectedDate || '—' }}</text>
            <text class="card-applicant">{{ item.applicantNames || '' }}</text>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script setup>
import { ref, watch } from 'vue'
import { api } from '@/api/index'
import { REQUISITION_STATUS } from '@/utils/materialForm'

/**
 * 可核销领用单弹层：仅当前员工可见、已通过/已发放、未核销完成、无审批中核销单。
 * 支持领用单号/用途/物料名称关键字 + 领用日期范围筛选。
 */
const props = defineProps({ visible: { type: Boolean, default: false } })
const emit = defineEmits(['update:visible', 'select'])

const keyword = ref('')
const dateFrom = ref('')
const dateTo = ref('')
const rows = ref([])
const loading = ref(false)
let searchTimer = null
let requestToken = 0

watch(() => props.visible, (visible) => {
  if (visible) load()
})

watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(load, 300)
})

function statusLabel(status) {
  return REQUISITION_STATUS[status] || status || ''
}

async function load() {
  loading.value = true
  const token = ++requestToken
  try {
    const res = await api.materialPickableRequisitions({
      keyword: String(keyword.value || '').trim() || undefined,
      dateFrom: dateFrom.value || undefined,
      dateTo: dateTo.value || undefined
    })
    if (token === requestToken) rows.value = res || []
  } catch (e) {
    if (token === requestToken) {
      rows.value = []
      uni.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
    }
  } finally {
    if (token === requestToken) loading.value = false
  }
}

function clearDates() {
  dateFrom.value = ''
  dateTo.value = ''
  load()
}

function pick(item) {
  emit('select', item)
  emit('update:visible', false)
}

function close() {
  emit('update:visible', false)
}
</script>

<style scoped>
.overlay {
  position: fixed; inset: 0; z-index: 260; background: rgba(0, 0, 0, .42);
  display: flex; align-items: flex-end;
}
.sheet {
  width: 100%; max-height: 86vh; background: #fff;
  border-radius: 24rpx 24rpx 0 0; padding: 24rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  display: flex; flex-direction: column; box-sizing: border-box;
}
.sheet-head { display: flex; align-items: center; justify-content: space-between; }
.sheet-title { font-size: 32rpx; font-weight: 700; color: #1D2129; }
.close { font-size: 26rpx; color: #86909C; padding: 8rpx; }
.search-row {
  margin-top: 16rpx; display: flex; align-items: center;
  background: #f5f5f5; border-radius: 12rpx; padding: 0 18rpx;
}
.search-input { flex: 1; height: 72rpx !important; min-height: 72rpx !important; font-size: 27rpx; color: #333; }
.search-clear { font-size: 23rpx; color: #999; padding-left: 12rpx; }
.date-row { display: flex; align-items: center; gap: 12rpx; margin-top: 12rpx; }
.date-trigger {
  padding: 12rpx 20rpx; background: #f5f5f5; border-radius: 10rpx;
  font-size: 24rpx; color: #4E5969;
}
.date-sep { font-size: 24rpx; color: #86909C; }
.date-clear { font-size: 23rpx; color: #FF6B35; margin-left: auto; }
.list { margin-top: 12rpx; max-height: 60vh; }
.card {
  padding: 20rpx; margin-bottom: 12rpx; border: 2rpx solid #F0F1F5;
  border-radius: 14rpx; background: #fcfcfc;
}
.card:active { background: #FFF8F4; border-color: #FFD9C8; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-no { font-size: 27rpx; font-weight: 700; color: #1D2129; }
.card-status { font-size: 22rpx; color: #FF6B35; background: #FFF3ED; border-radius: 6rpx; padding: 2rpx 10rpx; }
.card-purpose { display: block; margin-top: 8rpx; font-size: 25rpx; color: #4E5969; }
.card-meta { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.card-foot { margin-top: 10rpx; display: flex; justify-content: space-between; }
.card-date, .card-applicant { font-size: 21rpx; color: #C9CDD4; }
.state { padding: 60rpx 0; text-align: center; font-size: 25rpx; color: #86909C; }
</style>
