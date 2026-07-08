<template>
  <view class="page">
    <view class="header" @click="goLogin" v-if="!isLogin"><image src="/static/gas-avatar.png" class="avatar"/><text class="nick">点击登录</text><text class="phone">登录后享积分权益</text></view>
    <view class="header" v-else>
      <view class="avatar-wrap" @click="changeAvatar"><image :src="avatarUrl" class="avatar"/><text class="avatar-hint">更换头像</text></view>
      <text class="nick">{{user.nickname||'燃气用户'}}</text><text class="phone">{{user.phone||''}}</text>
    </view>
    <view class="points-card" v-if="isLogin"><text class="points-label">我的积分</text><text class="points-val">{{user.pointsBalance||0}}</text><text class="points-unit">积分</text></view>
    <view class="menu">
      <view class="menu-item" @click="goPage('/pages/points/log')"><text>📊 积分明细</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goPage('/pages/order/list')"><text>📦 兑换记录</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goPage('/pages/coupon/list')"><text>🎫 我的优惠券</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goPage('/pages/booking/list')"><text>📅 我的预约</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goPage('/pages/address/list')"><text>📍 收货地址</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goStation" v-if="isLogin"><text>{{ stationMenuText }}</text><text class="arrow">›</text></view>
      <view class="menu-item" @click="goEmployee" v-if="isLogin && isEmployee"><text>🏢 我是员工</text><text class="arrow">›</text></view>
    </view>
    <view class="logout" v-if="isLogin" @click="doLogout"><text>退出登录</text></view>
  </view>
</template>
<script setup>
import {ref,computed,onMounted} from 'vue';import {api} from '@/api/index';import {onShow} from '@dcloudio/uni-app'
const isLogin=ref(false),user=ref({}),avatarKey=ref(0),stationLabel=ref('成为站长'),stationIsMaster=ref(false),isEmployee=ref(false),employeeCompanies=ref([])
const avatarUrl=computed(()=>{
  const ua=user.value.avatar
  if(!ua) return '/static/gas-avatar.png'
  return ua + (ua.includes('?')?'&':'?') + 't=' + avatarKey.value
})
async function loadUser(){
  const token = uni.getStorageSync('ops_token')
  if (!token) { isLogin.value = false; return }
  try { user.value = await api.getMe(); isLogin.value = true; checkStation(); checkEmployee() }
  catch (e) {
    isLogin.value = false
    // Only clear token on authentication errors (401)
    if (e && e.code === 401) { uni.removeStorageSync('ops_token') }
  }
}
async function checkStation(){
  try{
    const info = await api.stationInfo()
    if (info) { stationIsMaster.value = true; stationLabel.value = '🏠 站长中心' }
    else {
      const s = await api.stationApplyStatus()
      if (s) { stationLabel.value = s.status === '待审批' ? '⏳ 站长申请审核中' : '🏠 站长中心' }
      else { stationLabel.value = '🎯 成为站长' }
    }
  } catch { stationLabel.value = '🎯 成为站长' }
}
const stationMenuText = computed(() => stationLabel.value)
onMounted(loadUser);onShow(loadUser)

