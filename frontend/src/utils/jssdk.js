/**
 * 微信 JSSDK 初始化模块
 *
 * JSSDK 初始化已移至 index.html 内联脚本中执行（同步加载、无需轮询）。
 * 此模块保留 isWechat 和 fetchPublicAccountConfig 两个工具函数。
 */

import { DEFAULT_CORP_ID } from '@/config'

function getCorpId() {
  // #ifdef H5
  const params = new URLSearchParams(location.search)
  if (params.get('corpId')) return params.get('corpId')
  try {
    const stored = uni.getStorageSync('corp_id')
    if (stored) return String(stored)
  } catch (_) {}
  return String(DEFAULT_CORP_ID)
  // #endif
  return String(DEFAULT_CORP_ID)
}

export function isWechat() {
  // #ifdef H5
  return /micromessenger/i.test(navigator.userAgent)
  // #endif
  // #ifdef MP-WEIXIN
  return false
  // #endif
}

/** 兼容 main.js 调用，实际初始化在 index.html 中完成 */
export function initJssdk() {}

export async function fetchPublicAccountConfig(corpId) {
  const cid = corpId || getCorpId()
  try {
    const resp = await fetch(`/api/public-account/config?corpId=${cid}`)
    const json = await resp.json()
    return json.ok ? json.data : null
  } catch (e) {
    console.error('[JSSDK] 获取公众号配置失败:', e)
    return null
  }
}
