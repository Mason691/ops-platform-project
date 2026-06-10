/**
 * 微信 JSSDK 初始化模块
 *
 * 自动检测微信环境，等待 jweixin-1.6.0.js 加载完成后获取 JSSDK 签名并调用 wx.config。
 * 公众号选择规则：URL 参数 corpId > uni.storage corp_id > config DEFAULT_CORP_ID
 */

import { DEFAULT_CORP_ID } from '@/config'

function isWechat() {
  // #ifdef H5
  const ua = navigator.userAgent.toLowerCase()
  return ua.indexOf('micromessenger') > -1
  // #endif
  // #ifdef MP-WEIXIN
  return false
  // #endif
}

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

/**
 * 等待 wx.config 方法就绪。
 * jweixin-1.6.0.js 通过 CDN 加载，在 WeChat DevTools 中可能延迟。
 * 最长等待 5 秒，超时放弃。
 */
function waitForWxConfig(timeoutMs = 5000) {
  return new Promise((resolve) => {
    if (typeof wx !== 'undefined' && typeof wx.config === 'function') {
      return resolve(true)
    }
    const start = Date.now()
    const timer = setInterval(() => {
      if (typeof wx !== 'undefined' && typeof wx.config === 'function') {
        clearInterval(timer)
        resolve(true)
      } else if (Date.now() - start > timeoutMs) {
        clearInterval(timer)
        console.error('[JSSDK] wx.config 未就绪，jweixin-1.6.0.js 可能未加载 — timeout ' + timeoutMs + 'ms')
        resolve(false)
      }
    }, 200)
  })
}

/**
 * 初始化 JSSDK
 */
export async function initJssdk(url) {
  // #ifdef H5
  if (!isWechat()) {
    console.log('[JSSDK] 非微信环境，跳过')
    return
  }

  console.log('[JSSDK] 微信环境，等待 jweixin 加载...')

  // 等待 jweixin-1.6.0.js 完全加载（wx.config 方法可用）
  const ready = await waitForWxConfig()
  if (!ready) {
    console.error('[JSSDK] 放弃初始化：jweixin-1.6.0.js 无法加载，请检查网络或 CDN')
    return
  }

  console.log('[JSSDK] wx.config 就绪，获取签名...')
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
    console.log('[JSSDK] 签名获取成功, appId:', appId)

    wx.config({
      debug: false, // 关掉弹窗，看 Console 即可
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
    console.error('[JSSDK] 签名请求失败:', e)
  }
  // #endif
}

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
