import { computed, ref } from 'vue'
import { api } from '@/api/index'

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '待发起', value: 'PENDING' },
  { label: '已发起', value: 'REQUESTED' },
  { label: '已处理', value: 'HANDLED' }
]

const GROUP_STATUS = {
  PENDING: '待发起',
  REQUESTED: '已发起',
  HANDLED: '已处理',
  INVALID: '已失效'
}

/** 向上采购申请：加载记录、按订单分组、发起采购申请并回写状态。 */
export function useUpwardPurchase() {
  const loading = ref(true)
  const loadingMore = ref(false)
  const errorMsg = ref('')
  const records = ref([])
  const total = ref(0)
  const pendingCount = ref(0)
  const status = ref('')
  const submittingKey = ref('')
  const pageNum = ref(1)
  let requestSeq = 0
  const groups = computed(() => groupRecords(records.value))
  const hasMore = computed(() => records.value.length < total.value)

  async function fetchPage(page) {
    return api.upwardPurchasePage({
      status: status.value || undefined,
      pageNum: page,
      pageSize: 50
    })
  }

  async function load() {
    const seq = ++requestSeq
    loading.value = true
    loadingMore.value = false
    errorMsg.value = ''
    pageNum.value = 1
    try {
      const data = await fetchPage(1)
      if (seq !== requestSeq) return
      records.value = (data && data.records) || []
      total.value = Number(data && data.total) || 0
      pendingCount.value = Number(data && data.pendingCount) || 0
    } catch (e) {
      if (seq === requestSeq) errorMsg.value = (e && e.msg) || '加载失败，请稍后重试'
    } finally {
      if (seq === requestSeq) loading.value = false
    }
  }

  async function loadMore() {
    if (loadingMore.value || !hasMore.value) return
    const seq = requestSeq
    loadingMore.value = true
    try {
      const next = pageNum.value + 1
      const data = await fetchPage(next)
      if (seq !== requestSeq) return
      pageNum.value = next
      const seen = new Set(records.value.map(row => row.id))
      const appended = ((data && data.records) || []).filter(row => !seen.has(row.id))
      records.value = [...records.value, ...appended]
      total.value = Number(data && data.total) || total.value
      pendingCount.value = Number(data && data.pendingCount) || pendingCount.value
    } catch (e) {
      if (seq === requestSeq) toast((e && e.msg) || '加载更多失败')
    } finally {
      if (seq === requestSeq) loadingMore.value = false
    }
  }

  function setStatus(value) {
    if (status.value === value) return
    status.value = value
    load()
  }

  async function initiate(group) {
    if (!group || !group.orderId || submittingKey.value) return
    submittingKey.value = group.key
    let rowIds = []
    try {
      // 列表按记录分页，可能与同订单其他商品分到不同页；发起前取该订单全量待发起明细
      const pending = await api.upwardPurchasePendingItems(group.orderId)
      rowIds = [...new Set((pending && pending.itemIds) || [])]
    } catch (e) {
      toast((e && e.msg) || '获取待发起商品失败')
      submittingKey.value = ''
      return
    }
    if (!rowIds.length) {
      toast('该订单已无待发起商品，请刷新')
      submittingKey.value = ''
      await load()
      return
    }
    let billNo = ''
    try {
      const order = await api.scmCreatePurchaseRequest({
        orderNo: group.orderNo || null,
        receiptNo: group.receiptNo || null,
        rowIds,
        upwardPurchase: true
      })
      billNo = order && order.billNo
    } catch (e) {
      // SCM 已存在采购申请时（例如上次回写失败），按错误中的单号补齐状态
      const existed = existingBillNo(e && e.msg)
      if (!existed) {
        toast((e && e.msg) || '发起失败，请稍后重试')
        submittingKey.value = ''
        return
      }
      billNo = existed
      toast('该订单已发起采购申请（' + existed + '）')
    }
    try {
      await markRequested(group, billNo)
      if (billNo) {
        toast('采购申请已发起（' + billNo + '），请 PC 端完善')
      } else {
        toast('采购申请已发起，但未返回单号，请刷新后确认')
      }
    } catch (e) {
      // SCM 已建单但状态回写失败：保留页面提示，下次列表加载/重试会按单号对齐
      toast('采购申请已发起，状态同步失败，请刷新后重试')
    } finally {
      submittingKey.value = ''
    }
    await load()
  }

  async function markRequested(group, billNo) {
    if (!group.orderId || !billNo) return
    await api.upwardPurchaseMarkRequested({ orderId: group.orderId, billNo })
  }

  return {
    loading, loadingMore, errorMsg, records, total, pendingCount, status, submittingKey,
    groups, hasMore, statusOptions: STATUS_OPTIONS, load, loadMore, setStatus, initiate
  }
}

function groupRecords(list) {
  const map = new Map()
  for (const row of list || []) {
    const key = String(row.orderId || row.orderNo || row.id)
    if (!map.has(key)) {
      map.set(key, {
        key,
        orderId: row.orderId,
        orderNo: row.orderNo,
        receiptNo: row.receiptNo,
        batchNo: row.batchNo,
        customerName: row.customerName,
        financeCheckTime: row.financeCheckTime,
        records: []
      })
    }
    map.get(key).records.push(row)
  }
  return [...map.values()].map(group => {
    const pending = group.records.filter(row => row.status === 'PENDING')
    const requested = group.records.filter(row => row.status === 'REQUESTED')
    const handled = group.records.filter(row => row.status === 'HANDLED')
    const groupStatus = pending.length ? 'PENDING'
      : requested.length ? 'REQUESTED' : handled.length ? 'HANDLED' : 'INVALID'
    return {
      ...group,
      pendingRecords: pending,
      requestedRecords: requested,
      handledRecords: handled,
      pendingCount: pending.length,
      status: groupStatus,
      statusName: GROUP_STATUS[groupStatus],
      requestBillNo: requested[0] ? requested[0].requestBillNo : ''
    }
  })
}

function existingBillNo(message) {
  const match = String(message || '').match(/单号\s*([A-Za-z0-9_-]+)/)
  return match ? match[1] : ''
}

function toast(title) {
  uni.showToast({ title, icon: 'none', duration: 2500 })
}
