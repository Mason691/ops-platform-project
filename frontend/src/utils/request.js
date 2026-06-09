const BASE_URL = 'https://a.15891589.xyz'
const CORP_ID = 3

export function request(options) {
  const token = uni.getStorageSync('ops_token') || ''
  const header = {}
  if (!token && options.url.startsWith('/api/ops') && !options.url.startsWith('/api/ops/auth')) {
    header['X-Corp-Id'] = String(CORP_ID)
  }
  if (token) header['Authorization'] = 'Bearer ' + token
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      header: { ...header, ...(options.header||{}) },
      success: (res) => {
        const d = res.data
        if (d && d.code === 200) resolve(d.data)
        else reject({ msg: (d && d.msg) || '请求失败' })
      },
      fail: (err) => reject(err)
    })
  })
}
