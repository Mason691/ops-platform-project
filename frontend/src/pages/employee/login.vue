<template>
  <view class="page">
    <!-- Header -->
    <view class="header">
      <text class="header-logo">⛽</text>
      <text class="header-title">金地燃气</text>
      <text class="header-sub">员工工作台</text>
    </view>

    <!-- Login Card -->
    <view class="card">
      <!-- Phone -->
      <view class="field">
        <text class="field-label">手机号</text>
        <view class="field-box">
          <text class="field-icon">📱</text>
          <input
            class="field-input"
            v-model="phone"
            type="number"
            maxlength="11"
            placeholder="请输入手机号"
            :disabled="submitting"
          />
        </view>
        <text v-if="phoneErr" class="field-hint">{{ phoneErr }}</text>
      </view>

      <!-- Password -->
      <view class="field">
        <text class="field-label">密码</text>
        <view class="field-box">
          <text class="field-icon">🔒</text>
          <input
            class="field-input full"
            v-model="password"
            :type="showPwd ? 'text' : 'password'"
            placeholder="请输入密码"
            :disabled="submitting"
          />
          <text class="field-eye" @click="showPwd = !showPwd">{{ showPwd ? '🙈' : '👁' }}</text>
        </view>
      </view>

      <!-- Captcha -->
      <view class="field">
        <text class="field-label">验证码</text>
        <view class="captcha-row">
          <view class="field-box captcha-input-box">
            <text class="field-icon">🔐</text>
            <input
              class="field-input full"
              v-model="captchaCode"
              type="text"
              maxlength="6"
              placeholder="请输入验证码"
              :disabled="submitting"
            />
          </view>
          <image
            class="captcha-img"
            :src="captchaImage"
            mode="aspectFill"
            @click="refreshCaptcha"
          />
        </view>
      </view>

      <!-- Submit -->
      <view
        class="submit-btn"
        :class="{ disabled: !canSubmit || submitting }"
        @click="doLogin"
      >
        <text v-if="submitting" class="btn-loading"></text>
        <text>{{ submitting ? '登录中...' : '登 录' }}</text>
      </view>

      <!-- Footer -->
      <view class="footer-link">
        <text class="link" @click="forgetPwd">忘记密码</text>
      </view>
    </view>

    <!-- Bottom hint -->
    <view class="bottom-hint">
      <text>账号由管理员创建，请联系运营商获取</text>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/index'

const phone = ref('')
const password = ref('')
const captchaCode = ref('')
const captchaId = ref('')
const captchaImage = ref('')
const showPwd = ref(false)
const submitting = ref(false)

const phoneErr = computed(() => {
  if (!phone.value) return ''
  return /^1[3-9]\d{9}$/.test(phone.value) ? '' : '请输入正确的11位手机号'
})

const canSubmit = computed(() => {
  return /^1[3-9]\d{9}$/.test(phone.value)
    && password.value.length >= 1
    && captchaCode.value.length >= 4
})

onMounted(() => {
  // Check if already logged in as employee
  const empToken = uni.getStorageSync('ops_employee_token')
  if (empToken) {
    uni.redirectTo({ url: '/pages/employee/dashboard' })
    return
  }
  refreshCaptcha()
})

function refreshCaptcha() {
  captchaCode.value = ''
  api.employeeCaptcha().then(r => {
    captchaId.value = r.captchaId
    captchaImage.value = r.captchaImage
  }).catch(() => {
    uni.showToast({ title: '验证码加载失败', icon: 'none' })
  })
}

async function doLogin() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    const r = await api.employeeLogin({
      phone: phone.value,
      password: password.value,
      captchaId: captchaId.value,
      captchaCode: captchaCode.value
    })
    // Store employee token under separate key (isolated from member token)
    uni.setStorageSync('ops_employee_token', r.token)
    uni.setStorageSync('corp_id', String(r.corpId))
    uni.setStorageSync('ops_employee_info', JSON.stringify({
      employeeId: r.employeeId,
      name: r.name,
      phone: r.phone,
      role: r.role,
      corpId: r.corpId
    }))
    uni.showToast({ title: '登录成功', icon: 'success' })
    setTimeout(() => {
      uni.redirectTo({ url: '/pages/employee/dashboard' })
    }, 500)
  } catch (e) {
    uni.showToast({ title: e.msg || '登录失败', icon: 'none' })
    refreshCaptcha()
  } finally {
    submitting.value = false
  }
}

function forgetPwd() {
  uni.showToast({ title: '请联系管理员重置密码', icon: 'none' })
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: linear-gradient(160deg, #FF6B35 0%, #E85D2C 55%, #f0f4f8 55%);
  padding: 80rpx 40rpx 40rpx;
}

/* Header */
.header { text-align: center; margin-bottom: 64rpx; }
.header-logo { font-size: 80rpx; display: block; }
.header-title { display: block; font-size: 48rpx; color: #fff; font-weight: 700; margin-top: 16rpx; }
.header-sub { display: block; font-size: 26rpx; color: rgba(255,255,255,.75); margin-top: 8rpx; }

/* Card */
.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 48rpx 36rpx 36rpx;
  box-shadow: 0 8rpx 40rpx rgba(0,0,0,.08);
}

/* Field */
.field { margin-bottom: 28rpx; }
.field-label { display: block; font-size: 28rpx; color: #333; font-weight: 600; margin-bottom: 14rpx; }
.field-box {
  display: flex;
  align-items: center;
  background: #f5f7fa;
  border-radius: 16rpx;
  padding: 0 24rpx;
  border: 2rpx solid transparent;
  transition: border-color .2s;
}
.field-box:focus-within { border-color: #FF6B35; background: #fff; }
.field-icon { font-size: 32rpx; flex-shrink: 0; margin-right: 12rpx; }
.field-input {
  flex: 1;
  height: 96rpx;
  font-size: 30rpx;
  color: #333;
  border: none;
  background: transparent;
  outline: none;
  width: 0;
  min-width: 0;
}
.field-input.full { width: 100%; }
.field-eye { font-size: 36rpx; flex-shrink: 0; padding-left: 8rpx; }
.field-hint { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; padding-left: 4rpx; }

/* Captcha row */
.captcha-row { display: flex; align-items: center; gap: 16rpx; }
.captcha-input-box { flex: 1; }
.captcha-img {
  width: 160rpx;
  height: 72rpx;
  border-radius: 12rpx;
  flex-shrink: 0;
  border: 2rpx solid #eee;
}

/* Submit */
.submit-btn {
  height: 96rpx;
  line-height: 96rpx;
  text-align: center;
  border-radius: 48rpx;
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  color: #fff;
  font-size: 32rpx;
  font-weight: 600;
  margin-top: 40rpx;
  transition: opacity .2s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
}
.submit-btn:active { opacity: .85; }
.submit-btn.disabled { background: #ccc; pointer-events: none; }

/* Loading spinner inside button */
.btn-loading {
  width: 32rpx;
  height: 32rpx;
  border: 3rpx solid rgba(255,255,255,.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin .6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* Footer */
.footer-link { text-align: center; margin-top: 32rpx; font-size: 26rpx; color: #999; }
.link { color: #FF6B35; font-weight: 500; }

/* Bottom hint */
.bottom-hint { text-align: center; margin-top: 40rpx; font-size: 24rpx; color: #aaa; }
</style>
