<template>
  <view class="ai-chat-page">
    <!-- Messages -->
    <scroll-view class="chat-body" scroll-y :scroll-top="scrollTop"
                 :scroll-with-animation="true" :enhanced="true"
                 :show-scrollbar="false">
      <!-- Welcome card (shown when no messages yet) -->
      <view class="welcome-card" v-if="messages.length===0 && !loading">
        <view class="wc-avatar">
          <image class="wc-avatar-img" :src="aiAvatar" mode="aspectFill"/>
        </view>
        <text class="wc-title">{{ aiNickname }}</text>
        <text class="wc-desc">你的专属燃气生活AI助手</text>
        <view class="wc-features">
          <view class="wc-feat"><text>📦</text><text>商品推荐</text></view>
          <view class="wc-feat"><text>💰</text><text>积分查询</text></view>
          <view class="wc-feat"><text>🔥</text><text>活动资讯</text></view>
          <view class="wc-feat"><text>📋</text><text>订单答疑</text></view>
        </view>
      </view>

      <!-- Messages -->
      <view class="msg-item" v-for="(m, i) in messages" :key="i"
            :class="m.role === 'assistant' ? 'msg-left' : 'msg-right'">
        <image v-if="m.role === 'assistant'" class="msg-avatar"
               :src="aiAvatar" mode="aspectFill" />
        <view class="msg-main">
          <view class="msg-bubble"
                :class="m.role === 'assistant' ? 'bubble-ai' : 'bubble-user'">
            <text class="msg-text" :selectable="true">{{ m.content }}</text>
          </view>
          <!-- Voice play on AI messages -->
          <view v-if="m.role === 'assistant' && voiceEnabled" class="msg-actions">
            <view class="voice-btn-sm" @tap="playVoice(m, i)">
              <text class="voice-icon">{{ playingIdx === i ? '🔊' : '🔈' }}</text>
            </view>
          </view>
        </view>
        <image v-if="m.role === 'user'" class="msg-avatar"
               :src="userAvatar" mode="aspectFill" />
      </view>

      <!-- Typing indicator -->
      <view v-if="loading" class="msg-item msg-left">
        <image class="msg-avatar" :src="aiAvatar" mode="aspectFill" />
        <view class="msg-bubble bubble-ai typing-bubble">
          <view class="typing-dots">
            <view class="dot" v-for="d in 3" :key="d"></view>
          </view>
        </view>
      </view>
    </scroll-view>

    <!-- Quick questions -->
    <view class="quick-bar" v-if="quickQuestions.length && messages.length <= 2">
      <scroll-view scroll-x="true" :show-scrollbar="false" class="quick-scroll">
        <view class="quick-chip" v-for="(q, i) in quickQuestions" :key="i"
              @tap="sendQuick(q)">{{ q }}</view>
      </scroll-view>
    </view>

    <!-- Recording overlay -->
    <view class="recording-overlay" v-if="recording">
      <view class="recording-popup">
        <view class="recording-wave">
          <view class="wave-bar" v-for="b in 7" :key="b"
                :style="{ animationDelay: (b * 0.12) + 's', height: (12 + b * 3) + 'px' }"></view>
        </view>
        <text class="recording-hint">松开发送，上滑取消</text>
      </view>
    </view>

    <!-- Input bar -->
    <view class="chat-input-bar">
      <view class="voice-main-btn" @touchstart="startRecord"
            @touchend="stopRecord" @touchmove="onRecordMove"
            :class="{ active: recording }">
        <text>🎤</text>
      </view>
      <view class="input-wrap">
        <input class="chat-input" v-model="inputText"
               placeholder="输入您的问题..."
               :disabled="loading"
               confirm-type="send"
               @confirm="sendMsg"
               @focus="scrollToBottom" />
      </view>
      <view class="send-main-btn" :class="{ disabled: loading || !inputText.trim() }"
            @tap="sendMsg">
        <text class="send-arrow">↑</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { api } from '@/api/index.js'

const aiNickname  = ref('小金')
const aiAvatar    = ref('/static/ai-avatar.png')
const userAvatar  = ref('/static/default-avatar.png')
const messages    = ref([])
const inputText   = ref('')
const loading     = ref(false)
const scrollTop   = ref(0)
const recording   = ref(false)
const playingIdx  = ref(-1)
const voiceEnabled = ref(true)
const quickQuestions = ref([
  '有什么优惠活动？', '推荐几款燃气灶',
  '我的积分有多少？', '如何兑换商品？',
])
let conversationId = null
let recordTimer = null
let recordCancelled = false

