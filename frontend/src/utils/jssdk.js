/**
 * 微信 JSSDK 初始化模块
 *
 * 自动检测微信环境，获取 JSSDK 签名并调用 wx.config。
 * 公众号选择规则：URL 参数 corpId > uni.storage corp_id > config DEFAULT_CORP_ID
 */

import { DEFAULT_CORP_ID } from '@/config'

// 是否在微信浏览器中
function isWechat() {
  // #ifdef H5
  const ua = navigator.userAgent.toLowerCase()
  return ua.indexOf('micromessenger') > -1
  // #endif
  // #ifdef MP-WEIXIN
  return false
  // #endif
}

/**
 * 获取当前生效的 corpId
 */
function getCorpId() {
  // #ifdef H5
  // 1. URL 参数 ?corpId=4
  const params = new URLSearchParams(location.search)
  if (params.get('corpId')) return params.get('corpId')
  // 2. uni storage
  try {
    const stored = uni.getStorageSync('corp_id')
    if (stored) return String(stored)
  } catch (_) {}
  // 3. 默认
  return String(DEFAULT_CORP_ID)
  // #endif
  return String(DEFAULT_CORP_ID)
}

/**
 * 初始化 JSSDK — 在页面 onReady 或 app onLaunch 中调用。
 * @param {string} url - 当前页 URL（不传则自动取 location.href 去 hash）
 */
export async function initJssdk(url) {
  // #ifdef H5
  if (!isWechat()) {
    console.log('[JSSDK] 非微信环境，跳过')
    return
  }

  console.log('[JSSDK] 微信环境，开始初始化...')
  // 取 url 不带 #hash，微信 JSSDK 签名要求完全相同
  const configUrl = url || location.href.split('#')[0]
  const corpId = getCorpId()
  console.log('[JSSDK] url=', configUrl, 'corpId=', corpId)

  try {
    const resp = await fetch(
      `/api/public-account/js-config?url=${encodeURIComponent(configUrl)}&corpId=${corpId}`
    )
    const json = await resp.json()
    if (!json.ok || !json.data) {
      console.error('[JSSDK] 获取签名失败:', json.msg)
      return
    }

    const { appId, timestamp, nonceStr, signature } = json.data
    console.log('[JSSDK] 签名获取成功, appId:', appId, 'sig:', signature.slice(0, 12) + '…')

    wx.config({
      debug: true, // true 会弹窗显示每一步结果，调试完改回 false
      appId,
      timestamp,
      nonceStr,
      signature,
      jsApiList: [
        'updateAppMessageShareData',
        'updateTimelineShareData',
        'onMenuShareTimeline',
        'onMenuShareAppMessage',
        'getLocation',
        'openLocation',
        'chooseImage',
        'previewImage',
        'miniProgram',
      ],
      openTagList: ['wx-open-launch-weapp'],
    })

    wx.ready(() => {
      console.log('[JSSDK] ✅ ready — 初始化成功')
    })

    wx.error((err) => {
      console.error('[JSSDK] ❌ error:', JSON.stringify(err))
    })
  } catch (e) {
    console.error('[JSSDK] 请求失败:', e)
  }
  // #endif
}

/**
 * 获取当前公众号配置
 */
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

export { isWechat }
