import { computed, ref, unref } from 'vue'
import { api } from '@/api/index'
import { buildFullAddress, containsDistrictPrefix,
  shortDistrict, stripDistrictPrefix } from '@/utils/address'
import { formatCoords, getCurrentLocation, isDistrictMatch, isWechat,
  reverseGeocode, trackGeo } from '@/utils/location'

const LOCATION_REASON = {
  PERMISSION_DENIED: '未获取定位权限，可手动填写地址',
  TIMEOUT: '定位超时，可手动填写地址',
  UNSUPPORTED: '当前浏览器不支持定位，可手动填写地址',
  POSITION_UNAVAILABLE: '无法获取当前位置，可手动填写地址',
  CANCELLED: '已取消定位，可手动填写地址',
  LOCATION_FAILED: '定位失败，可手动填写地址',
  WECHAT_FAILED: '定位失败，可手动填写地址'
}

/**
 * 新建小区地址状态机：手动输入为主，定位为辅。
 * - 手动地址：geocode() 调后端拼接 + 高德正向编码，成功得到坐标/精度
 * - 当前位置：useCurrentLocation() 调微信/H5 定位 + 逆地理编码，回填地址与坐标
 * - 任意一次地址编辑都会使旧坐标失效，避免地址与坐标不一致
 *
 * @param options.regionId   组织区县 regionId（ref/computed）
 * @param options.regionName 组织区划路径（ref/computed）
 */
