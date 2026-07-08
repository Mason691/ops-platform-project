<template>
  <view class="page">
    <view class="brand">
      <text class="brand-icon">⛽</text>
      <text class="brand-name">金地燃气</text>
      <text class="brand-desc">便民服务 · 积分商城</text>
    </view>

    <view class="card">
      <view class="field">
        <text class="field-label">手机号</text>
        <view class="field-box">
          <text class="field-prefix">+86</text>
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

      <view class="field">
        <text class="field-label">密码</text>
        <view class="field-box">
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

      <view
        class="submit-btn"
        :class="{ disabled: !canSubmit || submitting }"
        @click="doLogin"
      >
        {{ submitting ? '登录中...' : '登录' }}
      </view>

      <view class="footer-link" @click="toRegister">
        还没有账号？<text class="link">立即注册</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { api } from '@/api/index'

const phone      = ref('')
const password   = ref('')
const showPwd    = ref(false)
const submitting = ref(false)

const phoneErr = computed(() => {
  if (!phone.value) return ''
  return /^1[3-9]\d{9}$/.test(phone.value) ? '' : '请输入正确的11位手机号'
})
const canSubmit = computed(() => {
  return /^1[3-9]\d{9}$/.test(phone.value) && password.value.length >= 6
})

async function doLogin() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    const r = await api.login(phone.value, password.value)
    // Clear stale employee tokens and activate member session
    uni.removeStorageSync('ops_employee_token')
    uni.removeStorageSync('ops_employee_info')
    uni.removeStorageSync('ops_employee_companies')
    uni.removeStorageSync('ops_employee_active_company')
    uni.setStorageSync('token_type', 'member')
    uni.setStorageSync('ops_token', r.token)
    uni.setStorageSync('corp_id', String(r.corpId))
    uni.setStorageSync('ops_member', JSON.stringify(r))
    uni.showToast({ title: '登录成功', icon: 'success' })
    setTimeout(() => uni.switchTab({ url: '/pages/index/index' }), 500)
  } catch (e) {
    uni.showToast({ title: e.msg || '登录失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function toRegister() {
  uni.navigateTo({ url: '/pages/auth/register' })
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
.card { background: #fff; border-radius: 24rpx; padding: 48rpx 36rpx; box-shadow: 0 8rpx 40rpx rgba(0,0,0,.08) }

/* Field */
.field { margin-bottom: 32rpx }
.field-label { display: block; font-size: 28rpx; color: #333; font-weight: 600; margin-bottom: 16rpx }
.field-box { display: flex; align-items: center; background: #f5f7fa; border-radius: 16rpx; padding: 0 24rpx; border: 2rpx solid transparent; transition: border-color .2s }
.field-box:focus-within { border-color: #FF6B35; background: #fff }
.field-prefix { font-size: 28rpx; color: #999; padding-right: 16rpx; flex-shrink: 0 }
.field-input { flex: 1; height: 96rpx; font-size: 30rpx; color: #333; border: none; background: transparent; outline: none; width: 0; min-width: 0 }
.field-input.full { width: 100% }
.field-eye { font-size: 36rpx; flex-shrink: 0; padding-left: 8rpx }
.field-hint { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; padding-left: 4rpx }

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
  margin-top: 48rpx;
  transition: opacity .2s;
}
.submit-btn:active { opacity: .85 }
.submit-btn.disabled { background: #ccc; pointer-events: none }

.footer-link { text-align: center; margin-top: 36rpx; font-size: 26rpx; color: #999 }
.link { color: #FF6B35; font-weight: 500 }
</style>
