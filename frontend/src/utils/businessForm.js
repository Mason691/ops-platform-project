/**
 * 员工手工新增业务 — 表单校验与金额计算（纯函数，便于单测/复用）。
 */

export const PHONE_RE = /^1[3-9]\d{9}$/

/** 单笔业务最多产品行（与后端 MAX_PRODUCTS 保持一致） */
export const MAX_PRODUCTS = 50

/** 单笔业务最多经办人（与后端 MAX_AGENTS 保持一致，含本人） */
export const MAX_AGENTS = 20

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

/** 新增一行空白产品。 */
export function emptyProduct() {
  return {
    category: '', brand: '', model: '',
    salePrice: '', saleQuantity: '1', commissionRate: ''
  }
}

/** 单行产品的收款金额 = 销售单价 × 销售数量。 */
export function computeProductAmount(product) {
  return computeReceiptAmount(product && product.salePrice, product && product.saleQuantity)
}

/** 整笔业务收款金额合计（按产品求和，不随经办人数翻倍）。 */
export function computeProductsTotal(products) {
  const sum = (products || []).reduce(
    (total, product) => total + computeProductAmount(product), 0)
  return normalizeMoney(sum)
}

export function today() {
  const d = new Date()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

/**
 * 前端校验（与后端口径一致）。返回 { valid, errors, firstError }。
 * 错误键：公共字段用字段名；产品行用 products.{index}.{field}。
 * 后端仍会重复校验，前端仅用于即时提示。
 */
export function validateBusinessForm(form, meta = {}) {
  const errors = {}
  const customer = String(form.customer || '').trim()

  if (!form.newBatch && !String(form.batchNo || '').trim()) {
    errors.batchNo = '请选择批次'
  }
  if (!PHONE_RE.test(normalizePhone(form.customerPhone))) {
    errors.customerPhone = '请输入正确的11位手机号'
  }
  if (!customer) errors.customer = '请输入客户名称'
  // 小区选填（会员查询自动带出）；详细地址必填，需能定位到具体门牌
  const detailAddress = String(form.detailAddress || '').trim()
  if (!detailAddress) errors.detailAddress = '请输入详细地址（楼栋/单元/门牌号）'
  else if (detailAddress.length > 200) errors.detailAddress = '详细地址不能超过200个字'

  const employees = Array.isArray(meta.employees) ? meta.employees : []
  const selectedIds = (form.agentUserIds || []).map(String)
  const selfIds = employees.filter(item => item && item.isSelf)
    .map(item => String(item.userId))
  if (!selectedIds.length) errors.agentUserIds = '请选择经办人'
  else if (selectedIds.length > MAX_AGENTS) {
    errors.agentUserIds = `单笔业务最多选择${MAX_AGENTS}名经办人`
  } else if (selfIds.length && !selfIds.some(id => selectedIds.includes(id))) {
    errors.agentUserIds = '经办人必须包含本人'
  }

  const products = Array.isArray(form.products) ? form.products : []
  const seenModels = {}
  if (!products.length) errors.products = '请至少添加一行产品'
  if (products.length > MAX_PRODUCTS) errors.products = `单笔业务最多${MAX_PRODUCTS}行产品`

  products.forEach((product, index) => {
    const key = field => `products.${index}.${field}`
    const prefix = `第${index + 1}个产品：`
    if (!product || !product.category) {
      errors[key('category')] = prefix + '请选择品类'
    }
    if (!product || !product.brand) {
      errors[key('brand')] = prefix + '请选择品牌'
    }
    if (!product || !product.model) {
      errors[key('model')] = prefix + '请选择型号'
    } else if (product.brand && product.category) {
      const combo = [product.brand, product.category, product.model].join('|')
      if (seenModels[combo]) {
        errors[key('model')] = prefix + `与第${seenModels[combo]}个产品重复，请合并数量`
      } else {
        seenModels[combo] = index + 1
      }
    }
    const price = normalizeMoney(product && product.salePrice)
    if (price <= 0) errors[key('salePrice')] = prefix + '销售单价必须大于0'
    const qty = Number(product && product.saleQuantity)
    if (!Number.isInteger(qty) || qty < 1) {
      errors[key('saleQuantity')] = prefix + '销售数量必须为正整数'
    }
    if (form.isSpecial) {
      const raw = product && product.commissionRate
      const empty = String(raw ?? '').trim() === ''
      const rate = Number(raw)
      if (empty || !Number.isFinite(rate) || rate < 0 || rate > 100) {
        errors[key('commissionRate')] = prefix + '请输入0-100的提成比例'
      }
    }
  })

  if (!form.receiptDate) errors.receiptDate = '请选择市场收款日期'

  if (form.isReceived && (!Array.isArray(form.voucherUrls) || form.voucherUrls.length === 0)) {
    errors.voucherUrls = '请上传至少1张收款凭证'
  }

  const order = ['batchNo', 'customerPhone', 'customer', 'detailAddress', 'agentUserIds', 'products']
  products.forEach((_, index) => {
    ['category', 'brand', 'model', 'salePrice', 'saleQuantity', 'commissionRate']
      .forEach(field => order.push(`products.${index}.${field}`))
  })
  order.push('receiptDate', 'voucherUrls')
  const firstError = order.map(key => errors[key]).find(Boolean) || ''
  return { valid: Object.keys(errors).length === 0, errors, firstError }
}
