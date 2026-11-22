/**
 * 获取当前定位（公众号 H5 优先微信 JS-SDK，降级浏览器 geolocation）。
 *
 * <p>永远 resolve，不抛异常：现场信号差是常态，业务层用 FAILED + 手填地址兜底。
 * 返回 {status:'SUCCESS'|'FAILED', lng, lat}。</p>
 */
export function getCurrentLocation() {
  return new Promise(resolve => {
    // #ifdef H5
    if (typeof window !== 'undefined' && window.__wxReady && window.wx
        && typeof window.wx.getLocation === 'function') {
      window.wx.getLocation({
        type: 'wgs84',
        success: res => resolve({ status: 'SUCCESS', lng: res.longitude, lat: res.latitude }),
        fail: () => resolve(browserLocation())
      })
      return
    }
    resolve(browserLocation())
    // #endif
    // #ifndef H5
    uni.getLocation({
      type: 'wgs84',
      success: res => resolve({ status: 'SUCCESS', lng: res.longitude, lat: res.latitude }),
      fail: () => resolve({ status: 'FAILED' })
    })
    // #endif
  })
}

function browserLocation() {
  // #ifdef H5
  return new Promise(resolve => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      resolve({ status: 'FAILED' })
      return
    }
    navigator.geolocation.getCurrentPosition(
      position => resolve({
        status: 'SUCCESS',
        lng: position.coords.longitude,
        lat: position.coords.latitude
      }),
      () => resolve({ status: 'FAILED' }),
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 }
    )
  })
  // #endif
  // #ifndef H5
  return Promise.resolve({ status: 'FAILED' })
  // #endif
}
