<template>
  <view class="ai-chat-page">
    <!-- Header -->
    <view class="chat-header">
      <text class="header-title">{{ aiNickname }}</text>
    </view>
    <!-- Messages -->
    <scroll-view class="chat-body" scroll-y :scroll-top="scrollTop"
                 :scroll-with-animation="true">
      <view class="msg-item" v-for="(m, i) in messages" :key="i"
            :class="m.role === 'assistant' ? 'msg-ai' : 'msg-user'">
        <image v-if="m.role === 'assistant'" class="msg-avatar"
               :src="aiAvatar" mode="aspectFill" />
        <view class="msg-bubble"
              :class="m.role === 'assistant' ? 'bubble-ai' : 'bubble-user'">
          <text>{{ m.content }}</text>
        </view>
        <!-- Voice play button on assistant messages -->
        <view v-if="m.role === 'assistant' && voiceEnabled" class="voice-play-btn" @tap="playVoice(m)">
          <text>{{ playingIdx === i ? '🔊' : '🔈' }}</text>
        </view>
        <image v-if="m.role === 'user'" class="msg-avatar"
               :src="userAvatar" mode="aspectFill" />
      </view>
      <view v-if="loading" class="msg-item msg-ai">
        <image class="msg-avatar" :src="aiAvatar" mode="aspectFill" />
        <view class="msg-bubble bubble-ai typing">小金正在思考...</view>
      </view>
      <view v-if="recording" class="recording-bar">
        <text class="recording-icon">🎙️</text>
        <text class="recording-text">正在聆听...</text>
      </view>
    </scroll-view>
    <!-- Input -->
    <view class="chat-input-bar">
      <view class="voice-btn" @longpress="startRecord" @touchend="stopRecord"
            :class="{ active: recording }">
        <text>🎤</text>
      </view>
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
const recording  = ref(false)
const playingIdx = ref(-1)
const voiceEnabled = ref(true)
let conversationId = null
let recordTimer = null

onMounted(async () => {
  // 加载配置 + 个性化问候
  const token = uni.getStorageSync('ops_token')
  try {
    const cfg = await api.aiConfig()
    if (cfg?.nickname) aiNickname.value = cfg.nickname
    if (cfg?.voiceEnabled !== undefined) voiceEnabled.value = cfg.voiceEnabled
  } catch (_) {}

  // 个性化问候
  try {
    if (token) {
      const r = await request({ url: '/api/ops/ai/profile', method: 'GET' })
      if (r?.greeting) messages.value.push({ role: 'assistant', content: r.greeting })
      else messages.value.push({ role: 'assistant', content: '您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？' })
    } else {
      messages.value.push({ role: 'assistant', content: '你好！我是你的燃气小助手，可以帮你选商品、查积分、找优惠～\n登录后更懂你。' })
    }
  } catch (_) {
    messages.value.push({ role: 'assistant', content: '您好！我是小金，您的燃气生活助手～' })
  }
})

// ── Text chat ──
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

// ── Voice input ──
function startRecord() {
  recording.value = true
  recordTimer = setTimeout(() => {
    recording.value = false
    uni.showToast({ title: '录音完成（模拟）', icon: 'none' })
    messages.value.push({ role: 'user', content: '[语音消息]' })
    loading.value = true
    setTimeout(() => {
      messages.value.push({ role: 'assistant', content: '语音识别功能已就绪，您的语音已收到～' })
      loading.value = false
      scrollToBottom()
    }, 800)
  }, 1500)
}
function stopRecord() {
  if (recordTimer) clearTimeout(recordTimer)
  recording.value = false
}

// ── Voice playback (TTS via browser SpeechSynthesis) ──
function playVoice(msg) {
  try {
    if (playingIdx.value >= 0) {
      window.speechSynthesis?.cancel()
      playingIdx.value = -1
      return
    }
    if (!window.speechSynthesis) {
      uni.showToast({ title: '语音播报不支持', icon: 'none' })
      return
    }
    const utterance = new SpeechSynthesisUtterance(msg.content)
    utterance.lang = 'zh-CN'
    utterance.rate = 1.0
    const idx = messages.value.indexOf(msg)
    playingIdx.value = idx
    utterance.onend = () => { playingIdx.value = -1 }
    utterance.onerror = () => { playingIdx.value = -1 }
    window.speechSynthesis.speak(utterance)
  } catch (e) {
    playingIdx.value = -1
  }
}

function scrollToBottom() {
  nextTick(() => { scrollTop.value = 99999 })
}

// ── Request wrapper (for calls not in api/index.js) ──
function request(opts) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('ops_token') || ''
    uni.request({
      url: '/api' + opts.url,
      method: opts.method || 'GET',
      header: token ? { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' },
      success(r) { if (r.data?.code === 200) resolve(r.data.data); else reject(r.data) },
      fail: reject
    })
  })
}
</script>

<style scoped>
.ai-chat-page{display:flex;flex-direction:column;height:100vh;background:#f0f2f5}
.chat-header{padding:12px 16px;background:#FF6B35;text-align:center}
.header-title{color:#fff;font-size:18px;font-weight:600}
.chat-body{flex:1;padding:12px;overflow-y:auto}
.msg-item{display:flex;margin-bottom:14px;align-items:flex-start}
.msg-user{flex-direction:row-reverse}
.msg-avatar{width:36px;height:36px;border-radius:50%;flex-shrink:0}
.msg-user .msg-avatar{margin-left:8px}
.msg-ai .msg-avatar{margin-right:8px}
.msg-bubble{max-width:70%;padding:10px 14px;border-radius:12px;font-size:15px;line-height:1.5;word-break:break-all}
.bubble-ai{background:#fff;border-radius:2px 12px 12px 12px}
.bubble-user{background:#FF6B35;color:#fff;border-radius:12px 2px 12px 12px}
.typing{color:#999;font-style:italic}
.voice-play-btn{margin-left:4px;width:28px;height:28px;display:flex;align-items:center;justify-content:center;font-size:16px;cursor:pointer}
.recording-bar{display:flex;align-items:center;justify-content:center;gap:8px;padding:12px;margin:8px 0;background:#fff3cd;border-radius:8px}
.recording-icon{font-size:24px;animation:pulse 1s infinite}
.recording-text{font-size:14px;color:#856404}@keyframes pulse{0%,100%{opacity:1}50%{opacity:.5}}
.chat-input-bar{display:flex;align-items:center;padding:10px 12px;background:#fff;border-top:1px solid #eee;gap:6px}
.voice-btn{width:40px;height:40px;display:flex;align-items:center;justify-content:center;font-size:22px;border-radius:50%;background:#f5f5f5}
.voice-btn.active{background:#FF6B35;color:#fff}
.chat-input{flex:1;height:40px;padding:0 12px;background:#f5f5f5;border-radius:20px;font-size:14px}
.send-btn{margin-left:0;background:#FF6B35;color:#fff;border:none;border-radius:20px;padding:0 16px;height:40px;line-height:40px}
</style>
