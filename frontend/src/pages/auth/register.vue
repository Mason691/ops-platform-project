<template>
  <view class="page">
    <view class="brand">
      <text class="brand-icon">⛽</text>
      <text class="brand-name">创建账号</text>
      <text class="brand-desc">注册即送 200 积分</text>
    </view>

    <!-- Step 1: 获取手机号 -->
    <view class="card" v-if="!phoneObtained">
      <view class="phone-auth">
        <text class="phone-icon">📱</text>
        <text class="phone-title">微信安全授权</text>
        <text class="phone-desc">一键获取本机号码，无需手动输入</text>
        <view class="phone-btn" @click="requestWechatAuth">
          <text class="phone-btn-icon">💚</text>
          <text>微信授权获取手机号</text>
        </view>
        <text class="phone-note">手机号仅用于登录，不会公开</text>
      </view>
    </view>

    <!-- Step 2: 设置密码 -->
    <template v-else>
      <view class="card phone-card">
        <view class="got-phone">
          <text class="got-check">✓</text>
          <view>
            <text class="got-label">已获取手机号</text>
            <text class="got-value">{{ maskedPhone }}</text>
          </view>
        </view>
      </view>

      <view class="card">
        <view class="field">
          <text class="field-label">设置密码</text>
          <input
            class="field-input full-input"
            v-model="password"
            :type="showPwd ? 'text' : 'password'"
            placeholder="6-20位，建议字母+数字混合"
            :disabled="submitting"
          />
        </view>

        <view class="field">
          <text class="field-label">确认密码</text>
          <input
            class="field-input full-input"
            v-model="confirmPwd"
            :type="showPwd ? 'text' : 'password'"
            placeholder="请再次输入密码"
            :disabled="submitting"
          />
          <view class="pwd-toggle" @click="showPwd = !showPwd">
            <text>{{ showPwd ? '隐藏' : '显示' }}密码</text>
          </view>
        </view>

        <!-- 密码强度 -->
        <view class="strength">
          <view class="strength-bar"><view :class="strengthClass" :style="{ width: strengthPercent + '%' }" /></view>
          <text class="strength-text">{{ strengthText }}</text>
        </view>

        <text v-if="pwdErr" class="field-hint">{{ pwdErr }}</text>

        <view
          class="submit-btn"
          :class="{ disabled: !canSubmit || submitting }"
          @click="doRegister"
        >
          {{ submitting ? '注册中...' : '完成注册' }}
        </view>
      </view>
    </template>

    <view class="footer-link" @click="toLogin">
      已有账号？<text class="link">去登录</text>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/index'

const phone         = ref('')
const password      = ref('')
const confirmPwd    = ref('')
const showPwd       = ref(false)
const submitting    = ref(false)
const phoneObtained = ref(false)

// ── 计算属性 ──

const maskedPhone = computed(() => {
  const p = phone.value
  return p ? p.slice(0, 3) + '****' + p.slice(7) : ''
})


const strengthVal = computed(() => {
  const v = password.value
  if (!v) return 0
  let s = v.length >= 8 ? 1 : 0
  if (/[a-z]/.test(v) && /[A-Z]/.test(v)) s++
  if (/\d/.test(v)) s++
  if (/[^a-zA-Z0-9]/.test(v)) s++
  return Math.min(s, 4)
})

const strengthClass = computed(() => {
  const m = ['', 'weak', 'mid', 'good', 'strong']
  return m[strengthVal.value]
})

const strengthPercent = computed(() => (strengthVal.value / 4) * 100)

const strengthText = computed(() => {
  const m = ['', '弱 — 建议混合字母数字', '一般', '较好', '强']
  return m[strengthVal.value]
})

const pwdErr = computed(() => {
  if (!confirmPwd.value) return ''
  return password.value === confirmPwd.value ? '' : '两次密码输入不一致'
})

const canSubmit = computed(() => {
  return password.value.length >= 6
    && password.value === confirmPwd.value
    && phoneObtained.value
})

// ── 开发模式 mock 手机号 ──

onMounted(() => {
  // #ifdef H5
  const mock = new URLSearchParams(location.search).get('mockPhone')
  if (mock && /^1[3-9]\d{9}$/.test(mock)) {
    phone.value = mock
    phoneObtained.value = true
  }
  // #endif
})

// ── 微信授权（带 mock 降级） ──

function injectMock() {
  phone.value = '13800138000'
  phoneObtained.value = true
  uni.showToast({ title: '已获取手机号', icon: 'success' })
}

function requestWechatAuth() {
  // #ifdef H5
  // 非微信 → 直接 mock
  if (!/micromessenger/i.test(navigator.userAgent)) return injectMock()

  // 微信环境：调 getPhoneNumber，失败降级 mock
  if (typeof WeixinJSBridge !== 'undefined') {
    WeixinJSBridge.invoke('getPhoneNumber', {}, (res) => {
      if (res.err_msg && res.err_msg.indexOf(':ok') > -1) {
        uni.showToast({ title: '授权成功', icon: 'success' })
      } else {
        injectMock()
      }
    })
  } else {
    injectMock()
  }
  // #endif
}

