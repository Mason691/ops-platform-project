import { isWechat } from '@/utils/jssdk'

/**
 * 公众号 H5 短视频选择/录制。
 *
 * 微信内优先 wx.chooseMedia（可设置 maxDuration=30，直接调起系统相机），
 * 低版本 JSSDK 降级 wx.chooseVideo，非微信/失败时降级原生 file input(capture)。
 * 所有路径都做一次时长复核：部分机型会忽略 maxDuration 返回超长视频。
 */
const MAX_SECONDS = 30
const MAX_BYTES = 50 * 1024 * 1024

export function useVideoRecorder() {
  /** @returns {Promise<{path?:string,file?:File,name:string,size:number,duration:number}>} */
  async function chooseVideo(sourceType = ['camera', 'album']) {
    if (isWechat()) {
      try {
        return await chooseByWechat(sourceType)
      } catch (e) {
        if (isCancel(e)) throw e
        console.warn('[AcceptanceVideo] 微信选择失败，降级 file input:', e)
      }
    }
    return chooseByInput()
  }

  async function chooseByWechat(sourceType) {
    await waitWxReady()
    if (typeof wx !== 'undefined' && typeof wx.chooseMedia === 'function') {
      return new Promise((resolve, reject) => {
        wx.chooseMedia({
          count: 1,
          mediaType: ['video'],
          sourceType,
          maxDuration: MAX_SECONDS,
          camera: 'back',
          success: res => resolveFromWxMedia(res).then(resolve).catch(reject),
          fail: reject
        })
      })
    }
    if (typeof wx !== 'undefined' && typeof wx.chooseVideo === 'function') {
      return new Promise((resolve, reject) => {
        wx.chooseVideo({
          sourceType,
          maxDuration: MAX_SECONDS,
          camera: 'back',
          success: res => resolveFromWxVideo(res).then(resolve).catch(reject),
          fail: reject
        })
      })
    }
    throw new Error('微信视频接口不可用')
  }

  async function resolveFromWxMedia(res) {
    const file = res && res.tempFiles && res.tempFiles[0]
    if (!file) throw new Error('未获取到视频')
    return normalize({
      path: file.tempFilePath,
      name: fileNameOf(file.tempFilePath, 'mp4'),
      size: Number(file.size || 0),
      duration: Number(file.duration || 0)
    })
  }

  async function resolveFromWxVideo(res) {
    return normalize({
      path: res.tempFilePath,
      name: fileNameOf(res.tempFilePath, 'mp4'),
      size: Number(res.size || 0),
      duration: Number(res.duration || 0)
    })
  }

  function chooseByInput() {
    return new Promise((resolve, reject) => {
      if (typeof document === 'undefined') {
        reject(new Error('当前环境不支持录制视频'))
        return
      }
      const input = document.createElement('input')
      input.type = 'file'
      input.accept = 'video/mp4,video/quicktime,.mp4,.mov'
      // 移动端浏览器/微信内直接调起系统相机；桌面端退化为文件选择
      input.capture = 'user'
      input.onchange = async event => {
        const file = event.target && event.target.files && event.target.files[0]
        if (!file) {
          reject(cancelError())
          return
        }
        try {
          resolve(await normalize({
            file,
            name: file.name || fileNameOf('', 'mp4'),
            size: file.size,
            duration: 0
          }))
        } catch (e) {
          reject(e)
        }
      }
      input.oncancel = () => reject(cancelError())
      input.click()
    })
  }

  async function normalize(item) {
    if (!/\.(mp4|mov)$/i.test(item.name || '')) {
      throw new Error('视频仅支持 MP4、MOV')
    }
    if (item.size > MAX_BYTES) {
      throw new Error('视频不能超过 50MB')
    }
    let duration = Number(item.duration || 0)
    if (!duration && item.file) {
      duration = await readDuration(item.file)
    }
    if (duration && duration > MAX_SECONDS + 0.5) {
      throw new Error('短视频时长不能超过 30 秒')
    }
    return { ...item, duration: duration ? Math.ceil(duration) : 0 }
  }

  return { chooseVideo }
}

function waitWxReady(timeoutMs = 5000) {
  return new Promise(resolve => {
    if (!isWechat() || typeof window === 'undefined') return resolve()
    if (window.__wxReady) return resolve()
    const startedAt = Date.now()
    const timer = setInterval(() => {
      if (window.__wxReady || Date.now() - startedAt > timeoutMs) {
        clearInterval(timer)
        resolve()
      }
    }, 100)
  })
}

function readDuration(file) {
  return new Promise(resolve => {
    const url = URL.createObjectURL(file)
    const video = document.createElement('video')
    const done = value => {
      URL.revokeObjectURL(url)
      resolve(value)
    }
    const timer = setTimeout(() => done(0), 4000)
    video.preload = 'metadata'
    video.onloadedmetadata = () => {
      clearTimeout(timer)
      done(Number.isFinite(video.duration) ? video.duration : 0)
    }
    video.onerror = () => {
      clearTimeout(timer)
      done(0)
    }
    video.src = url
  })
}

function fileNameOf(path, ext) {
  const value = String(path || '')
  const match = value.match(/\.([a-z0-9]+)(\?|$)/i)
  return `acceptance_video_${Date.now()}.${match ? match[1].toLowerCase() : ext}`
}

function isCancel(error) {
  return /cancel/i.test(String((error && (error.errMsg || error.message)) || ''))
}

function cancelError() {
  const error = new Error('已取消录制')
  error.cancelled = true
  return error
}
