<template>
  <view class="page">
    <view class="logo-area"><text class="logo">🔥</text><text class="title">金地燃气</text><text class="subtitle">积分商城·精彩活动</text></view>
    <view class="form"><text class="label">手机号</text><input class="input" v-model="phone" type="number" maxlength="11" placeholder="请输入手机号"/>
      <text class="label">验证码</text><view class="code-row"><input class="input code-input" v-model="smsCode" placeholder="验证码" maxlength="6"/>
        <view class="send-btn" :class="{disabled:countdown>0}" @click="sendSms">{{countdown>0?countdown+'s':'发送验证码'}}</view>
      </view>
      <view class="agree" @click="agreed=!agreed"><view class="check" :class="{on:agreed}">{{agreed?'✓':''}}</view><text>同意《用户协议》和《隐私政策》</text></view>
      <view class="login-btn" @click="doLogin">登录</view>
    </view>
  </view>
</template>
<script setup>
import {ref} from 'vue';import {api} from '@/api/index'
const phone=ref(''),smsCode=ref(''),countdown=ref(0),agreed=ref(false)
function sendSms(){if(countdown.value>0||!phone.value)return;countdown.value=60;const t=setInterval(()=>{countdown.value--;if(countdown.value<=0)clearInterval(t)},1000)}
async function doLogin(){
  if(!agreed.value){uni.showToast({title:'请先同意用户协议',icon:'none'});return}
  if(!phone.value||!smsCode.value){uni.showToast({title:'请输入手机号和验证码',icon:'none'});return}
  try{const r=await api.login(phone.value,smsCode.value);uni.setStorageSync('gas_token',r.token);uni.setStorageSync('gas_user',JSON.stringify(r));uni.navigateBack()}catch(e){uni.showToast({title:e.msg||'登录失败',icon:'none'})}
}
</script>
<style scoped>
.page{min-height:100vh;background:linear-gradient(180deg,#FF6B35 0%,#FF8C5A 40%,#f5f5f5 40%);padding:60rpx 40rpx}
.logo-area{text-align:center;margin-bottom:80rpx}.logo{font-size:80rpx}.title{font-size:40rpx;color:#fff;font-weight:700;display:block;margin-top:16rpx}.subtitle{font-size:26rpx;color:rgba(255,255,255,.8);margin-top:8rpx;display:block}
.form{background:#fff;border-radius:24rpx;padding:40rpx}.label{font-size:28rpx;color:#333;display:block;margin:20rpx 0 12rpx}.label:first-child{margin-top:0}.input{width:100%;background:#f5f5f5;border-radius:12rpx;padding:24rpx;font-size:30rpx;box-sizing:border-box}.code-row{display:flex;gap:16rpx}.code-input{flex:1}.send-btn{background:#FF6B35;color:#fff;padding:24rpx 28rpx;border-radius:12rpx;font-size:26rpx;white-space:nowrap;flex-shrink:0}.send-btn.disabled{background:#ccc}
.agree{display:flex;align-items:center;gap:12rpx;margin-top:32rpx;font-size:24rpx;color:#999}.check{width:36rpx;height:36rpx;border:2rpx solid #ddd;border-radius:6rpx;display:flex;align-items:center;justify-content:center;font-size:24rpx;color:#FF6B35}.check.on{border-color:#FF6B35;background:#FFF3EE}
.login-btn{background:#FF6B35;color:#fff;text-align:center;padding:28rpx;border-radius:48rpx;margin-top:32rpx;font-size:32rpx;font-weight:600}
</style>
