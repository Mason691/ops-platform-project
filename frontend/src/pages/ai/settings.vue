<template>
  <view class="settings-page">
    <!-- 性格风格 -->
    <view class="section">
      <view class="section-title">💬 回复风格</view>
      <view class="style-list">
        <view
          v-for="item in styles"
          :key="item.value"
          class="style-item"
          :class="{ active: currentStyle === item.value }"
          @click="handleStyleChange(item.value)"
        >
          <text class="style-name">{{ item.label }}</text>
          <text class="style-desc">{{ item.desc }}</text>
          <view v-if="currentStyle === item.value" class="check-mark">✓</view>
        </view>
      </view>
    </view>

    <!-- 偏好 -->
    <view class="section">
      <view class="section-title">⚙️ 我的偏好</view>
      <view v-if="preferences.length === 0" class="empty-tip">
        暂无偏好，AI 会在对话中自动学习你的偏好
      </view>
      <view v-for="pref in preferences" :key="pref.prefKey" class="pref-item">
        <view class="pref-info">
          <text class="pref-key">{{ pref.prefKey }}</text>
          <text class="pref-val">{{ pref.prefValue }}</text>
        </view>
        <text class="pref-confidence">{{ Math.round((pref.confidence || 0) * 100) }}%</text>
        <text class="del-btn" @click="handleDeletePreference(pref.prefKey)">删除</text>
      </view>
      <view class="add-pref">
        <input v-model="newPrefKey" placeholder="偏好键，如 prefer_tables" class="input" />
        <input v-model="newPrefValue" placeholder="偏好值，如 true" class="input" />
        <button size="mini" type="primary" @click="handleAddPreference" :disabled="!newPrefKey">添加</button>
      </view>
    </view>

    <!-- 习惯 -->
    <view class="section">
      <view class="section-title">🔄 AI 发现的习惯</view>
      <view v-if="habits.length === 0" class="empty-tip">
        使用越多，AI 越懂你。暂无检测到的习惯
      </view>
      <view v-for="habit in habits" :key="habit.habitKey" class="habit-item">
        <view class="habit-info">
          <text class="habit-confidence">{{ Math.round((habit.confidence || 0) * 100) }}%</text>
          <text class="habit-key">{{ habit.habitKey }}</text>
        </view>
        <view class="habit-actions">
          <text class="ack-btn" @click="handleAcknowledgeHabit(habit.habitKey)">确认</text>
          <text class="del-btn" @click="handleDeleteHabit(habit.habitKey)">忽略</text>
        </view>
      </view>
    </view>

    <!-- 记忆 -->
    <view class="section">
      <view class="section-title">🧠 AI 记住的事</view>
      <view v-if="memories.length === 0" class="empty-tip">
        AI 还没有记住特别的事，多聊聊天吧
      </view>
      <view v-for="mem in memories" :key="mem.id" class="memory-item">
        <text class="mem-category">{{ mem.category || '通用' }}</text>
        <text class="mem-content">{{ mem.content }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '@/api/index.js'

const styles = [
  { value: 'caring', label: '🌸 贴心助理', desc: '温柔耐心，主动关怀' },
  { value: 'professional', label: '📊 专业严谨', desc: '数据驱动，结构清晰' },
  { value: 'humorous', label: '😄 幽默风趣', desc: '轻松活泼，偶尔俏皮' }
]

const currentStyle = ref('caring')
const preferences = ref([])
const habits = ref([])
const memories = ref([])
const newPrefKey = ref('')
const newPrefValue = ref('')

onMounted(async () => {
  try {
    const profile = await api.memberAiGetProfile()
    if (profile) {
      currentStyle.value = profile.style || 'caring'
      preferences.value = profile.preferences || []
      habits.value = profile.habits || []
      memories.value = profile.memories || []
    }
  } catch (e) {
    // Graceful
  }
})

async function handleStyleChange(val) {
  try {
    await api.memberAiUpdateStyle(val)
    currentStyle.value = val
    uni.showToast({ title: '风格已更新', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: '更新失败', icon: 'error' })
  }
}

async function handleAddPreference() {
  if (!newPrefKey.value) return
  try {
    await api.memberAiSetPreference(newPrefKey.value, newPrefValue.value)
    preferences.value.push({ prefKey: newPrefKey.value, prefValue: newPrefValue.value, confidence: 1.0 })
    newPrefKey.value = ''
    newPrefValue.value = ''
    uni.showToast({ title: '已添加', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: '添加失败', icon: 'error' })
  }
}

async function handleDeletePreference(key) {
  try {
    await api.memberAiDeletePreference(key)
    preferences.value = preferences.value.filter(p => p.prefKey !== key)
    uni.showToast({ title: '已删除', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: '删除失败', icon: 'error' })
  }
}

async function handleAcknowledgeHabit(key) {
  try {
    await api.memberAiAcknowledgeHabit(key)
    const h = habits.value.find(h => h.habitKey === key)
    if (h) h.confidence = 1.0
    uni.showToast({ title: '已确认', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: '操作失败', icon: 'error' })
  }
}

async function handleDeleteHabit(key) {
  try {
    await api.memberAiDeleteHabit(key)
    habits.value = habits.value.filter(h => h.habitKey !== key)
    uni.showToast({ title: '已忽略', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: '操作失败', icon: 'error' })
  }
}
</script>

<style scoped>
.settings-page {
  padding: 16px;
  background: #f5f5f5;
  min-height: 100vh;
}
.section {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}
.section-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
}
.style-item {
  display: flex;
  align-items: center;
  padding: 12px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  margin-bottom: 8px;
  position: relative;
}
.style-item.active {
  border-color: #FF6B35;
  background: #fff5f0;
}
.style-name {
  font-weight: 600;
  min-width: 120px;
}
.style-desc {
  color: #999;
  font-size: 12px;
  flex: 1;
}
.check-mark {
  color: #FF6B35;
  font-size: 18px;
  font-weight: bold;
}
.empty-tip {
  color: #999;
  font-size: 14px;
  text-align: center;
  padding: 20px 0;
}
.pref-item, .habit-item, .memory-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}
.pref-key, .habit-key, .mem-category {
  font-weight: 600;
  font-size: 14px;
}
.pref-val, .habit-confidence, .mem-content {
  color: #666;
  font-size: 14px;
}
.del-btn {
  color: #f56c6c;
  font-size: 13px;
  margin-left: 8px;
}
.ack-btn {
  color: #67c23a;
  font-size: 13px;
  margin-left: 8px;
}
.add-pref {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.input {
  flex: 1;
  min-width: 120px;
  border: 1px solid #ddd;
  border-radius: 4px;
  padding: 6px;
  font-size: 13px;
}
</style>
