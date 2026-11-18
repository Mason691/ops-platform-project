<template>
  <view class="worker-picker">
    <view class="trigger" @click="open">
      <text v-if="selected" class="selected">
        {{ selected.userName }}（{{ selected.userType === 1 ? '外部' : '内部' }} · {{ selected.phoneMasked }}）
      </text>
      <text v-else class="placeholder">请选择上门员工</text>
      <text class="arrow">›</text>
    </view>
    <text v-if="selected && selected.wechatBound === false" class="warn">
      该施工员未绑定微信，可能无法接收任务通知
    </text>

    <view v-if="visible" class="mask" @click.self="visible = false">
      <view class="sheet">
        <view class="head">
          <text class="title">选择施工员</text>
          <text class="close" @click="visible = false">×</text>
        </view>
        <input v-model="keyword" class="search" placeholder="搜索姓名 / 手机号"
               confirm-type="search" @input="onInput" />
        <scroll-view scroll-y class="list">
          <view v-for="worker in workers" :key="worker.userId" class="row" @click="choose(worker)">
            <view class="row-main">
              <text class="name">{{ worker.userName }}</text>
              <text class="tag" :class="{ external: worker.userType === 1 }">
                {{ worker.userType === 1 ? '外部' : '内部' }}
              </text>
              <text v-if="worker.workerRole" class="tag worker">施工员</text>
              <text class="tag" :class="{ unbound: !worker.wechatBound }">
                {{ worker.wechatBound ? '已绑微信' : '未绑微信' }}
              </text>
            </view>
            <view class="row-sub">
              <text>{{ worker.phoneMasked || worker.phone || '—' }}</text>
              <text v-if="(worker.roles || []).length">角色：{{ (worker.roles || []).join('、') }}</text>
            </view>
          </view>
          <view v-if="loading" class="empty">加载中…</view>
          <view v-else-if="!workers.length" class="empty">未找到施工员</view>
        </scroll-view>
        <view class="add" @click="createVisible = true">未找到？新增外部施工员</view>
      </view>
    </view>

    <DispatchExternalWorkerDialog
      :visible="createVisible"
      :order-id="orderId"
      :corp-name="corpName"
      @update:visible="createVisible = $event"
      @created="onCreated"
    />
  </view>
</template>

<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { useDispatchWorker } from '@/hooks/useDispatchWorker'
import DispatchExternalWorkerDialog from './DispatchExternalWorkerDialog.vue'

const props = defineProps({
  modelValue: { type: [Number, String], default: null },
  orderId: { type: [Number, String], default: null },
  corpName: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'selected'])

const visible = ref(false)
const createVisible = ref(false)
const selected = ref(null)
const { workers, loading, keyword, search, onKeywordInput, destroy } =
  useDispatchWorker(() => props.orderId)

async function open() {
  visible.value = true
  await search('')
  if (props.modelValue) {
    const found = workers.value.find(w => String(w.userId) === String(props.modelValue))
    if (found) selected.value = found
  }
}

function onInput(event) {
  onKeywordInput(event?.detail?.value ?? keyword.value)
}

function choose(worker) {
  selected.value = worker
  emit('update:modelValue', worker.userId)
  emit('selected', worker)
  visible.value = false
}

function onCreated(result) {
  const worker = {
    userId: result.userId,
    userOrgId: result.userOrgId,
    userName: result.userName,
    phoneMasked: result.phoneMasked,
    userType: 1,
    roles: ['施工员'],
    workerRole: true,
    wechatBound: result.wechatBound,
    createdOnSite: true
  }
  selected.value = worker
  emit('update:modelValue', worker.userId)
  emit('selected', worker)
  createVisible.value = false
  visible.value = false
  search('')
}

onBeforeUnmount(destroy)
</script>

<style scoped>
.trigger { display: flex; align-items: center; justify-content: space-between;
  min-height: 76rpx; padding: 0 20rpx; background: #F7F8FA; border-radius: 10rpx; }
.selected { font-size: 27rpx; color: #1D2129; }
.placeholder { font-size: 27rpx; color: #A9AEB8; }
.arrow { color: #A9AEB8; font-size: 34rpx; }
.warn { display: block; margin-top: 8rpx; color: #B26A00; font-size: 22rpx; }
.mask { position: fixed; inset: 0; z-index: 999; background: rgba(0,0,0,.45);
  display: flex; align-items: flex-end; }
.sheet { width: 100%; max-height: 82vh; background: #fff; border-radius: 24rpx 24rpx 0 0;
  padding: 24rpx 28rpx calc(24rpx + env(safe-area-inset-bottom)); display: flex; flex-direction: column; }
.head { display: flex; justify-content: space-between; align-items: center; }
.title { font-size: 32rpx; font-weight: 600; color: #1D2129; }
.close { font-size: 44rpx; color: #86909C; line-height: 1; }
.search { height: 76rpx; background: #F7F8FA; border-radius: 10rpx; padding: 0 20rpx;
  margin-top: 16rpx; font-size: 27rpx; }
.list { flex: 1; min-height: 200rpx; max-height: 56vh; margin-top: 12rpx; }
.row { padding: 18rpx 4rpx; border-bottom: 1rpx solid #F2F3F5; }
.row-main { display: flex; align-items: center; flex-wrap: wrap; gap: 10rpx; }
.name { font-size: 28rpx; font-weight: 600; color: #1D2129; }
.tag { font-size: 20rpx; color: #4E5969; background: #F2F3F5; border-radius: 6rpx; padding: 2rpx 10rpx; }
.tag.external { background: #FFF3E8; color: #D25F00; }
.tag.worker { background: #E8F8E8; color: #0B7A42; }
.tag.unbound { background: #FFF1F0; color: #CB2634; }
.row-sub { display: flex; gap: 16rpx; margin-top: 6rpx; font-size: 22rpx; color: #86909C; }
.empty { padding: 40rpx 0; text-align: center; color: #A9AEB8; font-size: 24rpx; }
.add { margin-top: 12rpx; padding: 20rpx 0; text-align: center; color: #FF6B35; font-size: 26rpx; }
</style>
