<template>
  <view class="page">
    <view class="card">
      <text class="title">请修改初始密码</text>
      <text class="subtitle">请输入管理员提供的初始密码，修改成功后需重新登录</text>

      <view class="field">
        <text class="label">原密码</text>
        <input class="input" type="password" v-model="oldPassword" placeholder="请输入当前初始密码" />
      </view>
      <view class="field">
        <text class="label">新密码</text>
        <input class="input" type="password" v-model="newPassword" placeholder="8~20 位，需包含字母和数字" />
      </view>
      <view class="field">
        <text class="label">确认新密码</text>
        <input class="input" type="password" v-model="confirmPassword" placeholder="请再次输入新密码" />
      </view>

      <text v-if="error" class="error">{{ error }}</text>

      <view class="submit" :class="{ disabled: submitting }" @click="submit">
        <text>{{ submitting ? '提交中...' : '修改密码' }}</text>
      </view>
      <view class="logout" @click="logout">
        <text>退出登录</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '@/api/index'
import {
  clearEmployeeSession,
  isEmployeeLoggedIn,
  isForcePasswordChange
} from '@/utils/employeeSession'

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const error = ref('')
const submitting = ref(false)

onMounted(() => {
  if (!isEmployeeLoggedIn()) {
    uni.reLaunch({ url: '/pages/employee/login' })
    return
  }
  if (!isForcePasswordChange()) {
    uni.reLaunch({ url: '/pages/employee/dashboard' })
  }
})

function validate() {
  if (!oldPassword.value) return '请输入原密码'
  if (newPassword.value.length < 8 || newPassword.value.length > 20) {
    return '新密码需为 8~20 位'
  }
  if (!/[A-Za-z]/.test(newPassword.value) || !/\d/.test(newPassword.value)) {
    return '新密码需同时包含字母和数字'
  }
  if (newPassword.value === oldPassword.value) return '新密码不能与原密码相同'
  if (newPassword.value !== confirmPassword.value) return '两次输入的密码不一致'
  return ''
}

async function submit() {
  if (submitting.value) return
  error.value = validate()
  if (error.value) return
  submitting.value = true
  try {
    await api.employeeChangePassword({
      oldPassword: oldPassword.value,
      newPassword: newPassword.value
    })
    clearEmployeeSession()
    uni.showToast({ title: '密码修改成功，请重新登录', icon: 'none', duration: 1500 })
    setTimeout(() => uni.reLaunch({ url: '/pages/employee/login' }), 800)
  } catch (e) {
    error.value = e.msg || '密码修改失败，请重试'
  } finally {
    submitting.value = false
  }
}

function logout() {
  clearEmployeeSession()
  uni.reLaunch({ url: '/pages/employee/login' })
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  background: linear-gradient(160deg, #FF6B35 0%, #E85D2C 45%, #f0f4f8 45%);
  padding: 120rpx 40rpx 40rpx;
  box-sizing: border-box;
}
.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 44rpx 36rpx;
  box-shadow: 0 8rpx 40rpx rgba(0, 0, 0, .08);
}
.title { display: block; font-size: 40rpx; font-weight: 700; color: #303133; }
.subtitle { display: block; margin-top: 12rpx; font-size: 24rpx; color: #909399; }
.field { margin-top: 32rpx; }
.label { display: block; font-size: 26rpx; color: #606266; margin-bottom: 12rpx; }
.input {
  height: 88rpx;
  background: #f5f7fa;
  border-radius: 16rpx;
  padding: 0 24rpx;
  font-size: 28rpx;
  color: #303133;
}
.error { display: block; margin-top: 20rpx; font-size: 24rpx; color: #e53935; }
.submit {
  margin-top: 40rpx;
  height: 92rpx;
  line-height: 92rpx;
  text-align: center;
  border-radius: 46rpx;
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  color: #fff;
  font-size: 30rpx;
  font-weight: 600;
}
.submit.disabled { background: #ccc; }
.logout { margin-top: 28rpx; text-align: center; font-size: 26rpx; color: #909399; }
</style>
