import { ref } from 'vue'
import { api } from '@/api/index'

/**
 * 施工状态链操作封装：统一 loading/error，调用失败抛出给组件 toast。
 *
 * <p>每个动作成功后由调用方重新拉取任务详情（状态、按钮、时间线全部刷新），
 * 避免前端本地推断状态造成与服务端不一致。</p>
 */
export function useOrderConstruct() {
  const submitting = ref(false)
  const error = ref('')

  async function run(action) {
    if (submitting.value) throw new Error('操作处理中，请稍后')
    submitting.value = true
    error.value = ''
    try {
      return await action()
    } catch (e) {
      error.value = (e && e.msg) || (e && e.message) || '操作失败，请重试'
      throw e
    } finally {
      submitting.value = false
    }
  }

  return {
    submitting,
    error,
    load: orderId => run(() => api.constructView(orderId)),
    accept: orderId => run(() => api.constructAccept(orderId)),
    reject: (orderId, reason) => run(() => api.constructReject(orderId, reason)),
    arrive: (orderId, data) => run(() => api.constructArrive(orderId, data)),
    customerConfirm: (orderId, data) => run(() => api.constructCustomerConfirm(orderId, data)),
    customerRefuse: (orderId, reason) => run(() => api.constructCustomerRefuse(orderId, reason)),
    start: orderId => run(() => api.constructStart(orderId))
  }
}
