<template>
  <view v-if="visible" id="field-communityId" class="section">
    <view class="label-row">
      <text class="label" @click="focusField('field-communityId')">小区 <text class="req">*</text></text>
      <text class="create-link" :class="{ disabled: !districtConfigured }" @click="openCommunityCreate()">＋ 新建小区</text>
    </view>
    <template v-if="communities.length > 8">
      <view class="picker-trigger" :class="{ 'control-error': errors.communityId }"
            @click="communitySheet = true">
        <text :class="form.communityName ? 'trigger-value' : 'placeholder'">
          {{ form.communityName || '请选择小区' }}
        </text>
        <text class="arrow">›</text>
      </view>
    </template>
    <template v-else-if="communities.length">
      <view v-if="communities.length > 4" class="search-row">
        <input class="search-input" v-model="communityKeyword" placeholder="搜索小区" />
        <text v-if="communityKeyword" class="search-clear" @click="communityKeyword = ''">清空</text>
      </view>
      <FieldChips class="chips-block" :class="{ 'control-error-box': errors.communityId }"
                  :options="communityChips" :model-value="form.communityId"
                  @update:model-value="setCommunity" compact />
    </template>
    <text v-else class="warn-text">
      {{ districtConfigured ? '组织暂未配置小区，可直接点击「新建小区」创建' : '组织暂未配置小区' }}
    </text>
    <text v-if="errors.communityId" class="ferr">{{ errors.communityId }}</text>
    <text v-else-if="!districtConfigured" class="warn-text">
      当前组织未配置所在区县，请联系管理员完善组织信息后再新建小区
    </text>

    <view v-if="selectedCommunity && (selectedCommunity.detailAddress || hasCoords)"
          class="community-address">
      <text class="ca-text">{{ selectedCommunity.detailAddress || '已记录坐标' }}</text>
      <text v-if="hasCoords" class="ca-nav" @click="navigateToCommunity">导航</text>
      <text v-else class="ca-nav disabled">暂无位置信息</text>
    </view>

    <BottomSheetSelect v-model:visible="communitySheet" title="选择小区" :options="communityOptions"
                       :model-value="form.communityId" placeholder="搜索小区名称"
                       :action-text="districtConfigured ? '＋ 新建小区' : ''"
                       @action="onCommunityAction"
                       @select="option => setCommunity(option.value)" />
    <BusinessCommunityCreateSheet v-model:visible="communityCreateVisible"
                                  :initial-name="communityCreateInitial"
                                  :district-name="meta.regionName || ''"
                                  :district-configured="districtConfigured"
                                  :region-id="meta.regionId"
                                  @created="onCommunityCreated" />
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import FieldChips from '@/components/FieldChips.vue'
import BottomSheetSelect from '@/components/BottomSheetSelect.vue'
import BusinessCommunityCreateSheet from '@/components/BusinessCommunityCreateSheet.vue'
import { focusField } from '@/utils/fieldFocus'

/**
 * 小区选择字段：标签/搜索/底部弹层 + 新建小区 + 已选小区地址与导航。
 * 从 BusinessStepBasic 抽出，避免单文件超过 300 行。
 */
const props = defineProps({
  form: { type: Object, required: true },
  meta: { type: Object, required: true },
  errors: { type: Object, default: () => ({}) },
  visible: { type: Boolean, default: true }
})

const communitySheet = ref(false)
const communityCreateVisible = ref(false)
const communityCreateInitial = ref('')
const communityKeyword = ref('')

const communities = computed(() => props.meta.communities || [])
const districtConfigured = computed(() => props.meta.regionConfigured !== false)
const communityOptions = computed(() => communities.value.map(item => ({
  value: item.id, label: item.name
})))
const communityChips = computed(() => {
  const word = communityKeyword.value.trim().toLowerCase()
  const list = word
    ? communities.value.filter(item => String(item.name || '').toLowerCase().includes(word))
    : communities.value
  return list.map(item => ({ value: item.id, label: item.name }))
})
const selectedCommunity = computed(() => communities.value.find(
  item => String(item.id) === String(props.form.communityId)))
const hasCoords = computed(() => selectedCommunity.value
  && selectedCommunity.value.longitude != null && selectedCommunity.value.latitude != null)

function setCommunity(value) {
  const found = communities.value.find(item => String(item.id) === String(value))
  props.form.communityId = found ? found.id : null
  props.form.communityName = found ? found.name : ''
}

