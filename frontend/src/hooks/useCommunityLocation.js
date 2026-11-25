import { computed, unref, ref } from 'vue'
import { extractDistrictName, formatCoords, getCurrentLocation,
  isDistrictMatch, isWechat, reverseGeocode, trackGeo } from '@/utils/location'

/** 本会话内用户拒绝授权后，不再自动弹窗；手动「重新定位」仍可尝试。 */
let autoLocateSuppressed = false

const REASON_TEXT = {
  PERMISSION_DENIED: '未获取定位权限，可手动填写地址',
  TIMEOUT: '定位超时，可手动填写地址',
  UNSUPPORTED: '当前浏览器不支持定位，可手动填写地址',
  POSITION_UNAVAILABLE: '无法获取当前位置，可手动填写地址',
  CANCELLED: '已取消定位，可手动填写地址',
  LOCATION_FAILED: '定位失败，可手动填写地址',
  WECHAT_FAILED: '定位失败，可手动填写地址'
}

/**
 * 新建小区定位状态机：idle → locating → success / partial / failed。
 * partial 表示坐标已拿到但高德反解析失败，仍可手填地址保存。
 *
 * @param options.regionId    组织区县 regionId（ref/computed）
 * @param options.districtName 组织区县全路径（ref/computed）
 */
export function useCommunityLocation(options = {}) {
  const status = ref('idle')
  const address = ref('')
  const lng = ref(null)
  const lat = ref(null)
  const accuracy = ref(null)
  const source = ref('MANUAL')
  const failureReason = ref('')
  const regeoError = ref('')
  const province = ref('')
  const city = ref('')
  const district = ref('')
  const township = ref('')
  const adcode = ref('')
  let userEditedAddress = false
  let locateToken = 0

  const locating = computed(() => status.value === 'locating')
  const hasLocation = computed(() => lng.value != null && lat.value != null)
  const coordsText = computed(() => formatCoords(lng.value, lat.value))
  const sourceText = computed(() => {
    if (source.value === 'WECHAT') return '微信定位'
    if (source.value === 'H5') return '浏览器定位'
    if (source.value === 'MOCK') return '模拟定位'
    return ''
  })
  const mismatch = computed(() => hasLocation.value && !!district.value
    && !isDistrictMatch(unref(options.regionId), adcode.value,
      unref(options.districtName), district.value))
  const mismatchText = computed(() => {
    if (!mismatch.value) return ''
    const located = district.value || '未知区县'
    const org = extractDistrictName(unref(options.districtName)) || '组织所在区县'
    return `定位显示该位置位于【${located}】，与当前组织所在区县【${org}】不一致，保存前请确认`
  })

  async function locate({ force = false } = {}) {
    if (!force && autoLocateSuppressed) {
      status.value = 'failed'
      failureReason.value = REASON_TEXT.PERMISSION_DENIED
      return
    }
    const token = ++locateToken
    status.value = 'locating'
    failureReason.value = ''
    regeoError.value = ''
    trackGeo('locate_start', { force })
    const location = await getCurrentLocation()
    if (token !== locateToken) return
    if (location.status !== 'SUCCESS') {
      status.value = 'failed'
      failureReason.value = REASON_TEXT[location.reason] || REASON_TEXT.LOCATION_FAILED
      trackGeo('locate_failed', { reason: location.reason || 'LOCATION_FAILED' })
      if (location.reason === 'PERMISSION_DENIED') autoLocateSuppressed = true
      return
    }
    lng.value = location.lng
    lat.value = location.lat
    accuracy.value = location.accuracy
    source.value = location.source || 'H5'
    autoLocateSuppressed = false
    trackGeo('locate_success', { source: location.source, accuracy: location.accuracy })
    if (isWechat() && location.source === 'H5') {
      trackGeo('locate_degraded', { from: 'WECHAT', to: 'H5' })
    }
    try {
      const regeo = await reverseGeocode(location.lng, location.lat)
      if (token !== locateToken) return
      // 用户已手工改过地址时，自动定位不覆盖；点「重新定位」才强制刷新。
      if (!userEditedAddress || force) address.value = regeo.formattedAddress || ''
      province.value = regeo.province || ''
      city.value = regeo.city || ''
      district.value = regeo.district || ''
      township.value = regeo.township || ''
      adcode.value = regeo.adcode || ''
      status.value = 'success'
      trackGeo('regeo_success', { district: regeo.district || '' })
    } catch (e) {
      if (token !== locateToken) return
      status.value = 'partial'
      regeoError.value = (e && e.msg) || '地址解析失败，请手动填写详细地址'
      trackGeo('regeo_failed', { source: location.source, message: regeoError.value })
    }
  }

  function onAddressInput(value) {
    address.value = String(value || '')
    userEditedAddress = true
  }

  function skip() {
    locateToken++
    status.value = 'failed'
    failureReason.value = '已跳过定位，可手动填写地址'
  }

  function reset() {
    locateToken++
    status.value = 'idle'
    address.value = ''
    lng.value = null
    lat.value = null
    accuracy.value = null
    source.value = 'MANUAL'
    failureReason.value = ''
    regeoError.value = ''
    province.value = ''
    city.value = ''
    district.value = ''
    township.value = ''
    adcode.value = ''
    userEditedAddress = false
  }

  /** 提交载荷：无坐标时 locationSource=MANUAL；MOCK 仅本地调试，按 H5 入库。 */
  function payload() {
    const has = hasLocation.value
    const persistedSource = source.value === 'MOCK' ? 'H5' : source.value
    return {
      detailAddress: address.value.trim() || null,
      longitude: has ? lng.value : null,
      latitude: has ? lat.value : null,
      coordinateType: has ? 'GCJ02' : null,
      locationSource: has ? persistedSource : 'MANUAL',
      locationAccuracy: has ? accuracy.value : null,
      province: province.value || null,
      city: city.value || null,
      districtName: district.value || null,
      township: township.value || null,
      adcode: adcode.value || null
    }
  }

  return {
    status, address, lng, lat, accuracy, source, failureReason, regeoError,
    province, city, district, township, adcode,
    locating, hasLocation, coordsText, sourceText, mismatch, mismatchText,
    locate, onAddressInput, skip, reset, payload
  }
}
