/**
 * 定位工具：微信 JS-SDK（GCJ-02）→ H5 geolocation（WGS-84 转 GCJ-02）→ 手动。
 *
 * 永远 resolve、不抛异常：现场信号差/拒绝授权是常态，业务层用「手填地址」兜底。
 * 不写入 localStorage；拒绝授权后本会话不再自动弹窗（手动「重新定位」除外）。
 */
import { api } from '@/api/index'

const WX_TIMEOUT_MS = 8000
const H5_TIMEOUT_MS = 8000
let wxDeniedThisSession = false

export function isWechat() {
  // #ifdef H5
  return /micromessenger/i.test(navigator.userAgent)
  // #endif
  return false
}

/** 轻量埋点：平台暂无前端埋点 SDK，先统一输出，后续可替换为 uni.report/自有通道。 */
export function trackGeo(event, payload = {}) {
  try {
    console.info('[geo]', event, payload)
  } catch (_) {
    // 埋点不能影响定位主流程
  }
}

/** GCJ-02 坐标格式化展示（约 1 米精度，避免暴露过多小数）。 */
export function formatCoords(lng, lat) {
  if (lng == null || lat == null) return '—'
  return `${Number(lng).toFixed(6)}, ${Number(lat).toFixed(6)}`
}

/**
 * 获取当前定位。返回：
 * { status:'SUCCESS'|'FAILED', source:'WECHAT'|'H5'|'MOCK'|'NONE',
 *   lng, lat, accuracy, reason }
 */
export function getCurrentLocation() {
  return new Promise(resolve => {
    const mock = import.meta.env.VITE_MOCK_LOCATION
    if (mock) {
      const [lng, lat] = String(mock).split(',').map(Number)
      if (Number.isFinite(lng) && Number.isFinite(lat)) {
        resolve({ status: 'SUCCESS', source: 'MOCK', lng, lat, accuracy: null })
        return
      }
    }

    // #ifdef H5
    if (isWechat() && window.__wxReady && window.wx
        && typeof window.wx.getLocation === 'function' && !wxDeniedThisSession) {
      let settled = false
      const timer = setTimeout(() => {
        if (settled) return
        settled = true
        resolve(browserLocation('TIMEOUT'))
      }, WX_TIMEOUT_MS)
      try {
        window.wx.getLocation({
          type: 'gcj02',
          success(res) {
            if (settled) return
            const lng = Number(res.longitude)
            const lat = Number(res.latitude)
            if (!Number.isFinite(lng) || !Number.isFinite(lat)) {
              settled = true
              clearTimeout(timer)
              resolve(browserLocation('LOCATION_FAILED'))
              return
            }
            settled = true
            clearTimeout(timer)
            resolve({ status: 'SUCCESS', source: 'WECHAT', lng, lat, accuracy: null })
          },
          fail(err) {
            if (settled) return
            settled = true
            clearTimeout(timer)
            const reason = classifyWxError(err)
            if (reason === 'PERMISSION_DENIED') wxDeniedThisSession = true
            resolve(browserLocation(reason))
          }
        })
      } catch (e) {
        if (!settled) {
          settled = true
          clearTimeout(timer)
          resolve(browserLocation('LOCATION_FAILED'))
        }
      }
      return
    }
    resolve(browserLocation())
    // #endif

    // #ifndef H5
    uni.getLocation({
      type: 'gcj02',
      success: res => resolve({
        status: 'SUCCESS', source: 'H5',
        lng: Number(res.longitude), lat: Number(res.latitude), accuracy: null
      }),
      fail: err => resolve({
        status: 'FAILED', source: 'NONE', reason: classifyWxError(err)
      })
    })
    // #endif
  })
}

function browserLocation(fallbackReason) {
  // #ifdef H5
  return new Promise(resolve => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      resolve({ status: 'FAILED', source: 'NONE', reason: fallbackReason || 'UNSUPPORTED' })
      return
    }
    let settled = false
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      resolve({ status: 'FAILED', source: 'NONE', reason: 'TIMEOUT' })
    }, H5_TIMEOUT_MS)
    navigator.geolocation.getCurrentPosition(
      position => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        const [lng, lat] = wgs84ToGcj02(
          position.coords.longitude, position.coords.latitude)
        if (!Number.isFinite(lng) || !Number.isFinite(lat)) {
          resolve({ status: 'FAILED', source: 'NONE', reason: 'LOCATION_FAILED' })
          return
        }
        resolve({
          status: 'SUCCESS', source: 'H5', lng, lat,
          accuracy: position.coords.accuracy == null ? null : Number(position.coords.accuracy)
        })
      },
      err => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        resolve({ status: 'FAILED', source: 'NONE', reason: classifyH5Error(err) })
      },
      { enableHighAccuracy: true, timeout: H5_TIMEOUT_MS, maximumAge: 30000 }
    )
  })
  // #endif
  // #ifndef H5
  return Promise.resolve({ status: 'FAILED', source: 'NONE', reason: fallbackReason || 'UNSUPPORTED' })
  // #endif
}

