<template>
  <view class="page">
    <view class="logo-area">
      <text class="logo">🔥</text>
      <text class="title">创建账号</text>
      <text class="subtitle">注册即送200积分</text>
    </view>

    <!-- 微信一键授权区域 -->
    <view class="form">
      <view class="wechat-section">
        <view class="wechat-icon">💬</view>
        <text class="wechat-title">微信一键授权</text>
        <text class="wechat-desc">安全获取手机号，无需手动输入</text>

        <!-- 正式：微信授权按钮（开放标签，需认证公众号） -->
        <!-- #ifdef H5 -->
        <view v-if="!phoneObtained" class="wechat-btn" @click="requestWechatAuth">
          微信授权获取手机号
        </view>
        <view v-else class="phone-display">
          <text class="phone-label">已获取手机号</text>
          <text class="phone-value">{{ phone }}</text>
        </view>
        <!-- #endif -->
      </view>
    </view>

    <!-- 密码设置（手机号获取后出现） -->
    <view v-if="phoneObtained" class="form">
      <text class="label">设置密码</text>
      <input class="input" v-model="password" type="password" placeholder="6-20位，建议字母+数字"/>

      <text class="label">确认密码</text>
      <input class="input" v-model="confirmPwd" type="password" placeholder="请再次输入密码"/>

      <view class="strength">
        <view class="bar" :class="strengthClass" />
        <text class="strength-text">{{ strengthText }}</text>
      </view>

      <text class="label">昵称（可选）</text>
      <input class="input" v-model="nickname" placeholder="给自己起个名字吧"/>

      <view class="register-btn" @click="doRegister">完成注册</view>
    </view>

    <view class="switch-link" @click="toLogin">
      已有账号？<text class="link">去登录</text>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/index'

const phone       = ref('')
const password    = ref('')
const confirmPwd  = ref('')
const nickname    = ref('')
const loading     = ref(false)
const phoneObtained = ref(false)

// 密码强度
const strengthClass = computed(() => {
  const v = password.value
  if (!v) return ''
  if (v.length < 6) return 'weak'
  const hasNum  = /\d/.test(v)
  const hasChar = /[a-zA-Z]/.test(v)
  const hasSym  = /[!@#$%^&*(),.?":{}|<>_]/.test(v)
  if (hasSym && hasChar && hasNum) return 'strong'
  if ((hasNum && hasChar) || v.length >= 8) return 'medium'
  return 'weak'
})

const strengthText = computed(() => {
  const m = { weak:'弱 — 建议加上字母', medium:'中', strong:'强' }
  return m[strengthClass.value] || ''
})

// 开发模式：从 URL 参数读取 mock 手机号
onMounted(() => {
  // #ifdef H5
  const params = new URLSearchParams(location.search)
  const mockPhone = params.get('mockPhone')
  if (mockPhone && /^1[3-9]\d{9}$/.test(mockPhone)) {
    phone.value = mockPhone
    phoneObtained.value = true
    uni.showToast({title:'开发模式：已注入 ' + mockPhone, icon:'none', duration:2000})
  }
  // #endif
})

// 微信授权获取手机号
async function requestWechatAuth() {
  // #ifdef H5
  // 正式环境：调用 JSSDK 拉起手机号授权
  // 测试环境：直接使用 mock phone
  try {
    const config = await api.getPublicAccountConfig()
    if (config && config.debug) {
      // 测试号无手机号授权能力 — 使用 mock
      const mockPhone = '13800138000'
      phone.value = mockPhone
      phoneObtained.value = true
      uni.showToast({title:'测试模式：已注入 ' + mockPhone, icon:'none'})
      return
    }

    // 正式环境：wx-open-subscribe 或 jssdk 手机号组件
    // TODO: 对接微信手机号组件，获取 encryptedData + iv + code
    uni.showToast({title:'请通过微信一键授权获取手机号', icon:'none'})
  } catch (e) {
    uni.showToast({title:'授权失败，请重试', icon:'none'})
  }
  // #endif
}

async function doRegister() {
  if (loading.value) return
  if (!password.value) { uni.showToast({title:'请输入密码',icon:'none'}); return }
  if (password.value.length < 6) { uni.showToast({title:'密码至少6位',icon:'none'}); return }
  if (password.value !== confirmPwd.value) { uni.showToast({title:'两次密码不一致',icon:'none'}); return }

  loading.value = true
  try {
    // 开发模式下传入 mockPhone
    const payload = {
      password: password.value,
      confirmPassword: confirmPwd.value,
      nickname: nickname.value || undefined,
    }
    // #ifdef H5
    const params = new URLSearchParams(location.search)
    if (params.get('mockPhone')) payload.mockPhone = params.get('mockPhone')
    else payload.mockPhone = phone.value // 测试模式 fallback
    // #endif

    const r = await api.register(payload)
    uni.showToast({title:'注册成功！',icon:'success'})
    // 自动登录
    await api.login(phone.value || r.phone, password.value)
    setTimeout(() => uni.switchTab({url:'/pages/index/index'}), 600)
  } catch (e) {
    uni.showToast({title:e.msg||'注册失败',icon:'none'})
  } finally { loading.value = false }
}

function toLogin() {
  uni.navigateBack()
}
</script>

<style scoped>
.page{min-height:100vh;background:linear-gradient(180deg,#FF6B35 0%,#FF8C5A 40%,#f5f5f5 40%);padding:60rpx 40rpx}
.logo-area{text-align:center;margin-bottom:60rpx}
.logo{font-size:80rpx}
.title{font-size:40rpx;color:#fff;font-weight:700;display:block;margin-top:16rpx}
.subtitle{font-size:26rpx;color:rgba(255,255,255,.8);margin-top:8rpx;display:block}
.form{background:#fff;border-radius:24rpx;padding:40rpx;margin-bottom:24rpx}
.wechat-section{text-align:center;padding:20rpx 0}
.wechat-icon{font-size:60rpx;margin-bottom:12rpx}
.wechat-title{font-size:30rpx;font-weight:600;color:#333;display:block;margin-bottom:8rpx}
.wechat-desc{font-size:24rpx;color:#999;display:block;margin-bottom:24rpx}
.wechat-btn{background:linear-gradient(135deg,#07C160,#05A84E);color:#fff;text-align:center;padding:24rpx;border-radius:48rpx;font-size:28rpx;font-weight:600;margin:0 20rpx}
.wechat-btn:active{opacity:.85}
.phone-display{text-align:center;padding:20rpx 0}
.phone-label{font-size:24rpx;color:#999;display:block}
.phone-value{font-size:36rpx;color:#333;font-weight:700;display:block;margin-top:8rpx}
.label{font-size:28rpx;color:#333;display:block;margin:20rpx 0 12rpx}
.label:first-child{margin-top:0}
.input{width:100%;background:#f5f5f5;border-radius:12rpx;padding:24rpx;font-size:30rpx;box-sizing:border-box}
.strength{display:flex;align-items:center;gap:12rpx;margin-top:16rpx}
.bar{flex:1;height:8rpx;border-radius:4rpx;background:#eee;transition:all .3s}
.bar.weak{background:#f56c6c}
.bar.medium{background:#e6a23c}
.bar.strong{background:#67c23a}
.strength-text{font-size:22rpx;color:#999;flex-shrink:0}
.register-btn{background:#FF6B35;color:#fff;text-align:center;padding:28rpx;border-radius:48rpx;margin-top:40rpx;font-size:32rpx;font-weight:600}
.register-btn:active{opacity:.8}
.switch-link{text-align:center;font-size:26rpx;color:#999}
.link{color:#FF6B35}
</style>
