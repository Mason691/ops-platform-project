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

// ── 微信 JSSDK 录音 ──────────────────────────────────────────────

/**
 * 检查 wx JSSDK 录音 API 是否可用。
 * 注意：这些 API 在 jweixin-1.6.0 中仍存在，但新版微信客户端可能不支持。
 */
export function isWxRecordAvailable() {
  // #ifdef H5
  return typeof wx !== 'undefined'
      && typeof wx.startRecord === 'function'
      && typeof wx.stopRecord === 'function'
      && typeof wx.uploadVoice === 'function'
  // #endif
  return false
}

/**
 * 使用微信 JSSDK 开始录音。
 * 需要在 wx.ready 回调中调用。
 */
export function wxStartRecord() {
  return new Promise((resolve, reject) => {
    if (!isWxRecordAvailable()) return reject(new Error('微信录音 API 不可用'))
    wx.startRecord({
      success() { console.log('[JSSDK] 录音已开始') },
      fail(err) {
        console.error('[JSSDK] 开始录音失败:', err)
        reject(err)
      }
    })
    // startRecord 没有直接的成功回调告知录音开始，
    // 它只有在 stopRecord 时才返回结果。这里等待一小段时间后 resolve
    setTimeout(() => resolve(), 200)
  })
}

/**
 * 停止微信 JSSDK 录音并上传到微信服务器，返回 serverId。
 * @returns {Promise<{serverId: string, localId: string}>}
 */
export function wxStopRecordAndUpload() {
  return new Promise((resolve, reject) => {
    if (!isWxRecordAvailable()) return reject(new Error('微信录音 API 不可用'))
    wx.stopRecord({
      success(res) {
        const localId = res.localId
        console.log('[JSSDK] 录音停止, localId:', localId)
        if (!localId) return reject(new Error('未获取到录音 localId'))

        // 上传到微信服务器获取 serverId
        wx.uploadVoice({
          localId,
          isShowProgressTips: 0,
          success(uploadRes) {
            const serverId = uploadRes.serverId
            console.log('[JSSDK] 语音上传成功, serverId:', serverId)
            resolve({ serverId, localId })
          },
          fail(err) {
            console.error('[JSSDK] 语音上传失败:', err)
            reject(err)
          }
        })
      },
      fail(err) {
        console.error('[JSSDK] 停止录音失败:', err)
        reject(err)
      }
    })
  })
}

// ── 视频号直播跳转（共享工具）──────────────────────────────────────

const MP_APPID = 'wx095b91409f8ecbfe'
const MP_PATH = 'pages/channels/live'

/**
 * 通过小程序 scheme 跳转到视频号。
 * H5 → weixin:// scheme 唤起小程序 → 小程序内调用 wx.openChannelsUserProfile → 视频号主页。
 *
 * @param {string} finderUsername 视频号 finder_username
 */
export function openChannelsLive(finderUsername) {
  if (!finderUsername) {
    uni.showToast({ title: '直播信息不完整', icon: 'none' })
    return
  }
  window.location.href = buildMpScheme(finderUsername)
}

/**
 * 跳转到视频号主页（与 openChannelsLive 相同）。
 */
export function openChannelsProfile(finderUsername) {
  openChannelsLive(finderUsername)
}

/**
 * 构建小程序 scheme URL。
 * 格式: weixin://dl/business/?appid=xxx&path=xxx&query=xxx&env_version=release
 */
function buildMpScheme(finderUsername) {
  var path = encodeURIComponent(MP_PATH)
  var query = encodeURIComponent('finderUserName=' + encodeURIComponent(finderUsername))
  return 'weixin://dl/business/?appid=' + MP_APPID
    + '&path=' + path
    + '&query=' + query
    + '&env_version=release'
}
