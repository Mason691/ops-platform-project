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
      <!-- 登录方式切换 -->
      <view class="tabs">
        <view class="tab" :class="{ active: activeTab === 'wechat' }" @click="activeTab = 'wechat'">
          <text>微信登录</text>
        </view>
        <view class="tab" :class="{ active: activeTab === 'password' }" @click="activeTab = 'password'">
          <text>密码登录</text>
        </view>
      </view>

      <EmployeeWechatLogin
        v-if="activeTab === 'wechat'"
        @switch-password="activeTab = 'password'"
      />
      <EmployeePasswordLogin
        v-else
        @success="goDashboard"
      />
    </view>

    <!-- Bottom hint -->
    <view class="bottom-hint">
      <text>账号由管理员创建，请联系运营商获取</text>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import EmployeePasswordLogin from '@/components/EmployeePasswordLogin.vue'
import EmployeeWechatLogin from '@/components/EmployeeWechatLogin.vue'
import {
  isEmployeeLoggedIn,
  ensureActiveCompany,
  setLoginRedirect,
  consumeLoginRedirect
} from '@/utils/employeeSession'

const activeTab = ref('wechat')

onLoad((options) => {
  // 公众号消息/分享链接带入：登录后回跳原页面
  if (options && options.redirect) {
    setLoginRedirect(decodeURIComponent(options.redirect))
  }
})

onMounted(() => {
  if (isEmployeeLoggedIn()) {
    ensureActiveCompany()
    uni.redirectTo({ url: consumeLoginRedirect() })
    return
  }
  // 兜底：若微信 OAuth 回调直接落到登录页，转入回调页统一处理
  if (uni.getStorageSync('ops_wechat_oauth_code')) {
    uni.reLaunch({ url: '/pages/employee/wechat-callback' })
  }
})

function goDashboard() {
  uni.redirectTo({ url: consumeLoginRedirect() })
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
  padding: 36rpx 36rpx 36rpx;
  box-shadow: 0 8rpx 40rpx rgba(0,0,0,.08);
}

/* Tabs */
.tabs {
  display: flex;
  background: #f5f7fa;
  border-radius: 16rpx;
  padding: 6rpx;
  margin-bottom: 36rpx;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 18rpx 0;
  border-radius: 12rpx;
  font-size: 27rpx;
  color: #666;
  transition: all .2s;
}
.tab.active {
  background: #fff;
  color: #FF6B35;
  font-weight: 700;
  box-shadow: 0 2rpx 10rpx rgba(0,0,0,.06);
}

/* Bottom hint */
.bottom-hint { text-align: center; margin-top: 40rpx; font-size: 24rpx; color: #aaa; }
</style>
