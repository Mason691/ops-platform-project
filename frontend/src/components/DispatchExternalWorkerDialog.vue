<template>
  <view v-if="visible" class="mask" @click.self="close">
    <view class="dialog">
      <view class="head">
        <text class="title">新增外部施工员</text>
        <text class="close" @click="close">×</text>
      </view>

      <view class="field">
        <text class="label">手机号 <text class="req">*</text></text>
        <input v-model="form.phone" class="input" type="number" maxlength="11"
               placeholder="请输入11位手机号" @blur="checkPhone(form.phone)" />
        <text v-if="checking" class="hint">正在检查手机号…</text>
      </view>
      <view class="field">
        <text class="label">姓名 <text class="req">*</text></text>
        <input v-model="form.name" class="input" maxlength="50" placeholder="请输入姓名" />
      </view>
      <view class="field"><text class="label">所属组织</text>
        <view class="readonly">{{ corpName || '当前订单组织' }}</view>
      </view>
      <view class="field"><text class="label">角色</text>
        <view class="readonly">施工员（自动授予）</view>
      </view>
      <view class="field"><text class="label">用户类型</text>
        <view class="readonly">外部用户</view>
      </view>

      <view v-if="displayMessage" class="alert" :class="alertClass">
        <text>{{ displayMessage }}</text>
      </view>

      <view class="actions">
        <button class="btn ghost" @click="close">取消</button>
        <button class="btn primary" :disabled="!canSubmit" @click="submit">
          {{ creating ? '创建中...' : '创建并选中' }}
        </button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'
import { useExternalWorkerCreate } from '@/hooks/useExternalWorkerCreate'

const props = defineProps({
  visible: { type: Boolean, default: false },
  orderId: { type: [Number, String], default: null },
  corpName: { type: String, default: '' }
})
const emit = defineEmits(['update:visible', 'created'])

const form = reactive({ phone: '', name: '' })
watch(() => props.visible, visible => {
  if (!visible) return
  form.phone = ''
  form.name = ''
  checkResult.value = null
})
const { checking, creating, checkResult, checkPhone, create } =
  useExternalWorkerCreate(() => props.orderId)

const blocked = computed(() => {
  const r = checkResult.value
  if (!r) return false
  if (r.errorCode) return true
  return r.exists && !r.reusable && !r.deleted
})
const canSubmit = computed(() => !checking.value && !creating.value && !blocked.value)
const alertClass = computed(() => blocked.value ? 'error' : 'info')
const displayMessage = computed(() => {
  const r = checkResult.value
  if (!r) return ''
  if (blocked.value) return r.message || '该手机号不能作为外部施工员新增'
  if (!r.exists) return r.message || '新手机号，将创建外部施工员并授予施工员角色'
  if (r.inCurrentOrg) {
    return r.hasWorkerRole
      ? '该员工已在当前组织，将直接复用并选中'
      : '该员工已在当前组织，将授予“施工员”角色并选中'
  }
  const orgs = (r.orgs || []).map(o => o.corpName).filter(Boolean).join('、')
  return orgs ? `该手机号已在【${orgs}】任职，将加入本组织并授予施工员角色`
    : '该手机号已存在，将复用外部用户并授予施工员角色'
})

function close() {
  emit('update:visible', false)
}

function confirmSameName(message) {
  return new Promise(resolve => {
    uni.showModal({
      title: '同名员工确认',
      content: message,
      confirmText: '确认不是同一人',
      cancelText: '取消',
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
}

async function submit() {
  const phone = String(form.phone || '').trim()
  const name = String(form.name || '').trim()
  if (!/^1\d{10}$/.test(phone)) return toast('手机号格式不正确')
  if (!name || name.length > 50) return toast('姓名需为 1~50 个字符')
  if (blocked.value) return toast(checkResult.value?.message || '该手机号不可用')
  try {
    let result = await create({ phone, name })
    if (result && result.sameNameConfirmRequired) {
      const confirmed = await confirmSameName(result.message || '本组织已存在同名员工')
      if (!confirmed) return
      result = await create({ phone, name, confirmSameName: true })
    }
    if (result && result.errorCode) return toast(result.message || '创建失败')
    toast(result.message || '外部施工员创建成功，已自动选中')
    emit('created', result)
    emit('update:visible', false)
  } catch (e) {
    toast((e && e.msg) || '创建失败，请重试')
  }
}

function toast(title) {
  uni.showToast({ title, icon: 'none', duration: 2500 })
}
</script>

<style scoped>
.mask { position: fixed; inset: 0; z-index: 1000; background: rgba(0,0,0,.45);
  display: flex; align-items: flex-end; }
.dialog { width: 100%; background: #fff; border-radius: 24rpx 24rpx 0 0;
  padding: 28rpx 32rpx calc(28rpx + env(safe-area-inset-bottom)); max-height: 88vh; overflow-y: auto; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16rpx; }
.title { font-size: 32rpx; font-weight: 600; color: #1D2129; }
.close { font-size: 44rpx; color: #86909C; line-height: 1; }
.field { padding: 14rpx 0; border-bottom: 1rpx solid #F2F3F5; }
.label { font-size: 25rpx; color: #4E5969; }
.req { color: #F53F3F; }
.input { margin-top: 10rpx; height: 76rpx; background: #F7F8FA; border-radius: 10rpx;
  padding: 0 20rpx; font-size: 28rpx; }
.readonly { margin-top: 10rpx; font-size: 27rpx; color: #1D2129; }
.hint { display: block; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.alert { margin-top: 16rpx; padding: 16rpx 20rpx; border-radius: 10rpx; font-size: 24rpx; line-height: 1.5; }
.alert.info { background: #F2F3F5; color: #4E5969; }
.alert.error { background: #FFF1F0; color: #CB2634; }
.actions { display: flex; gap: 20rpx; margin-top: 28rpx; }
.btn { flex: 1; height: 84rpx; line-height: 84rpx; border-radius: 42rpx; font-size: 28rpx; border: none; }
.btn.ghost { background: #F2F3F5; color: #4E5969; }
.btn.primary { background: #FF6B35; color: #fff; }
.btn[disabled] { opacity: .5; }
</style>
