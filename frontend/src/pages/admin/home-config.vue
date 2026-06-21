<template>
  <view class="page">
    <!-- Header -->
    <view class="hc-topbar"><text class="hc-topbar-title">首页配置</text></view>

    <!-- Tabs -->
    <view class="hc-tabs">
      <view class="hc-tab" :class="{active:activeTab==='banner'}" @click="activeTab='banner'">广告轮播</view>
      <view class="hc-tab" :class="{active:activeTab==='video'}" @click="activeTab='video'">精彩视频</view>
      <view class="hc-tab" :class="{active:activeTab==='recommend'}" @click="activeTab='recommend'">热门推荐</view>
    </view>

    <!-- ═══ Banner Tab ═══ -->
    <view class="hc-content" v-show="activeTab==='banner'">
      <view class="hc-actions"><view class="hc-btn hc-btn-primary" @click="openBannerForm()">+ 添加轮播图</view></view>
      <view class="hc-empty" v-if="banners.length===0">暂无轮播图配置</view>
      <view class="hc-card" v-for="(b,i) in banners" :key="b.id">
        <view class="hc-card-row">
          <image v-if="b.imageUrl" :src="assetUrl(b.imageUrl)" mode="aspectFill" class="hc-thumb" @error="onImgError"/>
          <view v-else class="hc-thumb-placeholder">暂无图</view>
          <view class="hc-card-info">
            <text class="hc-card-title">{{b.title||'无标题'}}</text>
            <text class="hc-card-sub">跳转: {{b.jumpPath||'未设置'}}</text>
            <view class="hc-card-meta">
              <text>排序: </text><input class="hc-sort-input" type="number" :value="b.sort" @blur="e=>updateSort('banner',b.id,i,parseInt(e.detail.value)||0)"/>
              <view class="hc-status" :class="{on:b.status===1}" @click="toggleStatus('banner',b)">{{b.status===1?'启用':'禁用'}}</view>
            </view>
          </view>
        </view>
        <view class="hc-card-bar">
          <text class="hc-arrow-btn" @click="moveUp('banner',i)" :class="{disabled:i===0}">▲</text>
          <text class="hc-arrow-btn" @click="moveDown('banner',i)" :class="{disabled:i===banners.length-1}">▼</text>
          <text class="hc-link" @click="openBannerForm(b)">编辑</text>
          <text class="hc-link hc-del" @click="deleteConfig('banner',b.id)">删除</text>
        </view>
      </view>
    </view>

    <!-- ═══ Video Tab ═══ -->
    <view class="hc-content" v-show="activeTab==='video'">
      <view class="hc-actions"><view class="hc-btn hc-btn-primary" @click="openVideoPicker()">+ 添加视频</view></view>
      <view class="hc-empty" v-if="videos.length===0">暂无视频配置</view>
      <view class="hc-card" v-for="(v,i) in videos" :key="v.id">
        <view class="hc-card-row">
          <image v-if="v.videoCoverUrl||v.coverImage" :src="assetUrl(v.videoCoverUrl||v.coverImage)" mode="aspectFill" class="hc-thumb" @error="onImgError"/>
          <view v-else class="hc-thumb-placeholder">🎬</view>
          <view class="hc-card-info">
            <text class="hc-card-title">{{v.activityTitle||v.title||'未知活动'}}</text>
            <text class="hc-card-sub">排序: {{v.sort}}</text>
          </view>
        </view>
        <view class="hc-card-bar">
          <text class="hc-arrow-btn" @click="moveUp('video',i)" :class="{disabled:i===0}">▲</text>
          <text class="hc-arrow-btn" @click="moveDown('video',i)" :class="{disabled:i===videos.length-1}">▼</text>
          <text class="hc-link hc-del" @click="deleteConfig('video',v.id)">移除</text>
        </view>
      </view>
    </view>

    <!-- ═══ Recommend Tab ═══ -->
    <view class="hc-content" v-show="activeTab==='recommend'">
      <view class="hc-actions"><view class="hc-btn hc-btn-primary" @click="openRecommendPicker()">+ 添加商品</view></view>
      <view class="hc-empty" v-if="recommends.length===0">暂无推荐商品</view>
      <view class="hc-card" v-for="(r,i) in recommends" :key="r.id">
        <view class="hc-card-row">
          <image v-if="r.coverImage" :src="assetUrl(r.coverImage)" mode="aspectFill" class="hc-thumb" @error="onImgError"/>
          <view v-else class="hc-thumb-placeholder">📦</view>
          <view class="hc-card-info">
            <text class="hc-card-title">{{r.productName||r.title||'未知商品'}}</text>
            <text class="hc-card-sub">{{r.merchantName||''}}</text>
          </view>
        </view>
        <view class="hc-card-bar">
          <text class="hc-arrow-btn" @click="moveUp('recommend',i)" :class="{disabled:i===0}">▲</text>
          <text class="hc-arrow-btn" @click="moveDown('recommend',i)" :class="{disabled:i===recommends.length-1}">▼</text>
          <text class="hc-link hc-del" @click="deleteConfig('recommend',r.id)">移除</text>
        </view>
      </view>
    </view>

    <!-- ═══ Banner Add/Edit Modal ═══ -->
    <view class="hc-overlay" v-if="bannerFormVisible" @click.self="bannerFormVisible=false">
      <view class="hc-modal">
        <text class="hc-modal-title">{{editingBanner?'编辑轮播图':'新增轮播图'}}</text>
        <!-- Image Upload -->
        <view class="hc-upload-box" @click="chooseBannerImage">
          <image v-if="bannerForm.imageUrl" :src="assetUrl(bannerForm.imageUrl)" mode="aspectFill" class="hc-upload-img"/>
          <text v-else class="hc-upload-hint">点击上传图片 (750×400)</text>
        </view>
        <input type="file" ref="bannerFileInput" accept="image/*" style="display:none" @change="onBannerFilePicked"/>
        <input class="hc-input" v-model="bannerForm.title" placeholder="标题（如：618燃气灶大促）"/>
        <input class="hc-input" v-model="bannerForm.jumpPath" placeholder="跳转小程序路径（如：pages/product/detail?id=10）"/>
        <view class="hc-form-row"><text>排序</text><input class="hc-input hc-inline" type="number" v-model.number="bannerForm.sort" placeholder="0"/></view>
        <view class="hc-modal-bar">
          <view class="hc-btn hc-btn-ghost" @click="bannerFormVisible=false">取消</view>
          <view class="hc-btn hc-btn-primary" @click="saveBanner()">保存</view>
        </view>
      </view>
    </view>

    <!-- ═══ Video Picker Modal ═══ -->
    <view class="hc-overlay" v-if="videoPickerVisible" @click.self="videoPickerVisible=false">
      <view class="hc-modal" style="max-height:80vh;overflow-y:auto;">
        <text class="hc-modal-title">选择活动视频</text>
        <view class="hc-empty" v-if="availableVideos.length===0" style="padding:32rpx 0;">暂无可选视频</view>
        <view class="hc-pick-item" v-for="av in availableVideos" :key="av.id">
          <view class="hc-pick-info">
            <text class="hc-pick-name">{{av.title}}</text>
            <text class="hc-pick-sub">{{av.startTime||''}}</text>
          </view>
          <view class="hc-btn hc-btn-sm" :class="selectedVideoIds.includes(av.id)?'hc-btn-outline':''" @click="toggleVideoSelect(av.id)">
            {{selectedVideoIds.includes(av.id)?'已选':'选择'}}
          </view>
        </view>
        <view class="hc-modal-bar">
          <view class="hc-btn hc-btn-ghost" @click="videoPickerVisible=false">取消</view>
          <view class="hc-btn hc-btn-primary" @click="saveVideoSelection()">保存选中 ({{selectedVideoIds.length}})</view>
        </view>
      </view>
    </view>

    <!-- ═══ Recommend Picker Modal ═══ -->
    <view class="hc-overlay" v-if="recommendPickerVisible" @click.self="recommendPickerVisible=false">
      <view class="hc-modal" style="max-height:80vh;overflow-y:auto;">
        <text class="hc-modal-title">选择推荐商品</text>
        <view class="hc-empty" v-if="availableProducts.length===0" style="padding:32rpx 0;">暂无可选商品</view>
        <view class="hc-pick-item" v-for="ap in availableProducts" :key="ap.mappingId">
          <view class="hc-pick-info">
            <text class="hc-pick-name">{{ap.name}}</text>
            <text class="hc-pick-sub">{{ap.merchantName||''}} · {{ap.pointsPrice||0}}积分</text>
          </view>
          <view class="hc-btn hc-btn-sm" :class="selectedProductIds.includes(ap.mappingId)?'hc-btn-outline':''" @click="toggleProductSelect(ap.mappingId)">
            {{selectedProductIds.includes(ap.mappingId)?'已选':'选择'}}
          </view>
        </view>
        <view class="hc-modal-bar">
          <view class="hc-btn hc-btn-ghost" @click="recommendPickerVisible=false">取消</view>
          <view class="hc-btn hc-btn-primary" @click="saveRecommendSelection()">保存选中 ({{selectedProductIds.length}})</view>
        </view>
      </view>
    </view>

    <!-- ═══ Cropper Overlay ═══ -->
    <view class="hc-overlay" v-if="cropperVisible" style="z-index:1000;">
      <view class="hc-cropper-container">
        <text class="hc-modal-title">裁剪图片 (750×400)</text>
        <view class="hc-cropper-wrap"><image :src="cropSrc" id="hcCropperImg" style="max-width:100%;display:block;"/></view>
        <view class="hc-cropper-bar">
          <view class="hc-btn hc-btn-sm" @click="cropperRotate(-15)">↺</view>
          <view class="hc-btn hc-btn-sm" @click="cropperRotate(15)">↻</view>
          <view class="hc-btn hc-btn-sm" @click="cropperReset()">重置</view>
          <view class="hc-btn hc-btn-ghost" @click="cropperVisible=false">取消</view>
          <view class="hc-btn hc-btn-primary" @click="cropperConfirm()">确定裁剪</view>
        </view>
      </view>
    </view>

    <!-- Toast -->
    <view class="hc-toast" v-if="toastMsg">{{toastMsg}}</view>
  </view>