onMounted(async () => {
  const token = uni.getStorageSync('ops_token')
  try {
    const cfg = await api.aiConfig()
    if (cfg?.nickname) aiNickname.value = cfg.nickname
    if (cfg?.voiceEnabled !== undefined) voiceEnabled.value = cfg.voiceEnabled
    if (cfg?.quickQuestions) quickQuestions.value = cfg.quickQuestions
  } catch (_) {}

  // Personalized greeting
  try {
    if (token) {
      const r = await request({ url: '/api/ops/ai/profile', method: 'GET' })
      if (r?.greeting) messages.value.push({ role: 'assistant', content: r.greeting })
      else messages.value.push({ role: 'assistant', content: '您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？' })
    }
  } catch (_) {
    // Welcome card handles empty state — no default message needed
  }

  // Load user avatar
  try {
    if (token) {
      const me = await api.getMe()
      if (me?.avatar) userAvatar.value = me.avatar
    }
  } catch (_) {}
})

// Text chat
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

// Quick question tap
function sendQuick(q) {
  inputText.value = q
  sendMsg()
}

// Voice input
function startRecord() {
  recording.value = true
  recordCancelled = false
  uni.vibrateShort && uni.vibrateShort()
  recordTimer = setTimeout(() => {
    recording.value = false
    uni.showToast({ title: '语音识别中...', icon: 'loading', duration: 1500 })
    setTimeout(() => {
      messages.value.push({ role: 'user', content: '[语音]' })
      loading.value = true
      scrollToBottom()
      setTimeout(() => {
        messages.value.push({ role: 'assistant', content: '您的语音消息已收到～我正在努力理解中，目前建议使用文字输入以获得更准确的回复。' })
        loading.value = false
        scrollToBottom()
      }, 800)
    }, 1500)
  }, 2000)
}
function stopRecord() {
  if (recordCancelled) { recording.value = false; return }
  if (recordTimer) clearTimeout(recordTimer)
  recording.value = false
}
function onRecordMove(e) {
  if (e.touches && e.touches[0]) {
    const y = e.touches[0].clientY
    recordCancelled = y < 100
  }
}

// Voice playback
function playVoice(msg, i) {
  try {
    if (playingIdx.value >= 0) {
      window.speechSynthesis?.cancel()
      if (playingIdx.value === i) { playingIdx.value = -1; return }
    }
    if (!window.speechSynthesis) {
      uni.showToast({ title: '当前环境不支持语音播报', icon: 'none' })
      return
    }
    const utterance = new SpeechSynthesisUtterance(msg.content)
    utterance.lang = 'zh-CN'
    utterance.rate = 1.0
    utterance.pitch = 1.1
    playingIdx.value = i
    utterance.onend = () => { playingIdx.value = -1 }
    utterance.onerror = () => { playingIdx.value = -1 }
    window.speechSynthesis.speak(utterance)
  } catch (e) { playingIdx.value = -1 }
}

function scrollToBottom() {
  nextTick(() => { setTimeout(() => { scrollTop.value = 999999 }, 100) })
}

function request(opts) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('ops_token') || ''
    uni.request({
      url: '/api' + opts.url,
      method: opts.method || 'GET',
      data: opts.data,
      header: token ? { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' },
      success(r) { if (r.data?.code === 200) resolve(r.data.data); else reject(r.data) },
      fail: reject
    })
  })
}
</script>

<style scoped>
.ai-chat-page {
  display:flex; flex-direction:column; height:100vh;
  background:#f0f2f5;
}

