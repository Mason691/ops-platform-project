<template>
  <view class="page">
    <!-- Header -->
    <view class="header">
      <view class="avatar-wrap" @click="changeAvatar">
        <image :src="avatarUrl" class="avatar" mode="aspectFill"/>
        <text class="avatar-hint">更换头像</text>
      </view>
      <view class="name-row" @click="showNickModal = true">
        <text class="name">{{ info.name || '员工' }}</text>
        <text class="edit-icon">✎</text>
      </view>
      <text class="phone">{{ info.phone || '' }}</text>
      <text class="role-tag">{{ info.role || '员工' }}</text>
    </view>

    <!-- Info Cards -->
    <view class="cards">
      <view class="card">
        <text class="card-label">员工ID</text>
        <text class="card-value">{{ info.employeeId || '-' }}</text>
      </view>
      <view class="card">
        <text class="card-label">所属组织</text>
        <text class="card-value">{{ activeCompany.corpName || '-' }}</text>
      </view>
      <view class="card">
        <text class="card-label">组织角色</text>
        <text class="card-value">{{ activeCompany.roleName || activeCompany.roleType || '-' }}</text>
      </view>
    </view>

    <!-- Personal Settings -->
    <view class="section">
      <view class="section-title">安全设置</view>
      <view class="menu-item" @click="showPwdModal = true">
        <text class="menu-label">修改密码</text>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <!-- Nickname Modal -->
    <view class="modal-mask" v-if="showNickModal" @click="showNickModal = false">
      <view class="modal-box" @click.stop>
        <text class="modal-title">修改昵称</text>
        <input class="modal-input" v-model="nickInput" placeholder="输入新昵称"/>
        <text class="modal-error" v-if="nickError">{{ nickError }}</text>
        <view class="modal-btns">
          <view class="btn-cancel" @click="showNickModal = false"><text>取消</text></view>
          <view class="btn-confirm" @click="updateNickname"><text>确认修改</text></view>
        </view>
      </view>
    </view>

    <!-- Logout -->
    <view class="logout-btn" @click="doLogout">
      <text>退出登录</text>
    </view>

    <!-- Password Modal -->
    <view class="modal-mask" v-if="showPwdModal" @click="showPwdModal = false">
      <view class="modal-box" @click.stop>
        <text class="modal-title">修改密码</text>
        <input class="modal-input" type="password" v-model="oldPwd" placeholder="当前密码"/>
        <input class="modal-input" type="password" v-model="newPwd" placeholder="新密码（至少6位）"/>
        <input class="modal-input" type="password" v-model="confirmPwd" placeholder="确认新密码"/>
        <text class="modal-error" v-if="pwdError">{{ pwdError }}</text>
        <view class="modal-btns">
          <view class="btn-cancel" @click="showPwdModal = false"><text>取消</text></view>
          <view class="btn-confirm" @click="changePassword"><text>确认修改</text></view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/index.js'

const info = ref({})
const activeCompany = ref({})
const showPwdModal = ref(false)
const showNickModal = ref(false)
const oldPwd = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const pwdError = ref('')
const nickInput = ref('')
const nickError = ref('')
const avatarKey = ref(0)

const avatarUrl = ref('/static/gas-avatar.png')

onMounted(() => {
  const empToken = uni.getStorageSync('ops_employee_token')
  if (!empToken) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  const cached = uni.getStorageSync('ops_employee_info')
  if (cached) {
    try { info.value = JSON.parse(cached) } catch(e) {}
  }
  const active = uni.getStorageSync('ops_employee_active_company')
  if (active) {
    try { activeCompany.value = JSON.parse(active) } catch(e) {}
  }
  nickInput.value = info.value.name || ''
})

function changeAvatar() {
  uni.chooseImage({
    count: 1,
    sizeType: ['compressed'],
    sourceType: ['album', 'camera'],
    success(res) {
      const filePath = res.tempFilePaths[0]
      uni.showLoading({ title: '上传中...' })
      api.employeeUploadAvatar(filePath).then(r => {
        uni.hideLoading()
        avatarUrl.value = r.avatar + '?t=' + Date.now()
        avatarKey.value++
        uni.showToast({ title: '头像已更新', icon: 'success' })
      }).catch(e => {
        uni.hideLoading()
        uni.showToast({ title: (e && e.msg) || '上传失败', icon: 'none' })
      })
    }
  })
}

