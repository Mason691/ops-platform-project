<template>
  <view class="ai-chat-page">
    <!-- Header -->
    <view class="chat-header">
      <text class="header-title">{{ aiNickname }}</text>
    </view>
    <!-- Messages -->
    <scroll-view class="chat-body" scroll-y :scroll-top="scrollTop"
                 :scroll-with-animation="true" @scrolltoupper="loadMore">
      <view class="msg-item" v-for="(m, i) in messages" :key="i"
            :class="m.role === 'assistant' ? 'msg-ai' : 'msg-user'">
        <image v-if="m.role === 'assistant'" class="msg-avatar"
               :src="aiAvatar" mode="aspectFill" />
        <view class="msg-bubble"
              :class="m.role === 'assistant' ? 'bubble-ai' : 'bubble-user'">
          <text>{{ m.content }}</text>
        </view>
        <image v-if="m.role === 'user'" class="msg-avatar"
               :src="userAvatar" mode="aspectFill" />
      </view>
      <view v-if="loading" class="msg-item msg-ai">
        <image class="msg-avatar" :src="aiAvatar" mode="aspectFill" />
        <view class="msg-bubble bubble-ai typing">小金正在思考...</view>
      </view>
    </scroll-view>
    <!-- Input -->
    <view class="chat-input-bar">
      <input class="chat-input" v-model="inputText"
             placeholder="输入您的问题..."
             :disabled="loading"
             @confirm="sendMsg" />
      <button class="send-btn" :disabled="loading || !inputText.trim()"
              @tap="sendMsg" size="mini">发送</button>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { api } from '@/api/index.js'

const aiNickname = ref('小金')
const aiAvatar   = ref('/static/ai-avatar.png')
const userAvatar = ref('/static/default-avatar.png')
const messages   = ref([])
const inputText  = ref('')
const loading    = ref(false)
const scrollTop  = ref(0)
let conversationId = null

onMounted(async () => {
  try {
    const cfg = await api.aiConfig()
    if (cfg?.nickname) aiNickname.value = cfg.nickname
    if (cfg?.greeting) messages.value.push({ role: 'assistant', content: cfg.greeting })
  } catch (_) {
    messages.value.push({ role: 'assistant', content: '您好！我是小金，您的燃气生活助手～' })
  }
})

async function sendMsg() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true
  scrollToBottom()

  try {
    const res = await api.aiChat({ message: text, conversationId })
    const reply = res?.reply || res?.data?.reply || '抱歉，AI 服务暂时不可用。'
    messages.value.push({ role: 'assistant', content: reply })
    if (res?.conversationId) conversationId = res.conversationId
  } catch (_) {
    messages.value.push({ role: 'assistant', content: '网络异常，请稍后重试。' })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

function scrollToBottom() {
  nextTick(() => { scrollTop.value = 99999 })
}
</script>

<style scoped>
.ai-chat-page {
  display: flex; flex-direction: column; height: 100vh;
  background: #f0f2f5;
}
.chat-header {
  padding: 12px 16px; background: #FF6B35; text-align: center;
}
.header-title { color: #fff; font-size: 18px; font-weight: 600; }
.chat-body { flex: 1; padding: 12px; overflow-y: auto; }
.msg-item { display: flex; margin-bottom: 14px; align-items: flex-start; }
.msg-user { flex-direction: row-reverse; }
.msg-avatar { width: 36px; height: 36px; border-radius: 50%; flex-shrink: 0; }
.msg-user .msg-avatar { margin-left: 8px; }
.msg-ai .msg-avatar { margin-right: 8px; }
.msg-bubble { max-width: 75%; padding: 10px 14px; border-radius: 12px; font-size: 15px; line-height: 1.5; }
.bubble-ai { background: #fff; border-radius: 2px 12px 12px 12px; }
.bubble-user { background: #FF6B35; color: #fff; border-radius: 12px 2px 12px 12px; }
.typing { color: #999; font-style: italic; }
.chat-input-bar {
  display: flex; align-items: center; padding: 10px 12px;
  background: #fff; border-top: 1px solid #eee;
}
.chat-input { flex: 1; height: 40px; padding: 0 12px; background: #f5f5f5; border-radius: 20px; font-size: 14px; }
.send-btn { margin-left: 8px; background: #FF6B35; color: #fff; border: none; border-radius: 20px; padding: 0 16px; height: 40px; line-height: 40px; }
</style>
