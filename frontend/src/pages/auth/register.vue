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
        <view class="phone-btn" :class="{ loading: authLoading }" @click="requestWechatAuth">
          <text class="phone-btn-icon">💚</text>
          <text>{{ authLoading ? '授权中...' : '微信授权获取手机号' }}</text>
        </view>
        <text class="phone-note">手机号仅用于登录，不会公开</text>
      </view>

      <!-- Manual phone input fallback -->
      <view v-if="showManual" class="manual-phone">
        <view class="divider"><text>或手动输入</text></view>
        <view class="field">
          <text class="field-label">手机号</text>
          <input
            class="field-input full-input"
            v-model="manualPhone"
            type="number"
            maxlength="11"
            placeholder="请输入手机号"
          />
        </view>
        <view
          class="submit-btn"
          :class="{ disabled: !manualPhoneValid || submitting }"
          @click="confirmManualPhone"
        >
          确认手机号
        </view>
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
import { ref, computed } from 'vue'
import { api } from '@/api/index'

const phone         = ref('')
const password      = ref('')
const confirmPwd    = ref('')
const showPwd       = ref(false)
const submitting    = ref(false)
const phoneObtained = ref(false)
const authLoading   = ref(false)
const wechatProcessing = ref(false)  // true while calling wechatLogin (one-click)
const showManual    = ref(false)  // fallback: manual phone input
const manualPhone   = ref('')

// WeChat encrypted phone data (sent to backend for decryption)
const wechatAuth = ref({ code: '', encryptedData: '', iv: '' })

const manualPhoneValid = computed(() => /^1[3-9]\d{9}$/.test(manualPhone.value))

// ── 计算属性 ──

function confirmManualPhone() {
  if (!manualPhoneValid.value) return
  phone.value = manualPhone.value.trim()
  phoneObtained.value = true
}

const maskedPhone = computed(() => {
  // Show from manual input or from backend-decrypted phone after register
  const p = phone.value || manualPhone.value
  return p ? p.slice(0, 3) + '****' + p.slice(7) : '已通过微信授权获取'
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
  return !wechatProcessing.value
    && !submitting.value
    && password.value.length >= 6
    && password.value === confirmPwd.value
    && phoneObtained.value
})

// ── 微信授权获取手机号 ──

function isWechat() {
  return /micromessenger/i.test(navigator.userAgent)
}

async function requestWechatAuth() {
  // #ifdef H5
  if (!isWechat()) {
    showManual.value = true
    uni.showToast({ title: '请在微信中打开，或手动输入手机号', icon: 'none' })
    return
  }
  authLoading.value = true

  try {
    // Step 1: wx.login() to get code
    const code = await getWxCode()
    if (!code) throw new Error('wx.login 失败')

    // Step 2: wx.getPhoneNumber() to get encryptedData + iv
    const phoneData = await getWxPhoneNumber()
    if (!phoneData) throw new Error('getPhoneNumber 失败')

    // Step 3: Store encrypted data and do one-click login
    wechatAuth.value = { code, encryptedData: phoneData.encryptedData, iv: phoneData.iv }

    // 一键登录：直接调用 wechat-login，无需密码
    await doWechatLogin()
  } catch (e) {
    console.error('[register] WeChat auth failed:', e)
    showManual.value = true
    uni.showToast({ title: '授权失败，请手动输入手机号', icon: 'none' })
  } finally {
    authLoading.value = false
  }
  // #endif
}

/** 微信一键登录：调用 wechat-login 端点，无需密码 */
async function doWechatLogin() {
  if (wechatProcessing.value || submitting.value) return
  wechatProcessing.value = true
  submitting.value = true
  try {
    const payload = {
      encryptedData: wechatAuth.value.encryptedData,
      iv: wechatAuth.value.iv,
      code: wechatAuth.value.code,
      nickname: '燃气用户',
    }
    const r = await api.wechatLogin(payload)
    uni.setStorageSync('ops_token', r.token)
    uni.setStorageSync('corp_id', String(r.corpId))
    uni.setStorageSync('ops_member', JSON.stringify(r))
    uni.showToast({ title: r.isNewUser ? '注册成功' : '登录成功', icon: 'success' })
    setTimeout(() => uni.switchTab({ url: '/pages/index/index' }), 600)
  } catch (e) {
    // wechat-login 失败 → 回退到手动手机号输入
    console.warn('[register] wechatLogin failed, fallback to manual:', e)
    showManual.value = true
    // 清除 wechatAuth 让手动流程走 mockPhone 路径
    wechatAuth.value = { code: '', encryptedData: '', iv: '' }
    uni.showToast({ title: '微信登录失败，请手动输入', icon: 'none' })
  } finally {
    wechatProcessing.value = false
    submitting.value = false
  }
}

