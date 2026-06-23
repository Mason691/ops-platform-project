import { request } from '@/utils/request'

export const api = {
  // 登录 & 注册
  login: (phone, password) => request({url:'/api/ops/auth/login',method:'POST',data:{phone,password}}),
  register: (payload) => request({url:'/api/ops/auth/register',method:'POST',data:payload}),
  getMe: () => request({url:'/api/ops/user/me'}),

  // 公众号配置
  getPublicAccountConfig: (corpId) => request({url:'/api/public-account/config',data:{corpId}}),
  updateProfile: (data) => request({url:'/api/ops/user/profile',method:'PUT',data}),
  // 商品
  productPage: (params) => request({url:'/api/ops/product/page',data:params}),
  productDetail: (id) => request({url:'/api/ops/product/'+id}),
  productCategories: () => request({url:'/api/ops/product/categories'}),

  // 运营商管理后台 — 商品展示管理
  adminProductPage: (params) => request({url:'/api/ops/admin/products',data:params}),
  adminProductShelf: (id, shelfStatus) => request({url:'/api/ops/admin/products/'+id+'/shelf-status',method:'PUT',data:{shelfStatus}}),
  adminProductBatchShelf: (ids, shelfStatus) => request({url:'/api/ops/admin/products/batch-shelf',method:'POST',data:{ids,shelfStatus}}),
  adminProductBatchCategory: (ids, categoryId) => request({url:'/api/ops/admin/products/batch-category',method:'PUT',data:{ids,categoryId}}),
  adminProductCategories: () => request({url:'/api/ops/admin/product-categories'}),
  adminCreateCategory: (data) => request({url:'/api/ops/admin/product-categories',method:'POST',data}),
  adminUpdateCategory: (id,data) => request({url:'/api/ops/admin/product-categories/'+id,method:'PUT',data}),
  adminDeleteCategory: (id) => request({url:'/api/ops/admin/product-categories/'+id,method:'DELETE'}),
  // 订单
  createOrder: (data) => request({url:'/api/ops/order/create',method:'POST',data}),
  myOrders: (params) => request({url:'/api/ops/order/my',data:params}),
  orderDetail: (no) => request({url:'/api/ops/order/'+no}),
  // 活动
  activityPage: (params) => request({url:'/api/ops/activity/page',data:params}),
  activityDetail: (id) => request({url:'/api/ops/activity/'+id}),
  // 运营商管理后台 — 活动管理
  adminActivityPage: (params) => request({url:'/api/ops/admin/activities',data:params}),
  adminActivityDetail: (id) => request({url:'/api/ops/admin/activities/'+id}),
  adminCreateActivity: (data) => request({url:'/api/ops/admin/activities',method:'POST',data}),
  adminUpdateActivity: (id,data) => request({url:'/api/ops/admin/activities/'+id,method:'PUT',data}),
  adminUpdateActivityStatus: (id,status) => request({url:'/api/ops/admin/activities/'+id+'/status',method:'PUT',data:{status}}),
  adminDeleteActivity: (id) => request({url:'/api/ops/admin/activities/'+id,method:'DELETE'}),
  adminAvailableLiveRooms: () => request({url:'/api/ops/admin/live-rooms/available'}),
  // 运营商管理后台 — 首页配置管理
  adminHomeConfigs: (params) => request({url:'/api/ops/admin/home-configs',data:params}),
  adminCreateHomeConfig: (data) => request({url:'/api/ops/admin/home-configs',method:'POST',data}),
  adminUpdateHomeConfig: (id,data) => request({url:'/api/ops/admin/home-configs/'+id,method:'PUT',data}),
  adminDeleteHomeConfig: (id) => request({url:'/api/ops/admin/home-configs/'+id,method:'DELETE'}),
  adminBatchSortHomeConfig: (items) => request({url:'/api/ops/admin/home-configs/sort',method:'PUT',data:{items}}),
  adminAvailableVideos: () => request({url:'/api/ops/admin/activities/available-videos'}),
  adminRecommendableProducts: () => request({url:'/api/ops/admin/products/recommendable'}),
  // 首页公开数据
  homeData: () => {
    const corpId = uni.getStorageSync('corp_id') || 4
    return request({url:'/api/ops/home-data', data:{corpId}})
  },
  // 地址
  addressList: () => request({url:'/api/ops/address/list'}),
  saveAddress: (data) => request({url:'/api/ops/address',method:'POST',data}),
  updateAddress: (id,data) => request({url:'/api/ops/address/'+id,method:'PUT',data}),
  deleteAddress: (id) => request({url:'/api/ops/address/'+id,method:'DELETE'}),
  defaultAddress: () => request({url:'/api/ops/address/default'}),
  // 积分
  pointsLog: (params) => request({url:'/api/ops/points/log',data:params}),
  // 优惠券
  couponList: () => request({url:'/api/ops/coupon/list'}),
  drawCoupon: (couponId) => request({url:'/api/ops/coupon/draw',method:'POST',data:{couponId}}),
  myCoupons: () => request({url:'/api/ops/coupon/my'}),
  // 历史
  history: (params) => request({url:'/api/ops/history',data:params}),
  // AI 助手
  aiChat: (data) => request({url:'/api/ops/ai/chat',method:'POST',data}),
  aiConfig: () => request({url:'/api/ops/ai/config'}),

  // 站长
  stationApply: (data) => request({url:'/api/ops/station/apply',method:'POST',data}),
  stationApplyStatus: () => request({url:'/api/ops/station/apply/status'}),
  stationInfo: () => request({url:'/api/ops/station/info'}),

  aiVoiceRecognize: (blob) => {
    // Upload audio blob as multipart/form-data via XMLHttpRequest
    // (uni.uploadFile requires filePath, not blob; XHR works in H5)
    return new Promise((resolve, reject) => {
      const token = uni.getStorageSync('ops_token') || ''
      const formData = new FormData()
      formData.append('audio', blob, 'recording.webm')
      const xhr = new XMLHttpRequest()
      xhr.open('POST', '/api/ops/ai/voice/recognize')
      if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
      xhr.onload = () => {
        try { const d = JSON.parse(xhr.responseText); if (d.ok) resolve(d.data); else reject(d) }
        catch(e) { reject(xhr) }
      }
      xhr.onerror = () => reject(new Error('网络错误'))
      xhr.send(formData)
    })
  },
  aiVoiceRecognizeWechat: (serverId) => request({url:'/api/ops/ai/voice/recognize-wechat',method:'POST',data:{serverId}}),
  aiVoiceSynthesize: (data) => request({url:'/api/ops/ai/voice/synthesize',method:'POST',data}),
  // 用户头像
  uploadAvatar: (imageData) => request({url:'/api/ops/user/avatar',method:'POST',data:{imageData}}),
  // 视频直播
  activeLiveRooms: () => request({url:'/api/ops/live-rooms/active',data:{limit:20}}),
}
