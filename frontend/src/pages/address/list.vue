<template>
  <view class="page">
    <view v-if="list.length===0" class="empty">暂无收货地址</view>
    <view v-for="a in list" :key="a.id" class="card">
      <view class="info"><text class="name">{{a.name}} {{a.phone}}</text><text class="addr">{{a.province}}{{a.city}}{{a.district}}{{a.detail}}</text></view>
      <view class="actions"><text @click="edit(a)" class="edit">编辑</text><text @click="doDelete(a.id)" class="del">删除</text></view>
    </view>
    <view class="add-btn" @click="goAdd">+ 新增地址</view>
  </view>
</template>
<script setup>
import {ref,onMounted} from 'vue';import {api} from '@/api/index';import {onShow} from '@dcloudio/uni-app'
const list=ref([])
async function load(){try{list.value=await api.addressList()||[]}catch{}}
onMounted(load);onShow(load)
function edit(a){uni.navigateTo({url:'/pages/address/add?id='+a.id+'&data='+encodeURIComponent(JSON.stringify(a))})}
async function doDelete(id){try{await api.deleteAddress(id);await load()}catch(e){uni.showToast({title:e.msg,icon:'none'})}}
function goAdd(){uni.navigateTo({url:'/pages/address/add'})}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding:16rpx}.empty{text-align:center;padding:80rpx;color:#999}.card{background:#fff;border-radius:16rpx;padding:24rpx;margin-bottom:12rpx}.name{font-size:30rpx;color:#333;font-weight:600}.addr{font-size:26rpx;color:#666;margin-top:8rpx}.actions{display:flex;gap:32rpx;margin-top:16rpx}.edit{font-size:26rpx;color:#4A90E2}.del{font-size:26rpx;color:#e44}.add-btn{text-align:center;padding:32rpx;background:#fff;border-radius:16rpx;font-size:28rpx;color:#FF6B35}
</style>
