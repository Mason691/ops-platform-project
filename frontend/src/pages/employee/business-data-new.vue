<template>
  <view class="page">
    <view v-if="loading" class="state">
      <text class="state-text">加载中...</text>
    </view>
    <view v-else-if="errorMsg" class="state">
      <text class="state-error">{{ errorMsg }}</text>
      <view class="retry-btn" @click="loadOptions"><text>重试</text></view>
    </view>
    <template v-else>
      <view class="intro">
        <text class="intro-title">新增业务数据</text>
        <text class="intro-sub">{{ meta.company }} · {{ meta.employeeName }} · {{ meta.employeePhone }}</text>
      </view>
      <view v-if="meta.productError" class="warn">
        <text>⚠️ {{ meta.productError }}</text>
      </view>

      <BusinessEntryForm :form="form" :meta="meta" :errors="errors"
                         :querying="querying" @query-member="queryMember" />
      <BusinessProductForm :form="form" :meta="meta" :errors="errors" />
      <BusinessReceiptForm :form="form" :errors="errors" />

      <view class="footer-space"></view>
      <view class="submit-bar">
        <view class="submit-btn" :class="{ disabled: submitting }" @click="submit">
          {{ submitting ? '提交中...' : '保存' }}
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '@/api/index'
import BusinessEntryForm from '@/components/BusinessEntryForm.vue'
import BusinessProductForm from '@/components/BusinessProductForm.vue'
import BusinessReceiptForm from '@/components/BusinessReceiptForm.vue'
import { validateBusinessForm, today, PHONE_RE, normalizePhone, normalizeMoney } from '@/utils/businessForm'

const loading = ref(true)
const errorMsg = ref('')
const querying = ref(false)
const submitting = ref(false)
const meta = reactive(emptyMeta())
const form = reactive(emptyForm())
const errors = reactive({})

function emptyMeta() {
  return {
    corpId: null, corpName: '', company: '', employeeName: '', employeePhone: '',
    batches: [], newBatchPrefix: '', brands: [], categories: [], materials: [],
    communities: [], productError: ''
  }
}

function emptyForm() {
  return {
    batchNo: '', newBatch: false, customerPhone: '', customer: '',
    communityId: null, communityName: '', detailAddress: '',
    isSpecial: false, commissionRate: '', brand: '', category: '', model: '',
    salePrice: '', saleQuantity: '1', receiptDate: today(),
    isReceived: false, voucherUrls: []
  }
}

onMounted(() => {
  if (!uni.getStorageSync('ops_employee_token')) {
    uni.redirectTo({ url: '/pages/employee/login' })
    return
  }
  loadOptions()
})