async function updateNickname() {
  nickError.value = ''
  const name = (nickInput.value || '').trim()
  if (!name) { nickError.value = '昵称不能为空'; return }
  if (name.length > 50) { nickError.value = '昵称长度不能超过50个字符'; return }
  try {
    await api.employeeUpdateProfile({ realName: name })
    info.value.name = name
    uni.setStorageSync('ops_employee_info', JSON.stringify({ ...info.value, name }))
    uni.showToast({ title: '昵称已更新', icon: 'success' })
    showNickModal.value = false
  } catch (e) {
    nickError.value = e.msg || '修改失败'
  }
}

async function changePassword() {
  pwdError.value = ''
  if (!oldPwd.value) { pwdError.value = '请输入当前密码'; return }
  if (!newPwd.value || newPwd.value.length < 6) { pwdError.value = '新密码至少6位'; return }
  if (newPwd.value !== confirmPwd.value) { pwdError.value = '两次密码不一致'; return }

  try {
    await api.employeeChangePassword({
      oldPassword: oldPwd.value,
      newPassword: newPwd.value
    })
    uni.showToast({ title: '密码修改成功', icon: 'success' })
    showPwdModal.value = false
    oldPwd.value = ''; newPwd.value = ''; confirmPwd.value = ''
  } catch (e) {
    pwdError.value = e.msg || '修改失败，请重试'
  }
}

function doLogout() {
  uni.showModal({
    title: '确认退出',
    content: '退出后需要重新登录，确定要退出吗？',
    success(res) {
      if (res.confirm) {
        uni.removeStorageSync('ops_employee_token')
        uni.removeStorageSync('ops_employee_info')
        uni.removeStorageSync('ops_employee_companies')
        uni.removeStorageSync('ops_employee_active_company')
        uni.redirectTo({ url: '/pages/employee/login' })
      }
    }
  })
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f5f5; }
.header { background: linear-gradient(135deg, #FF6B35, #FF8C5A); padding: 60rpx 32rpx 48rpx; text-align: center; }
.avatar { width: 120rpx; height: 120rpx; border-radius: 50%; border: 4rpx solid rgba(255,255,255,.4); }
.name { display: block; font-size: 36rpx; color: #fff; font-weight: 700; margin-top: 20rpx; }
.phone { display: block; font-size: 26rpx; color: rgba(255,255,255,.7); margin-top: 8rpx; }
.role-tag { display: inline-block; margin-top: 16rpx; font-size: 24rpx; color: #fff; background: rgba(255,255,255,.2); padding: 6rpx 24rpx; border-radius: 20rpx; }
.cards { margin: 24rpx; background: #fff; border-radius: 16rpx; padding: 8rpx 0; }
.card { display: flex; justify-content: space-between; align-items: center; padding: 28rpx 32rpx; border-bottom: 1rpx solid #f0f0f0; }
.card:last-child { border-bottom: none; }
.card-label { font-size: 28rpx; color: #666; }
.card-value { font-size: 28rpx; color: #333; font-weight: 500; }
.section { margin: 24rpx; }
.section-title { font-size: 26rpx; color: #999; padding: 0 0 16rpx 8rpx; }
.menu-item { display: flex; justify-content: space-between; align-items: center; background: #fff; border-radius: 16rpx; padding: 32rpx; }
.menu-label { font-size: 30rpx; color: #333; }
.menu-arrow { font-size: 36rpx; color: #ccc; }
.logout-btn { margin: 48rpx 24rpx; text-align: center; padding: 24rpx; background: #fff; border-radius: 16rpx; font-size: 28rpx; color: #e74c3c; }

.modal-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center; z-index: 999; }
.modal-box { width: 580rpx; background: #fff; border-radius: 20rpx; padding: 40rpx; }
.modal-title { font-size: 34rpx; font-weight: 700; color: #333; display: block; margin-bottom: 32rpx; text-align: center; }
.modal-input { width: 100%; height: 80rpx; background: #f5f5f5; border-radius: 12rpx; padding: 0 24rpx; font-size: 28rpx; margin-bottom: 20rpx; box-sizing: border-box; }
.modal-error { font-size: 24rpx; color: #e74c3c; display: block; margin-bottom: 16rpx; }
.modal-btns { display: flex; gap: 24rpx; margin-top: 8rpx; }
.btn-cancel, .btn-confirm { flex: 1; text-align: center; padding: 24rpx; border-radius: 12rpx; font-size: 28rpx; }
.btn-cancel { background: #f5f5f5; color: #666; }
.btn-confirm { background: linear-gradient(135deg, #FF6B35, #FF8C5A); color: #fff; }
</style>
