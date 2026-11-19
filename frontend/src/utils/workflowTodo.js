import { api } from '@/api'
import { toTimestamp, urgencyLevel } from '@/utils/formatRemainTime'

/**
 * 待办任务的共享口径：首页卡片与完整待办列表必须用同一套
 * 「过滤 + 排序」逻辑，否则会出现首页看到、列表页找不到的情况。
 *
 * 紧急度排序（超时 > 临期 > 普通，同级按到达时间倒序）目前在 H5 侧完成：
 * 底座 pageTodo 只按任务创建时间倒序，且未提供排序参数；跨仓库改平台后端
 * 不在本次范围。首页与列表页统一调用本文件，保证两处顺序一致。
 */

const HOUR_MS = 60 * 60 * 1000

/** 首页与列表页统一拉取上限（列表页原有口径）。 */
export const TODO_PAGE_SIZE = 50

const URGENCY_RANK = { overdue: 0, urgent: 1, normal: 2, unknown: 3 }

export const BUSINESS_TYPE_LABELS = {
  OPS_BUSINESS_ORDER: '业务数据订单',
  PUR_ORDER: '采购订单',
  OPS_MATERIAL_REQUISITION: '物资领用',
  OPS_MATERIAL_WRITE_OFF: '物资核销',
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

function remainMsOf(item, nowMs) {
  return item && Number.isFinite(item.__deadlineAt) ? item.__deadlineAt - nowMs : NaN
}

/** 排序快照：只在加载时调用一次，运行中只刷新文案不重排，避免卡片跳动误触。 */
export function sortTodoByPriority(records, nowMs) {
  return [...(records || [])].sort((a, b) => {
    const rankA = URGENCY_RANK[urgencyLevel(remainMsOf(a, nowMs))] ?? 3
    const rankB = URGENCY_RANK[urgencyLevel(remainMsOf(b, nowMs))] ?? 3
    if (rankA !== rankB) return rankA - rankB
    const arriveA = toTimestamp(a && a.task && a.task.createTime) || toTimestamp(a && a.submitTime)
    const arriveB = toTimestamp(b && b.task && b.task.createTime) || toTimestamp(b && b.submitTime)
    if (arriveA !== arriveB) return arriveB - arriveA
    return (Number(b && b.task && b.task.id) || 0) - (Number(a && a.task && a.task.id) || 0)
  })
}

/**
 * 派工待办不展示给未收齐全款的订单：底座仍会生成任务（工作流结构决定），
 * H5 按订单派工资格过滤；任务页与后端节点动作仍会二次拦截。
 * 资格查询失败时 fail-open，由任务页/后端的最终校验兜底。
 */
export async function filterIneligibleDispatch(records) {
  const dispatch = (records || []).filter(item => item.businessType === 'OPS_BUSINESS_ORDER'
    && String((item.task && item.task.nodeName) || item.currentNodeName || '').includes('派工'))
  const ids = [...new Set(dispatch.map(item => Number(item.businessId)).filter(Boolean))]
  if (!ids.length) return records || []
  try {
    const infos = await api.businessDispatchEligibility(ids)
    const blocked = new Set((infos || [])
      .filter(info => info.canDispatch === false)
      .map(info => String(info.orderId)))
    return (records || []).filter(item => !(item.businessType === 'OPS_BUSINESS_ORDER'
      && blocked.has(String(item.businessId))))
  } catch (e) {
    return records || []
  }
}
