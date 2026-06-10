import { BASE_URL, DEFAULT_CORP_ID } from '@/config'

export function request(options) {
  const token = uni.getStorageSync('ops_token') || ''
  const corpId = uni.getStorageSync('corp_id') || DEFAULT_CORP_ID
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      timeout: 12000,
      header: {
        'Content-Type': 'application/json',
        'X-Corp-Id': String(corpId),
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      success(res) {
        const d = res.data
        if (d && d.code === 200) resolve(d.data)
        else {
          if (d && d.code === 401) { uni.removeStorageSync('ops_token'); uni.removeStorageSync('ops_user') }
          reject({ code: (d && d.code) || 0, msg: (d && d.msg) || '请求失败' })
        }
      },
      fail(err) { reject(err) }
    })
  })
}
