import { computed, onUnmounted, ref } from 'vue'
import { api } from '@/api'
import {
  TODO_PAGE_SIZE, decorateTodo, filterIneligibleConstruct, filterDispatchTodoVisibility,
  sortTodoByCreateTime
} from '@/utils/workflowTodo'

/** 首页最多直接展示的待办条数；超出走「查看更多」。 */
export const TODO_HOME_LIMIT = 5
const TICK_MS = 60 * 1000

/**
 * 工作台首页待办摘要。
 *
 * - 数据源与完整待办列表页完全一致（/api/wf/task/todo），过滤与排序走
 *   utils/workflowTodo，保证「首页看到的 5 条」在列表页同样在最前面。
 * - remainingHours 只在加载时取一次，之后每 60s 本地重算文案；
 *   页面重新可见时由调用方 reload() 与服务端校准。
 * - 组织切换/连续刷新用 requestSeq 丢弃过期响应（uni.request 无法取消）。
 */
export function useWorkTodo() {
  const items = ref([])
  const total = ref(0)
  const loading = ref(true)
  const error = ref('')
  const nowMs = ref(Date.now())
  let requestSeq = 0
  let ticker = null

  const visibleItems = computed(() => items.value.slice(0, TODO_HOME_LIMIT))
  const hasMore = computed(() => total.value > visibleItems.value.length)

  async function load(options = {}) {
    const silent = options && options.silent === true
    if (!uni.getStorageSync('ops_employee_token')) {
      items.value = []
      total.value = 0
      loading.value = false
      return
    }
    const seq = ++requestSeq
    // 页面返回后的静默刷新不闪 loading；首屏与组织切换保留加载态
    loading.value = !silent
    error.value = ''
    if (!silent) {
      // 组织切换时先清空，避免顶部计数短暂显示上一个组织的总数
      items.value = []
      total.value = 0
    }
    try {
      const data = await api.workflowTodo({ pageNum: 1, pageSize: TODO_PAGE_SIZE })
      if (seq !== requestSeq) return
      const raw = Array.isArray(data && data.records) ? data.records : []
      const filtered = await filterIneligibleConstruct(
        await filterDispatchTodoVisibility(raw))
      if (seq !== requestSeq) return
      const receivedAt = Date.now()
      items.value = sortTodoByCreateTime(decorateTodo(filtered, receivedAt))
      const apiTotal = Math.max(0, Number(data && data.total) || 0)
      // 已取回全部记录时以过滤后的真实条数为准，避免被隐藏的派工任务虚增 N。
      total.value = apiTotal <= raw.length ? filtered.length : apiTotal
      nowMs.value = receivedAt
    } catch (e) {
      if (seq !== requestSeq) return
      // 静默刷新失败时保留已有数据，避免返回工作台瞬间因一次网络抖动清空卡片
      if (!silent || !items.value.length) {
        items.value = []
        total.value = 0
        error.value = (e && e.msg) || '待办加载失败，请稍后重试'
      }
    } finally {
      if (seq === requestSeq) loading.value = false
    }
  }

  function startTicker() {
    stopTicker()
    ticker = setInterval(() => { nowMs.value = Date.now() }, TICK_MS)
  }

  function stopTicker() {
    if (ticker) {
      clearInterval(ticker)
      ticker = null
    }
  }

  onUnmounted(stopTicker)

  return { items: visibleItems, total, hasMore, loading, error, nowMs, load, startTicker, stopTicker }
}
