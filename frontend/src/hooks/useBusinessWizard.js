import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api } from '@/api/index'
import {
  PHONE_RE, STEPS, emptyBusinessForm, emptyProduct, missingItems, newProductUid,
  newTradeInUid, normalizeMoney, normalizePhone, validateBusinessForm, validateStep
} from '@/utils/businessForm'
import { focusField, scrollToField, vibrateLight } from '@/utils/fieldFocus'

const DRAFT_PREFIX = 'ops_business_draft_'
const DRAFT_TTL = 7 * 24 * 60 * 60 * 1000
const BANNER_MS = 2600

function emptyMeta() {
  return {
    corpId: null, corpName: '', company: '',
    employeeUserId: null, employeeName: '', employeePhone: '',
    batches: [], newBatchPrefix: '', brands: [], categories: [], materials: [],
    communities: [], employees: [], productError: '',
    // 组织所在区县：仅用于新建小区弹层展示/未配置时禁用入口，实际归属由服务端派生
    regionId: null, regionName: '', regionConfigured: true
  }
}

/**
 * 员工手工新增业务 — 5 步向导状态机。
 *
 * 校验策略：下一步只拦截当前步骤；确认页执行全量校验，缺失项在
 * errors 中实时维护，供“待补充事项”卡片就地渲染与收缩。
 * 草稿按 corp+员工隔离，成功提交后清除。
 */