async function loadOptions() {
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await api.employeeManualOptions()
    Object.assign(meta, emptyMeta(), data || {})
    meta.batches = Array.isArray(data && data.batches) ? data.batches : []
    meta.brands = Array.isArray(data && data.brands) ? data.brands : []
    meta.categories = Array.isArray(data && data.categories) ? data.categories : []
    meta.materials = Array.isArray(data && data.materials) ? data.materials : []
    meta.communities = Array.isArray(data && data.communities) ? data.communities : []
    if (meta.batches.length) {
      form.newBatch = false
      form.batchNo = meta.batches[0]
    } else {
      form.newBatch = true
      form.batchNo = ''
    }
  } catch (e) {
    errorMsg.value = (e && e.msg) || '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

async function queryMember() {
  if (querying.value) return
  const phone = normalizePhone(form.customerPhone)
  form.customerPhone = phone
  if (!PHONE_RE.test(phone)) {
    toast('请输入正确的11位手机号')
    return
  }
  querying.value = true
  try {
    const res = await api.employeeLookupMember(phone)
    if (res && res.found) {
      if (res.name) form.customer = res.name
      if (res.communityId != null) {
        const community = (meta.communities || []).find(
          item => String(item.id) === String(res.communityId))
        if (community) {
          form.communityId = community.id
          form.communityName = community.name
        }
      }
      toast(res.name ? '已找到会员：' + res.name : '已找到会员')
    } else {
      toast((res && res.message) || '未找到该手机号对应的会员')
    }
  } catch (e) {
    toast((e && e.msg) || '查询失败')
  } finally {
    querying.value = false
  }
}

async function submit() {
  if (submitting.value) return
  const result = validateBusinessForm(form)
  Object.keys(errors).forEach(key => delete errors[key])
  Object.assign(errors, result.errors)
  if (!result.valid) {
    toast(result.firstError)
    return
  }
  if (meta.productError) {
    toast(meta.productError)
    return
  }

  submitting.value = true
  try {
    const created = await api.employeeCreateBusiness({
      batchNo: form.newBatch ? null : form.batchNo,
      newBatch: !!form.newBatch,
      customerPhone: normalizePhone(form.customerPhone),
      customer: String(form.customer || '').trim(),
      communityId: form.communityId || null,
      detailAddress: String(form.detailAddress || '').trim(),
      isSpecial: !!form.isSpecial,
      commissionRate: form.isSpecial ? Number(form.commissionRate) : 0,
      brand: form.brand,
      category: form.category,
      model: form.model,
      salePrice: normalizeMoney(form.salePrice),
      saleQuantity: Number(form.saleQuantity),
      receiptDate: form.receiptDate,
      isReceived: !!form.isReceived,
      voucherUrls: form.isReceived ? form.voucherUrls : []
    })
    showSuccess(created)
  } catch (e) {
    toast((e && e.msg) || '保存失败，请重试')
  } finally {
    submitting.value = false
  }
}

function showSuccess(created) {
  const amount = Number(created && created.receiptAmount ? created.receiptAmount : 0).toFixed(2)
  uni.showModal({
    title: '新增成功',
    content: '批次：' + (created ? created.batchNo : '') + '，收款金额：¥' + amount,
    confirmText: '继续新增',
    cancelText: '返回列表',
    success: (res) => {
      if (res.confirm) resetForNext(created ? created.batchNo : '')
      else uni.navigateBack()
    }
  })
}

function resetForNext(batchNo) {
  Object.assign(form, emptyForm())
  Object.keys(errors).forEach(key => delete errors[key])
  if (batchNo) {
    if (!meta.batches.includes(batchNo)) meta.batches.unshift(batchNo)
    form.batchNo = batchNo
    form.newBatch = false
  } else {
    form.newBatch = meta.batches.length === 0
    form.batchNo = meta.batches[0] || ''
  }
}

function toast(title) {
  uni.showToast({ title: String(title || ''), icon: 'none', duration: 2200 })
}
</script>

<style scoped>
.page {
  min-height: 100vh; background: #f5f5f5;
  padding: 24rpx 24rpx 0; box-sizing: border-box;
}
.state { padding: 200rpx 32rpx; text-align: center; }
.state-text { font-size: 28rpx; color: #999; }
.state-error { display: block; font-size: 28rpx; color: #e53935; margin-bottom: 24rpx; line-height: 1.5; }
.retry-btn {
  display: inline-block; padding: 16rpx 48rpx; background: #FF6B35;
  color: #fff; border-radius: 32rpx; font-size: 28rpx;
}
.intro {
  background: linear-gradient(135deg, #FF6B35, #FF8C5A);
  border-radius: 16rpx; padding: 28rpx 24rpx; margin-bottom: 20rpx;
}
.intro-title { display: block; font-size: 36rpx; font-weight: 700; color: #fff; }
.intro-sub { display: block; font-size: 22rpx; color: rgba(255, 255, 255, .88); margin-top: 8rpx; }
.warn {
  background: #FFF7E6; color: #B26A00; border-radius: 12rpx;
  padding: 20rpx 24rpx; font-size: 24rpx; line-height: 1.5; margin-bottom: 20rpx;
}
.footer-space { height: 160rpx; }
.submit-bar {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
  background: #fff; border-top: 1rpx solid #f0f0f0;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
}
.submit-btn {
  height: 84rpx; line-height: 84rpx; text-align: center;
  border-radius: 42rpx; background: #FF6B35; color: #fff;
  font-size: 30rpx; font-weight: 600;
}
.submit-btn.disabled { opacity: .6; }
</style>
