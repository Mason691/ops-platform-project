<template>
  <view v-if="enabled && types.length" class="add-biz">
    <view class="add-biz-btn" hover-class="add-biz-btn-active" :hover-stay-time="80" @click="open">
      <text class="add-biz-plus">＋</text>
      <text class="add-biz-text">{{ buttonText }}</text>
    </view>

    <BottomSheetSelect
      v-model:visible="sheetVisible"
      title="选择业务类型"
      :options="sheetOptions"
      :searchable="false"
      list-max-height="42vh"
      @select="pick"
    />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import BottomSheetSelect from '@/components/BottomSheetSelect.vue'
import { useBusinessTypes } from '@/hooks/useBusinessTypes'
import { track } from '@/utils/track'

/**
 * 工作台主行动入口。
 * - 单业务类型：点击直接进入新增页，不弹层（当前仅「业务数据订单」）；
 * - 多业务类型：底部弹层选择（复用 BottomSheetSelect，非原生 alert/confirm）；
 * - enabled=false（组织无任何菜单权限）时整体隐藏，避免出现点不动的死按钮。
 */
const props = defineProps({
  enabled: { type: Boolean, default: true }
})

const { types, buttonText } = useBusinessTypes()
const sheetVisible = ref(false)

const sheetOptions = computed(() => types.value.map(type => ({
  value: type.code,
  label: type.name,
  desc: type.desc || ''
})))

function open() {
  if (!props.enabled || !types.value.length) return
  track('add_business_click', {
    count: types.value.length,
    businessTypes: types.value.map(type => type.code)
  })
  if (types.value.length === 1) {
    go(types.value[0])
    return
  }
  sheetVisible.value = true
}

function pick(option) {
  const type = types.value.find(item => item.code === option.value)
  if (type) go(type)
}

function go(type) {
  uni.navigateTo({ url: type.route })
}

defineExpose({ open })
</script>

<style scoped>
.add-biz {
  margin: 24rpx 24rpx 0;
}
.add-biz-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  height: 96rpx;
  border-radius: 48rpx;
  background: linear-gradient(135deg, #FF8C5A, #FF6B35);
  box-shadow: 0 10rpx 24rpx rgba(255, 107, 53, .28);
  transition: transform .12s ease, box-shadow .12s ease;
}
.add-biz-btn-active {
  transform: scale(.98);
  box-shadow: 0 4rpx 12rpx rgba(255, 107, 53, .22);
}
.add-biz-plus {
  font-size: 36rpx;
  font-weight: 700;
  color: #fff;
  line-height: 1;
}
.add-biz-text {
  font-size: 32rpx;
  font-weight: 700;
  color: #fff;
  letter-spacing: 2rpx;
}
</style>