// ── 提交注册 ──

async function doRegister() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    const payload = {
      password: password.value,
      confirmPassword: confirmPwd.value,
    }
    // #ifdef H5
    const mock = new URLSearchParams(location.search).get('mockPhone')
    payload.mockPhone = mock || phone.value
    // #endif

    const r = await api.register(payload)
    uni.showToast({ title: '注册成功', icon: 'success' })
    await api.login(phone.value || r.phone, password.value)
    setTimeout(() => uni.switchTab({ url: '/pages/index/index' }), 600)
  } catch (e) {
    uni.showToast({ title: e.msg || '注册失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function toLogin() {
  uni.navigateBack()
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: linear-gradient(160deg, #1a73e8 0%, #0d47a1 50%, #f0f4f8 50%);
  padding: 80rpx 40rpx 40rpx;
}

/* Brand */
.brand { text-align: center; margin-bottom: 64rpx }
.brand-icon { font-size: 72rpx }
.brand-name { display: block; font-size: 44rpx; color: #fff; font-weight: 700; margin-top: 16rpx }
.brand-desc { display: block; font-size: 26rpx; color: rgba(255,255,255,.75); margin-top: 8rpx }

/* Card */
.card { background: #fff; border-radius: 24rpx; padding: 48rpx 36rpx; box-shadow: 0 8rpx 40rpx rgba(0,0,0,.08); margin-bottom: 24rpx }

/* ── Step 1: Phone Auth ── */
.phone-auth { text-align: center; padding: 24rpx 0 }
.phone-icon { font-size: 72rpx; display: block }
.phone-title { display: block; font-size: 32rpx; color: #333; font-weight: 700; margin-top: 20rpx }
.phone-desc { display: block; font-size: 26rpx; color: #999; margin-top: 12rpx; line-height: 1.6 }
.phone-btn {
  display: flex; align-items: center; justify-content: center; gap: 12rpx;
  margin: 40rpx auto 24rpx; padding: 28rpx;
  background: linear-gradient(135deg, #07C160, #05A84E);
  color: #fff; border-radius: 48rpx; font-size: 30rpx; font-weight: 600;
  max-width: 480rpx;
}
.phone-btn:active { opacity: .85 }
.phone-btn-icon { font-size: 36rpx }
.phone-note { font-size: 22rpx; color: #bbb }

/* ── Step 2: Got Phone ── */
.got-phone { display: flex; align-items: center; gap: 20rpx }
.got-check { width: 52rpx; height: 52rpx; line-height: 52rpx; text-align: center; border-radius: 50%; background: #07C160; color: #fff; font-size: 28rpx; font-weight: 700; flex-shrink: 0 }
.got-label { display: block; font-size: 24rpx; color: #999 }
.got-value { display: block; font-size: 34rpx; color: #333; font-weight: 700; margin-top: 4rpx; letter-spacing: 2rpx }

/* ── Fields ── */
.field { margin-bottom: 28rpx }
.field-label { display: block; font-size: 28rpx; color: #333; font-weight: 600; margin-bottom: 16rpx }
.full-input {
  width: 100%; height: 96rpx; padding: 0 24rpx;
  background: #f5f7fa; border: 2rpx solid transparent; border-radius: 16rpx;
  font-size: 30rpx; color: #333; outline: none; box-sizing: border-box;
  transition: border-color .2s;
}
.full-input:focus { border-color: #1a73e8; background: #fff }
.field-hint { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; padding-left: 4rpx }
.pwd-toggle { text-align: right; margin-top: 12rpx; font-size: 24rpx; color: #1a73e8 }

/* ── Strength ── */
.strength { display: flex; align-items: center; gap: 16rpx; margin-top: -8rpx; margin-bottom: 8rpx }
.strength-bar { flex: 1; height: 8rpx; border-radius: 4rpx; background: #eee; overflow: hidden }
.strength-bar view { height: 100%; border-radius: 4rpx; transition: width .3s }
.weak  { background: #e53935 }
.mid   { background: #fb8c00 }
.good  { background: #43a047 }
.strong{ background: #1a73e8 }
.strength-text { font-size: 22rpx; color: #999; flex-shrink: 0 }

/* Submit */
.submit-btn {
  height: 96rpx; line-height: 96rpx; text-align: center; border-radius: 48rpx;
  background: linear-gradient(135deg, #1a73e8, #0d47a1);
  color: #fff; font-size: 32rpx; font-weight: 600; margin-top: 40rpx;
  transition: opacity .2s;
}
.submit-btn:active { opacity: .85 }
.submit-btn.disabled { background: #ccc; pointer-events: none }

.footer-link { text-align: center; margin-top: 20rpx; font-size: 26rpx; color: #999 }
.link { color: #1a73e8; font-weight: 500 }
</style>
