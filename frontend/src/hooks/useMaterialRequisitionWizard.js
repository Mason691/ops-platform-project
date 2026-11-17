import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api } from '@/api/index'
import {
  REQUISITION_STEPS, emptyRequisitionForm, emptyRequisitionItem,
  fieldIdOf, missingItems, newUid, requisitionLabels, requisitionStepOf,
  validateRequisition
} from '@/utils/materialForm'
import { focusField, scrollToField, vibrateLight } from '@/utils/fieldFocus'

const DRAFT_PREFIX = 'ops_material_req_draft_'
const DRAFT_TTL = 7 * 24 * 60 * 60 * 1000
const BANNER_MS = 2600
const DRAFT_DEBOUNCE = 600
const CONFIRM_DEBOUNCE = 800

/** 员工物资领用：3 步向导状态机（参照 useBusinessWizard）。 */
export function useMaterialRequisitionWizard() {
  const loading = ref(true)
  const errorMsg = ref('')
  const submitting = ref(false)
  const step = ref(0)
  const maxVisited = ref(0)
  const success = ref(null)
  const banner = ref('')
  const bannerType = ref('error')
  const showErrors = ref(false)
  const meta = reactive({
    corpId: null, corpName: '', company: '', employeeUserId: null,
    employeeName: '', employeePhone: '', deptId: null, deptName: '',
    employees: [], categories: [], productError: ''
  })
  const form = reactive(emptyRequisitionForm())
  const errors = reactive({})
  const steps = REQUISITION_STEPS
  const missing = computed(() => missingItems(errors, requisitionStepOf, requisitionLabels()))

  let bannerTimer = null
  let draftTimer = null
  let confirmTimer = null
  let draftEnabled = false
  let disposed = false

  watch(form, () => {
    scheduleDraft()
    if (step.value === steps.length - 1) scheduleConfirmRefresh()
    else if (showErrors.value) {
      applyErrors(stepErrors(step.value))
    }
  }, { deep: true })

  onBeforeUnmount(() => {
    disposed = true
    saveDraft()
    clearTimeout(bannerTimer)
    clearTimeout(draftTimer)
    clearTimeout(confirmTimer)
  })

  async function loadOptions() {
    loading.value = true
    errorMsg.value = ''
    try {
      const data = await api.materialOptions()
      Object.assign(meta, data || {})
      meta.employees = Array.isArray(meta.employees) ? meta.employees : []
      meta.categories = Array.isArray(meta.categories) ? meta.categories : []
      if (!form.agentUserIds.length && meta.employeeUserId) {
        form.agentUserIds = [meta.employeeUserId]
      }
      if (!Array.isArray(form.items) || !form.items.length) {
        form.items = [emptyRequisitionItem()]
      }
    } catch (e) {
      errorMsg.value = (e && e.msg) || '加载失败，请重试'
    } finally {
      loading.value = false
    }
    if (!errorMsg.value && !disposed) promptRestoreDraft()
  }

  function stepErrors(target) {
    const all = validateRequisition(form, meta).errors
    const result = {}
    Object.entries(all).forEach(([key, message]) => {
      if (requisitionStepOf(key) === target) result[key] = message
    })
    return result
  }

  function next() {
    const result = validateRequisition(form, meta)
    const errorsOfStep = {}
    Object.entries(result.errors).forEach(([key, message]) => {
      if (requisitionStepOf(key) === step.value) errorsOfStep[key] = message
    })
    if (Object.keys(errorsOfStep).length) {
      applyErrors(errorsOfStep)
      showErrors.value = true
      notifyMissing(Object.keys(errorsOfStep).length, errorsOfStep)
      return false
    }
    clearErrors()
    showErrors.value = false
    clearBanner()
    step.value = Math.min(step.value + 1, steps.length - 1)
    maxVisited.value = Math.max(maxVisited.value, step.value)
    if (step.value === steps.length - 1) refreshConfirmErrors()
    saveDraft()
    scrollTop()
    return true
  }

  function back() {
    if (step.value <= 0) return
    step.value -= 1
    clearErrors()
    showErrors.value = false
    clearBanner()
    saveDraft()
    scrollTop()
  }

  function goStep(index) {
    if (index < 0 || index > maxVisited.value || index === step.value) return
    step.value = index
    clearErrors()
    showErrors.value = false
    clearBanner()
    if (index === steps.length - 1) refreshConfirmErrors()
    saveDraft()
    scrollTop()
  }

  function refreshConfirmErrors() {
    clearTimeout(confirmTimer)
    const result = validateRequisition(form, meta)
    applyErrors(result.errors)
    return result
  }

  function scheduleConfirmRefresh() {
    clearTimeout(confirmTimer)
    confirmTimer = setTimeout(refreshConfirmErrorsWhenIdle, CONFIRM_DEBOUNCE)
  }

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
      const created = await api.materialRequisitionCreate(buildPayload())
      clearTimeout(draftTimer)
      clearDraft()
      success.value = created || {}
      if (success.value.id && success.value.approverResolved === false) {
        pollApprover(success.value.id)
      }
    } catch (e) {
      showBanner((e && e.msg) || '提交失败，请重试')
    } finally {
      submitting.value = false
    }
  }

  async function pollApprover(id) {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      await sleep(1000 * (attempt + 1))
      if (!success.value || success.value.id !== id) return
      try {
        const detail = await api.materialRequisitionDetail(id)
        if (!success.value || success.value.id !== id) return
        if (detail && detail.approverResolved) {
          success.value = {
            ...success.value,
            currentApproverName: detail.currentApproverName || '',
            currentApproverRole: detail.currentApproverRole || '',
            approverResolved: true,
            approverMessage: ''
          }
          return
        }
      } catch (e) {
        // 继续重试
      }
    }
    if (success.value && success.value.id === id && !success.value.approverResolved) {
      success.value = {
        ...success.value,
        approverResolved: false,
        approverMessage: '审批人信息获取失败，请到领用记录查看'
      }
    }
  }

  function buildPayload() {
    return {
      purpose: String(form.purpose || '').trim(),
      expectedDate: form.expectedDate,
      remark: String(form.remark || '').trim() || null,
      applicantUserIds: (form.agentUserIds || []).map(Number),
      entries: (form.items || [])
        .filter(row => row && row.materialId)
        .map(row => ({
          materialId: row.materialId,
          quantity: Number(row.quantity),
          remark: String(row.remark || '').trim() || null
        }))
    }
  }

  function continueOrdering() {
    success.value = null
    clearTimeout(confirmTimer)
    clearBanner()
    Object.assign(form, emptyRequisitionForm())
    form.agentUserIds = meta.employeeUserId ? [meta.employeeUserId] : []
    clearErrors()
    showErrors.value = false
    step.value = 0
    maxVisited.value = 0
    clearDraft()
    scrollTop()
  }

  function viewRecords() {
    uni.redirectTo({ url: '/pages/employee/material-requisition-records' })
  }

  function applyErrors(next) {
    Object.keys(errors).forEach(key => delete errors[key])
    Object.assign(errors, next || {})
  }
  function clearErrors() { applyErrors({}) }

  function notifyMissing(count, errorMap) {
    showBanner(`还有 ${count} 项未填写`, 'error')
    vibrateLight()
    const firstKey = Object.keys(errorMap || {})[0]
    if (firstKey) {
      const id = fieldIdOf(firstKey)
      setTimeout(() => { scrollToField(id); focusField(id) }, 60)
    }
  }

  function showBanner(message, type = 'error') {
    banner.value = String(message || '')
    bannerType.value = type
    clearTimeout(bannerTimer)
    if (banner.value) bannerTimer = setTimeout(() => { banner.value = '' }, BANNER_MS)
  }
  function clearBanner() { banner.value = ''; clearTimeout(bannerTimer) }
  function scrollTop() {
    setTimeout(() => {
      try { uni.pageScrollTo({ scrollTop: 0, duration: 0 }) }
      catch (e) { if (typeof window !== 'undefined') window.scrollTo(0, 0) }
    }, 30)
  }

  // ── 草稿 ──
  function draftKey() {
    return `${DRAFT_PREFIX}${meta.corpId || 0}_${meta.employeeUserId || 0}`
  }
  function scheduleDraft() {
    if (!draftEnabled || !meta.employeeUserId || success.value) return
    clearTimeout(draftTimer)
    draftTimer = setTimeout(saveDraft, DRAFT_DEBOUNCE)
  }
  function saveDraft() {
    if (!draftEnabled || success.value || !isMeaningful(form)) return
    try {
      uni.setStorageSync(draftKey(), {
        form: JSON.parse(JSON.stringify(form)), step: step.value, savedAt: Date.now()
      })
    } catch (e) { /* 存储不可用静默降级 */ }
  }
  function promptRestoreDraft() {
    const draft = readDraft()
    if (!draft || !draft.form || Date.now() - Number(draft.savedAt || 0) > DRAFT_TTL) {
      clearDraft()
      draftEnabled = true
      return
    }
    uni.showModal({
      title: '恢复上次填写？',
      content: '检测到未提交的领用草稿，是否继续填写？',
      confirmText: '恢复填写', cancelText: '重新填写',
      success: res => {
        if (res.confirm) restoreDraft(draft)
        else clearDraft()
        draftEnabled = true
      },
      fail: () => { draftEnabled = true }
    })
  }
  function readDraft() {
    try { return uni.getStorageSync(draftKey()) || null } catch (e) { return null }
  }
  function restoreDraft(draft) {
    Object.assign(form, emptyRequisitionForm(), draft.form || {})
    if (!Array.isArray(form.items) || !form.items.length) {
      form.items = [emptyRequisitionItem()]
    }
    form.items.forEach(row => { if (row && !row.uid) row.uid = newUid() })
    const validIds = new Set((meta.employees || []).map(e => String(e.userId)))
    if (meta.employeeUserId) validIds.add(String(meta.employeeUserId))
    const ids = (form.agentUserIds || []).map(String).filter(id => validIds.has(id))
    if (!ids.length && meta.employeeUserId) ids.push(String(meta.employeeUserId))
    form.agentUserIds = [...new Set(ids)].map(Number)
    step.value = Math.min(Math.max(Number(draft.step) || 0, 0), steps.length - 1)
    maxVisited.value = step.value
    if (step.value === steps.length - 1) refreshConfirmErrors()
    showBanner('已恢复上次填写', 'success')
  }
  function clearDraft() {
    try { uni.removeStorageSync(draftKey()) } catch (e) { /* ignore */ }
  }
  function isMeaningful(value) {
    return !!(String(value.purpose || '').trim()
      || String(value.remark || '').trim()
      || (value.items || []).some(row => row && (row.materialId || row.category)))
  }
  function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)) }

  return {
    loading, errorMsg, submitting, step, maxVisited, success, banner, bannerType,
    showErrors, meta, form, errors, missing, steps,
    loadOptions, next, back, goStep, submit, continueOrdering, viewRecords, showBanner
  }
}
