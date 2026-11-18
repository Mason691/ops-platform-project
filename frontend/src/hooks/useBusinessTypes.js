import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/index'

/**
 * 可发起业务类型 — 由底座按 userType + 角色权限下发。
 *
 * 外部用户默认白名单为空（返回空列表），AddBusinessButton 自动整体隐藏；
 * 后续放开某类型只需调整后端配置，前端无需改动。
 */
const ROUTES = {
  OPS_BUSINESS_ORDER: '/pages/employee/business-data-new'
}

export function useBusinessTypes() {
  const types = ref([])
  const loading = ref(false)

  async function load() {
    loading.value = true
    try {
      const list = await api.workbenchBusinessTypes()
      types.value = (Array.isArray(list) ? list : [])
        .map(item => ({ ...item, route: ROUTES[item.code] }))
        .filter(item => item.route)
    } catch (_) {
      types.value = []
    } finally {
      loading.value = false
    }
  }

  onMounted(load)

  const buttonText = computed(() => (types.value.length === 1
    ? types.value[0].action || '新增业务'
    : '新增业务'))

  return { types, buttonText, loading, load }
}
