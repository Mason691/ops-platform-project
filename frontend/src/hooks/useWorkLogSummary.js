import { ref } from 'vue'
import { api } from '@/api'

function pad2(value) {
  return String(value).padStart(2, '0')
}

/** 本周一（本地时区）的 YYYY-MM-DD，供 /api/wf/log/my 的 startDate 过滤。 */
export function weekStartDate(now = new Date()) {
  const date = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const weekday = date.getDay() || 7
  date.setDate(date.getDate() - weekday + 1)
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

/**
 * 工作业务日志首页摘要。
 *
 * 复用 /api/wf/log/my（底座按当前登录人过滤，含我发起的 + 我处理的）：
 * - 最近一条：pageSize=1，后端按 createTime DESC；
 * - 本周条数：同一接口带 startDate=本周一，取 total；
 * - 累计条数：最近一条请求的 total。
 * 两个请求并行且互不阻塞：本周统计失败只降级为「—」，不影响最近一条展示。
 */
export function useWorkLogSummary() {
  const latest = ref(null)
  const totalCount = ref(0)
  const weekCount = ref(null)
  const loading = ref(true)
  const error = ref('')
  let requestSeq = 0

  async function load(options = {}) {
    const silent = options && options.silent === true
    if (!uni.getStorageSync('ops_employee_token')) {
      latest.value = null
      totalCount.value = 0
      weekCount.value = null
      loading.value = false
      return
    }
    const seq = ++requestSeq
    loading.value = !silent
    error.value = ''

    const latestTask = api.workflowMyLogs({ pageNum: 1, pageSize: 1 })
      .then(data => ({ ok: true, data }))
      .catch(err => ({ ok: false, err }))
    const weekTask = api.workflowMyLogs({ pageNum: 1, pageSize: 1, startDate: weekStartDate() })
      .then(data => ({ ok: true, data }))
      .catch(err => ({ ok: false, err }))

    const [latestRes, weekRes] = await Promise.all([latestTask, weekTask])
    if (seq !== requestSeq) return

    if (latestRes.ok) {
      const records = Array.isArray(latestRes.data && latestRes.data.records)
        ? latestRes.data.records : []
      latest.value = records[0] || null
      totalCount.value = Math.max(0, Number(latestRes.data && latestRes.data.total) || 0)
    } else if (!silent || !latest.value) {
      // 静默刷新失败且有旧数据时保留，避免返回工作台瞬间清空日志摘要
      latest.value = null
      totalCount.value = 0
      error.value = (latestRes.err && latestRes.err.msg) || '业务日志加载失败'
    }
    if (weekRes.ok) {
      weekCount.value = Math.max(0, Number(weekRes.data && weekRes.data.total) || 0)
    } else if (!silent) {
      weekCount.value = null
    }
    loading.value = false
  }

  return { latest, totalCount, weekCount, loading, error, load }
}
