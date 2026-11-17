import { api } from '@/api/index'
import {
  applyOrgSwitch,
  getActiveCompany,
  getEmployeeCompanies
} from '@/utils/employeeSession'

/**
 * 工作台组织切换：列表读取 + 切换执行。
 *
 * 复用底座 GET /api/auth/orgs、POST /api/auth/switch-org；公众号 H5 与平台
 * 同域，无需 ops 后端重复实现。接口不可用时降级到登录返回的 companies 缓存，
 * 保证面板仍可用（切换仍以服务端校验为准）。
 */

function currentCorpId() {
  const active = getActiveCompany()
  return Number(active.corpId || uni.getStorageSync('corp_id') || 0)
}

function roleNamesOf(org) {
  if (Array.isArray(org.roleNames) && org.roleNames.length) return org.roleNames
  if (org.roleName) return [org.roleName]
  return []
}

function normalizeOrg(org, currentId) {
  const corpId = Number(org.corpId)
  return {
    corpId,
    corpName: org.corpName || ('组织' + corpId),
    roleNames: roleNamesOf(org),
    lastLoginTime: org.lastLoginTime || '',
    isDefault: !!org.isDefault || !!org.loginPreference,
    isCurrent: corpId === currentId,
    status: org.status
  }
}

/** 读取可切换组织；只保留在职（status=1 或缺省）记录。 */
export async function fetchSwitchableOrgs() {
  const currentId = currentCorpId()
  let rows
  try {
    rows = await api.employeeOrgs()
  } catch (e) {
    // 平台接口不可用时退回登录返回的缓存，避免工作台白屏
    rows = getEmployeeCompanies()
    if (!rows || !rows.length) throw e
  }
  const list = (rows || [])
    .filter(org => org && org.corpId != null)
    .filter(org => org.status === undefined || org.status === null || Number(org.status) === 1)
    .map(org => normalizeOrg(org, currentId))
  list.sort((a, b) => {
    if (a.isCurrent !== b.isCurrent) return a.isCurrent ? -1 : 1
    const at = a.lastLoginTime || ''
    const bt = b.lastLoginTime || ''
    if (at !== bt) return bt.localeCompare(at)
    if (a.isDefault !== b.isDefault) return a.isDefault ? -1 : 1
    return a.corpId - b.corpId
  })
  return list
}

/** 执行切换：服务端校验任职/组织状态并重签 JWT，成功后替换本地会话。 */
export async function performOrgSwitch(corpId) {
  const data = await api.employeeSwitchOrg(corpId)
  if (!data || Number(data.corpId) !== Number(corpId)) {
    throw { code: 0, msg: '切换结果异常，请重试' }
  }
  applyOrgSwitch(data)
  uni.$emit('ops:org-changed', data)
  return data
}

export function formatLastLogin(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}