</template>

<script setup>
import {ref,reactive,onMounted,nextTick,onBeforeUnmount} from 'vue'
import {api} from '@/api/index'
import {assetUrl,onImgError} from '@/utils/image.js'
import Cropper from 'cropperjs'
import 'cropperjs/dist/cropper.css'

// --- state ---
const activeTab = ref('banner')
const banners = ref([])
const videos = ref([])
const recommends = ref([])
const toastMsg = ref('')
let toastTimer = null
function showToast(m){toastMsg.value=m;clearTimeout(toastTimer);toastTimer=setTimeout(()=>{toastMsg.value=''},2000)}

// --- load ---
async function loadBanners(){
  try{const r=await api.adminHomeConfigs({configType:'banner',pageSize:100});banners.value=r.records||[]}catch{showToast('加载轮播图失败')}
}
async function loadVideos(){
  try{
    const r=await api.adminHomeConfigs({configType:'video',pageSize:100})
    const list=r.records||[]
    // Enrich with activity info
    try{
      const av=await api.adminAvailableVideos();const m={};av.forEach(a=>{m[a.id]=a})
      list.forEach(v=>{const a=m[v.targetId];if(a){v.activityTitle=a.title;v.videoCoverUrl=a.videoCoverUrl;v.coverImage=a.coverImage}})
    }catch{}
    videos.value=list
  }catch{showToast('加载视频列表失败')}
}
async function loadRecommends(){
  try{
    const r=await api.adminHomeConfigs({configType:'recommend',pageSize:100})
    const list=r.records||[]
    try{
      const ap=await api.adminRecommendableProducts();const m={};ap.forEach(p=>{m[p.mappingId]=p})
      list.forEach(r=>{const p=m[r.targetId];if(p){r.productName=p.name;r.coverImage=p.coverImage;r.merchantName=p.merchantName}})
    }catch{}
    recommends.value=list
  }catch{showToast('加载推荐列表失败')}
}

