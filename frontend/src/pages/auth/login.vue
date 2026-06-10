<template>
  <view class="page">
    <view class="logo-area">
      <text class="logo">🔥</text>
      <text class="title">金地燃气</text>
      <text class="subtitle">积分商城·精彩活动</text>
    </view>

    <view class="form">
      <!-- 手机号 -->
      <text class="label">手机号</text>
      <input class="input" v-model="phone" type="number" maxlength="11" placeholder="请输入手机号"/>

      <!-- 密码 -->
      <text class="label">密码</text>
      <input class="input" v-model="password" type="password" placeholder="请输入密码"/>

      <!-- 登录按钮 -->
      <view class="login-btn" @click="doLogin">登录</view>

      <!-- 切换注册 -->
      <view class="switch-link" @click="toRegister">
        还没有账号？<text class="link">立即注册</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/index'

const phone    = ref('')
const password = ref('')
const loading  = ref(false)

// WeChat DevTools 中手动 focus 输入框确保键盘可用
onMounted(() => {
  setTimeout(() => {
    const el = document.querySelector('.input[type="number"]') || document.querySelector('uni-input')
    if (el) { el.focus(); try { el.querySelector('input')?.focus() } catch(_) {} }
  }, 500)
})

function validatePhone(v) {
  if (!v) return '请输入手机号'
  if (!/^1[3-9]\d{9}$/.test(v)) return '请输入正确的手机号'
  return ''
}

async function doLogin() {
  if (loading.value) return
  const err = validatePhone(phone.value)
  if (err) { uni.showToast({title:err,icon:'none'}); return }
  if (!password.value) { uni.showToast({title:'请输入密码',icon:'none'}); return }

  loading.value = true
  try {
    const r = await api.login(phone.value, password.value)
    uni.setStorageSync('ops_token', r.token)
    uni.setStorageSync('corp_id', String(r.corpId))
    uni.setStorageSync('ops_user', JSON.stringify(r))
    uni.showToast({title:'登录成功',icon:'success'})
    setTimeout(() => uni.switchTab({url:'/pages/index/index'}), 600)
  } catch (e) {
    uni.showToast({title: e.msg || '登录失败', icon:'none'})
  } finally { loading.value = false }
}

function toRegister() {
  uni.navigateTo({url:'/pages/auth/register'})
}
</script>

<style scoped>
.page{min-height:100vh;background:linear-gradient(180deg,#FF6B35 0%,#FF8C5A 40%,#f5f5f5 40%);padding:60rpx 40rpx}
.logo-area{text-align:center;margin-bottom:80rpx}
.logo{font-size:80rpx}
.title{font-size:40rpx;color:#fff;font-weight:700;display:block;margin-top:16rpx}
.subtitle{font-size:26rpx;color:rgba(255,255,255,.8);margin-top:8rpx;display:block}
.form{background:#fff;border-radius:24rpx;padding:40rpx}
.label{font-size:28rpx;color:#333;display:block;margin:24rpx 0 12rpx}
.label:first-child{margin-top:0}
.input{width:100%;background:#f5f5f5;border-radius:12rpx;padding:24rpx;font-size:30rpx;box-sizing:border-box}
.login-btn{background:#FF6B35;color:#fff;text-align:center;padding:28rpx;border-radius:48rpx;margin-top:40rpx;font-size:32rpx;font-weight:600}
.login-btn:active{opacity:.8}
.switch-link{text-align:center;margin-top:32rpx;font-size:26rpx;color:#999}
.link{color:#FF6B35}
</style>
