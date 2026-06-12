<template>
  <view class="page" v-if="a">
    <image :src="a.coverImage||'/static/gas-default.png'" class="cover" mode="aspectFill"/>
    <view class="info">
      <text class="title">{{a.title}}</text>
      <text class="time">🕐 {{a.startTime}} ~ {{a.endTime}}</text>
      <text v-if="a.location" class="loc">📍 {{a.location}}</text>
      <view class="reward" v-if="a.pointsReward">🎁 参加可获得 <text class="r-points">{{a.pointsReward}} 积分</text></view>
      <view class="detail"><rich-text :nodes="a.detail||'暂无详情'"/></view>
    </view>
    <view class="bottom-bar">
      <view class="join-btn" :class="{ disabled: booked }" @click="doBook">
        {{ booked ? '已预约 ✓' : '预约参加' }}
      </view>
    </view>

    <!-- WeChat follow guide -->
    <view class="mask" v-if="showFollowGuide" @click="showFollowGuide=false">
      <view class="guide" @click.stop>
        <text class="guide-icon">📢</text>
        <text class="guide-title">关注公众号</text>
        <text class="guide-desc">关注公众号后，我们会在活动开始前通过模板消息提醒您，不错过精彩活动！</text>
        <view class="guide-btns">
          <view class="g-btn cancel" @click="showFollowGuide=false">暂不关注</view>
          <view class="g-btn ok" @click="doBookAfterFollow">已关注，去预约</view>
        </view>
      </view>
    </view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue'
const a=ref(null),booked=ref(false),showFollowGuide=ref(false)
onMounted(async()=>{
  const id=(getCurrentPages().pop())?.options?.id
  if(id){try{a.value=await request({url:'/api/ops/activity/'+id})}catch{};if(id)checkBooked(Number(id))}
})
async function checkBooked(activityId){
  const token=uni.getStorageSync('ops_token')
  if(!token)return
  try{const r=await request({url:'/api/ops/my-bookings'});booked.value=(r||[]).some(b=>b.activityId===activityId&&b.status===0)}catch{}
}
function doBook(){
  const token=uni.getStorageSync('ops_token')
  if(!token){uni.navigateTo({url:'/pages/auth/login'});return}
  // Show follow guide (WeChat follow needed for template messages)
  showFollowGuide.value=true
}
async function doBookAfterFollow(){
  showFollowGuide.value=false
  try{
    await request({url:'/api/ops/activity/'+a.value.id+'/book',method:'POST'})
    booked.value=true
    uni.showToast({title:'预约成功！活动开始前会提醒您',icon:'success',duration:2000})
  }catch(e){uni.showToast({title:e?.msg||'预约失败',icon:'none'})}
}
function request(opts){
  return new Promise((resolve,reject)=>{
    const token=uni.getStorageSync('ops_token')||''
    uni.request({
      url:'/api/ops'+opts.url,method:opts.method||'GET',data:opts.data||{},
      header:token?{'Authorization':'Bearer '+token,'Content-Type':'application/json'}:{'Content-Type':'application/json'},
      success(r){if(r.data?.code===200)resolve(r.data.data);else reject(r.data)},
      fail:reject
    })
  })
}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding-bottom:120rpx}.cover{width:100%;height:400rpx}.info{background:#fff;padding:32rpx;margin-top:-24rpx;border-radius:24rpx 24rpx 0 0}.title{font-size:34rpx;color:#333;font-weight:600;display:block}.time,.loc{font-size:26rpx;color:#999;display:block;margin-top:12rpx}.reward{margin-top:20rpx;background:#FFF3EE;padding:16rpx 24rpx;border-radius:12rpx;font-size:28rpx}.r-points{color:#FF6B35;font-weight:600}.detail{margin-top:24rpx;font-size:28rpx;color:#666;line-height:1.8}.bottom-bar{position:fixed;bottom:0;left:0;right:0;padding:16rpx 32rpx calc(env(safe-area-inset-bottom)+16rpx);background:#fff;border-top:1rpx solid #eee}.join-btn{background:#FF6B35;color:#fff;text-align:center;padding:24rpx;border-radius:48rpx;font-size:32rpx;font-weight:600}.join-btn.disabled{background:#ccc}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:999}.guide{background:#fff;border-radius:24rpx;padding:48rpx 40rpx;width:80%;text-align:center}.guide-icon{font-size:64rpx;display:block;margin-bottom:20rpx}.guide-title{font-size:34rpx;font-weight:600;color:#333;display:block;margin-bottom:12rpx}.guide-desc{font-size:26rpx;color:#999;display:block;margin-bottom:32rpx;line-height:1.5}.guide-btns{display:flex;gap:20rpx}.g-btn{flex:1;padding:20rpx;border-radius:48rpx;font-size:28rpx;text-align:center}.g-btn.cancel{border:2rpx solid #ddd;color:#666}.g-btn.ok{background:#FF6B35;color:#fff}
</style>