// --- move / sort ---
function swapSort(list,i,j){const t=list[i].sort;list[i].sort=list[j].sort;list[j].sort=t}
async function doBatchSort(list){
  try{await api.adminBatchSortHomeConfig(list.map(x=>({id:x.id,sort:x.sort})));showToast('排序已更新')}catch{showToast('排序更新失败')}
}
async function moveUp(type,i){
  const list=type==='banner'?banners:type==='video'?videos:recommends
  if(i<=0)return;swapSort(list,i,i-1);[list[i],list[i-1]]=[list[i-1],list[i]];await doBatchSort(list)
}
async function moveDown(type,i){
  const list=type==='banner'?banners:type==='video'?videos:recommends
  if(i>=list.length-1)return;swapSort(list,i,i+1);[list[i],list[i+1]]=[list[i+1],list[i]];await doBatchSort(list)
}
async function updateSort(type,id,i,val){
  const list=type==='banner'?banners:type==='video'?videos:recommends
  list[i].sort=val;await doBatchSort(list)
}
async function toggleStatus(type,item){
  try{await api.adminUpdateHomeConfig(item.id,{status:item.status===1?0:1});item.status=item.status===1?0:1;showToast('状态已更新')}catch{showToast('更新失败')}
}
async function deleteConfig(type,id){
  try{await api.adminDeleteHomeConfig(id)
    if(type==='banner'){banners.value=banners.value.filter(x=>x.id!==id)}
    else if(type==='video'){videos.value=videos.value.filter(x=>x.id!==id)}
    else{recommends.value=recommends.value.filter(x=>x.id!==id)}
    showToast('已删除')
  }catch{showToast('删除失败')}
}

