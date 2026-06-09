<template>
  <view class="page" v-if="p">
    <swiper class="swiper" indicator-dots><swiper-item v-for="(img,i) in images" :key="i"><image :src="img" class="swiper-img" mode="aspectFill"/></swiper-item></swiper>
    <view class="info"><text class="name">{{p.name}}</text>
      <view class="price-row"><text class="points">{{p.pointsPrice}} 积分</text><text v-if="p.cashPrice>0" class="cash">+ ¥{{p.cashPrice}}</text></view>
      <view class="desc"><text class="desc-title">商品详情</text><text>{{p.description||'暂无描述'}}</text></view>
    </view>
    <view class="bottom-bar"><text class="total">需消耗 {{p.pointsPrice}} 积分</text><view class="buy-btn" @click="doExchange">立即兑换</view></view>
    <!-- 确认弹窗 -->
    <view class="mask" v-if="showConfirm" @click="showConfirm=false"><view class="confirm" @click.stop>
      <text class="confirm-title">确认兑换</text><text class="confirm-desc">消耗 {{p.pointsPrice}} 积分兑换「{{p.name}}」</text>
      <view class="confirm-btns"><view class="cancel" @click="showConfirm=false">取消</view><view class="ok" @click="confirmExchange">确认兑换</view></view>
    </view></view>
  </view>
</template>
<script setup>
import {ref,computed,onMounted} from 'vue';import {api} from '@/api/index'
const id=ref(0),p=ref(null),showConfirm=ref(false)
const images=computed(()=>{if(!p.value)return[];try{return JSON.parse(p.value.images)}catch{return[p.value.coverImage]}})
onMounted(async()=>{
  const opts=(getCurrentPages().pop())?.options||{};
  if(opts.id){id.value=Number(opts.id);try{p.value=await api.productDetail(id.value)}catch{}}
})
function doExchange(){if(!uni.getStorageSync('gas_token')){uni.navigateTo({url:'/pages/auth/login'});return};showConfirm.value=true}
async function confirmExchange(){
  try{
    const r=await api.createOrder({productId:p.value.id});
    uni.showToast({title:'兑换成功！将跳转到金地燃气掌厅小程序',icon:'success',duration:2000});
    // 跳转第三方小程序
    // #ifdef MP-WEIXIN
    setTimeout(()=>{wx.navigateToMiniProgram({appId:'',path:'pages/index/index',extraData:{orderNo:r.orderNo},success:()=>{},fail:()=>uni.showToast({title:'请手动打开燃气掌厅小程序',icon:'none'})})},1500)
    // #endif
    // #ifdef H5
    setTimeout(()=>{uni.navigateTo({url:'/pages/order/detail?no='+r.orderNo})},1500)
    // #endif
  }catch(e){uni.showToast({title:e.msg||'兑换失败',icon:'none'})}
}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding-bottom:120rpx}.swiper{height:500rpx}.swiper-img{width:100%;height:100%}.info{background:#fff;border-radius:24rpx 24rpx 0 0;padding:32rpx;margin-top:-24rpx;position:relative}.name{font-size:34rpx;color:#333;font-weight:600;display:block}.price-row{display:flex;align-items:baseline;gap:12rpx;margin:20rpx 0}.points{font-size:40rpx;color:#FF6B35;font-weight:700}.cash{font-size:28rpx;color:#999}.desc{margin-top:32rpx}.desc-title{font-size:28rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}.bottom-bar{position:fixed;bottom:0;left:0;right:0;display:flex;align-items:center;justify-content:space-between;background:#fff;padding:16rpx 32rpx calc(env(safe-area-inset-bottom)+16rpx);border-top:1rpx solid #eee}.total{font-size:28rpx;color:#FF6B35;font-weight:600}.buy-btn{background:#FF6B35;color:#fff;padding:20rpx 48rpx;border-radius:48rpx;font-size:30rpx;font-weight:600}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}.confirm{background:#fff;border-radius:24rpx;padding:40rpx;width:80%}.confirm-title{font-size:34rpx;font-weight:600;text-align:center;display:block}.confirm-desc{font-size:28rpx;color:#666;text-align:center;margin:24rpx 0;display:block}.confirm-btns{display:flex;gap:20rpx}.cancel{flex:1;text-align:center;padding:24rpx;border:2rpx solid #ddd;border-radius:48rpx;font-size:28rpx;color:#666}.ok{flex:1;text-align:center;padding:24rpx;background:#FF6B35;color:#fff;border-radius:48rpx;font-size:28rpx;font-weight:600}
</style>