/** 逆地理编码走后端代理（高德 key 不下发前端）；失败由调用方降级为手填。 */
export function reverseGeocode(lng, lat) {
  if (import.meta.env.VITE_MOCK_REGEO === '1') {
    return Promise.resolve({
      formattedAddress: '重庆市沙坪坝区大学城南路1号（本地 mock）',
      province: '重庆市', city: '重庆市', district: '沙坪坝区',
      township: '虎溪街道', streetNumber: '1号', adcode: '500106', pois: []
    })
  }
  return api.employeeRegeo(lng, lat)
}

function classifyWxError(err) {
  const msg = String((err && (err.errMsg || err.message)) || '')
  if (/deny|denied|auth|permission|forbidden/i.test(msg)) return 'PERMISSION_DENIED'
  if (/timeout/i.test(msg)) return 'TIMEOUT'
  if (/cancel/i.test(msg)) return 'CANCELLED'
  return 'LOCATION_FAILED'
}

function classifyH5Error(err) {
  if (!err) return 'LOCATION_FAILED'
  if (err.code === 1) return 'PERMISSION_DENIED'
  if (err.code === 2) return 'POSITION_UNAVAILABLE'
  if (err.code === 3) return 'TIMEOUT'
  return 'LOCATION_FAILED'
}

/** WGS-84 → GCJ-02（高德坐标系）；境外坐标原样返回。 */
export function wgs84ToGcj02(lng, lat) {
  const x = Number(lng)
  const y = Number(lat)
  if (!Number.isFinite(x) || !Number.isFinite(y)) return [x, y]
  if (x < 72.004 || x > 137.8347 || y < 0.8293 || y > 55.8271) return [x, y]
  const dLat = transformLat(x - 105.0, y - 35.0)
  const dLng = transformLng(x - 105.0, y - 35.0)
  const radLat = y / 180.0 * Math.PI
  let magic = Math.sin(radLat)
  magic = 1 - 0.00669342162296594323 * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  const deltaLat = (dLat * 180.0) / ((6378245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtMagic) * Math.PI)
  const deltaLng = (dLng * 180.0) / (6378245.0 / sqrtMagic * Math.cos(radLat) * Math.PI)
  return [x + deltaLng, y + deltaLat]
}

function transformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
  ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
  ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
  return ret
}

function transformLng(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
  ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
  ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
  return ret
}

/** 定位区县与组织区县是否一致；与后端 CommunityGeoSupport 同口径。 */
export function isDistrictMatch(regionId, adcode, orgRegionName, regeoDistrict) {
  const rid = regionId == null ? '' : String(regionId)
  if (!rid || Number(rid) <= 0) return true
  if (rid.length >= 6 && rid.endsWith('0000')) return true
  if (adcode && /^\d{6}$/.test(String(adcode))) {
    const code = String(adcode)
    if (rid.length === 6) return rid.endsWith('00')
      ? rid.slice(0, 4) === code.slice(0, 4) : rid === code
    if (rid.length > 6) return rid.slice(0, 6) === code
  }
  const org = extractDistrictName(orgRegionName)
  const located = normalizeDistrict(regeoDistrict)
  if (!org || !located) return true
  return org === located
}

export function extractDistrictName(regionPath) {
  if (!regionPath) return ''
  const parts = String(regionPath).split(/[/>,、]/)
  for (let i = parts.length - 1; i >= 0; i--) {
    const part = String(parts[i] || '').trim()
    if (!part || isPlaceholder(part)) continue
    if (/(区|县|旗|市|州|盟)$/.test(part)) return part
  }
  return ''
}

function normalizeDistrict(value) {
  if (!value) return ''
  const trimmed = String(value).trim().replace(/\s/g, '')
  return isPlaceholder(trimmed) ? '' : trimmed
}

function isPlaceholder(part) {
  return part === '市辖区' || part === '省直辖县级行政区划' || part === '自治区直辖县级行政区划'
}
