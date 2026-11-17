<template>
  <view v-if="visible" class="sheet-root">
    <view class="sheet-mask" @click="close"></view>
    <view class="sheet-panel">
      <view class="sheet-head">
        <text class="sheet-title">{{ title }}</text>
        <text class="sheet-close" @click="close">✕</text>
      </view>
      <view v-if="searchable" class="search-row">
        <input class="search-input" v-model="keyword" :placeholder="placeholder"
               confirm-type="search" />
        <text v-if="keyword" class="search-clear" @click="keyword = ''">清空</text>
      </view>
      <scroll-view scroll-y class="sheet-list" :style="{ maxHeight: listMaxHeight }">
        <view v-for="option in filtered" :key="String(option.value)"
              class="sheet-item" :class="{ on: isSelected(option.value) }"
              @click="select(option)">
          <view class="item-main">
            <text class="item-label">{{ option.label }}</text>
            <text v-if="option.desc" class="item-desc">{{ option.desc }}</text>
          </view>
          <text v-if="isSelected(option.value)" class="item-check">✓</text>
        </view>
        <view v-if="!filtered.length" class="sheet-empty">
          <text>{{ keyword ? '没有匹配的选项' : emptyText }}</text>
        </view>
      </scroll-view>
      <view class="sheet-safe"></view>
    </view>
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { lockBodyScroll, unlockBodyScroll } from '@/utils/bodyScrollLock'

/**
 * 底部弹层选择器（选项多时使用）：支持搜索、选中标记、安全区适配。
 * 通过 v-if 渲染，打开/关闭使用 CSS 动画，避免 uni-app 小程序端
 * 不支持 <transition> 的问题。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '请选择' },
  options: { type: Array, default: () => [] },
  modelValue: { type: [String, Number], default: null },
  searchable: { type: Boolean, default: true },
  placeholder: { type: String, default: '搜索' },
  emptyText: { type: String, default: '暂无可选项' },
  listMaxHeight: { type: String, default: '58vh' }
})
const emit = defineEmits(['update:visible', 'select', 'close'])

const keyword = ref('')
let bodyLocked = false

const filtered = computed(() => {
  const word = keyword.value.trim().toLowerCase()
  const options = (props.options || []).filter(option => option
    && option.value !== undefined && option.value !== null)
  if (!word) return options
  return options.filter(option => {
    const text = [option.label, option.desc].filter(Boolean).join(' ').toLowerCase()
    return text.includes(word)
  })
})

watch(() => props.visible, visible => {
  if (visible) keyword.value = ''
  if (visible && !bodyLocked) {
    bodyLocked = true
    lockBodyScroll()
  } else if (!visible && bodyLocked) {
    bodyLocked = false
    unlockBodyScroll()
  }
}, { immediate: true })

function isSelected(value) {
  return props.modelValue !== null && props.modelValue !== undefined
    && String(props.modelValue) === String(value)
}

function select(option) {
  emit('select', option)
  close()
}

function close() {
  emit('update:visible', false)
  emit('close')
}

onBeforeUnmount(() => {
  // 弹层在打开状态下被卸载（切步/回退）时释放自己的滚动锁
  if (bodyLocked) {
    bodyLocked = false
    unlockBodyScroll()
  }
})
</script>

<style scoped>
.sheet-root { position: fixed; inset: 0; z-index: 300; }
.sheet-mask {
  position: absolute; inset: 0; background: rgba(17, 17, 17, .45);
  animation: maskIn .2s ease-out;
}
.sheet-panel {
  position: absolute; left: 0; right: 0; bottom: 0;
  background: #fff; border-radius: 24rpx 24rpx 0 0;
  animation: panelUp .24s cubic-bezier(.22, .61, .36, 1);
  display: flex; flex-direction: column; max-height: 82vh;
}
.sheet-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 28rpx 32rpx 16rpx;
}
.sheet-title { font-size: 32rpx; font-weight: 700; color: #333; }
.sheet-close { font-size: 30rpx; color: #999; padding: 8rpx 8rpx 8rpx 24rpx; }
.search-row {
  display: flex; align-items: center; margin: 0 32rpx 12rpx;
  background: #f5f5f5; border-radius: 12rpx; padding: 0 20rpx;
}
.search-input { flex: 1; height: 72rpx !important; min-height: 72rpx !important; font-size: 27rpx; color: #333; }
.search-clear { font-size: 24rpx; color: #999; padding-left: 16rpx; }
.sheet-list { padding: 0 32rpx 0 24rpx; }
.sheet-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 24rpx 8rpx; border-bottom: 1rpx solid #f2f2f2;
}
.sheet-item:active { background: #fafafa; }
.item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.item-label {
  font-size: 28rpx; color: #333; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.item-desc { font-size: 23rpx; color: #999; margin-top: 4rpx; }
.item-check { font-size: 30rpx; color: #FF6B35; margin-left: 16rpx; }
.sheet-item.on .item-label { color: #FF6B35; font-weight: 600; }
.sheet-empty { padding: 48rpx 0; text-align: center; font-size: 26rpx; color: #999; }
.sheet-safe { height: env(safe-area-inset-bottom); }
@keyframes panelUp {
  from { transform: translateY(24%); opacity: .4; }
  to { transform: translateY(0); opacity: 1; }
}
@keyframes maskIn { from { opacity: 0; } to { opacity: 1; } }
</style>
