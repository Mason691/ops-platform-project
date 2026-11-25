/**
 * 新建小区地址工具：组织区划路径 → 前缀识别 / 去重 / 完整地址预览。
 * 最终拼接与区县归属以后端 CommunityGeoSupport 为准，这里只用于输入提示与预览。
 */

const PLACEHOLDERS = new Set([
  '市辖区',
  '省直辖县级行政区划',
  '自治区直辖县级行政区划'
])

export function regionSegments(regionPath) {
  if (!regionPath) return []
  const segments = []
  for (const part of String(regionPath).split(/[/>,、]/)) {
    const trimmed = part.trim()
    if (!trimmed || PLACEHOLDERS.has(trimmed) || /^\d+$/.test(trimmed)) continue
    if (segments.length && segments[segments.length - 1] === trimmed) continue
    segments.push(trimmed)
  }
  return segments
}

export function regionPrefix(regionPath) {
  return regionSegments(regionPath).join('')
}

/** 组织区县短名（如「临洮县」「沙坪坝区」），用于提示文案。 */
export function shortDistrict(regionPath) {
  const segments = regionSegments(regionPath)
  for (let i = segments.length - 1; i >= 0; i--) {
    if (/(区|县|旗|市|州|盟)$/.test(segments[i])) return segments[i]
  }
  return segments.length ? segments[segments.length - 1] : ''
}

/** 是否以组织省/市/区县名称开头，用于提示用户无需重复填写。 */
export function containsDistrictPrefix(input, regionPath) {
  const value = String(input || '').trim()
  if (!value) return false
  return regionSegments(regionPath).some(segment => value.startsWith(segment))
}

/** 去掉开头连续出现的省/市/区县名称，仅保留区县之后的地址。 */
export function stripDistrictPrefix(input, regionPath) {
  let result = String(input || '').trim()
  for (const segment of regionSegments(regionPath)) {
    if (result.startsWith(segment)) result = result.slice(segment.length).trim()
  }
  return result.replace(/^[\s,，/、>]+/, '')
}

export function buildFullAddress(regionPath, detailAddress) {
  return regionPrefix(regionPath) + stripDistrictPrefix(detailAddress, regionPath)
}

export function normalizeDetailAddress(input) {
  return String(input || '').trim()
}
