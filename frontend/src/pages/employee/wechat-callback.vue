<template>
  <view class="page">
    <view class="card">
      <view v-if="loading" class="state">
        <view class="spinner"></view>
        <text class="title">{{ mode === 'bind' ? '正在绑定微信...' : '正在登录...' }}</text>
        <text class="sub">请稍候，不要关闭页面</text>
      </view>

      <view v-else class="state">
        <text class="icon">{{ success ? '✅' : '⚠️' }}</text>
        <text class="title">{{ success ? successText : '授权未完成' }}</text>
        <text class="sub">{{ message }}</text>
        <view class="btns">
          <view v-if="!success && mode !== 'bind'" class="btn primary" @click="goPassword">使用密码登录</view>
          <view class="btn" @click="goBack">{{ success ? '继续' : '返回' }}</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/index'
import { saveEmployeeSession, isEmployeeLoggedIn } from '@/utils/employeeSession'

const loading = ref(true)
const success = ref(false)
const successText = ref('')
const message = ref('')
const mode = ref('login')

onMounted(handleCallback)

async function handleCallback() {
  const params = readCallbackParams()
  mode.value = params.mode

  if (!params.code || !params.state) {
    finishFail('授权信息已失效，请重新发起微信授权')
    return
  }
  // 服务端 state 一次性校验之外，再校验本浏览器发起时记录的 state，防登录 CSRF
  if (!params.expected || params.expected !== params.state) {
    finishFail('授权状态已失效，请重新发起微信授权')
    return
  }

  try {
    if (params.mode === 'bind') {
      if (!isEmployeeLoggedIn()) {
        finishFail('登录已过期，请重新登录后再绑定微信')
        return
      }
      await api.employeeWechatBind({ code: params.code, state: params.state })
      success.value = true
      successText.value = '微信绑定成功'
      message.value = '下次可直接使用微信授权登录员工工作台'
      setTimeout(() => {
        uni.reLaunch({ url: '/pages/employee/profile' })
      }, 900)
      return
    }

    const r = await api.employeeWechatLogin({ code: params.code, state: params.state })
    saveEmployeeSession(r, 'wechat')
    success.value = true
    successText.value = '登录成功'
    message.value = '正在进入员工工作台...'
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/employee/dashboard' })
    }, 700)
  } catch (e) {
    finishFail(e.msg || '微信授权失败，请重新发起')
  } finally {
    loading.value = false
  }
}

/** 读取 App.vue 落盘的 code/state；兼容直接打开回调地址的情况。 */
function readCallbackParams() {
  let code = uni.getStorageSync('ops_wechat_oauth_code') || ''
  let state = uni.getStorageSync('ops_wechat_oauth_state') || ''
  let mode = uni.getStorageSync('ops_wechat_oauth_mode') || 'login'
  const expected = uni.getStorageSync('ops_wechat_oauth_expected_state') || ''

  // #ifdef H5
  if (!code) {
    try {
      const search = new URLSearchParams(window.location.search)
      code = search.get('code') || ''
      state = search.get('state') || ''
      if (code) {
        try {
          window.history.replaceState(
            {},
            document.title,
            window.location.pathname + window.location.hash
          )
        } catch (_) { /* ignore */ }
      }
    } catch (_) { /* ignore */ }
  }
  // #endif

  const tempKeys = [
    'ops_wechat_oauth_code',
    'ops_wechat_oauth_state',
    'ops_wechat_oauth_mode',
    'ops_wechat_oauth_expected_state'
  ]
  tempKeys.forEach(key => uni.removeStorageSync(key))
  return { code, state, mode, expected }
}

function finishFail(msg) {
  loading.value = false
  success.value = false
  message.value = msg
}

function goPassword() {
  uni.reLaunch({ url: '/pages/employee/login' })
}

function goBack() {
  if (isEmployeeLoggedIn()) {
    uni.reLaunch({ url: mode.value === 'bind' ? '/pages/employee/profile' : '/pages/employee/dashboard' })
  } else {
    uni.reLaunch({ url: '/pages/employee/login' })
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: linear-gradient(160deg, #FF6B35 0%, #E85D2C 45%, #f0f4f8 45%);
  padding: 140rpx 40rpx 40rpx;
}
.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 64rpx 40rpx;
  box-shadow: 0 8rpx 40rpx rgba(0,0,0,.08);
  min-height: 420rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.state { display: flex; flex-direction: column; align-items: center; width: 100%; }
.icon { font-size: 88rpx; line-height: 1; }
.title { font-size: 36rpx; font-weight: 700; color: #333; margin-top: 28rpx; text-align: center; }
.sub {
  font-size: 26rpx;
  color: #909399;
  margin-top: 16rpx;
  line-height: 1.7;
  text-align: center;
  padding: 0 12rpx;
  word-break: break-all;
}
.spinner {
  width: 72rpx;
  height: 72rpx;
  border: 6rpx solid #ffe3d8;
  border-top-color: #FF6B35;
  border-radius: 50%;
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.btns { display: flex; gap: 24rpx; margin-top: 48rpx; width: 100%; }
.btn {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  text-align: center;
  border-radius: 42rpx;
  background: #f5f7fa;
  color: #666;
  font-size: 28rpx;
}
.btn.primary { background: linear-gradient(135deg, #FF6B35, #FF8C5A); color: #fff; font-weight: 600; }
.btn:active { opacity: .85; }
</style>
