<template>
  <view class="wechat-panel">
    <view class="wx-hero">
      <view class="wx-avatar">💬</view>
      <text class="wx-title">微信授权登录</text>
      <text class="wx-sub">使用微信身份快速进入员工工作台</text>
    </view>

    <view class="wx-btn" :class="{ disabled: loading }" @click="startLogin">
      <text v-if="loading" class="btn-loading"></text>
      <text>{{ loading ? '正在跳转微信授权...' : '微信授权登录' }}</text>
    </view>

    <text v-if="error" class="wx-error">{{ error }}</text>

    <view class="wx-tip">
      <text>首次使用请先使用用户名+密码登录，登录后在「我的」中绑定微信。</text>
      <text>绑定后即可在微信中一键登录。</text>
    </view>

    <text class="switch-link" @click="$emit('switch-password')">使用用户名+密码登录</text>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { api } from '@/api/index'
import { isWechat } from '@/utils/jssdk'
import { DEFAULT_CORP_ID } from '@/config'

defineEmits(['switch-password'])

const loading = ref(false)
const error = ref('')

async function startLogin() {
  if (loading.value) return
  error.value = ''
  if (!isWechat()) {
    error.value = '请在微信中打开本页面使用微信授权登录'
    return
  }
  loading.value = true
  try {
    const corpId = uni.getStorageSync('corp_id') || DEFAULT_CORP_ID
    const r = await api.employeeWechatAuthorizeUrl('login', corpId)
    if (!r || !r.authorizeUrl) {
      throw new Error('未获取到微信授权地址')
    }
    uni.setStorageSync('ops_wechat_oauth_mode', 'login')
    // 校验回调 state 与本浏览器发起时一致，防登录 CSRF
    uni.setStorageSync('ops_wechat_oauth_expected_state', r.state || '')
    // #ifdef H5
    window.location.href = r.authorizeUrl
    // 导航被 WebView 拦截时恢复按钮，避免一直 loading
    setTimeout(() => { loading.value = false }, 4000)
    // #endif
  } catch (e) {
    loading.value = false
    error.value = e.msg || e.message || '微信授权登录发起失败'
  }
}
</script>

<style scoped>
.wechat-panel { padding-top: 8rpx; }

.wx-hero { text-align: center; padding: 24rpx 0 40rpx; }
.wx-avatar {
  width: 112rpx;
  height: 112rpx;
  line-height: 112rpx;
  margin: 0 auto 20rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  font-size: 52rpx;
  text-align: center;
}
.wx-title { display: block; font-size: 34rpx; font-weight: 700; color: #333; }
.wx-sub { display: block; font-size: 25rpx; color: #999; margin-top: 10rpx; }

.wx-btn {
  height: 96rpx;
  line-height: 96rpx;
  text-align: center;
  border-radius: 48rpx;
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  color: #fff;
  font-size: 32rpx;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  transition: opacity .2s;
}
.wx-btn:active { opacity: .85; }
.wx-btn.disabled { opacity: .7; pointer-events: none; }

.btn-loading {
  width: 32rpx;
  height: 32rpx;
  border: 3rpx solid rgba(255,255,255,.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin .6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.wx-error {
  display: block;
  margin-top: 20rpx;
  padding: 16rpx 20rpx;
  border-radius: 12rpx;
  background: #fff2f0;
  color: #e53935;
  font-size: 24rpx;
  line-height: 1.6;
  text-align: center;
}

.wx-tip {
  margin-top: 32rpx;
  padding: 20rpx 24rpx;
  background: #f8f9fb;
  border-radius: 14rpx;
  font-size: 23rpx;
  color: #909399;
  line-height: 1.7;
}
.wx-tip text { display: block; }

.switch-link {
  display: block;
  text-align: center;
  margin-top: 32rpx;
  font-size: 26rpx;
  color: #FF6B35;
  font-weight: 500;
}
</style>
