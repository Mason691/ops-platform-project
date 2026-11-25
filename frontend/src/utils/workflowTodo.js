import { api } from '@/api'
import { toTimestamp } from '@/utils/formatRemainTime'

/**
 * 待办任务的共享口径：首页卡片与完整待办列表必须用同一套
 * 「过滤 + 排序」逻辑，否则会出现首页看到、列表页找不到的情况。
 *
 * 排序口径：按任务创建时间倒序（最新到达的待办在最前），与底座
 * pageTodo 的排序一致；紧急度只用于剩余时限文案与颜色，不参与排序。
 * 首页与列表页统一调用本文件，保证两处顺序一致。
 */

const HOUR_MS = 60 * 60 * 1000

/** 首页与列表页统一拉取上限（列表页原有口径）。 */
export const TODO_PAGE_SIZE = 50

export const BUSINESS_TYPE_LABELS = {
  OPS_BUSINESS_ORDER: '业务数据订单',
  PUR_ORDER: '采购订单',
  MATERIAL_PICK: '物料领用',
  MATERIAL_WRITE_OFF: '物料核销'
}

export function businessTypeLabel(code) {
  return BUSINESS_TYPE_LABELS[code] || ''
}

/**
 * 固化服务端 remainingHours 为本地截止时刻。
 * 排序与倒计时都以 __deadlineAt 为基准，避免每次渲染重新请求。
 */
export function decorateTodo(records, receivedAt) {
  return (records || []).map(item => {
    // null/undefined 表示后端未给 SLA，不等同于 0（0 才是已超时）
    const rawHours = item ? item.remainingHours : null
    const hours = rawHours === null || rawHours === undefined ? NaN : Number(rawHours)
    return {
      ...item,
      __deadlineAt: Number.isFinite(hours)
        ? receivedAt + Math.max(0, hours) * HOUR_MS
        : NaN
    }
  })
}

/** 任务创建时间（缺省回退实例提交时间），与列表展示的到达时间口径一致。 */
function createdAtOf(item) {
  return toTimestamp(item && item.task && item.task.createTime)
    || toTimestamp(item && item.submitTime)
}

/** 排序快照：只在加载时调用一次，运行中只刷新文案不重排，避免卡片跳动误触。 */
export function sortTodoByCreateTime(records) {
  return [...(records || [])].sort((a, b) => {
    const createdA = createdAtOf(a)
    const createdB = createdAtOf(b)
    if (createdA !== createdB) return createdB - createdA
    return (Number(b && b.task && b.task.id) || 0) - (Number(a && a.task && a.task.id) || 0)
  })
}

/**
 * 施工任务按当前组织过滤：施工员在多个组织任职时，切换组织后
 * 首页/待办只展示当前组织的施工任务。
 *
 * <p>任务接口已返回业务单据归属组织（corpId），无需逐单查询施工视图；
 * 写操作仍由后端强校验「当前组织 = 订单组织」。</p>
 */
export function filterIneligibleConstruct(records) {
  const currentCorpId = String(uni.getStorageSync('corp_id') || '')
  if (!currentCorpId) return records || []
  return (records || []).filter(item => !(isConstructTask(item)
    && item.corpId != null && String(item.corpId) !== currentCorpId))
}

function isConstructTask(item) {
  if (!item || item.businessType !== 'OPS_BUSINESS_ORDER') return false
  const nodeName = String((item.task && item.task.nodeName)
    || item.currentNodeName || '')
  return nodeName.includes('施工')
}

function isDispatchTask(item) {
  return !!item && item.businessType === 'OPS_BUSINESS_ORDER'
    && String((item.task && item.task.nodeName) || item.currentNodeName || '').includes('派工')
}

/**
 * 派工待办可见性：
 * - 全款已付 / 定金尾款已结清 → 可见且可派工（待派工）
 * - 定金已付、尾款未结清、财务已核对 → 可见但不可派工（待收款）
 * - 定金未核对 / 未支付 → 不进入待办
 * 资格查询失败时 fail-open，由任务页与后端节点动作兜底。
 */
export async function filterDispatchTodoVisibility(records) {
  const dispatch = (records || []).filter(isDispatchTask)
  const ids = [...new Set(dispatch.map(item => Number(item.businessId)).filter(Boolean))]
  if (!ids.length) return records || []
  try {
    const infos = await api.businessDispatchEligibility(ids)
    const byOrder = new Map((infos || []).map(info => [String(info.orderId), info]))
    return (records || []).flatMap(item => {
      if (!isDispatchTask(item)) return [item]
      const info = byOrder.get(String(item.businessId))
      if (!info) return [item]
      // 兼容旧后端：无 visibleInDispatchTodo 时退回 canDispatch
      const visible = info.visibleInDispatchTodo !== undefined
        ? info.visibleInDispatchTodo : info.canDispatch === true
      if (!visible) return []
      return [{ ...item, dispatchInfo: info }]
    })
  } catch (e) {
    return records || []
  }
}
