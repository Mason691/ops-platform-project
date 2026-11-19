import { computed, ref } from 'vue'
import { api } from '@/api/index'

/**
 * 收据号 OCR 状态机（红/蓝双色候选 + 人工确认）。
 *
 * 规则：
 * - 只有一种颜色候选 → 自动填入；
 * - 红蓝都存在且不一致 → 自动填入配置优先色，并展示两组候选供一键切换；
 * - 用户手动修改/切换后 → 以用户值为准，后续识别只更新候选提示，不覆盖；
 * - 自动采用、切换、手改后失焦/离开步骤均上报最终采用值（图片ID、候选、是否人工修改）。
 */
const COLOR_LABEL = { RED: '红色', BLUE: '蓝色' }

export function useReceiptOcr(form) {
  const loading = ref(false)
  const message = ref('')
  const warning = ref(false)
  const red = ref('')
  const blue = ref('')
  // 首屏尚未拿到后端配置，留空由提示文案显示“系统配置色”；OCR 响应后覆盖
  const preferredColor = ref('')
  const needsConfirm = ref(false)
  const recommendation = ref('')
  const manual = ref(false)
  const candidates = ref([])
  const review = ref(false)

  /** REVIEW/同色多候选：按值去重后供收据号下方点击选择。 */
  const reviewCandidates = computed(() => {
    const seen = new Set()
    return candidates.value
      .filter(item => item && String(item.value || '').trim())
      .filter(item => {
        const value = String(item.value).trim()
        if (seen.has(value)) return false
        seen.add(value)
        return true
      })
      .slice(0, 6)
  })
  const hasCandidates = computed(() => !!(red.value || blue.value))
  const hasResult = computed(() => hasCandidates.value || candidates.value.length > 0)
  const showChoices = computed(() => !!(red.value && blue.value && red.value !== blue.value))
  const sameValue = computed(() => !!(red.value && red.value === blue.value))
  const currentChoice = computed(() => {
    const value = String(form.receiptNo || '').trim()
    if (!value) return ''
    if (red.value && value === red.value) return 'RED'
    if (blue.value && value === blue.value) return 'BLUE'
    return ''
  })
  const canRetry = computed(() =>
    !!((form.receiptImage && form.receiptImage.id) && !loading.value))

  let token = 0
  let disposed = false
  let autoValue = ''
  let userTouched = false
  let lastLogged = ''

  function reset() {
    token += 1
    loading.value = false
    message.value = ''
    warning.value = false
    red.value = ''
    blue.value = ''
    preferredColor.value = ''
    needsConfirm.value = false
    recommendation.value = ''
    manual.value = false
    candidates.value = []
    review.value = false
    autoValue = ''
    userTouched = false
    lastLogged = ''
  }

  async function runOcr(attachmentId) {
    const id = attachmentId || (form.receiptImage && form.receiptImage.id)
    if (!id) return
    const imageId = String(id)
    const myToken = ++token
    loading.value = true
    message.value = '正在识别收据号…'
    warning.value = false
    try {
      const res = await api.employeeReceiptOcr({ attachmentId: id })
      // 识别期间可能已删除/更换图片：丢弃过期响应
      if (myToken !== token) return
      if (!form.receiptImage || String(form.receiptImage.id) !== imageId) return
      applyResult(res || {})
    } catch (e) {
      if (myToken !== token) return
      message.value = (e && e.msg) || '识别失败，请手动填写收据号'
      warning.value = true
    } finally {
      if (myToken === token) loading.value = false
    }
  }

  function applyResult(res) {
    red.value = String(res.redReceiptNo || '').trim()
    blue.value = String(res.blueReceiptNo || '').trim()
    preferredColor.value = res.preferredColor === 'BLUE' ? 'BLUE' : 'RED'
    needsConfirm.value = !!res.needsConfirm
    recommendation.value = String(res.recommendation || '')
    candidates.value = Array.isArray(res.candidates) ? res.candidates : []
    review.value = res.source === 'REVIEW'
    const recognized = String(res.receiptNo || '').trim()
    if (!recognized) {
      message.value = res.message || '未能识别收据号，请手动填写'
      warning.value = true
      return
    }
    const current = String(form.receiptNo || '').trim()
    // 用户已手改/切换/清空：只更新候选提示，绝不覆盖用户值
    if (userTouched) {
      if (!current) {
        const summary = candidateSummary()
        message.value = summary !== '无'
          ? `识别候选：${summary}，点击可填入；当前收据号为空`
          : (res.message || '请手动填写收据号')
        warning.value = summary === '无'
        return
      }
      message.value = keepMessage(res, current, recognized)
      warning.value = res.exact === false
      return
    }
    if (!current || (autoValue && current === autoValue)) {
      applyAuto(recognized, res)
      return
    }
    if (current !== recognized) {
      showOverwriteModal(recognized, current, res)
      return
    }
    applyAuto(recognized, res)
  }

  function applyAuto(recognized, res) {
    form.receiptNo = recognized
    form.receiptNoCheck = null
    autoValue = recognized
    manual.value = false
    userTouched = false
    message.value = res.message || '已从收据图片自动识别，请核对'
    warning.value = res.exact === false
  }

  function showOverwriteModal(recognized, current, res) {
    const both = res.redReceiptNo && res.blueReceiptNo
      && String(res.redReceiptNo) !== String(res.blueReceiptNo)
    const content = both
      ? `检测到红色收据号 ${res.redReceiptNo} 与蓝色收据号 ${res.blueReceiptNo}，`
        + `当前收据号为 ${current}，是否使用推荐值 ${recognized}？`
      : `识别结果 ${recognized}，当前收据号为 ${current}，是否用识别结果覆盖？`
    uni.showModal({
      title: '识别到收据号',
      content,
      confirmText: '使用识别值',
      cancelText: '保留当前',
      success: modal => {
        if (disposed) return
        if (modal.confirm) {
          applyAuto(recognized, res)
        } else {
          message.value = '已保留当前收据号，可点击识别候选切换'
          warning.value = false
        }
      }
    })
  }

  function keepMessage(res, current, recognized) {
    if (current === recognized) return res.message || '已从收据图片自动识别，请核对'
    const summary = candidateSummary()
    if (summary === '无') {
      return `已保留你选择/填写的收据号 ${current}；本次未识别到红/蓝候选`
    }
    return `已保留你选择/填写的收据号 ${current}；识别候选：${summary}，可点击切换`
  }

  function candidateSummary() {
    const parts = []
    if (red.value) parts.push(`红色 ${red.value}`)
    if (blue.value) parts.push(`蓝色 ${blue.value}`)
    if (!parts.length && review.value) {
      return reviewCandidates.value.map(item => item.value).join(' / ') || '无'
    }
    return parts.length ? parts.join(' / ') : '无'
  }

  /** 用户手动输入：保留候选提示，但不再自动覆盖。 */
  function onManualInput() {
    userTouched = true
    manual.value = true
    autoValue = ''
    if (hasCandidates.value || review.value) {
      message.value = `已手动填写，识别候选保留：${candidateSummary()}，可点击填入`
      warning.value = false
    } else if (hasResult.value) {
      message.value = '已手动填写；本次未识别到红/蓝候选，请核对'
      warning.value = false
    }
  }

  /** 点击候选号直接录入；REVIEW 与同色多候选都走这里。 */
  function selectCandidate(value) {
    const picked = String(value || '').trim()
    if (!picked) return
    form.receiptNo = picked
    form.receiptNoCheck = null
    autoValue = picked
    manual.value = false
    userTouched = true
    message.value = `已录入识别候选 ${picked}，请核对`
    warning.value = false
    logAdoption('SWITCH', picked, false)
  }

  function switchTo(color) {
    const value = color === 'BLUE' ? blue.value : red.value
    if (!value) return
    form.receiptNo = value
    form.receiptNoCheck = null
    autoValue = value
    manual.value = false
    userTouched = true
    message.value = `已切换为${COLOR_LABEL[color]}收据号 ${value}，请核对`
    warning.value = false
    logAdoption('SWITCH', value, false)
  }

  /** 失焦/离开步骤时记录最终采用值；重复值不重复上报。 */
  function logFinal() {
    if (!hasResult.value) return
    const value = String(form.receiptNo || '').trim()
    if (!value) return
    const action = manual.value ? 'MANUAL' : (userTouched ? 'SWITCH' : 'ACCEPT')
    logAdoption(action, value, manual.value)
  }

  function logAdoption(action, adopted, isManual) {
    const attachmentId = form.receiptImage && form.receiptImage.id
    if (!attachmentId || !hasResult.value) return
    const key = `${attachmentId}|${adopted}|${action}|${isManual}`
    if (key === lastLogged) return
    lastLogged = key
    api.employeeReceiptOcrFeedback({
      attachmentId,
      redReceiptNo: red.value || null,
      blueReceiptNo: blue.value || null,
      adoptedReceiptNo: adopted || null,
      manual: !!isManual,
      action
    }).catch(() => {})
  }

  function dispose() {
    logFinal()
    disposed = true
    token += 1
  }

  return {
    loading, message, warning, red, blue, preferredColor, needsConfirm,
    recommendation, manual, candidates, review, reviewCandidates,
    hasCandidates, hasResult, showChoices, sameValue, currentChoice,
    canRetry, reset, runOcr, onManualInput, switchTo, selectCandidate,
    logFinal, dispose
  }
}
