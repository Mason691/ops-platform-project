<template>
  <view v-if="visible" class="overlay" @click.self="close">
    <view class="sheet">
      <view class="sheet-head">
        <text class="sheet-title">选择物料</text>
        <text class="close" @click="close">关闭</text>
      </view>

      <view class="category-wrap">
        <FieldChips :options="categoryOptions" :model-value="selectedCategory"
                    compact @update:model-value="setCategory" />
      </view>

      <view class="search-row">
        <input v-model="keyword" class="search-input" placeholder="搜索商品名称 / 编码 / 型号 / 品牌"
               confirm-type="search" />
        <text v-if="keyword" class="search-clear" @click="clearKeyword">清空</text>
      </view>

      <view v-if="loading" class="state"><text>加载中...</text></view>
      <view v-else-if="!rows.length" class="state"><text>没有匹配的物料</text></view>
      <scroll-view v-else scroll-y class="list">
        <view v-for="m in rows" :key="m.id" class="row" @click="pick(m)">
          <view class="row-main">
            <text class="row-name">{{ m.name }}</text>
            <text class="row-sub">
              {{ [m.category, m.brand, m.model].filter(Boolean).join(' · ') }}
            </text>
            <text v-if="m.number" class="row-code">编码 {{ m.number }}</text>
          </view>
          <text class="row-unit">{{ m.unit || '' }}</text>
        </view>
        <view v-if="rows.length < total" class="more" @click="loadMore">
          <text>{{ loadingMore ? '加载中...' : '加载更多' }}</text>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import FieldChips from '@/components/FieldChips.vue'
import { api } from '@/api/index'

/**
 * 物料选择弹层：类别标签 + 名称关键字搜索 + 分页加载。
 * 进入时保留步骤筛选区的类别与关键字，便于连续添加同类物料。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  categories: { type: Array, default: () => [] },
  category: { type: String, default: '' },
  keyword: { type: String, default: '' }
})
const emit = defineEmits(['update:visible', 'select'])

const PAGE_SIZE = 20
const selectedCategory = ref('')
const keyword = ref('')
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)
const loadingMore = ref(false)
let searchTimer = null
let requestToken = 0

const categoryOptions = computed(() => [
  { value: '', label: '全部类别' },
  ...(props.categories || []).map(c => ({ value: c.name, label: c.name }))
])

watch(() => props.visible, (visible) => {
  if (!visible) return
  selectedCategory.value = props.category || ''
  keyword.value = props.keyword || ''
  load(true)
})

watch([selectedCategory, keyword], () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => load(true), 300)
})

function setCategory(value) {
  selectedCategory.value = value || ''
}

function clearKeyword() {
  keyword.value = ''
}

async function load(reset) {
  if (reset) {
    pageNum.value = 1
    loading.value = true
  } else {
    loadingMore.value = true
  }
  const token = ++requestToken
  try {
    const res = await api.materialSearch({
      category: selectedCategory.value || undefined,
      keyword: String(keyword.value || '').trim() || undefined,
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE
    })
    if (token !== requestToken) return
    total.value = Number(res && res.total) || 0
    const records = (res && res.records) || []
    rows.value = reset ? records : rows.value.concat(records)
  } catch (e) {
    if (token === requestToken) uni.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
  } finally {
    if (token === requestToken) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

function loadMore() {
  if (loadingMore.value || rows.value.length >= total.value) return
  pageNum.value += 1
  load(false)
}

function pick(material) {
  emit('select', material)
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
.category-wrap { margin-top: 16rpx; max-height: 200rpx; overflow-y: auto; }
.search-row {
  margin-top: 16rpx; display: flex; align-items: center;
  background: #f5f5f5; border-radius: 12rpx; padding: 0 18rpx;
}
.search-input { flex: 1; height: 72rpx !important; min-height: 72rpx !important; font-size: 27rpx; color: #333; }
.search-clear { font-size: 23rpx; color: #999; padding-left: 12rpx; }
.list { margin-top: 12rpx; max-height: 56vh; }
.row {
  display: flex; align-items: center; gap: 16rpx;
  padding: 20rpx 8rpx; border-bottom: 1rpx solid #F2F3F5;
}
.row:active { background: #FFF8F4; }
.row-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.row-name { font-size: 28rpx; color: #1D2129; font-weight: 600; }
.row-sub { font-size: 23rpx; color: #86909C; }
.row-code { font-size: 21rpx; color: #C9CDD4; }
.row-unit { font-size: 24rpx; color: #4E5969; flex-shrink: 0; }
.more { padding: 24rpx 0; text-align: center; font-size: 25rpx; color: #FF6B35; }
.state { padding: 60rpx 0; text-align: center; font-size: 25rpx; color: #86909C; }
</style>
