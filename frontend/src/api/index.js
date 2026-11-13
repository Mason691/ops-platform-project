import { request } from '@/utils/request'

/** Strict token resolution — member and employee tokens are NEVER mixed. */
function resolveToken() {
  const tt = uni.getStorageSync('token_type') || ''
  if (tt === 'employee') return uni.getStorageSync('ops_employee_token') || ''
  if (tt === 'member')   return uni.getStorageSync('ops_token') || ''
  return uni.getStorageSync('ops_employee_token') || uni.getStorageSync('ops_token') || ''
}

export const api = {
  // 登录 & 注册
  login: (phone, password) => request({url:'/api/ops/auth/login',method:'POST',data:{phone,password}}),
  register: (payload) => request({url:'/api/ops/auth/register',method:'POST',data:payload}),
  wechatLogin: (payload) => request({url:'/api/ops/auth/wechat-login',method:'POST',data:payload}),
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
  aiChatAsync: (data) => request({url:'/api/ops/ai/chat/async',method:'POST',data}),
  aiChatStatus: (taskId) => request({url:'/api/ops/ai/chat/'+taskId+'/status'}),
  aiChatSseUrl: (taskId) => {
    const entry = import.meta.env.VITE_OPS_ENTRY || ''
    return entry + '/api/ops/ai/chat/' + taskId + '/stream'
  },
  aiConfig: () => request({url:'/api/ops/ai/config'}),

  // Employee AI 助手
  employeeAiChat: (data) => request({url:'/api/ops/ai/employee/chat',method:'POST',data}),
  employeeAiChatAsync: (data) => request({url:'/api/ops/ai/employee/chat/async',method:'POST',data}),
  employeeAiChatStatus: (taskId) => request({url:'/api/ops/ai/employee/chat/'+taskId+'/status'}),
  employeeAiChatSseUrl: (taskId) => {
    const entry = import.meta.env.VITE_OPS_ENTRY || ''
    return entry + '/api/ops/ai/employee/chat/' + taskId + '/stream'
  },
  employeeAiConfig: () => request({url:'/api/ops/ai/employee/config'}),
  employeeAiProfile: () => request({url:'/api/ops/ai/employee/profile'}),

  /** Build download URL for an AI-generated report by token */
  employeeAiReportDownloadUrl: (token) => {
    const entry = import.meta.env.VITE_OPS_ENTRY || ''
    return entry + '/api/ops/ai/employee/report/' + token
  },

  // 技能市场（代理到平台 /api/skill/installed）
  installedSkills: () => request({url:'/api/ops/ai/skills/installed'}),

  // 文件分析 — 直接调平台 API
  fileAnalysisResult: (fileId) => request({url:'/api/ai/file/'+fileId+'/result'}),

  // 站长
  stationApply: (data) => request({url:'/api/ops/station/apply',method:'POST',data}),
  stationApplyStatus: () => request({url:'/api/ops/station/apply/status'}),
  stationInfo: () => request({url:'/api/ops/station/info'}),

  aiVoiceRecognize: (blob) => {
    // Upload audio blob as multipart/form-data via XMLHttpRequest
    // (uni.uploadFile requires filePath, not blob; XHR works in H5)
    return new Promise((resolve, reject) => {
      const token = resolveToken()
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
  // 会员 AI 设置
  memberAiGetProfile: () => request({url:'/api/ops/ai/member/profile'}),
  memberAiUpdateStyle: (style) => request({url:'/api/ops/ai/member/profile/style',method:'PUT',data:{style}}),
  memberAiGetPreferences: () => request({url:'/api/ops/ai/member/preferences'}),
  memberAiSetPreference: (key, value) => request({url:'/api/ops/ai/member/preferences',method:'PUT',data:{key,value}}),
  memberAiDeletePreference: (key) => request({url:'/api/ops/ai/member/preferences/'+key,method:'DELETE'}),
  memberAiGetHabits: () => request({url:'/api/ops/ai/member/habits'}),
  memberAiAcknowledgeHabit: (key) => request({url:'/api/ops/ai/member/habits/'+key+'/acknowledge',method:'PUT'}),
  memberAiDeleteHabit: (key) => request({url:'/api/ops/ai/member/habits/'+key,method:'DELETE'}),
  memberAiGetMemories: () => request({url:'/api/ops/ai/member/memories'}),
  // 增值业务 — 业务数据
  businessDataList: (params) => request({url:'/api/ops/admin/business-data',data:params}),
  businessDataBatches: () => request({url:'/api/ops/admin/business-data/batches'}),
  businessDataDelete: (ids) => request({url:'/api/ops/admin/business-data/batch',method:'DELETE',data:{ids}}),
  businessDataCalcCommission: () => request({url:'/api/ops/admin/business-data/calc-commission',method:'POST'}),
  businessDataExport: (params) => request({url:'/api/ops/admin/business-data/export',method:'POST',data:params}),
  // 业务数据 — 文件上传 (异步导入)
  businessDataImportSubmit: (file) => {
    return new Promise((resolve, reject) => {
      const token = uni.getStorageSync('ops_token') || ''
      const formData = new FormData()
      formData.append('file', file)
      const xhr = new XMLHttpRequest()
      xhr.open('POST', '/api/ops/admin/business-data/import/submit')
      if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
      xhr.onload = () => {
        try { const d = JSON.parse(xhr.responseText); if (d.ok && d.data) resolve(d.data); else reject(d) }
        catch(e) { reject(xhr) }
      }
      xhr.onerror = () => reject(new Error('网络错误'))
      xhr.send(formData)
    })
  },
  businessDataImportStatus: (taskId) => request({url:'/api/ops/admin/business-data/import/'+taskId+'/status'}),
  businessDataImportHistory: () => request({url:'/api/ops/admin/business-data/import/history'}),
  // 业务数据 — 文件上传 (同步导入，降级用)
  businessDataImport: (file) => {
    return new Promise((resolve, reject) => {
      const token = uni.getStorageSync('ops_token') || ''
      const formData = new FormData()
      formData.append('file', file)
      const xhr = new XMLHttpRequest()
      xhr.open('POST', '/api/ops/admin/business-data/import')
      if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
      xhr.onload = () => {
        try { const d = JSON.parse(xhr.responseText); if (d.ok && d.data) resolve(d.data); else reject(d) }
        catch(e) { reject(xhr) }
      }
      xhr.onerror = () => reject(new Error('网络错误'))
      xhr.send(formData)
    })
  },
  businessDataTemplateUrl: () => {
    const BASE_URL = import.meta.env.VITE_OPS_ENTRY || ''
    return BASE_URL + '/api/ops/admin/business-data/template'
  },
  // 用户头像
  uploadAvatar: (imageData) => request({url:'/api/ops/user/avatar',method:'POST',data:{imageData}}),
  // 视频直播
  activeLiveRooms: () => request({url:'/api/ops/live-rooms/active',data:{limit:20}}),
  liveRoomDetail: (id) => request({url:'/api/ops/live-rooms/'+id}),
  remindLiveRoom: (id) => request({url:'/api/ops/live-rooms/'+id+'/remind',method:'POST'}),

  // 管理后台 — 直播管理
  adminLiveRooms: (params) => request({url:'/api/ops/admin/live-rooms',data:params}),
  adminCreateLiveRoom: (data) => request({url:'/api/ops/admin/live-rooms',method:'POST',data}),
  adminUpdateLiveRoom: (id,data) => request({url:'/api/ops/admin/live-rooms/'+id,method:'PUT',data}),
  adminDeleteLiveRoom: (id) => request({url:'/api/ops/admin/live-rooms/'+id,method:'DELETE'}),
  adminLiveRoomDetail: (id) => request({url:'/api/ops/admin/live-rooms/'+id}),
  adminUpdateLiveRoomStatus: (id,data) => request({url:'/api/ops/admin/live-rooms/'+id+'/status',method:'PUT',data}),
  adminLiveRoomQrCode: (id) => request({url:'/api/ops/admin/live-rooms/'+id+'/qrcode'}),

  // 管理后台 — 会员管理
  adminMembers: (params) => request({url:'/api/ops/admin/members',data:params}),
  adminMemberDetail: (id) => request({url:'/api/ops/admin/members/'+id}),
  adminMemberStatus: (id, status) => request({url:'/api/ops/admin/members/'+id+'/status',method:'PUT',data:{status}}),
  permissionProfile: () => request({url:'/api/ops/admin/permission/profile'}),

  // 燃气用户管理
  gasUserList: (params) => request({url:'/api/ops/admin/gas-user',data:params}),
  gasUserDetail: (id) => request({url:'/api/ops/admin/gas-user/'+id}),
  gasUserUpdate: (id, data) => request({url:'/api/ops/admin/gas-user/'+id,method:'PUT',data}),
  gasUserSearchByArea: (params) => request({url:'/api/ops/admin/gas-user/search-by-area',data:params}),
  gasUserBatchAssignCommunity: (data) => request({url:'/api/ops/admin/gas-user/batch-assign-community',method:'PUT',data}),
  gasUserCommunities: () => request({url:'/api/ops/admin/gas-user/communities'}),
  // 燃气用户 — 从外部燃气平台导入
  gasUserImportSubmit: (companyName) => request({url:'/api/ops/admin/gas-user/import-from-gas-platform',method:'POST',data:{companyName}}),
  gasUserImportStatus: (taskId) => request({url:'/api/ops/admin/gas-user/import/'+taskId+'/status'}),
  gasUserPreviewFromPlatform: (companyName) => request({url:'/api/ops/admin/gas-user/preview-from-gas-platform',method:'POST',data:{companyName}}),
  gasUserOrgShortName: () => request({url:'/api/ops/admin/gas-user/org-short-name'}),
  // 燃气用户 — 批量转化会员（异步）
  gasUserConvertSubmit: (data) => request({url:'/api/ops/admin/gas-user/convert/submit',method:'POST',data}),
  gasUserConvertStatus: (taskId) => request({url:'/api/ops/admin/gas-user/convert/'+taskId+'/status'}),

  // 员工身份 & 业务数据
  employeeIdentity: () => request({url:'/api/ops/employee/identity'}),
  employeeMonths: () => request({url:'/api/ops/employee/business-data/months'}),
  employeeSummary: (month) => request({url:'/api/ops/employee/business-data/summary',data:{month}}),
  employeeDetail: (month, category) => request({url:'/api/ops/employee/business-data/detail',data:{month, category}}),
  employeeStats: (month) => request({url:'/api/ops/employee/business-data/stats',data:{month}}),
  employeeOverview: (params) => request({url:'/api/ops/employee/business-data/overview',data:params}),
  employeeBatches: () => request({url:'/api/ops/employee/business-data/batches'}),
  employeeBatchCheck: (data) => request({url:'/api/ops/employee/business-data/batch/check',method:'POST',data}),
  // 员工手工新增业务数据
  employeeManualOptions: () => request({url:'/api/ops/employee/business-data/manual/options'}),
  employeeLookupMember: (phone) => request({url:'/api/ops/employee/business-data/manual/member',data:{phone}}),
  employeeCreateBusiness: (data) => request({url:'/api/ops/employee/business-data/manual',method:'POST',data}),

  // 员工待处理工单（派工单）
  employeePendingDispatches: (params) => request({url:'/api/ops/employee/dispatch/pending',data:params}),
  employeeMyDispatches: (params) => request({url:'/api/ops/employee/dispatch/mine',data:params}),
  employeeDispatchDetail: (id) => request({url:'/api/ops/employee/dispatch/' + id}),
  employeeAcceptDispatch: (id) => request({url:'/api/ops/employee/dispatch/' + id + '/accept',method:'POST'}),
  employeeStartDispatch: (id) => request({url:'/api/ops/employee/dispatch/' + id + '/start',method:'POST'}),
  employeeCompleteDispatch: (id) => request({url:'/api/ops/employee/dispatch/' + id + '/complete',method:'POST'}),
  employeeRejectDispatch: (id, data) => request({url:'/api/ops/employee/dispatch/' + id + '/reject',method:'POST',data}),
  // 员工 H5 发起派工（城市合伙人管理员/运营商/财务）
  employeeCanDispatch: () => request({url:'/api/ops/employee/dispatch/manage/can-dispatch'}),
  employeeDispatchableOrders: (params) => request({url:'/api/ops/employee/dispatch/manage/orders',data:params}),
  employeeDispatchableOrder: (orderId) => request({url:'/api/ops/employee/dispatch/manage/orders/' + orderId}),
  employeeDispatchAssignees: () => request({url:'/api/ops/employee/dispatch/manage/assignees'}),
  employeeCreateDispatch: (data) => request({url:'/api/ops/employee/dispatch/manage/create',method:'POST',data}),

  // 员工登录（独立于会员登录）
  employeeCaptcha: () => request({url:'/api/ops/auth/employee/captcha'}),
  employeeLogin: (data) => request({url:'/api/ops/auth/employee/login',method:'POST',data}),
  employeeRefreshToken: () => request({url:'/api/ops/auth/employee/refresh-token',method:'POST'}),
  // 微信授权登录 / 绑定
  employeeWechatAuthorizeUrl: (mode = 'login', corpId) => request({
    url: '/api/ops/auth/employee/wechat/authorize-url',
    data: corpId ? { mode, corpId } : { mode }
  }),
  employeeWechatLogin: (data) => request({url:'/api/ops/auth/employee/wechat/login',method:'POST',data}),
  employeeWechatBind: (data) => request({url:'/api/ops/auth/employee/wechat/bind',method:'POST',data}),
  employeeWechatBindStatus: () => request({url:'/api/ops/auth/employee/wechat/bind-status'}),
  employeeChangePassword: (data) => request({url:'/api/ops/employee/change-password',method:'POST',data}),
  employeeUploadAvatar: (filePath) => new Promise((resolve, reject) => {
    const token = resolveToken()
    uni.uploadFile({
      url: import.meta.env.VITE_OPS_ENTRY + '/api/ops/employee/avatar' || '/api/ops/employee/avatar',
      filePath,
      name: 'file',
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success(res) { try { const d = JSON.parse(res.data); if (d.ok) resolve(d.data); else reject(d) } catch(e) { reject(e) } },
      fail(err) { reject(err) }
    })
  }),
  employeeUpdateProfile: (data) => request({url:'/api/ops/employee/profile',method:'PUT',data}),

  // 提成规则 — 个人
  commissionPersonalList: (params) => request({url:'/api/ops/admin/commission/personal',data:params}),
  commissionPersonalDetail: (id) => request({url:'/api/ops/admin/commission/personal/'+id}),
  commissionPersonalCreate: (data) => request({url:'/api/ops/admin/commission/personal',method:'POST',data}),
  commissionPersonalUpdate: (id, data) => request({url:'/api/ops/admin/commission/personal/'+id,method:'PUT',data}),
  commissionPersonalDelete: (id) => request({url:'/api/ops/admin/commission/personal/'+id,method:'DELETE'}),
  // 提成规则 — 公司
  commissionCompanyList: (params) => request({url:'/api/ops/admin/commission/company',data:params}),
  commissionCompanyDetail: (id) => request({url:'/api/ops/admin/commission/company/'+id}),
  commissionCompanyCreate: (data) => request({url:'/api/ops/admin/commission/company',method:'POST',data}),
  commissionCompanyUpdate: (id, data) => request({url:'/api/ops/admin/commission/company/'+id,method:'PUT',data}),
  commissionCompanyDelete: (id) => request({url:'/api/ops/admin/commission/company/'+id,method:'DELETE'}),
  // 品类字典（提成规则下拉框用）
  commissionCategories: () => request({url:'/api/ops/admin/commission/categories'}),

  // 年度任务计划
  annualPlanList: (params) => request({url:'/api/ops/admin/annual-plans',data:params}),
  annualPlanDetail: (id) => request({url:'/api/ops/admin/annual-plans/'+id}),
  annualPlanCreate: (data) => request({url:'/api/ops/admin/annual-plans',method:'POST',data}),
  annualPlanUpdate: (id, data) => request({url:'/api/ops/admin/annual-plans/'+id,method:'PUT',data}),
  annualPlanDelete: (id) => request({url:'/api/ops/admin/annual-plans/'+id,method:'DELETE'}),
  annualPlanApprove: (id) => request({url:'/api/ops/admin/annual-plans/'+id+'/approve',method:'PUT'}),
  annualPlanSummary: (params) => request({url:'/api/ops/admin/annual-plans/summary',data:params||{}}),
  annualPlanSummaryByCategory: (params) => request({url:'/api/ops/admin/annual-plans/summary-by-category',data:params||{}}),
  annualPlanOrgDetail: (params) => request({url:'/api/ops/admin/annual-plans/org-detail',data:params||{}}),
  annualPlanImport: (file) => {
    return new Promise((resolve, reject) => {
      const token = uni.getStorageSync('ops_employee_token') || uni.getStorageSync('ops_token') || ''
      const formData = new FormData()
      formData.append('file', file)
      const xhr = new XMLHttpRequest()
      const baseUrl = (import.meta.env.VITE_OPS_ENTRY) || ''
      xhr.open('POST', baseUrl + '/api/ops/admin/annual-plans/import')
      if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)
      xhr.onload = () => {
        try { const d = JSON.parse(xhr.responseText); if (d.code === 200 && d.data) resolve(d.data); else reject(d) }
        catch(e) { reject(xhr) }
      }
      xhr.onerror = () => reject(new Error('网络错误'))
      xhr.send(formData)
    })
  },
}
