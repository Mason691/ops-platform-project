/**
 * 图片 URL 工具 — 统一处理 H5 / 小程序两种模式下的资源路径。
 *
 * - H5 模式：BASE_URL = ''，走根相对路径 `/uploads/...`，由 Nginx 反代
 * - 小程序模式：BASE_URL = 'https://a.15891589.xyz'，拼接完整 HTTPS URL
 */

import { BASE_URL } from '@/config'

/**
 * 1×1 透明占位 SVG（data URI，不依赖外部文件，避免 404）
 */
export const PLACEHOLDER_SVG =
  'data:image/svg+xml,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" fill="%23f0f0f0">' +
      '<rect width="400" height="400"/>' +
      '<text x="50%" y="50%" dominant-baseline="middle" text-anchor="middle" fill="%23bbb" font-size="28" font-family="sans-serif">暂无图片</text>' +
    '</svg>'
  )

/**
 * 将相对路径或绝对路径解析为可用的图片 URL。
 * - 空值 / "null" / "None" 字符串 → 返回占位 SVG
 * - http/https/data: → 原样返回
 * - / 开头 → 原样返回（根相对路径，Nginx 反代即可）
 * - 不以 / 开头 → 补齐前导 /
 */
export function assetUrl(path) {
  if (!path || path === 'null' || path === 'None' || path === '') {
    return PLACEHOLDER_SVG
  }

  const str = String(path).trim()

  // 完整 URL 或 Data URI → 直接使用
  if (/^https?:\/\//.test(str) || /^data:/.test(str)) {
    return str
  }

  // 根相对路径 → 保持（H5 走相对，小程序走 BASE_URL+path）
  if (str.startsWith('/')) {
    // H5 BASE_URL='' → 结果仍为 /xxx；小程序 BASE_URL='https://...' → 完整 URL
    return BASE_URL + str
  }

  // 缺少前导 / 的路径 → 补齐
  return BASE_URL + '/' + str
}

/**
 * <image> 加载失败回调 — 将 src 替换为占位 SVG。
 * 用法：<image :src="assetUrl(url)" @error="onImgError" />
 */
export function onImgError(e) {
  const img = e?.target || e?.detail?.target
  if (!img || !img.src) return
  // 避免无限重试
  img.onerror = null
  // uni-app <image> 组件在 H5 模式渲染为 <img>，小程序为原生组件
  if (typeof img.setAttribute === 'function') {
    img.setAttribute('src', PLACEHOLDER_SVG)
  } else if (img.src !== undefined) {
    img.src = PLACEHOLDER_SVG
  }
}