async function changeAvatar(){
  if(!isLogin.value) return
  try{
    const res=await new Promise((resolve,reject)=>{
      uni.chooseImage({count:1,sizeType:['compressed'],sourceType:['album','camera'],
        success:resolve,fail:reject})
    })
    const path=res.tempFilePaths[0]
    // H5 环境使用 fetch 读取 blob 转为 base64
    let base64=''
    try{
      // #ifdef H5
      const blob=await fetch(path).then(r=>r.blob())
      base64=await new Promise((resolve,reject)=>{
        const reader=new FileReader()
        reader.onload=()=>{const s=reader.result;resolve(s.split(',')[1])}
        reader.onerror=reject
        reader.readAsDataURL(blob)
      })
      // #endif
      // #ifdef MP-WEIXIN
      const fs=uni.getFileSystemManager()
      base64=fs.readFileSync(path,'base64')
      // #endif
    }catch{throw new Error('图片读取失败')}
    const ext=(path.split('.').pop()||'jpg').toLowerCase()
    const dataUrl='data:image/'+(ext==='png'?'png':'jpeg')+';base64,'+base64

    const token=uni.getStorageSync('ops_token')
    const uploadRes=await new Promise((resolve,reject)=>{
      uni.request({
        url:'/api/ops/user/avatar',method:'POST',
        header:{'Content-Type':'application/json','Authorization':'Bearer '+token},
        data:{imageData:dataUrl},
        success(r){if(r.data?.code===200)resolve(r.data.data);else reject(r.data)},
        fail:reject
      })
    })
    // Save to global storage so AI chat picks up the new avatar
    const savedUser=uni.getStorageSync('ops_member')
    const newUser={...(savedUser?JSON.parse(savedUser):{}),avatar:uploadRes.avatar}
    uni.setStorageSync('ops_member',JSON.stringify(newUser))
    avatarKey.value++
    uni.showToast({title:'头像已更新',icon:'success'})
    loadUser()
  }catch(e){
    if(e.errMsg&&!e.errMsg.includes('cancel')){
      uni.showToast({title:'更换失败，请重试',icon:'none'})
    }
  }
}

async function checkEmployee(){
  try {
    const resp = await api.employeeIdentity()
    isEmployee.value = !!resp.isEmployee
    employeeCompanies.value = resp.companies || []
    uni.setStorageSync('ops_employee', JSON.stringify(resp))
  } catch { /* keep current state on error */ }
}
function goEmployee(){
  if (!isEmployee.value) return
  const companies = employeeCompanies.value
  if (!companies || companies.length === 0) return
  uni.setStorageSync('ops_employee_companies', JSON.stringify(companies))
  if (companies.length === 1) {
    uni.setStorageSync('ops_employee_active_company', JSON.stringify(companies[0]))
    uni.navigateTo({ url: '/pages/employee/dashboard' })
  } else {
    uni.navigateTo({ url: '/pages/employee/dashboard' })
  }
}
function goLogin(){uni.navigateTo({url:'/pages/auth/login'})}
function goPage(url){if(!isLogin.value){goLogin();return};uni.navigateTo({url})}
function goStation(){
  if (!isLogin.value) { goLogin(); return }
  if (stationIsMaster.value) { uni.navigateTo({ url: '/pages/station/center' }) }
  else { uni.navigateTo({ url: '/pages/station/apply' }) }
}
function doLogout(){uni.clearStorageSync();isLogin.value=false;uni.showToast({title:'已退出',icon:'none'})}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5}.header{display:flex;align-items:center;gap:24rpx;padding:48rpx 32rpx;background:linear-gradient(135deg,#FF6B35,#FF8C5A)}.avatar-wrap{position:relative;flex-shrink:0}.avatar{width:100rpx;height:100rpx;border-radius:50%;border:3rpx solid rgba(255,255,255,.4)}.avatar-hint{position:absolute;bottom:-4rpx;left:50%;transform:translateX(-50%);font-size:18rpx;color:#fff;background:rgba(0,0,0,.3);padding:2rpx 12rpx;border-radius:8rpx;white-space:nowrap}.nick{font-size:36rpx;color:#fff;font-weight:600}.phone{font-size:24rpx;color:rgba(255,255,255,.7)}.points-card{background:#fff;margin:-40rpx 24rpx 24rpx;border-radius:20rpx;padding:32rpx;text-align:center;box-shadow:0 4rpx 20rpx rgba(255,107,53,.15)}.points-label{font-size:24rpx;color:#999;display:block}.points-val{font-size:64rpx;color:#FF6B35;font-weight:700}.points-unit{font-size:28rpx;color:#FF6B35;margin-left:8rpx}.menu{background:#fff;margin:16rpx 24rpx;border-radius:16rpx;overflow:hidden}.menu-item{display:flex;justify-content:space-between;align-items:center;padding:32rpx;border-bottom:1rpx solid #f0f0f0;font-size:30rpx;color:#333}.menu-item:last-child{border-bottom:none}.arrow{color:#ccc;font-size:32rpx}.logout{margin:32rpx 24rpx;text-align:center;padding:24rpx;color:#999;font-size:28rpx;background:#fff;border-radius:16rpx}
</style>