// --- Banner Form ---
const bannerFormVisible=ref(false),editingBanner=ref(null)
const bannerForm=reactive({imageUrl:'',title:'',jumpPath:'',sort:0})
function openBannerForm(item){
  editingBanner.value=item||null
  if(item){bannerForm.imageUrl=item.imageUrl||'';bannerForm.title=item.title||'';bannerForm.jumpPath=item.jumpPath||'';bannerForm.sort=item.sort||0}
  else{bannerForm.imageUrl='';bannerForm.title='';bannerForm.jumpPath='';bannerForm.sort=banners.value.length+1}
  bannerFormVisible.value=true
}
async function saveBanner(){
  const data={configType:'banner',title:bannerForm.title,imageUrl:bannerForm.imageUrl,jumpPath:bannerForm.jumpPath,sort:bannerForm.sort}
  try{
    if(editingBanner.value){await api.adminUpdateHomeConfig(editingBanner.value.id,data)}
    else{await api.adminCreateHomeConfig(data)}
    bannerFormVisible.value=false;editingBanner.value=null;loadBanners();showToast('已保存')
  }catch{showToast('保存失败')}
}

// --- Banner Image Upload ---
const bannerFileInput=ref(null)
function chooseBannerImage(){bannerFileInput.value&&bannerFileInput.value.click()}
const cropperVisible=ref(false),cropSrc=ref('')
let cropperInstance=null
async function onBannerFilePicked(e){
  const file=e.target.files?.[0];if(!file)return
  cropSrc.value=URL.createObjectURL(file)
  cropperVisible.value=true
  await nextTick()
  if(cropperInstance){cropperInstance.destroy();cropperInstance=null}
  const img=document.getElementById('hcCropperImg')
  if(!img)return
  cropperInstance=new Cropper(img,{aspectRatio:750/400,viewMode:1,autoCropArea:1,responsive:true,background:false})
}
function cropperRotate(deg){cropperInstance&&cropperInstance.rotate(deg)}
function cropperReset(){cropperInstance&&cropperInstance.reset()}
async function cropperConfirm(){
  if(!cropperInstance)return
  const canvas=cropperInstance.getCroppedCanvas({width:750,height:400})
  canvas.toBlob(async(blob)=>{
    try{
      const url=await uploadFile(blob,'banner_'+Date.now()+'.jpg')
      bannerForm.imageUrl=url;cropperVisible.value=false
      if(cropperInstance){cropperInstance.destroy();cropperInstance=null}
      showToast('图片已上传')
    }catch{showToast('上传失败')}
  },'image/jpeg',0.9)
}
onBeforeUnmount(()=>{if(cropperInstance){cropperInstance.destroy();cropperInstance=null}})

// --- File Upload ---
async function uploadFile(blob,filename){
  const token=uni.getStorageSync('ops_token')||''
  const formData=new FormData();formData.append('file',blob,filename)
  return new Promise((resolve,reject)=>{
    const xhr=new XMLHttpRequest()
    xhr.open('POST','/api/ops/files/upload')
    if(token)xhr.setRequestHeader('Authorization','Bearer '+token)
    xhr.onload=()=>{try{const d=JSON.parse(xhr.responseText);if(d.ok&&d.data?.url)resolve(d.data.url);else reject(d)}catch{reject(xhr)}}
    xhr.onerror=()=>reject(new Error('Upload failed'))
    xhr.send(formData)
  })
}

