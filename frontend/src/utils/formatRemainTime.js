/**
 * 待办时限展示与紧急度判定。
 *
 * 底座 `GET /api/wf/task/todo` 返回 remainingHours（任务创建时间 + 24h SLA，
 * 已超时时钳制为 0），没有 remainSeconds/overdue 字段。因此：
 * 1. 以服务端 remainingHours 换算成本地截止时刻（见 workflowTodo.decorateTodo）；
 * 2. 之后本地按分钟递减，页面可见时重新拉取校准，避免高频请求。
 */

const HOUR_MS = 60 * 60 * 1000
const MINUTE_MS = 60 * 1000

/** 临期阈值：SLA 最后 6 小时。超出即视为「临期」，需要醒目提示。 */
export const URGENT_REMAIN_MS = 6 * HOUR_MS

/** 把后端时间（ISO / "YYYY-MM-DD HH:mm:ss" / 时间戳）统一转成毫秒。 */
export function toTimestamp(value) {
  if (!value) return 0
  if (value instanceof Date) return value.getTime()
  if (typeof value === 'number') return value
  const text = String(value).trim().replace(' ', 'T')
  const ms = Date.parse(text)
  return Number.isFinite(ms) ? ms : 0
}

function pad2(value) {
  return String(value).padStart(2, '0')
}

function isSameDay(a, b) {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate()
}

/** 剩余毫秒 → 展示文案。unknown 用 NaN 表示（后端未给 remainingHours）。 */
export function formatRemain(remainMs) {
  if (!Number.isFinite(remainMs)) return '待处理'
  if (remainMs <= 0) return '已超时'
  if (remainMs >= 24 * HOUR_MS) {
    return `剩余 ${Math.max(1, Math.round(remainMs / (24 * HOUR_MS)))} 天`
  }
  if (remainMs >= HOUR_MS) return `剩余 ${Math.ceil(remainMs / HOUR_MS)} 小时`
  const minutes = Math.max(1, Math.ceil(remainMs / MINUTE_MS))
  return `剩余 ${minutes} 分钟`
}

/** 紧急度：overdue 已超时 / urgent 临期 / normal 普通 / unknown 无时限数据。 */
export function urgencyLevel(remainMs) {
  if (!Number.isFinite(remainMs)) return 'unknown'
  if (remainMs <= 0) return 'overdue'
  if (remainMs <= URGENT_REMAIN_MS) return 'urgent'
  return 'normal'
}

/** 到达时间展示：今天/昨天用相对文案，其余用 MM-DD HH:mm（跨年带年份）。 */
export function formatArriveTime(value) {
  const ms = toTimestamp(value)
  if (!ms) return ''
  const date = new Date(ms)
  const now = new Date()
  const hm = `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
  if (isSameDay(date, now)) return `今天 ${hm}`
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (isSameDay(date, yesterday)) return `昨天 ${hm}`
  const year = date.getFullYear() === now.getFullYear() ? '' : `${date.getFullYear()}-`
  return `${year}${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${hm}`
}
