/**
 * 员工手工新增业务 — 表单校验、分步错误归类与金额计算（纯函数）。
 *
 * 校验口径与后端 EmployeeBusinessEntryServiceImpl 保持一致；后端仍会
 * 重复校验，前端只用于分步引导与就地提示。
 */

export const PHONE_RE = /^1[3-9]\d{9}$/

/** 单笔业务最多产品行（与后端 MAX_PRODUCTS 保持一致） */
export const MAX_PRODUCTS = 50

/** 单笔业务最多经办人（与后端 MAX_AGENTS 保持一致，含本人） */
export const MAX_AGENTS = 20

/** 备注最大长度（与后端 MAX_REMARK_LENGTH 保持一致） */
export const MAX_REMARK = 500

/** 向导步骤定义：key 用于组件切换，title 用于步骤条。 */
export const STEPS = [
  { key: 'basic', title: '基础信息' },
  { key: 'products', title: '商品明细' },
  { key: 'receipt', title: '收款信息' },
  { key: 'confirm', title: '确认提交' }
]

const BASIC_FIELDS = ['batchNo', 'customerPhone', 'customer', 'communityId',
  'detailAddress', 'agentUserIds']
const RECEIPT_FIELDS = ['receiptDate', 'voucherUrls']
const PRODUCT_FIELD_LABELS = {
  category: '品类',
  brand: '品牌',
  model: '型号',
  salePrice: '销售单价',
  saleQuantity: '销售数量',
  commissionRate: '提成比例'
}
const FIELD_LABELS = {
  batchNo: '批次',
  customerPhone: '客户手机',
  customer: '客户名称',
  communityId: '小区',
  detailAddress: '详细地址',
  agentUserIds: '经办人',
  products: '产品明细',
  receiptDate: '市场收款日期',
  voucherUrls: '收款凭证',
  remark: '备注'
}

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

/** 新建空表单（批次默认值由向导初始化逻辑按选项决定）。 */
export function emptyBusinessForm() {
  return {
    batchNo: '', newBatch: true, customerPhone: '', customer: '',
    communityId: null, communityName: '', detailAddress: '',
    isSpecial: false, products: [emptyProduct()],
    receiptDate: today(), isReceived: false, voucherUrls: [], remark: '',
    agentUserIds: []
  }
}

/**
 * 前端全量校验（与后端口径一致）。返回 { valid, errors, firstError }。
 * 错误键：公共字段用字段名；产品行用 products.{index}.{field}。
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
  // 小区必填：业务订单需归属到具体小区，便于后续网格/台账统计。
  if (!form.communityId) errors.communityId = '请选择小区'
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
  if (String(form.remark || '').trim().length > MAX_REMARK) {
    errors.remark = `备注不能超过${MAX_REMARK}个字`
  }

  const order = ['batchNo', 'customerPhone', 'customer', 'communityId', 'detailAddress',
    'agentUserIds', 'products']
  products.forEach((_, index) => {
    ['category', 'brand', 'model', 'salePrice', 'saleQuantity', 'commissionRate']
      .forEach(field => order.push(`products.${index}.${field}`))
  })
  order.push('receiptDate', 'voucherUrls', 'remark')
  const firstError = order.map(key => errors[key]).find(Boolean) || ''
  return { valid: Object.keys(errors).length === 0, errors, firstError }
}

/** 错误键归属的步骤：0 基础信息 / 1 商品明细 / 2 收款信息 / 3 确认页。 */
export function stepOfErrorKey(key) {
  if (BASIC_FIELDS.includes(key)) return 0
  if (key === 'products') return 1
  if (key.startsWith('products.')) {
    return key.endsWith('.commissionRate') ? 2 : 1
  }
  if (RECEIPT_FIELDS.includes(key) || key === 'remark') return 2
  return 3
}

/** 只保留指定步骤的错误，供“下一步”拦截当前步骤使用。 */
export function validateStep(form, meta, step) {
  const result = validateBusinessForm(form, meta)
  const errors = {}
  Object.entries(result.errors).forEach(([key, message]) => {
    if (stepOfErrorKey(key) === step) errors[key] = message
  })
  return {
    valid: Object.keys(errors).length === 0,
    errors,
    firstError: result.firstError,
    count: Object.keys(errors).length
  }
}

/** 错误键的可读描述，如 products.1.model → 第2个产品 · 型号。 */
export function errorLabel(key) {
  if (!key) return ''
  if (key.startsWith('products.')) {
    const [, index, field] = key.split('.')
    const row = Number(index) + 1
    return `第${row}个产品 · ${PRODUCT_FIELD_LABELS[field] || '信息'}`
  }
  return FIELD_LABELS[key] || key
}

/** 全量校验错误 → 待补充事项列表（按步骤与表单顺序）。 */
export function missingItems(errors) {
  return Object.keys(errors || {})
    .map(key => ({ key, label: errorLabel(key), step: stepOfErrorKey(key) }))
    .sort((a, b) => a.step - b.step)
}

/** 错误键集合是否包含指定步骤。 */
export function hasStepErrors(errors, step) {
  return Object.keys(errors || {}).some(key => stepOfErrorKey(key) === step)
}
