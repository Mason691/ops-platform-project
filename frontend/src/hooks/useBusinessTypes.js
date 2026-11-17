import { computed } from 'vue'

/**
 * 可发起业务类型注册表。
 *
 * 底座目前没有「工作台可发起业务类型」聚合接口，ops H5 也只有
 * 「业务数据订单」一个新增入口（既有页面里的「＋ 新增业务」）。
 * 因此不新增后端接口，改为前端注册表：
 * - 新增类型时只需在此追加一项，AddBusinessButton 会自动从
 *   「单类型直跳」切换为「多类型底部弹层选择」；
 * - route 必须是 pages.json 中已注册的真实路由。
 */
export const BUSINESS_TYPES = [
  {
    code: 'OPS_BUSINESS_ORDER',
    name: '业务数据订单',
    action: '新增业务',
    desc: '制单后进入审批流程',
    route: '/pages/employee/business-data-new'
  }
]

/**
 * 类型本身不带权限码：底座登录/切组织返回的 permissions 是按钮权限列表，
 * H5 目前只用「是否为空」判断组织可用性（工作台菜单同口径），
 * 由 AddBusinessButton 的 enabled 属性控制整体显隐。
 */
export function useBusinessTypes() {
  const types = computed(() => BUSINESS_TYPES)
  const buttonText = computed(() => (types.value.length === 1
    ? types.value[0].action
    : '新增业务'))
  return { types, buttonText }
}
