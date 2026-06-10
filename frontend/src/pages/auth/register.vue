<template>
  <view class="page">
    <view class="logo-area">
      <text class="logo">🔥</text>
      <text class="title">创建账号</text>
      <text class="subtitle">注册即送200积分</text>
    </view>

    <view class="form">
      <!-- 手机号 -->
      <text class="label">手机号</text>
      <input class="input" v-model="phone" type="number" maxlength="11" placeholder="请输入手机号"/>

      <!-- 密码 -->
      <text class="label">设置密码</text>
      <input class="input" v-model="password" type="password" placeholder="6-20位，建议字母+数字"/>

      <!-- 确认密码 -->
      <text class="label">确认密码</text>
      <input class="input" v-model="confirmPwd" type="password" placeholder="请再次输入密码"/>

      <!-- 密码强度提示 -->
      <view class="strength">
        <view class="bar" :class="strengthClass" />
        <text class="strength-text">{{ strengthText }}</text>
      </view>

      <!-- 注册按钮 -->
      <view class="register-btn" @click="doRegister">注册</view>

      <!-- 切换登录 -->
      <view class="switch-link" @click="toLogin">
        已有账号？<text class="link">去登录</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { api } from '@/api/index'

const phone       = ref('')
const password    = ref('')
const confirmPwd  = ref('')
const loading     = ref(false)

function validatePhone(v) {
  if (!v) return '请输入手机号'
  if (!/^1[3-9]\d{9}$/.test(v)) return '请输入正确的手机号'
  return ''
}

function validatePassword(v) {
  if (!v) return '请输入密码'
  if (v.length < 6) return '密码长度不能少于6位'
  if (v.length > 20) return '密码长度不能超过20位'
  return ''
}

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

async function doRegister() {
  if (loading.value) return
  const err = validatePhone(phone.value) || validatePassword(password.value)
  if (err) { uni.showToast({title:err,icon:'none'}); return }
  if (password.value !== confirmPwd.value) {
    uni.showToast({title:'两次输入的密码不一致',icon:'none'}); return
  }

  loading.value = true
  try {
    await api.register(phone.value, password.value, confirmPwd.value)
    uni.showToast({title:'注册成功，请登录',icon:'success'})
    setTimeout(() => uni.navigateBack(), 800)
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
.form{background:#fff;border-radius:24rpx;padding:40rpx}
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
.switch-link{text-align:center;margin-top:32rpx;font-size:26rpx;color:#999}
.link{color:#FF6B35}
</style>
