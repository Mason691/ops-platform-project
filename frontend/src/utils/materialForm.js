/**
 * 员工物资领用/核销——表单常量、校验与错误归类（纯函数，与后端口径一致）。
 *
 * 错误键：普通字段用字段名；明细行用 items.{index}.{field}。
 * 后端始终二次校验；前端只负责分步引导与即时反馈。
 */

export const MAX_ITEMS = 50
export const MAX_PARTICIPANTS = 20
export const MAX_USAGE_DESC = 1000
export const MAX_REMARK = 500
export const MAX_VOUCHERS = 5

export const REQUISITION_STEPS = [
  { key: 'info', title: '领用信息' },
  { key: 'items', title: '物料明细' },
  { key: 'confirm', title: '确认提交' }
]

export const WRITE_OFF_STEPS = [
  { key: 'info', title: '核销信息' },
  { key: 'items', title: '物料核销' },
  { key: 'confirm', title: '确认提交' }
]

export const REQUISITION_STATUS = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  ISSUED: '已发放'
}

export const WRITE_OFF_STATUS = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

export const WRITE_OFF_PROGRESS = {
  NONE: '未核销',
  PARTIAL: '部分核销',
  COMPLETED: '已核销完成'
}

const REQUISITION_FIELDS = {
  purpose: '领用用途',
  expectedDate: '期望领用日期',
  agentUserIds: '领用人',
  items: '物料明细',
  remark: '备注'
}

const WRITE_OFF_FIELDS = {
  requisitionId: '领用单',
  writeOffDate: '核销日期',
  usageDesc: '使用说明',
  agentUserIds: '核销人',
  voucherUrls: '使用凭证',
  items: '核销明细',
  remark: '备注'
}

const ITEM_FIELDS = {
  category: '商品类别',
  materialId: '商品名称',
  quantity: '领用数量',
  writeOffQty: '本次核销数量',
  returnQty: '退回数量',
  sum: '核销+退回数量'
}

export function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

let uidSeed = 0
export function newUid() {
  uidSeed += 1
  return `m${Date.now().toString(36)}-${uidSeed}`
}

export function emptyRequisitionItem() {
  return {
    uid: newUid(), category: '', materialId: null, materialNumber: '',
    name: '', brand: '', model: '', unit: '', quantity: 1, remark: ''
  }
}

export function emptyRequisitionForm() {
  return {
    purpose: '', expectedDate: today(), remark: '',
    agentUserIds: [], items: [emptyRequisitionItem()]
  }
}

export function emptyWriteOffItem(entry) {
  const remaining = Number(entry && entry.remainingQty) || 0
  return {
    uid: newUid(),
    requisitionEntryId: entry ? entry.id : null,
    materialId: entry ? entry.materialId : null,
    materialNumber: entry ? entry.materialNumber : '',
    materialName: entry ? entry.materialName : '',
    category: entry ? entry.category : '',
    brand: entry ? entry.brand : '',
    model: entry ? entry.model : '',
    unit: entry ? entry.unit : '',
    requisitionQty: Number(entry && entry.quantity) || 0,
    remainingQty: remaining,
    writeOffQty: remaining,
    returnQty: 0,
    remark: ''
  }
}

export function emptyWriteOffForm() {
  return {
    requisitionId: null, requisitionNo: '', applicantName: '', deptName: '',
    writeOffDate: today(), usageDesc: '', remark: '',
    agentUserIds: [], voucherUrls: [], items: []
  }
}

// ── 校验 ──────────────────────────────────────────────────────────────

export function validateRequisition(form, meta = {}) {
  const errors = {}
  if (!String(form.purpose || '').trim()) errors.purpose = '请输入领用用途'
  else if (String(form.purpose).trim().length > MAX_REMARK) {
    errors.purpose = `领用用途不能超过${MAX_REMARK}字`
  }
  if (!form.expectedDate) errors.expectedDate = '请选择期望领用日期'
  validateParticipants(errors, form.agentUserIds, meta)
  validateRequisitionItems(errors, form.items)
  if (String(form.remark || '').trim().length > MAX_REMARK) {
    errors.remark = `备注不能超过${MAX_REMARK}字`
  }
  return { valid: Object.keys(errors).length === 0, errors }
}

function validateParticipants(errors, ids, meta) {
  const list = (ids || []).map(String)
  if (!list.length) {
    errors.agentUserIds = '请选择人员'
    return
  }
  if (list.length > MAX_PARTICIPANTS) {
    errors.agentUserIds = `最多选择${MAX_PARTICIPANTS}人`
    return
  }
  if (meta.employeeUserId && !list.includes(String(meta.employeeUserId))) {
    errors.agentUserIds = '必须包含本人'
  }
}

