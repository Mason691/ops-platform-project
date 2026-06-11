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
}
