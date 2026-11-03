/**
 * 员工手工新增业务 — 表单校验与金额计算（纯函数，便于单测/复用）。
 */

export const PHONE_RE = /^1[3-9]\d{9}$/

export function normalizePhone(value) {
  return String(value || '').replace(/[^\d]/g, '').slice(0, 11)
}

/** 金额统一保留两位小数；发送给后端前也用它，保证前后端按同一单价计算。 */
export function normalizeMoney(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return 0
  return Math.round((n + Number.EPSILON) * 100) / 100
}

export function computeReceiptAmount(price, quantity) {
  const p = normalizeMoney(price)
  const q = Number(quantity)
  if (!Number.isFinite(p) || p <= 0 || !Number.isInteger(q) || q < 1) return 0
  return normalizeMoney(p * q)
}

export function today() {
  const d = new Date()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

/**
 * 前端校验（与后端口径一致）。返回 { valid, errors, firstError }。
 * 后端仍会重复校验，前端仅用于即时提示。
 */
export function validateBusinessForm(form) {
  const errors = {}
  const customer = String(form.customer || '').trim()

  if (!form.newBatch && !String(form.batchNo || '').trim()) {
    errors.batchNo = '请选择批次'
  }
  if (!PHONE_RE.test(normalizePhone(form.customerPhone))) {
    errors.customerPhone = '请输入正确的11位手机号'
  }
  if (!customer) errors.customer = '请输入客户名称'

  if (form.isSpecial) {
    const rate = Number(form.commissionRate)
    const empty = String(form.commissionRate ?? '').trim() === ''
    if (empty || !Number.isFinite(rate) || rate < 0 || rate > 100) {
      errors.commissionRate = '请输入0-100的提成比例'
    }
  }

  if (!form.brand) errors.brand = '请选择品牌'
  if (!form.category) errors.category = '请选择品类'
  if (!form.model) errors.model = '请选择型号'

  const price = normalizeMoney(form.salePrice)
  if (price <= 0) errors.salePrice = '销售单价必须大于0'

  const qty = Number(form.saleQuantity)
  if (!Number.isInteger(qty) || qty < 1) errors.saleQuantity = '销售数量必须为正整数'

  if (!form.receiptDate) errors.receiptDate = '请选择市场收款日期'

  if (form.isReceived && (!Array.isArray(form.voucherUrls) || form.voucherUrls.length === 0)) {
    errors.voucherUrls = '请上传至少1张收款凭证'
  }

  const order = ['batchNo', 'customerPhone', 'customer', 'commissionRate', 'brand',
    'category', 'model', 'salePrice', 'saleQuantity', 'receiptDate', 'voucherUrls']
  const firstError = order.map(key => errors[key]).find(Boolean) || ''
  return { valid: Object.keys(errors).length === 0, errors, firstError }
}
