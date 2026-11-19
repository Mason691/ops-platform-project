/**
 * 物料领用单审批状态唯一映射：DRAFT 统一显示“待提交”。
 * 列表、详情、卡片、待办、我的单据都从这里取文案。
 */
export const MATERIAL_PICK_STATUS_MAP = {
  DRAFT: '待提交',
  IN_APPROVAL: '审批中',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

export function materialPickStatusLabel(status) {
  return MATERIAL_PICK_STATUS_MAP[status] || status || '—'
}

export function materialPickStatusClass(status) {
  return {
    DRAFT: 'st-draft',
    IN_APPROVAL: 'st-pending',
    APPROVED: 'st-ok',
    REJECTED: 'st-reject'
  }[status] || ''
}
