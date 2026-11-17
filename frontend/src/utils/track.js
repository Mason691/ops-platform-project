/**
 * 轻量埋点统一出口。
 *
 * 当前项目尚未接入行为分析服务：开发环境打印事件便于自测，生产静默。
 * 后续接入时只需在本函数内补上报请求，所有调用方无需改动；
 * 不要在业务组件里直接写 console 或上报代码。
 */
export function track(event, payload = {}) {
  if (!event) return
  try {
    if (import.meta.env && import.meta.env.DEV) {
      console.debug('[track]', event, payload)
    }
  } catch (e) {
    // 埋点异常不能影响业务交互
  }
}
