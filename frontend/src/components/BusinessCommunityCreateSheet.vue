<template>
  <view v-if="visible" class="cc-mask" @click="close">
    <view class="cc-sheet" @click.stop>
      <view class="sheet-head">
        <text class="sheet-title">新建小区</text>
        <text class="sheet-close" @click="close">×</text>
      </view>

      <view class="sheet-body">
        <text class="label">小区名称 <text class="req">*</text></text>
        <input class="input" :value="name" maxlength="200"
               placeholder="请输入小区名称" @input="onInput" />
        <view class="district-row">
          <text class="district-label">归属区县</text>
          <text class="district-value">{{ districtText }}</text>
        </view>
        <text v-if="error" class="ferr">{{ error }}</text>
        <text v-if="!districtConfigured" class="ferr">
          当前组织未配置所在区县，请联系管理员完善组织信息后再新建小区
        </text>
        <text v-else class="hint">新小区将自动关联到本组织所在区县，创建后自动选中</text>
      </view>

      <view class="sheet-foot">
        <view class="btn ghost" @click="close"><text>取消</text></view>
        <view class="btn primary" :class="{ disabled: submitting || !districtConfigured }" @click="submit">
          <text>{{ submitting ? '创建中...' : '创建并选择' }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '@/api/index'

/**
 * 员工制单新建小区：名称必填，组织与区县由服务端按 JWT / 组织配置派生。
 * 创建成功 emit created（{id, name, regionId, regionName, existed}），由父组件加入选项并选中。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  initialName: { type: String, default: '' },
  districtName: { type: String, default: '' },
  districtConfigured: { type: Boolean, default: true }
})
const emit = defineEmits(['update:visible', 'created'])

const name = ref('')
const error = ref('')
const submitting = ref(false)

/** 区县名称可能带省/市路径，只展示末级（如「广河县」）。 */
function shortRegionName(value) {
  const parts = String(value || '').split('/').filter(Boolean)
  return parts.length ? parts[parts.length - 1] : ''
}

const districtText = computed(() => {
  if (!props.districtConfigured) return '未配置'
  const name = shortRegionName(props.districtName)
  return name ? name + '（自动关联）' : '组织所在区县（自动关联）'
})

watch(() => props.visible, value => {
  if (value) {
    name.value = props.initialName || ''
    error.value = ''
  }
})

function onInput(event) {
  name.value = String(event.detail.value ?? '')
  error.value = ''
}

async function submit() {
  if (submitting.value) return
  if (!props.districtConfigured) {
    error.value = '当前组织未配置所在区县，请联系管理员完善组织信息'
    return
  }
  const value = String(name.value || '').trim()
  if (!value) {
    error.value = '请输入小区名称'
    return
  }
  if (value.length > 200) {
    error.value = '小区名称不能超过200个字符'
    return
  }
  submitting.value = true
  try {
    const created = await api.employeeCreateCommunity({ name: value })
    const shortRegion = shortRegionName(created && created.regionName)
    const title = created && created.existed
      ? '该小区已存在，已为您选中'
      : (shortRegion ? '小区创建成功，已关联至' + shortRegion : '小区创建成功')
    uni.showToast({ title, icon: 'none' })
    emit('created', created || {})
    close()
  } catch (e) {
    error.value = (e && e.msg) || '新建小区失败，请重试'
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
}
</script>

<style scoped>
.cc-mask {
  position: fixed; inset: 0; z-index: 210;
  background: rgba(0, 0, 0, .42);
  display: flex; align-items: flex-end;
}
.cc-sheet {
  width: 100%; background: #fff; border-radius: 24rpx 24rpx 0 0;
  display: flex; flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom);
}
.sheet-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 26rpx 28rpx 18rpx; border-bottom: 1rpx solid #f2f2f2;
}
.sheet-title { font-size: 31rpx; font-weight: 700; color: #1D2129; }
.sheet-close { font-size: 44rpx; color: #999; line-height: 1; padding: 0 8rpx; }
.sheet-body { padding: 24rpx 28rpx 8rpx; }
.label { display: block; font-size: 25rpx; color: #666; margin-bottom: 12rpx; }
.req { color: #e53935; }
.input {
  width: 100%; height: 84rpx !important; min-height: 84rpx !important;
  background: #f7f7f7; border-radius: 12rpx; padding: 0 20rpx;
  font-size: 29rpx; color: #1D2129; box-sizing: border-box;
}
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 10rpx; }
.district-row {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 18rpx; padding: 18rpx 20rpx; background: #f7f7f7; border-radius: 12rpx;
}
.district-label { font-size: 25rpx; color: #666; }
.district-value { font-size: 26rpx; color: #1D2129; font-weight: 600; }
.hint { display: block; font-size: 21rpx; color: #999; margin-top: 10rpx; line-height: 1.5; }
.sheet-foot {
  display: flex; gap: 18rpx; padding: 20rpx 28rpx 22rpx;
  border-top: 1rpx solid #f2f2f2;
}
.btn {
  flex: 1; height: 82rpx; line-height: 82rpx; text-align: center;
  border-radius: 41rpx; font-size: 28rpx; font-weight: 600;
}
.btn.ghost { background: #fff; color: #FF6B35; border: 2rpx solid #FF6B35; flex: 0 0 34%; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn.primary.disabled { opacity: .6; }
</style>
