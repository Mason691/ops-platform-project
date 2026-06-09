import { request } from '@/utils/request'

export const api = {
  // 登录
  login: (phone, code) => request({url:'/api/ops/auth/login',method:'POST',data:{phone,code}}),
  getMe: () => request({url:'/api/ops/user/me'}),
  updateProfile: (data) => request({url:'/api/ops/user/profile',method:'PUT',data}),
  // 商品
  productPage: (params) => request({url:'/api/ops/product/page',data:params}),
  productDetail: (id) => request({url:'/api/ops/product/'+id}),
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
}
