import { ref } from 'vue'
import { api } from '@/api/index'
import { normalizeMoney } from '@/utils/businessForm'

/**
 * 产品标准售价查询与低价特批判定（按产品卡片实例使用）。
 *
 * 型号选定后查询产品售价表：配置则自动带价并默认锁定；低于标准价自动标记特批，
 * 由卡片展开特批文件与提成比例。查询结果按商品ID/品牌品类型号做 60 秒进程内缓存，
 * 避免多行相同商品重复请求。
 */

const CACHE_TTL_MS = 60 * 1000
const cache = new Map()

function cacheKey(product) {
  // 组织切换后同一商品可能对应不同运营商的售价，缓存键必须带当前组织
  let corpKey = ''
  try { corpKey = String(uni.getStorageSync('corp_id') || '') } catch (e) { /* ignore */ }
  const productKey = product && product.productId
    ? 'id:' + product.productId
    : [product && product.brand, product && product.category, product && product.model]
      .map(value => String(value || ''))
      .join('|')
  return corpKey + '#' + productKey
}

export function useProductPrice(product) {
  const checking = ref(false)
  let lookupToken = 0

  function resetPrice() {
    lookupToken += 1
    product.standardSalePrice = null
    product.priceConfigured = null
    product.priceError = ''
    product.priceLoading = false
    product.salePrice = ''
    product.priceLocked = true
    product.isSpecial = false
    product.specialNotified = false
    clearSpecial()
  }

  async function lookup(options = {}) {
    const preservePrice = options.preservePrice === true
    if (!product || !product.brand || !product.category || !product.model) {
      resetPrice()
      return
    }
    const key = cacheKey(product)
    const cached = cache.get(key)
    if (cached && Date.now() - cached.ts < CACHE_TTL_MS) {
      apply(cached.info, preservePrice)
      return
    }
    const token = ++lookupToken
    checking.value = true
    product.priceLoading = true
    product.priceError = ''
    try {
      const info = await api.employeeProductPrice({
        productId: product.productId || undefined,
        brand: product.brand,
        category: product.category,
        model: product.model
      })
      // 型号快速切换时旧响应必须丢弃，避免把上一型号的价格写到当前行
      if (token !== lookupToken) return
      // 未配置/停用不缓存：管理员补配后用户重新选择型号即可立即生效
      if (info && info.configured) cache.set(key, { info, ts: Date.now() })
      apply(info, preservePrice)
    } catch (e) {
      if (token !== lookupToken) return
      product.priceConfigured = false
      product.priceError = (e && (e.msg || e.message)) || '标准售价查询失败，请重试'
      product.priceLoading = false
    } finally {
      if (token === lookupToken) checking.value = false
    }
  }

  function apply(info, preservePrice = false) {
    product.priceLoading = false
    if (!info || !info.configured) {
      product.priceConfigured = false
      product.priceError = (info && info.message) || '该商品未配置售价，请联系管理员'
      product.standardSalePrice = null
      product.salePrice = ''
      product.priceLocked = true
      product.isSpecial = false
      product.specialNotified = false
      clearSpecial()
      return
    }
    const standard = normalizeMoney(info.salePrice)
    product.priceConfigured = true
    product.priceError = ''
    product.standardSalePrice = standard
    const currentPrice = normalizeMoney(product.salePrice)
    if (preservePrice && currentPrice > 0) {
      // 草稿/步骤切换回显：保留用户已填价格与特批附件，只按最新标准价重判
      const below = standard > 0 && currentPrice < standard
      product.isSpecial = below
      product.priceLocked = currentPrice === standard
      if (!below) {
        clearSpecial()
        product.specialNotified = false
      }
      return
    }
    product.salePrice = standard.toFixed(2)
    product.priceLocked = true
    product.isSpecial = false
    product.specialNotified = false
    clearSpecial()
  }

  /** 价格变更后重新判定特批；notify=true 时在首次进入低价区给出提示。 */
  function evaluateSpecial(notify = true) {
    const standard = Number(product.standardSalePrice)
    const price = normalizeMoney(product.salePrice)
    const below = standard > 0 && price > 0 && price < standard
    product.isSpecial = below
    if (!below) {
      // 输入过程中价格可能短暂为空（清空重输），此时不能清掉已上传的
      // 特批文件/提成比例；仅在失焦且价格有效不低于标准价时清空。
      const validAbove = standard > 0 && price >= standard
      if (notify && validAbove) {
        clearSpecial()
        product.specialNotified = false
      }
      return
    }
    if (notify && !product.specialNotified) {
      product.specialNotified = true
      uni.showToast({ title: '本次售价低于标准销售价，需上传特批文件', icon: 'none', duration: 2600 })
    }
  }

  function unlockPrice() {
    product.priceLocked = false
  }

  function useStandardPrice() {
    if (product.standardSalePrice == null) return
    product.salePrice = Number(product.standardSalePrice).toFixed(2)
    product.priceLocked = true
    evaluateSpecial()
  }

  function clearSpecial() {
    product.specialFileId = null
    product.specialFileUrl = ''
    product.specialFileName = ''
    product.commissionRate = ''
  }

  return { checking, lookup, resetPrice, evaluateSpecial, unlockPrice, useStandardPrice }
}