export function useBusinessWizard() {
  const loading = ref(true)
  const errorMsg = ref('')
  const querying = ref(false)
  const submitting = ref(false)
  const step = ref(0)
  const maxVisited = ref(0)
  const success = ref(null)
  const banner = ref('')
  const bannerType = ref('error')
  const memberStatus = ref('idle')
  const editOrderId = ref('')
  const meta = reactive(emptyMeta())
  const form = reactive(emptyBusinessForm())
  const errors = reactive({})
  const showErrors = ref(false)

  let bannerTimer = null
  let draftTimer = null
  let confirmTimer = null
  let queryToken = 0
  let memberPhone = ''
  let inFlightPhone = ''
  let lastMemberName = ''
  let lastMemberCommunityId = null
  let draftEnabled = false
  let disposed = false

  const steps = STEPS
  const missing = computed(() => missingItems(errors))

  watch(form, () => {
    scheduleDraft()
    // 手机号变化后旧的会员匹配提示失效，避免把上一个号码的结果展示给新客户
    if (normalizePhone(form.customerPhone) !== memberPhone) {
      memberStatus.value = 'idle'
    }
    if (step.value === 4) {
      // 防抖刷新：输入过程中不立即重算，避免“刚输入一个字校验通过→
      // 待补充卡片被卸载→后续字符丢失”的焦点中断问题。
      scheduleConfirmRefresh()
    } else if (showErrors.value) {
      const result = validateStep(form, meta, step.value)
      applyErrors(result.errors)
    }
  }, { deep: true })

  onBeforeUnmount(() => {
    disposed = true
    // 页面被系统返回/关闭时，把最后 600ms 内的编辑立即落盘
    saveDraft()
    clearTimeout(bannerTimer)
    clearTimeout(draftTimer)
    clearTimeout(confirmTimer)
  })

  async function loadOptions() {
    loading.value = true
    errorMsg.value = ''
    try {
      // 可发起业务类型由底座按 userType + 权限下发；外部用户默认白名单为空
      const types = await api.workbenchBusinessTypes()
      if (!Array.isArray(types)
          || !types.some(type => type.code === 'OPS_BUSINESS_ORDER')) {
        errorMsg.value = '当前账号暂无可发起的业务类型'
        return
      }
      const data = await api.employeeManualOptions()
      Object.assign(meta, emptyMeta(), data || {})
      normalizeMeta()
      if (editOrderId.value) {
        const draft = await api.employeeDraftEditData(editOrderId.value)
        applyDraftForm(draft)
        return
      }
      form.agentUserIds = meta.employeeUserId ? [meta.employeeUserId] : []
      if (!Array.isArray(form.products) || !form.products.length) {
        form.products = [emptyProduct()]
      }
      applyBatchDefault('')
    } catch (e) {
      errorMsg.value = (e && e.msg) || '加载失败，请重试'
    } finally {
      loading.value = false
    }
    if (!errorMsg.value && !disposed) promptRestoreDraft()
  }

  function normalizeMeta() {
    meta.batches = Array.isArray(meta.batches) ? meta.batches : []
    meta.brands = Array.isArray(meta.brands) ? meta.brands : []
    meta.categories = Array.isArray(meta.categories) ? meta.categories : []
    meta.materials = Array.isArray(meta.materials) ? meta.materials : []
    meta.communities = Array.isArray(meta.communities) ? meta.communities : []
    meta.employees = Array.isArray(meta.employees) ? meta.employees : []
  }

  function setEditOrderId(id) {
    editOrderId.value = id ? String(id) : ''
  }

  /** 草稿编辑回显：沿用原订单编号/批次/经办人，允许跨步骤修改后重新提交。 */
  function applyDraftForm(draft) {
    Object.assign(form, emptyBusinessForm(), draft || {})
    if (!Array.isArray(form.products) || !form.products.length) {
      form.products = [emptyProduct()]
    }
    form.products.forEach(product => {
      if (!product) return
      if (!product.uid) product.uid = newProductUid()
      if (!Array.isArray(product.tradeInItems)) product.tradeInItems = []
      product.tradeInItems.forEach(item => {
        if (item && !item.uid) item.uid = newTradeInUid()
      })
      if (product.isTradeIn == null) product.isTradeIn = false
      if (product.deductionAmount == null) product.deductionAmount = ''
    })
    const agentIds = (Array.isArray(draft && draft.agentUserIds) ? draft.agentUserIds : [])
      .map(Number)
      .filter(Boolean)
    form.agentUserIds = agentIds.length ? agentIds
      : (meta.employeeUserId ? [meta.employeeUserId] : [])
    draftEnabled = false
    step.value = 0
    maxVisited.value = steps.length - 1
  }

  /** 默认使用当前月批次（已存在则复用），历史未上报批次仍可手动选择。 */
  function applyBatchDefault(batchNo) {
    if (batchNo) {
      if (!meta.batches.includes(batchNo)) meta.batches.unshift(batchNo)
      form.newBatch = false
      form.batchNo = batchNo
      return
    }
    const monthly = meta.newBatchPrefix
    if (monthly && meta.batches.includes(monthly)) {
      form.newBatch = false
      form.batchNo = monthly
    } else {
      form.newBatch = true
      form.batchNo = ''
    }
  }

  async function queryMember() {
    const phone = normalizePhone(form.customerPhone)
    form.customerPhone = phone
    if (!PHONE_RE.test(phone)) {
      showBanner('请输入正确的11位手机号', 'error')
      return
    }
    if (querying.value && inFlightPhone === phone) return
    querying.value = true
    const token = ++queryToken
    const originStep = step.value
    inFlightPhone = phone
    try {
      const res = await api.employeeLookupMember(phone)
      if (token !== queryToken) return
      // 查询期间用户可能已改号或已切步：丢弃过期响应，避免污染其它步骤
      if (normalizePhone(form.customerPhone) !== phone) return
      if (step.value !== originStep) return
      memberPhone = phone
      applyMemberResult(res)
    } catch (e) {
      memberStatus.value = 'idle'
      showBanner((e && e.msg) || '查询失败', 'error')
    } finally {
      if (token === queryToken) {
        querying.value = false
        inFlightPhone = ''
      }
    }
  }

  /** 应用会员查询结果：命中则回填，未命中则清理上一次自动回填。 */
  function applyMemberResult(res) {
    if (res && res.found) {
      memberStatus.value = 'found'
      applyMemberName(res)
      applyMemberCommunity(res)
      showBanner(res.name ? '已找到会员：' + res.name : '已找到会员', 'success')
      return
    }
    // 仅清理上一次“自动回填”的姓名/小区；用户手动填写的内容保留，
    // 避免把上一个会员的归属带到新客户。
    clearMemberAutoFill()
    memberStatus.value = 'notfound'
    showBanner((res && res.message) || '未找到会员，保存时将自动创建', 'info')
  }

  /** 清理上一次会员自动回填的内容（用户手动改过的不清）。 */
  function clearMemberAutoFill() {
    if (lastMemberName && String(form.customer || '').trim() === lastMemberName) {
      form.customer = ''
    }
    if (form.communityId && String(form.communityId) === String(lastMemberCommunityId)) {
      form.communityId = null
      form.communityName = ''
    }
    lastMemberName = ''
    lastMemberCommunityId = null
  }

  /** 自动回填客户名称，但不覆盖用户已手动修改的内容。 */
  function applyMemberName(res) {
    if (!res.name) return
    const current = String(form.customer || '').trim()
    if (current && current !== lastMemberName) return
    form.customer = res.name
    lastMemberName = res.name
  }

  /** 自动回填会员小区，但不覆盖用户已手动选择的小区。 */
  function applyMemberCommunity(res) {
    if (res.communityId == null) return
    const current = form.communityId
    if (current && String(current) !== String(lastMemberCommunityId)) return
    const community = (meta.communities || []).find(
      item => String(item.id) === String(res.communityId))
    if (community) {
      form.communityId = community.id
      form.communityName = community.name
      lastMemberCommunityId = community.id
    }
  }

  function next() {
    const result = validateStep(form, meta, step.value)
    if (!result.valid) {
      applyErrors(result.errors)
      showErrors.value = true
      notifyMissing(result.count, result.errors)
      return false
    }
    clearErrors()
    showErrors.value = false
    clearBanner()
    step.value = Math.min(step.value + 1, steps.length - 1)
    maxVisited.value = Math.max(maxVisited.value, step.value)
    if (step.value === 4) refreshConfirmErrors()
    saveDraft()
    scrollTop()
    return true
  }

  function back() {
    if (step.value <= 0) return
    clearTimeout(confirmTimer)
    step.value -= 1
    clearErrors()
    showErrors.value = false
    clearBanner()
    saveDraft()
    scrollTop()
  }

  function goStep(index) {
    if (index < 0 || index > maxVisited.value || index === step.value) return
    clearTimeout(confirmTimer)
    step.value = index
    clearErrors()
    showErrors.value = false
    clearBanner()
    if (index === 4) refreshConfirmErrors()
    saveDraft()
    scrollTop()
  }

  function refreshConfirmErrors() {
    clearTimeout(confirmTimer)
    const result = validateBusinessForm(form, meta)
    applyErrors(result.errors)
    return result
  }

  function scheduleConfirmRefresh() {
    clearTimeout(confirmTimer)
    confirmTimer = setTimeout(refreshConfirmErrorsWhenIdle, 800)
  }

  /**
   * 输入框仍聚焦时推迟重算：否则字段刚输入一个字就校验通过，
   * “待补充事项”卡片被卸载，后续字符会因焦点丢失而写不进去。
   */
  function refreshConfirmErrorsWhenIdle() {
    // #ifdef H5
    if (typeof document !== 'undefined') {
      const active = document.activeElement
      if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')) {
        confirmTimer = setTimeout(refreshConfirmErrorsWhenIdle, 600)
        return
      }
    }
    // #endif
    refreshConfirmErrors()
  }

  async function submit() {
    if (submitting.value) return
    const result = refreshConfirmErrors()
    if (!result.valid) {
      showErrors.value = true
      notifyMissing(Object.keys(result.errors).length, result.errors)
      return
    }
    if (meta.productError) {
      showBanner(meta.productError)
      return
    }
    submitting.value = true
    try {
      const created = editOrderId.value
        ? await api.employeeUpdateDraft(editOrderId.value, buildPayload())
        : await api.employeeCreateBusiness(buildPayload())
      // 提交成功后取消排队中的草稿写入，避免 600ms 后把已提交数据重新存成草稿
      clearTimeout(draftTimer)
      clearDraft()
      success.value = { ...(created || {}), editMode: !!editOrderId.value }
      // 发起成功但审批人未即时解析（组件延迟/解析失败）时异步补齐，避免成功页空白
      if (success.value.orderId && success.value.approverResolved === false) {
        pollApprover(success.value.orderId)
      }
    } catch (e) {
      showBanner((e && e.msg) || '保存失败，请重试')
    } finally {
      submitting.value = false
    }
  }

  /** 成功页审批人异步补齐：最多 3 次，拿到后刷新；仍失败展示明确兜底文案。 */
  async function pollApprover(orderId) {
    for (let attempt = 0; attempt < 3; attempt++) {
      await sleep(1000 * (attempt + 1))
      if (!success.value || success.value.orderId !== orderId) return
      try {
        const info = await api.employeeOrderApprover(orderId)
        if (!success.value || success.value.orderId !== orderId) return
        if (info && info.resolved) {
          success.value = {
            ...success.value,
            currentApproverName: info.approverName || '',
            currentApproverRole: info.approverRole || '',
            approverResolved: true,
            approverMessage: ''
          }
          return
        }
        if (info && info.message) {
          success.value = { ...success.value, approverMessage: info.message }
        }
      } catch (e) {
        // 单次失败继续重试
      }
    }
    if (success.value && success.value.orderId === orderId && !success.value.approverResolved) {
      success.value = {
        ...success.value,
        approverResolved: false,
        approverMessage: '审批人信息获取失败，请到订单详情查看'
      }
    }
  }

  function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms))
  }

  function buildPayload() {
    return {
      batchNo: form.newBatch ? null : form.batchNo,
      newBatch: !!form.newBatch,
      customerPhone: normalizePhone(form.customerPhone),
      customer: String(form.customer || '').trim(),
      communityId: form.communityId || null,
      detailAddress: String(form.detailAddress || '').trim(),
      paymentType: form.paymentType === 'DEPOSIT' ? 'DEPOSIT' : 'FULL',
      depositAmount: form.paymentType === 'DEPOSIT' ? normalizeMoney(form.depositAmount) : 0,
      paymentMethod: form.paymentMethod,
      agentUserIds: (form.agentUserIds || []).map(id => Number(id)),
      products: (form.products || []).map(product => ({
        productId: product.productId || null,
        brand: product.brand,
        category: product.category,
        model: product.model,
        salePrice: normalizeMoney(product.salePrice),
        saleQuantity: Number(product.saleQuantity),
        isSpecial: !!product.isSpecial,
        specialFileId: product.isSpecial ? (product.specialFileId || null) : null,
        commissionRate: product.isSpecial ? Number(product.commissionRate) : 0,
        isTradeIn: !!product.isTradeIn,
        deductionAmount: product.isTradeIn ? normalizeMoney(product.deductionAmount) : 0,
        tradeInItems: product.isTradeIn ? (product.tradeInItems || []).map(item => ({
          productId: item.productId || null,
          materialNumber: item.materialNumber || '',
          productName: String(item.productName || '').trim(),
          brand: item.brand || '',
          model: item.model || '',
          category: item.category || '',
          quantity: Number(item.quantity),
          unit: item.unit || '',
          remark: String(item.remark || '').trim(),
          isManual: !!item.isManual
        })) : []
      })),
      receiptDate: form.receiptDate,
      receiptNo: String(form.receiptNo || '').trim() || null,
      receiptImageId: form.receiptImage ? form.receiptImage.id : null,
      paymentVoucherIds: (form.paymentVouchers || [])
        .map(file => file && file.id).filter(Boolean),
      contractAttachmentIds: (form.contractAttachments || [])
        .map(file => file && file.id).filter(Boolean),
      remark: String(form.remark || '').trim()
    }
  }

  function continueOrdering() {
    const batchNo = success.value ? success.value.batchNo : ''
    success.value = null
    clearTimeout(confirmTimer)
    clearBanner()
    queryToken += 1
    querying.value = false
    inFlightPhone = ''
    memberPhone = ''
    lastMemberName = ''
    lastMemberCommunityId = null
    memberStatus.value = 'idle'
    draftEnabled = true
    editOrderId.value = ''
    Object.assign(form, emptyBusinessForm())
    clearErrors()
    showErrors.value = false
    step.value = 0
    maxVisited.value = 0
    form.agentUserIds = meta.employeeUserId ? [meta.employeeUserId] : []
    applyBatchDefault(batchNo)
    clearDraft()
    scrollTop()
  }

  function viewOrder() {
    uni.setStorageSync('ops_employee_view_batch', {
      batchNo: (success.value && success.value.batchNo) || form.batchNo || '',
      ts: Date.now()
    })
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    const previous = pages.length > 1 ? pages[pages.length - 2] : null
    if (previous && String(previous.route || '').includes('pages/employee/business-data')) {
      uni.navigateBack()
    } else {
      // 深链/从其它页面进入时，返回列表不可靠，直接替换到订单列表并定位批次
      uni.redirectTo({ url: '/pages/employee/business-data' })
    }
  }

  function applyErrors(nextErrors) {
    Object.keys(errors).forEach(key => delete errors[key])
    Object.assign(errors, nextErrors || {})
  }

  function clearErrors() {
    applyErrors({})
  }

  function notifyMissing(count, errorMap) {
    showBanner(`还有 ${count} 项未填写`, 'error')
    vibrateLight()
    const firstKey = Object.keys(errorMap || {})[0]
    if (firstKey) {
      const id = fieldIdOf(firstKey)
      setTimeout(() => {
        scrollToField(id)
        focusField(id)
      }, 60)
    }
  }

  function fieldIdOf(key) {
    if (key.startsWith('products.')) {
      const [, index, field] = key.split('.')
      return field ? `field-products-${index}-${field}` : 'field-products'
    }
    return 'field-' + key
  }

  function showBanner(message, type = 'error') {
    banner.value = String(message || '')
    bannerType.value = type
    clearTimeout(bannerTimer)
    if (banner.value) bannerTimer = setTimeout(() => { banner.value = '' }, BANNER_MS)
  }

  function clearBanner() {
    banner.value = ''
    clearTimeout(bannerTimer)
  }

  function scrollTop() {
    setTimeout(() => {
      try {
        uni.pageScrollTo({ scrollTop: 0, duration: 0 })
      } catch (e) {
        if (typeof window !== 'undefined') window.scrollTo(0, 0)
      }
    }, 30)
  }

  // ── 草稿 ──

  function draftKey() {
    return `${DRAFT_PREFIX}${meta.corpId || 0}_${meta.employeeUserId || 0}`
  }

  function scheduleDraft() {
    if (!draftEnabled || !meta.employeeUserId || success.value) return
    clearTimeout(draftTimer)
    draftTimer = setTimeout(saveDraft, 600)
  }

  function saveDraft() {
    if (!draftEnabled || success.value) return
    if (!isMeaningful(form)) {
      clearDraft()
      return
    }
    try {
      uni.setStorageSync(draftKey(), {
        form: JSON.parse(JSON.stringify(form)),
        step: step.value,
        savedAt: Date.now()
      })
    } catch (e) {
      // 本地存储不可用（隐私模式/空间不足）时静默降级，不影响制单
    }
  }

  function promptRestoreDraft() {
    const draft = readDraft()
    if (!draft || !draft.form) {
      draftEnabled = true
      return
    }
    if (Date.now() - Number(draft.savedAt || 0) > DRAFT_TTL) {
      clearDraft()
      draftEnabled = true
      return
    }
    uni.showModal({
      title: '恢复上次填写？',
      content: '检测到未提交的制单草稿，是否继续填写？',
      confirmText: '恢复填写',
      cancelText: '重新填写',
      success: res => {
        if (res.confirm) restoreDraft(draft)
        else clearDraft()
        draftEnabled = true
      },
      fail: () => {
        draftEnabled = true
      }
    })
  }

  function readDraft() {
    try {
      return uni.getStorageSync(draftKey()) || null
    } catch (e) {
      return null
    }
  }

  function restoreDraft(draft) {
    const saved = draft.form || {}
    Object.assign(form, emptyBusinessForm(), saved)
    if (!Array.isArray(form.products) || !form.products.length) {
      form.products = [emptyProduct()]
    }
    // 旧版草稿没有稳定 uid：补一个，避免删除行后价格状态串到相邻行
    form.products.forEach(product => {
      if (product && !product.uid) product.uid = newProductUid()
      if (!product) return
      if (product.isTradeIn == null) product.isTradeIn = false
      if (product.deductionAmount == null) product.deductionAmount = ''
      if (!Array.isArray(product.tradeInItems)) product.tradeInItems = []
      product.tradeInItems.forEach(item => {
        if (!item.uid) item.uid = newTradeInUid()
        if (item.quantity == null || item.quantity === '') item.quantity = '1'
        if (item.isManual == null) item.isManual = false
        if (item.productId == null) item.productId = null
        if (item.productName == null) item.productName = ''
      })
    })
    // 草稿可能跨天/跨组织：剔除已离岗或不在当前组织员工列表中的经办人，
    // 否则界面上无法取消、提交时会被后端整体拒绝。
    const validIds = new Set((meta.employees || []).map(item => String(item.userId)))
    if (meta.employeeUserId) validIds.add(String(meta.employeeUserId))
    const restoredIds = (Array.isArray(form.agentUserIds) ? form.agentUserIds : [])
      .map(String)
      .filter(id => validIds.has(id))
    const uniqueIds = [...new Set(restoredIds)].map(Number)
    if (!uniqueIds.length && meta.employeeUserId) uniqueIds.push(meta.employeeUserId)
    form.agentUserIds = uniqueIds
    if (!form.receiptImage) form.receiptImage = null
    if (!Array.isArray(form.paymentVouchers)) form.paymentVouchers = []
    if (!Array.isArray(form.contractAttachments)) form.contractAttachments = []
    if (!form.paymentMethod) form.paymentMethod = 'QR'
    if (!form.paymentType) form.paymentType = 'FULL'
    if (form.depositAmount == null) form.depositAmount = ''
    if (!form.receiptNo) form.receiptNo = ''
    // 草稿里的查重结果是旧值快照，恢复时必须丢弃，避免误报
    delete form.receiptNoCheck
    step.value = Math.min(Math.max(Number(draft.step) || 0, 0), steps.length - 1)
    maxVisited.value = step.value
    if (step.value === 4) refreshConfirmErrors()
    showBanner('已恢复上次填写', 'success')
  }

  function clearDraft() {
    try {
      uni.removeStorageSync(draftKey())
    } catch (e) {
      // 忽略存储异常
    }
  }

  function isMeaningful(value) {
    if (!value) return false
    if (String(value.customerPhone || '').length >= 6) return true
    if (String(value.customer || '').trim()) return true
    if (String(value.detailAddress || '').trim()) return true
    if (String(value.remark || '').trim()) return true
    return (value.products || []).some(product => product
      && (product.category || product.brand || product.model || Number(product.salePrice) > 0))
  }

  return {
    loading, errorMsg, querying, submitting, step, maxVisited, success, banner,
    bannerType, memberStatus, meta, form, errors, showErrors, steps, missing,
    editOrderId, setEditOrderId,
    loadOptions, queryMember, next, back, goStep, submit,
    continueOrdering, viewOrder, showBanner
  }
}
