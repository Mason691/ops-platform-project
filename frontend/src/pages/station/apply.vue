<template>
  <view class="page">
    <view class="nav-bar"><view class="back" @click="goBack">← 返回</view><text class="title">站长申请</text></view>

    <!-- 未申请：表单 -->
    <view class="card form-card" v-if="!applyStatus">
      <view class="form-item">
        <text class="label">姓名</text>
        <input class="input" v-model="form.name" placeholder="请输入您的姓名" />
      </view>
      <view class="form-item">
        <text class="label">手机号</text>
        <input class="input" v-model="form.phone" placeholder="请输入手机号" />
      </view>
      <view class="form-item">
        <text class="label">城市</text>
        <input class="input" v-model="form.area" placeholder="请输入所在城市" />
      </view>
      <view class="form-item">
        <text class="label">申请理由</text>
        <textarea class="textarea" v-model="form.reason" placeholder="请简述您申请成为站长的理由和优势" />
      </view>
      <button class="submit-btn" :loading="submitting" @click="doSubmit">提交申请</button>
    </view>

    <!-- 待审批 -->
    <view class="card status-card" v-else-if="applyStatus === '待审批'">
      <view class="status-icon">⏳</view>
      <text class="status-title">申请审核中</text>
      <text class="status-desc">您的站长申请已提交，运营管理员正在审核中，请耐心等待</text>
    </view>

    <!-- 已通过 → 转到站长中心 -->
    <view class="card status-card" v-else-if="applyStatus === '已通过'">
      <view class="status-icon">✅</view>
      <text class="status-title">申请已通过</text>
      <text class="status-desc">恭喜！您已成为站长</text>
      <button class="action-btn" @click="goCenter">进入站长中心</button>
    </view>

    <!-- 已拒绝 -->
    <view class="card status-card" v-else-if="applyStatus === '已拒绝'">
      <view class="status-icon">❌</view>
      <text class="status-title">申请未通过</text>
      <text class="status-desc" v-if="applyReviewComment">原因：{{ applyReviewComment }}</text>
      <text class="status-desc" v-else>很遗憾，您的申请未获通过</text>
      <button class="action-btn outline" @click="reApply">重新申请</button>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/index'

const form = ref({ name: '', phone: '', area: '', reason: '' })
const applyStatus = ref(null)
const applyReviewComment = ref('')
const submitting = ref(false)

onMounted(async () => {
  // 加载当前会员信息自动填充
  try {
    const user = await api.getMe()
    if (user) {
      form.value.name = user.nickname || ''
      form.value.phone = user.phone || ''
    }
  } catch { /* 忽略 */ }
  checkStatus()
})

async function checkStatus() {
  try {
    const res = await api.stationApplyStatus()
    if (res) {
      applyStatus.value = res.status
      applyReviewComment.value = res.reviewComment || ''
    }
  } catch { /* 忽略 */ }
}

async function doSubmit() {
  if (!form.value.name || !form.value.phone) {
    uni.showToast({ title: '请填写姓名和手机号', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await api.stationApply({
      applicantName: form.value.name,
      applicantPhone: form.value.phone,
      area: form.value.area,
      reason: form.value.reason
    })
    uni.showToast({ title: '申请已提交', icon: 'success' })
    checkStatus()
  } catch (e) {
    uni.showToast({ title: e.msg || '提交失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function reApply() {
  applyStatus.value = null
  applyReviewComment.value = ''
}

function goCenter() {
  uni.redirectTo({ url: '/pages/station/center' })
}
function goBack() {
  uni.navigateBack()
}
</script>

<style scoped>
.page{min-height:100vh;background:#f5f5f5}
.nav-bar{display:flex;align-items:center;padding:20rpx 32rpx;background:#fff;border-bottom:1rpx solid #eee}
.back{font-size:28rpx;color:#FF6B35}
.title{flex:1;text-align:center;font-size:32rpx;font-weight:600;margin-right:80rpx}
.card{background:#fff;margin:24rpx;border-radius:16rpx;padding:32rpx}
.form-item{margin-bottom:24rpx}
.label{display:block;font-size:28rpx;color:#666;margin-bottom:8rpx}
.input,.textarea{width:100%;padding:16rpx;border:1rpx solid #ddd;border-radius:8rpx;font-size:28rpx;box-sizing:border-box}
.textarea{height:200rpx}
.submit-btn{width:100%;margin-top:32rpx;background:linear-gradient(135deg,#FF6B35,#FF8C5A);color:#fff;border:none;border-radius:8rpx;font-size:30rpx;padding:24rpx}
.status-card{text-align:center}
.status-icon{font-size:80rpx;margin-bottom:16rpx}
.status-title{display:block;font-size:34rpx;font-weight:600;color:#333;margin-bottom:12rpx}
.status-desc{display:block;font-size:26rpx;color:#999;line-height:1.6}
.action-btn{margin-top:32rpx;background:linear-gradient(135deg,#FF6B35,#FF8C5A);color:#fff;border:none;border-radius:8rpx;font-size:28rpx;padding:20rpx 48rpx}
.action-btn.outline{background:#fff;color:#FF6B35;border:1rpx solid #FF6B35}
</style>