// --- Video Picker ---
const videoPickerVisible=ref(false),availableVideos=ref([]),selectedVideoIds=ref([])
async function openVideoPicker(){
  videoPickerVisible.value=true;selectedVideoIds.value=videos.value.map(v=>v.targetId).filter(Boolean)
  try{availableVideos.value=await api.adminAvailableVideos()}catch{showToast('加载视频列表失败')}
}
function toggleVideoSelect(id){const idx=selectedVideoIds.value.indexOf(id);if(idx>=0)selectedVideoIds.value.splice(idx,1);else selectedVideoIds.value.push(id)}
async function saveVideoSelection(){
  const currentIds=videos.value.map(v=>v.targetId).filter(Boolean)
  const toAdd=selectedVideoIds.value.filter(id=>!currentIds.includes(id))
  const toRemove=videos.value.filter(v=>!selectedVideoIds.value.includes(v.targetId))
  // Remove deselected
  for(const v of toRemove){try{await api.adminDeleteHomeConfig(v.id)}catch{}}
  // Add selected
  for(let i=0;i<toAdd.length;i++){
    try{await api.adminCreateHomeConfig({configType:'video',targetId:toAdd[i],sort:videos.value.length+i+1})}catch{}
  }
  videoPickerVisible.value=false;loadVideos();showToast('已更新')
}

// --- Recommend Picker ---
const recommendPickerVisible=ref(false),availableProducts=ref([]),selectedProductIds=ref([])
async function openRecommendPicker(){
  recommendPickerVisible.value=true;selectedProductIds.value=recommends.value.map(r=>r.targetId).filter(Boolean)
  try{availableProducts.value=await api.adminRecommendableProducts()}catch{showToast('加载商品列表失败')}
}
function toggleProductSelect(mappingId){const idx=selectedProductIds.value.indexOf(mappingId);if(idx>=0)selectedProductIds.value.splice(idx,1);else selectedProductIds.value.push(mappingId)}
async function saveRecommendSelection(){
  const currentIds=recommends.value.map(r=>r.targetId).filter(Boolean)
  const toAdd=selectedProductIds.value.filter(id=>!currentIds.includes(id))
  const toRemove=recommends.value.filter(r=>!selectedProductIds.value.includes(r.targetId))
  for(const r of toRemove){try{await api.adminDeleteHomeConfig(r.id)}catch{}}
  for(let i=0;i<toAdd.length;i++){
    try{await api.adminCreateHomeConfig({configType:'recommend',targetId:toAdd[i],sort:recommends.value.length+i+1})}catch{}
  }
  recommendPickerVisible.value=false;loadRecommends();showToast('已更新')
}

// --- init ---
onMounted(async()=>{
  // ensure login
  const token=uni.getStorageSync('ops_token')
  if(!token){const r=await api.login('13900000001','admin123');if(r)uni.setStorageSync('ops_token',r.token)}
  loadBanners();loadVideos();loadRecommends()
})
</script>

