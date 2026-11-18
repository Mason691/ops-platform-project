/**
 * 员工会话统一存取。
 * 密码登录与微信授权登录共用，保证两种方式写入完全相同的 Token / 员工信息结构。
 */

const SESSION_KEYS = [
  'ops_employee_token',
  'ops_employee_info',
  'ops_employee_companies',
  'ops_employee_active_company',
  'ops_employee_permissions',
  'ops_employee_roles',
  'ops_employee_no_permission',
  'ops_employee_login_mode',
  'ops_wechat_oauth_code',
  'ops_wechat_oauth_state',
  'ops_wechat_oauth_mode',
  'ops_wechat_oauth_expected_state',
  'ops_login_redirect',
  // 清理可能并存的会员会话，避免 token 混用
  'token_type',
  'ops_token',
  'ops_member',
  'ops_user',
  'corp_id'
]

const LOGIN_REDIRECT_KEY = 'ops_login_redirect'

/** 记录登录后要回的页面（公众号消息点击工单详情、未登录场景）。 */
export function setLoginRedirect(url) {
  if (url) uni.setStorageSync(LOGIN_REDIRECT_KEY, url)
}

/** 取出并清除登录回跳地址；无则回员工工作台。 */
export function consumeLoginRedirect() {
  const url = uni.getStorageSync(LOGIN_REDIRECT_KEY)
  uni.removeStorageSync(LOGIN_REDIRECT_KEY)
  return url || '/pages/employee/dashboard'
}

/** 当前是否存在员工登录态。 */
export function isEmployeeLoggedIn() {
  return !!uni.getStorageSync('ops_employee_token')
}

/**
 * 保存员工登录结果。
 * @param {object} r 后端返回的员工会话
 * @param {string} loginMode 'password' | 'wechat'
 */
export function saveEmployeeSession(r, loginMode) {
  uni.removeStorageSync('ops_token')
  uni.removeStorageSync('ops_member')
  uni.setStorageSync('token_type', 'employee')
  uni.setStorageSync('ops_employee_token', r.token)
  uni.setStorageSync('corp_id', String(r.corpId))
  uni.setStorageSync('ops_employee_info', JSON.stringify({
    employeeId: r.employeeId,
    name: r.name,
    phone: r.phone,
    role: r.role,
    corpId: r.corpId,
    avatar: r.avatar || ''
  }))
  const roleNames = r.roleNames || r.roles || []
  const role = r.role || roleNames[0] || '员工'
  // 默认公司直接用登录返回的 corpId/corpName（不依赖可选的 companies 列表）
  uni.setStorageSync('ops_employee_active_company', JSON.stringify({
    corpId: r.corpId,
    corpName: r.corpName || '',
    roleName: role
  }))
  const companies = (Array.isArray(r.companies) ? r.companies : []).map(c => ({
    ...c,
    roleName: c.roleName || (c.roleNames || [])[0] || '员工'
  }))
  uni.setStorageSync('ops_employee_companies', JSON.stringify(companies))
  const permissions = r.permissions || []
  uni.setStorageSync('ops_employee_permissions', JSON.stringify(permissions))
  uni.setStorageSync('ops_employee_roles', JSON.stringify(roleNames))
  uni.setStorageSync('ops_employee_no_permission', permissions.length ? '0' : '1')
  if (loginMode) {
    uni.setStorageSync('ops_employee_login_mode', loginMode)
  }
}

function readJson(key, fallback) {
  try {
    const raw = uni.getStorageSync(key)
    return raw ? JSON.parse(raw) : fallback
  } catch (_) {
    return fallback
  }
}

/** 当前组织（工作台顶部/我的页展示）。 */
export function getActiveCompany() {
  return readJson('ops_employee_active_company', {})
}

/** 登录时返回的全部组织（切换接口不可用时的兜底列表）。 */
export function getEmployeeCompanies() {
  return readJson('ops_employee_companies', [])
}

export function getEmployeeInfo() {
  return readJson('ops_employee_info', {})
}

export function getEmployeePermissions() {
  return readJson('ops_employee_permissions', [])
}

/** 员工工作台「已核对业务」卡片权限（采购订单 H5 制单）。业务员角色默认不含此权限。 */
export const PERM_VERIFIED_BUSINESS = 'scm:pur-order:h5:create'

/** 当前登录员工是否拥有指定权限（多角色权限并集，由登录/切换组织响应下发）。 */
export function hasEmployeePermission(code) {
  return !!code && getEmployeePermissions().includes(code)
}

/**
 * 应用切换组织响应：替换 token、当前组织、角色与权限缓存。
 * 切换接口返回 orgs 时同步刷新 companies，保证下次打开面板立即正确。
 */
export function applyOrgSwitch(data) {
  const token = data && (data.accessToken || data.token)
  if (!token) throw new Error('切换响应缺少 token')
  const info = getEmployeeInfo()
  const roleNames = data.roleNames || data.roles || []
  const role = roleNames[0] || info.role || '员工'

  uni.setStorageSync('token_type', 'employee')
  uni.setStorageSync('ops_employee_token', token)
  uni.setStorageSync('corp_id', String(data.corpId))
  uni.setStorageSync('ops_employee_info', JSON.stringify({
    ...info,
    employeeId: info.employeeId || data.userId,
    name: data.realName || info.name || '',
    phone: data.phone || info.phone || '',
    role,
    corpId: data.corpId,
    avatar: data.avatar || info.avatar || ''
  }))
  uni.setStorageSync('ops_employee_active_company', JSON.stringify({
    corpId: data.corpId,
    corpName: data.corpName || '',
    roleName: role
  }))
  const companies = (data.orgs || data.companies || []).map(c => ({
    corpId: c.corpId,
    corpName: c.corpName || '',
    roleName: (c.roleNames || [])[0] || c.roleName || '员工'
  }))
  if (companies.length) {
    uni.setStorageSync('ops_employee_companies', JSON.stringify(companies))
  }
  const permissions = data.permissions || []
  uni.setStorageSync('ops_employee_permissions', JSON.stringify(permissions))
  uni.setStorageSync('ops_employee_roles', JSON.stringify(roleNames))
  uni.setStorageSync('ops_employee_no_permission', permissions.length ? '0' : '1')
  return data
}

/** 无选中组织时，从缓存的 companies 取第一个作为默认组织。 */
export function ensureActiveCompany() {
  if (uni.getStorageSync('ops_employee_active_company')) return
  const cached = uni.getStorageSync('ops_employee_companies')
  if (!cached) return
  try {
    const companies = JSON.parse(cached)
    if (Array.isArray(companies) && companies.length > 0) {
      uni.setStorageSync('ops_employee_active_company', JSON.stringify(companies[0]))
    }
  } catch (_) { /* ignore malformed cache */ }
}

/** 退出登录：清理员工与可能并存的会员会话。 */
export function clearEmployeeSession() {
  SESSION_KEYS.forEach(key => uni.removeStorageSync(key))
}
