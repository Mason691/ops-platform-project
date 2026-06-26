/**
 * 环境感知配置。
 * - 本地 dev: VITE proxy 转发 /api 和 /uploads 到 localhost:8084
 * - 生产: Nginx 反代统一 HTTPS 协议
 *
 * H5 模式下必须使用完整 HTTPS URL（而非相对路径），
 * 因为 uni-app 的 <image> 组件在 H5 中渲染为 background-image，浏览器会
 * 将 CSS url() 中的根相对路径解析到页面 base（/subsystem/ops/），导致图片
 * 请求为 /subsystem/ops/uploads/... 而非 /uploads/...。
 *
 * 通过 .env 中的 VITE_OPS_ENTRY 控制：dev = http://localhost:3035, prod = https://boss.jdzn99.com
 */
const OPS_ENTRY = import.meta.env.VITE_OPS_ENTRY || 'https://boss.jdzn99.com'
// #ifdef H5
export const BASE_URL = OPS_ENTRY
// #endif
// #ifdef MP-WEIXIN
export const BASE_URL = OPS_ENTRY
// #endif

export const DEFAULT_CORP_ID = 4