<style scoped>
.page{min-height:100vh;background:#f5f5f5;padding-bottom:48rpx}
.hc-topbar{background:linear-gradient(135deg,#FF6B35,#FF8C5A);padding:24rpx 32rpx;color:#fff}
.hc-topbar-title{font-size:36rpx;font-weight:600}
.hc-tabs{display:flex;background:#fff;border-bottom:2rpx solid #eee;position:sticky;top:0;z-index:10}
.hc-tab{flex:1;text-align:center;padding:24rpx 0;font-size:28rpx;color:#666;border-bottom:4rpx solid transparent}
.hc-tab.active{color:#FF6B35;border-bottom-color:#FF6B35;font-weight:600}
.hc-content{padding:24rpx}
.hc-actions{margin-bottom:24rpx}
.hc-empty{text-align:center;color:#999;padding:80rpx 0;font-size:28rpx}
/* Cards */
.hc-card{background:#fff;border-radius:16rpx;padding:24rpx;margin-bottom:20rpx;box-shadow:0 2rpx 12rpx rgba(0,0,0,.06)}
.hc-card-row{display:flex;gap:20rpx}
.hc-thumb{width:140rpx;height:100rpx;border-radius:10rpx;background:#f0f0f0;flex-shrink:0}
.hc-thumb-placeholder{width:140rpx;height:100rpx;border-radius:10rpx;background:#f0f0f0;display:flex;align-items:center;justify-content:center;font-size:36rpx;color:#ccc;flex-shrink:0}
.hc-card-info{flex:1;display:flex;flex-direction:column;gap:8rpx;min-width:0}
.hc-card-title{font-size:28rpx;color:#333;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.hc-card-sub{font-size:22rpx;color:#999;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.hc-card-meta{display:flex;align-items:center;gap:16rpx;margin-top:4rpx}
.hc-sort-input{width:80rpx;height:48rpx;border:1rpx solid #ddd;border-radius:8rpx;text-align:center;font-size:24rpx}
.hc-status{padding:4rpx 16rpx;border-radius:20rpx;font-size:22rpx;background:#eee;color:#999}
.hc-status.on{background:#e8f5e9;color:#4caf50}
.hc-card-bar{display:flex;justify-content:flex-end;align-items:center;gap:24rpx;margin-top:16rpx;padding-top:16rpx;border-top:1rpx solid #f5f5f5}
.hc-arrow-btn{font-size:24rpx;color:#666;padding:4rpx 12rpx}
.hc-arrow-btn.disabled{color:#ccc;pointer-events:none}
.hc-link{font-size:24rpx;color:#FF6B35}
.hc-link.hc-del{color:#e53935}
/* Buttons */
.hc-btn{padding:16rpx 32rpx;border-radius:10rpx;font-size:26rpx;text-align:center}
.hc-btn-primary{background:#FF6B35;color:#fff}
.hc-btn-ghost{background:#f5f5f5;color:#666}
.hc-btn-sm{padding:10rpx 20rpx;font-size:22rpx;border-radius:8rpx;background:#FF6B35;color:#fff;white-space:nowrap}
.hc-btn-outline{background:#fff;color:#FF6B35;border:2rpx solid #FF6B35}
/* Modal */
.hc-overlay{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.5);z-index:100;display:flex;align-items:center;justify-content:center}
.hc-modal{background:#fff;border-radius:20rpx;padding:40rpx;width:640rpx;max-width:90vw;max-height:70vh;overflow-y:auto}
.hc-modal-title{font-size:32rpx;font-weight:600;color:#333;margin-bottom:24rpx;display:block}
.hc-modal-bar{display:flex;justify-content:flex-end;gap:20rpx;margin-top:32rpx}
.hc-input{width:100%;height:80rpx;border:1rpx solid #ddd;border-radius:10rpx;padding:0 20rpx;font-size:26rpx;margin-bottom:16rpx;box-sizing:border-box}
.hc-inline{width:120rpx;margin-bottom:0;margin-left:16rpx}
.hc-form-row{display:flex;align-items:center;font-size:26rpx;color:#666}
/* Upload */
.hc-upload-box{width:560rpx;height:300rpx;border:2rpx dashed #ddd;border-radius:16rpx;display:flex;align-items:center;justify-content:center;margin-bottom:20rpx;overflow:hidden;background:#fafafa}
.hc-upload-img{width:100%;height:100%}
.hc-upload-hint{color:#bbb;font-size:26rpx}
/* Picker items */
.hc-pick-item{display:flex;align-items:center;justify-content:space-between;padding:20rpx 0;border-bottom:1rpx solid #f5f5f5;gap:20rpx}
.hc-pick-name{font-size:26rpx;color:#333;display:block}
.hc-pick-sub{font-size:22rpx;color:#999;display:block;margin-top:4rpx}
.hc-pick-info{flex:1;min-width:0}
/* Cropper */
.hc-cropper-container{background:#fff;border-radius:20rpx;padding:32rpx;width:700rpx;max-width:95vw}
.hc-cropper-wrap{width:100%;max-height:500rpx;overflow:hidden;margin-bottom:20rpx}
.hc-cropper-bar{display:flex;justify-content:flex-end;gap:12rpx;flex-wrap:wrap}
/* Toast */
.hc-toast{position:fixed;bottom:200rpx;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.75);color:#fff;padding:16rpx 40rpx;border-radius:40rpx;font-size:26rpx;z-index:9999}
</style>
