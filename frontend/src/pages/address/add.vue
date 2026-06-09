<template>
  <view class="page"><view class="form"><text class="title">{{editId?'编辑地址':'新增地址'}}</text>
    <input class="input" v-model="form.name" placeholder="收货人姓名" maxlength="20"/>
    <input class="input" v-model="form.phone" type="number" placeholder="手机号" maxlength="11"/>
    <input class="input" v-model="form.detail" placeholder="详细地址：街道、小区、门牌号" maxlength="100"/>
    <picker mode="region" @change="onRegion"><view class="region" :class="{empty:!form.province}">{{form.province?form.province+' '+form.city+' '+form.district:'请选择省/市/区'}}</view></picker>
    <view class="btn" @click="submit">{{editId?'保存修改':'保存地址'}}</view>
  </view></view>
</template>
<script setup>
import {ref,reactive,onMounted} from 'vue';import {api} from '@/api/index'
const editId=ref(null),form=reactive({name:'',phone:'',province:'',city:'',district:'',detail:'',isDefault:0})
onMounted(()=>{const opts=(getCurrentPages().pop())?.options||{};if(opts.id){editId.value=Number(opts.id);try{const d=JSON.parse(decodeURIComponent(opts.data));Object.assign(form,d)}catch{}}})
function onRegion(e){const[v,c,d]=e.detail.value||['','',''];form.province=v;form.city=c;form.district=d}
async function submit(){
  if(!form.name||!form.phone||!form.detail){uni.showToast({title:'请填写完整',icon:'none'});return}
  try{if(editId.value){await api.updateAddress(editId.value,form)}else{await api.saveAddress(form)};uni.navigateBack()}catch(e){uni.showToast({title:e.msg,icon:'none'})}
}
</script>
<style scoped>
.page{min-height:100vh;background:#f5f5f5}.form{background:#fff;margin:16rpx;border-radius:16rpx;padding:32rpx}.title{font-size:32rpx;font-weight:600;text-align:center;display:block;margin-bottom:32rpx}.input{width:100%;background:#f5f5f5;border-radius:12rpx;padding:24rpx;font-size:28rpx;margin-bottom:20rpx;box-sizing:border-box}.region{padding:24rpx;font-size:28rpx;color:#333;background:#f5f5f5;border-radius:12rpx;margin-bottom:20rpx}.region.empty{color:#999}.btn{background:#FF6B35;color:#fff;text-align:center;padding:24rpx;border-radius:48rpx;font-size:30rpx;font-weight:600;margin-top:32rpx}
</style>