function validateRequisitionItems(errors, items) {
  const all = items || []
  const filled = all.filter(row => row && (row.materialId || row.category))
  if (!filled.length) {
    errors.items = '请至少添加一条物料'
    return
  }
  if (filled.length > MAX_ITEMS) {
    errors.items = `单笔最多${MAX_ITEMS}种物料`
    return
  }
  const seen = new Set()
  all.forEach((row, index) => {
    if (!row || (!row.materialId && !row.category)) return
    const key = field => `items.${index}.${field}`
    if (!row.category) errors[key('category')] = '请选择商品类别'
    if (!row.materialId) errors[key('materialId')] = '请选择商品名称'
    const qty = Number(row.quantity)
    if (!Number.isInteger(qty) || qty < 1) errors[key('quantity')] = '领用数量必须为正整数'
    if (row.materialId) {
      if (seen.has(String(row.materialId))) {
        errors[key('materialId')] = '该物料已在明细中'
      }
      seen.add(String(row.materialId))
    }
  })
}

export function validateWriteOff(form, meta = {}) {
  const errors = {}
  if (!form.requisitionId) errors.requisitionId = '请选择领用单'
  if (!form.writeOffDate) errors.writeOffDate = '请选择核销日期'
  const desc = String(form.usageDesc || '').trim()
  if (!desc) errors.usageDesc = '请输入使用说明'
  else if (desc.length > MAX_USAGE_DESC) errors.usageDesc = `使用说明不能超过${MAX_USAGE_DESC}字`
  validateParticipants(errors, form.agentUserIds, meta)
  validateWriteOffItems(errors, form.items)
  if (!(form.voucherUrls || []).length) errors.voucherUrls = '请至少上传1张使用凭证'
  if (String(form.remark || '').trim().length > MAX_REMARK) {
    errors.remark = `备注不能超过${MAX_REMARK}字`
  }
  return { valid: Object.keys(errors).length === 0, errors }
}

function validateWriteOffItems(errors, items) {
  const rows = items || []
  if (!rows.length) {
    errors.items = '领用单没有可核销物料'
    return
  }
  let anyPositive = false
  for (let index = 0; index < rows.length; index += 1) {
    const row = rows[index]
    const key = field => `items.${index}.${field}`
    const writeOff = Number(row.writeOffQty)
    const returnQty = Number(row.returnQty)
    if (!Number.isInteger(writeOff) || writeOff < 0) {
      errors[key('writeOffQty')] = '核销数量必须为≥0的整数'
    }
    if (!Number.isInteger(returnQty) || returnQty < 0) {
      errors[key('returnQty')] = '退回数量必须为≥0的整数'
    }
    const sum = (Number.isFinite(writeOff) ? writeOff : 0)
      + (Number.isFinite(returnQty) ? returnQty : 0)
    if (sum > Number(row.remainingQty || 0)) {
      errors[key('sum')] = `核销+退回不能超过可核销余量${row.remainingQty}`
    }
    if (sum > 0) anyPositive = true
  }
  if (!anyPositive) errors.items = '请至少核销或退回一种物料'
}

// ── 错误键定位 ────────────────────────────────────────────────────────

export function requisitionStepOf(key) {
  if (key === 'items' || key.startsWith('items.')) return 1
  return 0
}

export function writeOffStepOf(key) {
  if (key === 'items' || key.startsWith('items.')) return 1
  if (key === 'voucherUrls') return 2
  return 0
}

export function missingItems(errors, stepOf, fields) {
  return Object.keys(errors || {})
    .map(key => ({ key, label: labelOf(key, fields), step: stepOf(key) }))
    .sort((a, b) => a.step - b.step)
}

function labelOf(key, fields) {
  if (key.startsWith('items.')) {
    const [, index, field] = key.split('.')
    return `第${Number(index) + 1}条物料 · ${ITEM_FIELDS[field] || '信息'}`
  }
  return fields[key] || key
}

export function hasStepErrors(errors, stepOf, step) {
  return Object.keys(errors || {}).some(key => stepOf(key) === step)
}

export function fieldIdOf(key) {
  if (key.startsWith('items.')) {
    const [, index, field] = key.split('.')
    return field ? `field-items-${index}-${field}` : 'field-items'
  }
  return `field-${key}`
}

export function requisitionLabels() {
  return REQUISITION_FIELDS
}

export function writeOffLabels() {
  return WRITE_OFF_FIELDS
}

// ── 汇总 ──────────────────────────────────────────────────────────────

export function requisitionTotals(items) {
  const rows = (items || []).filter(row => row && row.materialId)
  const totalQty = rows.reduce((sum, row) => sum + (Number(row.quantity) || 0), 0)
  return { itemCount: rows.length, totalQty }
}

export function writeOffTotals(items) {
  const rows = (items || []).filter(row => row && row.requisitionEntryId)
  const writeOff = rows.reduce((sum, row) => sum + (Number(row.writeOffQty) || 0), 0)
  const returnQty = rows.reduce((sum, row) => sum + (Number(row.returnQty) || 0), 0)
  return { itemCount: rows.length, writeOffTotal: writeOff, returnTotal: returnQty }
}

export function formatQty(value) {
  const n = Number(value)
  return Number.isFinite(n) ? String(n) : '0'
}

/** LocalDateTime 展示：去掉 T、精确到分钟。 */
export function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}
