/**
 * 员工会话统一存取。
 * 密码登录与微信授权登录共用，保证两种方式写入完全相同的 Token / 员工信息结构。
 */

const SESSION_KEYS = [
  'ops_employee_token',
  'ops_employee_info',
  'ops_employee_companies',
  'ops_employee_active_company',
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
  // 默认公司直接用登录返回的 corpId/corpName（不依赖可选的 companies 列表）
  uni.setStorageSync('ops_employee_active_company', JSON.stringify({
    corpId: r.corpId,
    corpName: r.corpName || '',
    roleName: r.role || '员工'
  }))
  const companies = Array.isArray(r.companies) ? r.companies : []
  uni.setStorageSync('ops_employee_companies', JSON.stringify(companies))
  if (loginMode) {
    uni.setStorageSync('ops_employee_login_mode', loginMode)
  }
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
