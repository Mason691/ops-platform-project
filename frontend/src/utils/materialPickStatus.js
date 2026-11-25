/**
 * 物料领用单状态唯一映射（字典 material_pick_status）。
 *
 * 0 未审核 →（施工员接受派工）→ 1 已审核 →（库管打印）→ 2 已领用
 *          →（施工员申请核销）→ 3 申请核销 →（库管同意）→ 4 物料核销
 *
 * 列表、详情、卡片、待办、我的单据都从这里取文案，禁止页面内各写一套。
 */
export const MATERIAL_PICK_STATUS_MAP = {
  '0': '未审核',
  '1': '已审核',
  '2': '已领用',
  '3': '申请核销',
  '4': '物料核销'
}

/** 领用单状态筛选项（全部 + 5 个状态）。 */
export const MATERIAL_PICK_STATUS_OPTIONS = [
  { value: '', label: '全部' },
  { value: '0', label: '未审核' },
  { value: '1', label: '已审核' },
  { value: '2', label: '已领用' },
  { value: '3', label: '申请核销' },
  { value: '4', label: '物料核销' }
]

export function materialPickStatusLabel(status) {
  return MATERIAL_PICK_STATUS_MAP[String(status)] || status || '—'
}

export function materialPickStatusClass(status) {
  return {
    '0': 'st-draft',
    '1': 'st-ok',
    '2': 'st-pending',
    '3': 'st-pending',
    '4': 'st-ok'
  }[String(status)] || ''
}

/** 金蝶其他出库单同步状态（scm_material_pick.jdy_sync_status）。 */
export const MATERIAL_PICK_JDY_STATUS_MAP = {
  PUSHED: '金蝶已推送待审核',
  AUDITED: '金蝶已同步',
  PUSH_FAILED: '金蝶同步失败',
  NOT_REQUIRED: '金蝶无需同步'
}

export function materialPickJdyStatusLabel(status) {
  return MATERIAL_PICK_JDY_STATUS_MAP[status] || (status ? String(status) : '金蝶未推送')
}
