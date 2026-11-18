import { ref } from 'vue'
import { api } from '@/api/index'

/** 派工施工员搜索（H5）：300ms 防抖。 */
export function useDispatchWorker(orderIdGetter) {
  const getOrderId = typeof orderIdGetter === 'function' ? orderIdGetter : () => orderIdGetter
  const workers = ref([])
  const loading = ref(false)
  const keyword = ref('')
  let timer = null

  async function search(value) {
    if (!getOrderId()) return
    loading.value = true
    try {
      const data = await api.businessFlowAssignees(getOrderId(), value || undefined, 30)
      workers.value = Array.isArray(data) ? data : []
    } catch (e) {
      workers.value = []
    } finally {
      loading.value = false
    }
  }

  function onKeywordInput(value) {
    keyword.value = value
    clearTimeout(timer)
    timer = setTimeout(() => search(value), 300)
  }

  function destroy() {
    clearTimeout(timer)
  }

  return { workers, loading, keyword, search, onKeywordInput, destroy }
}
