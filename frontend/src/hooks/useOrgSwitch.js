import { ref } from 'vue'
import { fetchSwitchableOrgs, performOrgSwitch } from '@/utils/orgSwitch'
import { clearEmployeeSession, getActiveCompany } from '@/utils/employeeSession'

/**
 * 组织切换面板状态与动作（工作台 / 我的页共用）。
 *
 * 切换失败保持原组织不变：本地会话只在 performOrgSwitch 成功后才替换。
 * 401 说明旧 token/登录态已失效，清理会话回登录页；403/业务错误提示后刷新列表。
 */
export function useOrgSwitch(options = {}) {
  const orgs = ref([])
  const sheetVisible = ref(false)
  const switching = ref(false)
  const loadingOrgs = ref(false)
  const error = ref('')
  const currentCorpId = ref(Number(getActiveCompany().corpId || 0))
  let loadingPromise = null

  async function loadOrgs() {
    // 并发调用复用同一请求，避免 onShow 与点击面板同时加载出现空列表
    if (loadingPromise) return loadingPromise
    loadingOrgs.value = true
    error.value = ''
    loadingPromise = (async () => {
      try {
        orgs.value = await fetchSwitchableOrgs()
        currentCorpId.value = Number(getActiveCompany().corpId || 0)
      } catch (e) {
        error.value = e.msg || e.message || '组织列表加载失败'
      } finally {
        loadingOrgs.value = false
        loadingPromise = null
      }
      return orgs.value
    })()
    return loadingPromise
  }

  async function openSheet() {
    await loadOrgs()
    if (!orgs.value.length) {
      uni.showModal({ title: '暂无组织', content: error.value || '未找到可切换的组织', showCancel: false })
      return
    }
    if (orgs.value.length <= 1) {
      uni.showToast({ title: '当前账号仅一个组织', icon: 'none' })
      return
    }
    sheetVisible.value = true
  }

  function closeSheet() {
    if (!switching.value) sheetVisible.value = false
  }

  async function confirmSwitch(org) {
    if (!org || org.isCurrent || Number(org.corpId) === Number(currentCorpId.value)
        || switching.value) return
    switching.value = true
    error.value = ''
    try {
      const data = await performOrgSwitch(org.corpId)
      sheetVisible.value = false
      currentCorpId.value = Number(data.corpId)
      const roleText = (data.roleNames || data.roles || []).join('、') || '—'
      uni.showToast({
        title: `已切换至【${data.corpName}】，当前角色：${roleText}`,
        icon: 'none',
        duration: 3000
      })
      if (typeof options.onChanged === 'function') {
        try { await options.onChanged(data) } catch (_) { /* 页面刷新失败不影响切换结果 */ }
      }
      await loadOrgs()
    } catch (e) {
      // 401：登录态失效；403：目标组织/任职已失效（服务端已拒绝，保持原组织）
      if (e && e.code === 401) {
        clearEmployeeSession()
        uni.reLaunch({ url: '/pages/employee/login' })
        return
      }
      error.value = e.msg || e.message || '切换失败，请重试'
      uni.showModal({
        title: '切换失败',
        content: error.value,
        showCancel: false
      })
      await loadOrgs()
    } finally {
      switching.value = false
    }
  }

  return {
    orgs,
    sheetVisible,
    switching,
    loadingOrgs,
    error,
    currentCorpId,
    loadOrgs,
    openSheet,
    closeSheet,
    confirmSwitch
  }
}