/** wx.login() — get code for jscode2session */
function getWxCode() {
  return new Promise((resolve) => {
    const realWx = window.wx || (typeof wx !== 'undefined' ? wx : null)
    if (!realWx || typeof realWx.login !== 'function') {
      console.warn('[register] wx.login not available')
      resolve(null)
      return
    }
    realWx.login({
      success(res) { resolve(res.code || null) },
      fail(err) { console.warn('[register] wx.login fail:', err); resolve(null) }
    })
  })
}

/** wx.getPhoneNumber() or WeixinJSBridge fallback */
function getWxPhoneNumber() {
  return new Promise((resolve) => {
    const realWx = window.wx || (typeof wx !== 'undefined' ? wx : null)

    const cb = (res) => {
      if (res.err_msg && res.err_msg.indexOf(':ok') > -1) {
        resolve({ encryptedData: res.encryptedData, iv: res.iv })
      } else {
        console.warn('[register] getPhoneNumber fail:', res.err_msg)
        resolve(null)
      }
    }

    // Try wx.getPhoneNumber first (newer JSSDK)
    if (realWx && typeof realWx.getPhoneNumber === 'function') {
      realWx.getPhoneNumber({ success: cb, fail(err) { console.warn('[register] wx.getPhoneNumber fail:', err); resolve(null) } })
    } else if (typeof WeixinJSBridge !== 'undefined') {
      // Fallback: WeixinJSBridge (older WeChat versions)
      WeixinJSBridge.invoke('getPhoneNumber', {}, cb)
    } else {
      resolve(null)
    }
  })
}

// ── 提交注册 ──

async function doRegister() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    const payload = {
      password: password.value,
      confirmPassword: confirmPwd.value,
      nickname: '燃气用户',
    }

    // Send encrypted WeChat data for backend decryption (fallback path)
    if (wechatAuth.value.encryptedData) {
      payload.encryptedData = wechatAuth.value.encryptedData
      payload.iv = wechatAuth.value.iv
      payload.code = wechatAuth.value.code
    } else if (phone.value) {
      // Manual phone input → use as mockPhone for dev compatibility
      payload.mockPhone = phone.value
    }

    // register 现已返回 token，无需额外 login 调用
    const r = await api.register(payload)
    uni.setStorageSync('ops_token', r.token)
    uni.setStorageSync('corp_id', String(r.corpId))
    uni.setStorageSync('ops_member', JSON.stringify(r))
    uni.showToast({ title: '注册成功', icon: 'success' })
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
  background: linear-gradient(160deg, #FF6B35 0%, #E85D2C 50%, #f0f4f8 50%);
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
.phone-btn.loading { opacity: .7; pointer-events: none }
.phone-btn-icon { font-size: 36rpx }
.phone-note { font-size: 22rpx; color: #bbb }

/* Manual phone fallback */
.manual-phone { margin-top: 32rpx; padding-top: 32rpx; border-top: 1rpx solid #eee }
.divider { text-align: center; margin-bottom: 24rpx; font-size: 24rpx; color: #bbb; position: relative }
.divider text { background: #fff; padding: 0 20rpx; position: relative; z-index: 1 }
.divider::before { content: ''; position: absolute; left: 0; top: 50%; width: 100%; height: 1rpx; background: #eee }

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
.full-input:focus { border-color: #FF6B35; background: #fff }
.field-hint { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; padding-left: 4rpx }
.pwd-toggle { text-align: right; margin-top: 12rpx; font-size: 24rpx; color: #FF6B35 }

/* ── Strength ── */
.strength { display: flex; align-items: center; gap: 16rpx; margin-top: -8rpx; margin-bottom: 8rpx }
.strength-bar { flex: 1; height: 8rpx; border-radius: 4rpx; background: #eee; overflow: hidden }
.strength-bar view { height: 100%; border-radius: 4rpx; transition: width .3s }
.weak  { background: #e53935 }
.mid   { background: #fb8c00 }
.good  { background: #43a047 }
.strong{ background: #FF6B35 }
.strength-text { font-size: 22rpx; color: #999; flex-shrink: 0 }

/* Submit */
.submit-btn {
  height: 96rpx; line-height: 96rpx; text-align: center; border-radius: 48rpx;
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  color: #fff; font-size: 32rpx; font-weight: 600; margin-top: 40rpx;
  transition: opacity .2s;
}
.submit-btn:active { opacity: .85 }
.submit-btn.disabled { background: #ccc; pointer-events: none }

.footer-link { text-align: center; margin-top: 20rpx; font-size: 26rpx; color: #999 }
.link { color: #FF6B35; font-weight: 500 }
</style>
