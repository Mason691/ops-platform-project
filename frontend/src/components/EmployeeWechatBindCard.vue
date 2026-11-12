<template>
  <view class="bind-card">
    <view class="bind-row">
      <text class="bind-label">微信绑定</text>
      <view class="bind-status">
        <text v-if="loading" class="status-muted">查询中...</text>
        <text v-else-if="error" class="status-error">查询失败</text>
        <text v-else-if="!configured" class="status-muted">未配置公众号</text>
        <text v-else-if="bound" class="status-ok">已绑定</text>
        <text v-else class="status-muted">未绑定</text>
      </view>
    </view>

    <text v-if="bound && bindTime" class="bind-meta">绑定时间：{{ bindTime }}</text>
    <text v-else-if="bound" class="bind-meta">可使用微信授权登录员工工作台</text>
    <text v-else-if="configured && !loading && !error" class="bind-meta">绑定后可在微信中一键登录</text>

    <view
      v-if="configured && !bound && !loading && !error"
      class="bind-btn"
      :class="{ disabled: binding }"
      @click="startBind"
    >
      <text>{{ binding ? '正在跳转微信...' : '绑定微信' }}</text>
    </view>

    <text v-if="bound" class="bind-meta">如需解绑，请联系管理员在底座平台操作</text>
    <text v-if="error" class="bind-error">{{ error }}</text>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/index'
import { isWechat } from '@/utils/jssdk'
import { isEmployeeLoggedIn } from '@/utils/employeeSession'

const loading = ref(true)
const binding = ref(false)
const configured = ref(true)
const bound = ref(false)
const bindTime = ref('')
const error = ref('')

onMounted(loadStatus)

async function loadStatus() {
  if (!isEmployeeLoggedIn()) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const r = await api.employeeWechatBindStatus()
    configured.value = r.configured !== false
    bound.value = !!r.bound
    bindTime.value = Array.isArray(r.binds) && r.binds.length ? (r.binds[0].bindTime || '') : ''
    if (r.error) error.value = r.error
  } catch (e) {
    error.value = e.msg || '绑定状态查询失败'
  } finally {
    loading.value = false
  }
}

async function startBind() {
  if (binding.value) return
  error.value = ''
  if (!isWechat()) {
    error.value = '请在微信中打开本页面绑定微信'
    return
  }
  binding.value = true
  try {
    const r = await api.employeeWechatAuthorizeUrl('bind')
    if (!r || !r.authorizeUrl) {
      throw new Error('未获取到微信授权地址')
    }
    uni.setStorageSync('ops_wechat_oauth_mode', 'bind')
    // 校验回调 state 与本浏览器发起时一致，防绑定 CSRF
    uni.setStorageSync('ops_wechat_oauth_expected_state', r.state || '')
    // #ifdef H5
    window.location.href = r.authorizeUrl
    // 导航被 WebView 拦截时恢复按钮，避免一直 loading
    setTimeout(() => { binding.value = false }, 4000)
    // #endif
  } catch (e) {
    binding.value = false
    error.value = e.msg || e.message || '微信绑定发起失败'
  }
}
</script>

<style scoped>
.bind-card {
  margin: 24rpx;
  background: #fff;
  border-radius: 16rpx;
  padding: 28rpx 32rpx;
}
.bind-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.bind-label { font-size: 28rpx; color: #666; }
.status-ok { font-size: 26rpx; color: #07C160; font-weight: 600; }
.status-muted { font-size: 26rpx; color: #999; }
.status-error { font-size: 26rpx; color: #e74c3c; }
.bind-meta {
  display: block;
  font-size: 23rpx;
  color: #999;
  line-height: 1.7;
  margin-top: 10rpx;
}
.bind-btn {
  margin-top: 22rpx;
  height: 78rpx;
  line-height: 78rpx;
  text-align: center;
  border-radius: 39rpx;
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  color: #fff;
  font-size: 27rpx;
  font-weight: 600;
}
.bind-btn:active { opacity: .85; }
.bind-btn.disabled { opacity: .7; pointer-events: none; }
.bind-error { display: block; font-size: 23rpx; color: #e74c3c; margin-top: 12rpx; line-height: 1.6; }
</style>
