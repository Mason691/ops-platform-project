import { ref } from 'vue'
import { api } from '@/api/index'

/** 每页条数：配合后端 limit 上限 100，滚动加载按 offset 递增 */
const PAGE_SIZE = 20

/** 翻页期间人员数据变动会造成 offset 漂移，按 userId+userOrgId 去重，避免重复 key */
function appendUnique(base, list) {
  const key = worker => `${worker.userId ?? ''}-${worker.userOrgId ?? worker.corpId ?? ''}`
  const seen = new Set(base.map(key))
  return base.concat(list.filter(worker => !seen.has(key(worker))))
}

/**
 * 派工施工员搜索（H5）：300ms 防抖 + offset 分页；
 * roleName 非空时只返回该角色（施工工单=施工员）。
 */
export function useDispatchWorker(orderIdGetter, roleNameGetter) {
  const getOrderId = typeof orderIdGetter === 'function' ? orderIdGetter : () => orderIdGetter
  const getRoleName = typeof roleNameGetter === 'function' ? roleNameGetter : () => roleNameGetter
  const workers = ref([])
  const loading = ref(false)
  const keyword = ref('')
  const error = ref('')
  const hasMore = ref(false)
  let timer = null
  // 搜索词变化时丢弃过期响应，避免慢请求覆盖新列表
  let seq = 0

  async function fetchPage(value, offset) {
    if (!getOrderId()) {
      workers.value = []
      hasMore.value = false
      error.value = '缺少订单信息，请刷新页面后重试'
      return
    }
    const token = ++seq
    loading.value = true
    if (offset === 0) error.value = ''
    try {
      const data = await api.businessFlowAssignees(
        getOrderId(), value || undefined, PAGE_SIZE, getRoleName() || undefined, offset || undefined)
      if (token !== seq) return
      const list = Array.isArray(data) ? data : []
      workers.value = offset > 0 ? appendUnique(workers.value, list) : list
      hasMore.value = list.length === PAGE_SIZE
      error.value = ''
    } catch (e) {
      if (token !== seq) return
      // 不能把接口失败伪装成“没有施工员”，否则派工员无法判断是数据问题还是系统问题
      if (offset === 0) {
        workers.value = []
        hasMore.value = false
      }
      error.value = (e && e.msg) || '加载施工员失败，请稍后重试'
    } finally {
      if (token === seq) loading.value = false
    }
  }

  /** 重新搜索（回到第一页）；不传 value 时沿用当前关键词 */
  function search(value) {
    if (value != null) keyword.value = value
    return fetchPage(keyword.value, 0)
  }

  /** 滚动到底部加载下一页 */
  function loadMore() {
    if (loading.value || error.value || !hasMore.value) return
    return fetchPage(keyword.value, workers.value.length)
  }

  function onKeywordInput(value) {
    keyword.value = value
    clearTimeout(timer)
    timer = setTimeout(() => fetchPage(value, 0), 300)
  }

  function destroy() {
    seq++
    clearTimeout(timer)
  }

  return { workers, loading, keyword, error, hasMore, search, loadMore, onKeywordInput, destroy }
}
