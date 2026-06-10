/**
 * 环境感知配置。
 * 本地 dev 走 Vite proxy（相对路径），生产走 Nginx 反代统一协议。
 * 通过 .env 中的 VITE_* 变量控制。
 */
// #ifdef H5
export const BASE_URL = '' // H5 模式走相对路径，由 Nginx 反代统一协议
// #endif
// #ifdef MP-WEIXIN
export const BASE_URL = 'https://a.15891589.xyz' // 小程序模式走完整 HTTPS URL
// #endif

export const DEFAULT_CORP_ID = 4