export function useCommunityGeocode(options = {}) {
  const detailAddress = ref('')
  const locationStatus = ref('idle') // idle / locating / success / partial / failed
  const locationSource = ref('MANUAL')
  const locationFailure = ref('')
  const lng = ref(null)
  const lat = ref(null)
  const accuracy = ref(null)
  const locatedDistrict = ref('')
  const locatedAdcode = ref('')
  const locatedFullAddress = ref('')

  const geocodeLoading = ref(false)
  const geocodeResult = ref(null)
  const geocodeError = ref('')
  const geocodeStale = ref(false)
  let geocodeToken = 0
  let locateToken = 0
  let manualTracked = false

  const regionPath = computed(() => String(unref(options.regionName) || ''))
  const districtName = computed(() => shortDistrict(regionPath.value))
  const locating = computed(() => locationStatus.value === 'locating')
  const hasCoordinates = computed(() => lng.value != null && lat.value != null)
  const coordsText = computed(() => formatCoords(lng.value, lat.value))
  const duplicatePrefix = computed(() =>
    containsDistrictPrefix(detailAddress.value, regionPath.value))
  const fullAddressPreview = computed(() => {
    if (geocodeResult.value && geocodeResult.value.fullAddress) {
      return geocodeResult.value.fullAddress
    }
    if (locatedFullAddress.value) return locatedFullAddress.value
    return buildFullAddress(regionPath.value, detailAddress.value)
  })
  const lowAccuracy = computed(() => !!(geocodeResult.value && geocodeResult.value.lowAccuracy))
  const geocodeLevel = computed(() => geocodeResult.value ? geocodeResult.value.level || '' : '')
  const sourceText = computed(() => {
    if (locationSource.value === 'GEOCODE') return '地址编码'
    if (locationSource.value === 'WECHAT') return '微信定位'
    if (locationSource.value === 'H5') return '浏览器定位'
    if (locationSource.value === 'MOCK') return '模拟定位'
    return ''
  })
  const observedDistrict = computed(() => {
    if (geocodeResult.value) return geocodeResult.value.district || ''
    return locatedDistrict.value || ''
  })
  const observedAdcode = computed(() => {
    if (geocodeResult.value) return geocodeResult.value.adcode || ''
    return locatedAdcode.value || ''
  })
  const mismatch = computed(() => hasCoordinates.value && !!observedDistrict.value
    && !isDistrictMatch(unref(options.regionId), observedAdcode.value,
      regionPath.value, observedDistrict.value))
  const mismatchText = computed(() => {
    if (!mismatch.value) return ''
    const org = districtName.value || '组织所在区县'
    return `位置位于【${observedDistrict.value}】，与当前组织所在区县【${org}】不一致，保存前请确认`
  })

  function clearGeocode() {
    geocodeToken++
    geocodeLoading.value = false
    geocodeResult.value = null
    geocodeError.value = ''
    geocodeStale.value = false
  }

  function clearCoordinates() {
    lng.value = null
    lat.value = null
    accuracy.value = null
    locatedDistrict.value = ''
    locatedAdcode.value = ''
    locatedFullAddress.value = ''
    locationSource.value = 'MANUAL'
    locationFailure.value = ''
    if (locationStatus.value === 'success' || locationStatus.value === 'partial'
        || locationStatus.value === 'failed') {
      locationStatus.value = 'idle'
    }
  }

  function onAddressInput(value) {
    const next = String(value || '')
    if (next === detailAddress.value) return
    const hadCoordinates = hasCoordinates.value
    detailAddress.value = next
    clearGeocode()
    if (hadCoordinates) clearCoordinates()
    if (locationStatus.value === 'failed') {
      locationStatus.value = 'idle'
      locationFailure.value = ''
    }
    if (hadCoordinates) geocodeStale.value = true
    if (!manualTracked) {
      manualTracked = true
      trackGeo('community_address_manual_input', {})
    }
  }

  function stripPrefix() {
    onAddressInput(stripDistrictPrefix(detailAddress.value, regionPath.value))
  }

  async function geocode() {
    if (geocodeLoading.value) return false
    const detail = detailAddress.value.trim()
    if (!detail) {
      geocodeError.value = '请填写详细地址（区县之后的部分，如 XX路XX号）'
      return false
    }
    if (detail.length > 120) {
      geocodeError.value = '详细地址不能超过120个字符'
      return false
    }
    const token = ++geocodeToken
    geocodeLoading.value = true
    geocodeError.value = ''
    geocodeStale.value = false
    try {
      let data
      if (import.meta.env.VITE_MOCK_GEOCODE === '1') {
        const full = buildFullAddress(regionPath.value, detail)
        data = { fullAddress: full, formattedAddress: full, longitude: 103.8,
          latitude: 36.0, level: '门牌号', lowAccuracy: false,
          district: districtName.value, adcode: '' }
      } else {
        data = await api.employeeGeocode(detail)
      }
      if (token !== geocodeToken) return false
      geocodeResult.value = {
        fullAddress: data.fullAddress || buildFullAddress(regionPath.value, detail),
        formattedAddress: data.formattedAddress || '',
        level: data.level || '',
        lowAccuracy: !!data.lowAccuracy,
        province: data.province || '',
        city: data.city || '',
        district: data.district || '',
        adcode: data.adcode || ''
      }
      lng.value = data.longitude
      lat.value = data.latitude
      accuracy.value = null
      locationSource.value = 'GEOCODE'
      locatedDistrict.value = ''
      locatedAdcode.value = ''
      locatedFullAddress.value = data.fullAddress || ''
      locationStatus.value = 'idle'
      locationFailure.value = ''
      trackGeo('community_geocode_success',
        { level: data.level || '', lowAccuracy: !!data.lowAccuracy })
      return true
    } catch (e) {
      if (token !== geocodeToken) return false
      geocodeResult.value = null
      clearCoordinates()
      geocodeError.value = (e && e.msg) || '未找到该地址对应的位置，请检查地址是否准确'
      trackGeo('community_geocode_fail', { message: geocodeError.value })
      return false
    } finally {
      if (token === geocodeToken) geocodeLoading.value = false
    }
  }

  function confirmOverwrite() {
    return new Promise(resolve => {
      uni.showModal({
        title: '使用当前位置',
        content: '将覆盖已填写的详细地址，是否继续？',
        confirmText: '继续定位',
        cancelText: '取消',
        success: res => resolve(!!res.confirm),
        fail: () => resolve(false)
      })
    })
  }

  async function useCurrentLocation() {
    if (locating.value) return false
    if (detailAddress.value.trim() && !(await confirmOverwrite())) return false
    const token = ++locateToken
    locationStatus.value = 'locating'
    locationFailure.value = ''
    trackGeo('community_use_location', {})
    const location = await getCurrentLocation()
    if (token !== locateToken) return false
    if (location.status !== 'SUCCESS') {
      // 定位失败保留原地址/坐标，避免用户确认覆盖后反而丢失已填内容。
      locationStatus.value = 'failed'
      locationFailure.value = LOCATION_REASON[location.reason] || LOCATION_REASON.LOCATION_FAILED
      return false
    }
    clearGeocode()
    clearCoordinates()
    lng.value = location.lng
    lat.value = location.lat
    accuracy.value = location.accuracy
    locationSource.value = location.source || 'H5'
    if (isWechat() && location.source === 'H5') {
      trackGeo('locate_degraded', { from: 'WECHAT', to: 'H5' })
    }
    try {
      const regeo = await reverseGeocode(location.lng, location.lat)
      if (token !== locateToken) return false
      locatedDistrict.value = regeo.district || ''
      locatedAdcode.value = regeo.adcode || ''
      locatedFullAddress.value = regeo.formattedAddress || ''
      const suffix = stripDistrictPrefix(regeo.formattedAddress, regionPath.value)
      detailAddress.value = suffix || regeo.formattedAddress || ''
      locationStatus.value = 'success'
      return true
    } catch (e) {
      if (token !== locateToken) return false
      // 已有新坐标但地址反解析失败：清空旧地址，提示手动填写。
      detailAddress.value = ''
      locatedFullAddress.value = ''
      locationStatus.value = 'partial'
      locationFailure.value = (e && e.msg) || '地址解析失败，请手动填写详细地址'
      return true
    }
  }

  function reset() {
    geocodeToken++
    locateToken++
    detailAddress.value = ''
    locationStatus.value = 'idle'
    locationSource.value = 'MANUAL'
    locationFailure.value = ''
    lng.value = null
    lat.value = null
    accuracy.value = null
    locatedDistrict.value = ''
    locatedAdcode.value = ''
    locatedFullAddress.value = ''
    geocodeLoading.value = false
    geocodeResult.value = null
    geocodeError.value = ''
    geocodeStale.value = false
    manualTracked = false
  }

  /** 提交载荷：detailAddress 只含区县之后部分；fullAddress 为已拼接/反解析的完整地址。 */
  function payload() {
    const has = hasCoordinates.value
    const persistedSource = locationSource.value === 'MOCK' ? 'H5' : locationSource.value
    return {
      detailAddress: detailAddress.value.trim() || null,
      fullAddress: fullAddressPreview.value || null,
      longitude: has ? lng.value : null,
      latitude: has ? lat.value : null,
      coordinateType: has ? 'GCJ02' : null,
      locationSource: has ? persistedSource : 'MANUAL',
      locationAccuracy: has ? accuracy.value : null,
      geocodeLevel: geocodeResult.value ? geocodeResult.value.level || null : null,
      province: geocodeResult.value ? geocodeResult.value.province || null : null,
      city: geocodeResult.value ? geocodeResult.value.city || null : null,
      districtName: observedDistrict.value || null,
      township: null,
      adcode: observedAdcode.value || null
    }
  }

  return {
    detailAddress, locationStatus, locating, locationSource, locationFailure,
    geocodeLoading, geocodeResult, geocodeError, geocodeStale,
    lowAccuracy, geocodeLevel, hasCoordinates, coordsText, sourceText,
    districtName, duplicatePrefix, fullAddressPreview, mismatch, mismatchText,
    onAddressInput, stripPrefix, geocode, useCurrentLocation, reset, payload
  }
}