/* ── Welcome Card ── */
.welcome-card {
  display:flex; flex-direction:column; align-items:center;
  padding:64rpx 48rpx 48rpx; margin:24rpx; background:#fff;
  border-radius:24rpx; box-shadow:0 2rpx 16rpx rgba(0,0,0,.06);
}
.wc-avatar { margin-bottom:20rpx }
.wc-avatar-img { width:120rpx; height:120rpx; border-radius:50%; border:4rpx solid #FF6B35 }
.wc-title { font-size:38rpx; color:#333; font-weight:700; margin-bottom:8rpx }
.wc-desc { font-size:26rpx; color:#999; margin-bottom:24rpx }
.wc-features { display:flex; flex-wrap:wrap; gap:12rpx; justify-content:center }
.wc-feat {
  display:flex; align-items:center; gap:6rpx; padding:10rpx 20rpx;
  background:#FFF3EE; border-radius:12rpx; font-size:24rpx; color:#FF6B35;
}

/* ── Messages ── */
.chat-body { flex:1; padding:16rpx 24rpx; overflow-y:auto }
.msg-item { display:flex; align-items:flex-start; margin-bottom:24rpx }
.msg-left { flex-direction:row }
.msg-right { flex-direction:row-reverse }
.msg-avatar {
  width:72rpx; height:72rpx; border-radius:50%; flex-shrink:0;
  background:#fff; box-shadow:0 2rpx 8rpx rgba(0,0,0,.08);
}
.msg-left .msg-avatar  { margin-right:16rpx }
.msg-right .msg-avatar { margin-left:16rpx }
.msg-main { display:flex; flex-direction:column; max-width:72% }
.msg-bubble {
  padding:20rpx 24rpx; border-radius:16rpx; position:relative;
  box-shadow:0 2rpx 8rpx rgba(0,0,0,.04);
}
.msg-text { font-size:28rpx; line-height:1.6; word-break:break-all; white-space:pre-wrap }
.bubble-ai {
  background:#fff; color:#333;
  border-radius:4rpx 16rpx 16rpx 16rpx;
}
.bubble-user {
  background:linear-gradient(135deg,#FF6B35,#FF8C5A); color:#fff;
  border-radius:16rpx 4rpx 16rpx 16rpx;
}

/* Voice action */
.msg-actions { display:flex; padding-top:8rpx }
.voice-btn-sm {
  width:48rpx; height:48rpx; display:flex; align-items:center;
  justify-content:center; font-size:28rpx; opacity:.6;
}
.voice-btn-sm:active { opacity:1; transform:scale(1.15) }

/* Typing */
.typing-bubble { padding:24rpx 36rpx }
.typing-dots { display:flex; gap:8rpx }
.dot {
  width:14rpx; height:14rpx; background:#ccc;
  border-radius:50%; animation: bounce 1.4s infinite both;
}
.dot:nth-child(2) { animation-delay:.2s }
.dot:nth-child(3) { animation-delay:.4s }
@keyframes bounce { 0%,80%,100% { transform:scale(.6);opacity:.4 } 40% { transform:scale(1);opacity:1 } }

/* ── Quick questions ── */
.quick-bar { padding:16rpx 24rpx; background:transparent }
.quick-scroll { white-space:nowrap; display:flex; gap:12rpx }
.quick-chip {
  display:inline-flex; padding:14rpx 24rpx; background:#fff;
  border-radius:32rpx; font-size:26rpx; color:#FF6B35;
  box-shadow:0 2rpx 8rpx rgba(0,0,0,.05); transition:all .2s;
}
.quick-chip:active { background:#FF6B35; color:#fff; transform:scale(.96) }

/* ── Recording overlay ── */
.recording-overlay {
  position:fixed; inset:0; background:rgba(0,0,0,.35);
  display:flex; align-items:center; justify-content:center; z-index:999;
}
.recording-popup {
  background:#fff; border-radius:24rpx; padding:48rpx 64rpx;
  display:flex; flex-direction:column; align-items:center; gap:24rpx;
  box-shadow:0 16rpx 48rpx rgba(0,0,0,.15);
}
.recording-wave { display:flex; align-items:flex-end; gap:6rpx; height:80rpx }
.wave-bar {
  width:8rpx; background:linear-gradient(180deg,#FF6B35,#FF8C5A);
  border-radius:4rpx; animation:wave .8s infinite alternate;
}
@keyframes wave { 0% { height:12rpx } 100% { height:52rpx } }
.recording-hint { font-size:24rpx; color:#999 }

/* ── Input bar ── */
.chat-input-bar {
  display:flex; align-items:center; padding:16rpx 20rpx;
  background:#fff; gap:12rpx;
  border-top:2rpx solid #f0f0f0;
  box-shadow:0 -2rpx 8rpx rgba(0,0,0,.04);
}
.voice-main-btn {
  width:72rpx; height:72rpx; display:flex; align-items:center;
  justify-content:center; font-size:36rpx; border-radius:50%;
  background:#f5f5f5; transition:all .2s;
}
.voice-main-btn:active, .voice-main-btn.active {
  background:#FF6B35; transform:scale(.92);
}
.input-wrap { flex:1 }
.chat-input {
  width:100%; height:72rpx; padding:0 24rpx;
  background:#f5f5f5; border-radius:36rpx; font-size:28rpx;
}
.send-main-btn {
  width:72rpx; height:72rpx; display:flex; align-items:center;
  justify-content:center; border-radius:50%;
  background:linear-gradient(135deg,#FF6B35,#FF8C5A);
  transition:all .2s;
}
.send-main-btn:active { transform:scale(.92) }
.send-main-btn.disabled { background:#e0e0e0 }
.send-arrow { color:#fff; font-size:32rpx; font-weight:bold }
</style>