/** 新建小区：用当前搜索词预填名称，创建成功后加入列表并自动选中。 */
function openCommunityCreate(initialName = '') {
  if (!districtConfigured.value) return
  communityCreateInitial.value = String(initialName || communityKeyword.value || '').trim()
  communityCreateVisible.value = true
}

function onCommunityAction(keyword) {
  communitySheet.value = false
  openCommunityCreate(keyword)
}

/** 新建成功：保留定位字段，选中的小区可展示地址并提供导航。 */
function onCommunityCreated(created) {
  const id = created && created.id
  if (id == null) return
  const list = props.meta.communities || []
  let item = list.find(row => String(row.id) === String(id))
  if (!item) {
    item = { id, name: created.name }
    list.push(item)
    props.meta.communities = list
  }
  item.detailAddress = item.detailAddress || created.detailAddress || null
  item.longitude = item.longitude != null ? item.longitude : created.longitude
  item.latitude = item.latitude != null ? item.latitude : created.latitude
  setCommunity(id)
  communityKeyword.value = ''
}

/** 高德地图导航；微信内会优先唤起高德 App/小程序，未安装则打开网页地图。 */
function navigateToCommunity() {
  const community = selectedCommunity.value
  if (!community || community.longitude == null || community.latitude == null) return
  const url = 'https://uri.amap.com/marker?position=' + community.longitude + ','
    + community.latitude + '&name=' + encodeURIComponent(community.name || '小区')
    + '&coordinate=gaode&callnative=1'
  // #ifdef H5
  window.location.href = url
  // #endif
  // #ifndef H5
  uni.openLocation({
    longitude: Number(community.longitude),
    latitude: Number(community.latitude),
    name: community.name || '小区',
    address: community.detailAddress || ''
  })
  // #endif
}
</script>

<style scoped>
.section {
  background: #fff; border-radius: 16rpx; padding: 26rpx 24rpx;
  box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, .04);
}
.label-row { display: flex; align-items: baseline; justify-content: space-between; }
.label { display: block; font-size: 26rpx; color: #666; margin-bottom: 14rpx; }
.label-row .label { margin-bottom: 0; }
.req { color: #e53935; }
.create-link {
  font-size: 24rpx; font-weight: 600; color: #FF6B35;
  padding: 6rpx 18rpx; border: 2rpx solid #FF6B35; border-radius: 26rpx;
  line-height: 1.2;
}
.create-link.disabled { color: #bbb; border-color: #ddd; }
.picker-trigger {
  display: flex; align-items: center; justify-content: space-between;
  height: 80rpx; background: #f7f7f7; border-radius: 12rpx; padding: 0 20rpx;
}
.trigger-value {
  flex: 1; min-width: 0; font-size: 28rpx; color: #333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.placeholder { font-size: 28rpx; color: #bbb; }
.arrow { font-size: 32rpx; color: #999; }
.search-row {
  display: flex; align-items: center; background: #f5f5f5;
  border-radius: 10rpx; padding: 0 18rpx; margin-bottom: 14rpx;
}
.search-input { flex: 1; height: 66rpx !important; min-height: 66rpx !important; font-size: 26rpx; color: #333; }
.search-clear { font-size: 23rpx; color: #999; padding-left: 12rpx; }
.ferr { display: block; font-size: 22rpx; color: #e53935; margin-top: 8rpx; }
.control-error { border: 2rpx solid #e53935 !important; background: #FFF8F8 !important; }
.control-error-box {
  border: 2rpx solid #e53935; border-radius: 12rpx;
  padding: 8rpx; background: #FFF8F8;
}
.chips-block { width: 100%; }
.warn-text { display: block; font-size: 23rpx; color: #B26A00; margin-top: 8rpx; line-height: 1.5; }
.community-address {
  display: flex; align-items: flex-start; justify-content: space-between;
  margin-top: 12rpx; padding: 14rpx 18rpx; background: #F7F9FC; border-radius: 10rpx;
}
.ca-text { flex: 1; min-width: 0; font-size: 23rpx; color: #666; line-height: 1.5; }
.ca-nav {
  flex: none; margin-left: 16rpx; font-size: 23rpx; color: #FF6B35;
  font-weight: 600; padding: 2rpx 0 2rpx 12rpx;
}
.ca-nav.disabled { color: #bbb; font-weight: 400; }
</style>
