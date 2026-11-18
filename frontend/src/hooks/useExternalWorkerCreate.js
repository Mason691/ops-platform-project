import { ref } from 'vue'
import { api } from '@/api/index'

/** 外部施工员手机号检查与创建（H5）。 */
export function useExternalWorkerCreate(orderIdGetter) {
  const getOrderId = typeof orderIdGetter === 'function' ? orderIdGetter : () => orderIdGetter
  const checking = ref(false)
  const creating = ref(false)
  const checkResult = ref(null)

  async function checkPhone(phone) {
    const value = String(phone || '').trim()
    if (!/^1\d{10}$/.test(value)) {
      checkResult.value = null
      return null
    }
    checking.value = true
    try {
      checkResult.value = await api.dispatchWorkerCheckPhone(getOrderId(), value)
      return checkResult.value
    } catch (e) {
      checkResult.value = {
        exists: false,
        reusable: false,
        errorCode: 'CHECK_PHONE_FAILED',
        message: (e && e.msg) || '手机号检查失败，请稍后重试'
      }
      return checkResult.value
    } finally {
      checking.value = false
    }
  }

  async function create(payload) {
    creating.value = true
    try {
      return await api.createExternalWorker({
        orderId: getOrderId(),
        phone: payload.phone,
        name: payload.name,
        confirmSameName: !!payload.confirmSameName
      })
    } finally {
      creating.value = false
    }
  }

  return { checking, creating, checkResult, checkPhone, create }
}
