import { BASE_URL, DEFAULT_CORP_ID } from '@/config'

/**
 * Resolve the active authentication token.
 * Uses token_type to strictly separate member vs employee sessions —
 * the two token types are NEVER mixed in the same evaluation.
 */
function resolveToken() {
  const tokenType = uni.getStorageSync('token_type') || ''
  if (tokenType === 'employee') {
    return uni.getStorageSync('ops_employee_token') || ''
  }
  if (tokenType === 'member') {
    return uni.getStorageSync('ops_token') || ''
  }
  // Legacy: no token_type set — try employee first, member second
  // (only used during migration; once login pages set token_type consistently this branch is dead)
  return uni.getStorageSync('ops_employee_token') || uni.getStorageSync('ops_token') || ''
}

export function request(options) {
  const token = resolveToken()
  const corpId = uni.getStorageSync('corp_id') || DEFAULT_CORP_ID
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      timeout: 120000,
      header: {
        'Content-Type': 'application/json',
        'X-Corp-Id': String(corpId),
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      success(res) {
        const d = res.data
        if (d && d.code === 200) resolve(d.data)
        else {
          if (d && d.code === 401) {
            uni.removeStorageSync('ops_token')
            uni.removeStorageSync('ops_employee_token')
            uni.removeStorageSync('ops_member')
            uni.removeStorageSync('ops_user')
            uni.removeStorageSync('token_type')
          }
          reject({ code: (d && d.code) || 0, msg: (d && d.msg) || '请求失败' })
        }
      },
      fail(err) { reject(err) }
    })
  })
}
