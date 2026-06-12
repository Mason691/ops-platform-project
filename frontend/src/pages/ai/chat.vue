<template>
  <view class="ai-chat-page">
    <!-- Messages -->
    <scroll-view class="chat-body" scroll-y :scroll-top="scrollTop"
                 :scroll-with-animation="true" :enhanced="true"
                 :show-scrollbar="false">
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

      <view class="msg-item" v-for="(m, i) in messages" :key="i"
            :class="m.role === 'assistant' ? 'msg-left' : 'msg-right'">
        <image v-if="m.role === 'assistant'" class="msg-avatar"
               :src="aiAvatar" mode="aspectFill" />
        <view class="msg-main">
          <view class="msg-bubble"
                :class="m.role === 'assistant' ? 'bubble-ai' : 'bubble-user'">
            <text class="msg-text" :selectable="true">{{ m.content }}</text>
          </view>
          <view v-if="m.role === 'assistant' && voiceEnabled" class="msg-actions">
            <view class="voice-btn-sm" @tap="playVoice(m, i)">
              <text class="voice-icon">{{ playingIdx === i ? '⏹' : '🔈' }}</text>
            </view>
          </view>
        </view>
        <image v-if="m.role === 'user'" class="msg-avatar"
               :src="userAvatar" mode="aspectFill" />
      </view>

      <view v-if="loading" class="msg-item msg-left">
        <image class="msg-avatar" :src="aiAvatar" mode="aspectFill" />
        <view class="msg-bubble bubble-ai typing-bubble">
          <view class="typing-dots"><view class="dot" v-for="d in 3" :key="d"></view></view>
        </view>
      </view>

      <!-- bottom spacer so last message isn't hidden behind quick bar -->
      <view style="height:12rpx"></view>
    </scroll-view>

    <view class="quick-bar" v-if="quickQuestions.length && messages.length <= 2">
      <scroll-view scroll-x="true" :show-scrollbar="false" class="quick-scroll">
        <view class="quick-chip" v-for="(q, i) in quickQuestions" :key="i"
              @tap="sendQuick(q)">{{ q }}</view>
      </scroll-view>
    </view>

    <view class="recording-overlay" v-if="recording">
      <view class="recording-popup">
        <view class="recording-wave">
          <view class="wave-bar" v-for="b in 7" :key="b"
                :style="{ animationDelay: (b * 0.12) + 's' }"></view>
        </view>
        <text class="recording-hint">{{ recordingHint }}</text>
      </view>
    </view>

    <view class="chat-input-bar">
      <view class="voice-main-btn"
            @touchstart="onMicDown" @touchend="onMicUp"
            @mousedown="onMicDown" @mouseup="onMicUp"
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
import { onShow } from '@dcloudio/uni-app'
import { api } from '@/api/index.js'

const aiNickname    = ref('小金')
const aiAvatar      = ref('/static/ai-avatar.png')
const userAvatar    = ref('/static/default-avatar.png')
const messages      = ref([])
const inputText     = ref('')
const loading       = ref(false)
const scrollTop     = ref(0)
const recording     = ref(false)
const recordingHint = ref('松开发送，上滑取消')
const playingIdx    = ref(-1)
const voiceEnabled  = ref(true)
const quickQuestions = ref([
  '有什么优惠活动？', '推荐几款燃气灶',
  '我的积分有多少？', '如何兑换商品？',
])
let conversationId = null
let holdTimer = null
let cancelled = false
let mediaRecorder = null
let audioStream = null
let audioChunks = []
let currentAudio = null

onShow(async () => {
  const token = uni.getStorageSync('ops_token')
  if (token) {
    try { const me = await api.getMe(); if (me?.avatar) userAvatar.value = me.avatar } catch (_) {}
  }
})

onMounted(async () => {
  const token = uni.getStorageSync('ops_token')
  try {
    const cfg = await api.aiConfig()
    if (cfg?.nickname) aiNickname.value = cfg.nickname
    if (cfg?.voiceEnabled !== undefined) voiceEnabled.value = cfg.voiceEnabled
    if (cfg?.quickQuestions) quickQuestions.value = cfg.quickQuestions
  } catch (_) {}

  try {
    if (token) {
      const r = await request({ url: '/api/ops/ai/profile', method: 'GET' })
      if (r?.greeting) messages.value.push({ role: 'assistant', content: r.greeting })
      else messages.value.push({ role: 'assistant', content: '您好！我是小金，您的燃气生活助手～\n可以帮您查积分、兑商品、看活动，有什么需要？' })
    }
  } catch (_) {}

  try {
    if (token) { const me = await api.getMe(); if (me?.avatar) userAvatar.value = me.avatar }
  } catch (_) {}
})

// ── Text ──
async function sendMsg() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true; scrollToBottom()
  try {
    const res = await api.aiChat({ message: text, conversationId })
    const reply = res?.reply || res?.data?.reply || '抱歉，AI 服务暂时不可用。'
    messages.value.push({ role: 'assistant', content: reply })
    if (res?.conversationId) conversationId = res.conversationId
  } catch (_) {
    messages.value.push({ role: 'assistant', content: '网络异常，请稍后重试。' })
  } finally { loading.value = false; scrollToBottom() }
}

function sendQuick(q) { inputText.value = q; sendMsg() }

// ── Voice input (hold-to-record with timing gate) ──
// Key fix: getUserMedia is async. A quick tap fires touchstart then
// touchend before mic permission is granted → recorder never starts.
// Solution: 300ms hold threshold. Only start mic after hold confirmed.

function onMicDown(e) {
  e.preventDefault()
  cancelled = false
  recording.value = true
  recordingHint.value = '松开发送，上滑取消'

  // 300ms gate — user must hold for 300ms before recording actually starts
  holdTimer = setTimeout(() => {
    holdTimer = null
    startRecording()
  }, 300)
}

function onMicUp(e) {
  e.preventDefault()
  // If still in timer phase (quick tap) — cancel
  if (holdTimer) {
    clearTimeout(holdTimer)
    holdTimer = null
    recording.value = false
    return
  }
  // Actually recording — stop
  stopRecording()
}

function startRecording() {
  console.log('[Voice] 开始录音...')
  try {
    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
        if (cancelled) { stream.getTracks().forEach(t => t.stop()); return }
        audioStream = stream
        try {
          const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
            ? 'audio/webm;codecs=opus' : 'audio/webm'
          mediaRecorder = new MediaRecorder(stream, { mimeType })
        } catch (_) {
          mediaRecorder = new MediaRecorder(stream)
        }
        audioChunks = []
        mediaRecorder.ondataavailable = e => { if (e.data.size > 0) audioChunks.push(e.data) }
        mediaRecorder.onstop = () => {
          if (audioStream) { audioStream.getTracks().forEach(t => t.stop()); audioStream = null }
          if (cancelled) return
          const blob = new Blob(audioChunks, { type: mediaRecorder.mimeType || 'audio/webm' })
          processAudio(blob)
        }
        mediaRecorder.onerror = () => {
          recording.value = false
          uni.showToast({ title: '录音出错，请重试', icon: 'none' })
        }
        mediaRecorder.start(250) // 250ms timeslice for dataavailable events
        console.log('[Voice] 录音中...')
      }).catch(err => {
        console.warn('[Voice] 麦克风权限被拒绝:', err.message)
        recording.value = false
        uni.showToast({ title: '麦克风权限未开启，请在设置中允许', icon: 'none' })
      })
    } else {
      recording.value = false
      uni.showToast({ title: '当前浏览器不支持录音', icon: 'none' })
    }
  } catch (e) {
    console.error('[Voice] 录音启动异常:', e)
    recording.value = false
  }
}

function stopRecording() {
  console.log('[Voice] 停止录音')
  if (mediaRecorder && mediaRecorder.state === 'recording') {
    mediaRecorder.stop()
  } else {
    // Recorder not started yet (still in mic prompt), mark cancelled
    cancelled = true
    recording.value = false
  }
}

async function processAudio(blob) {
  recording.value = false
  recordingHint.value = '识别中...'
  uni.showToast({ title: '识别中...', icon: 'loading', duration: 3000 })
  try {
    const r = await api.aiVoiceRecognize(blob)
    uni.hideToast()
    const text = r?.text || ''
    if (text) {
      messages.value.push({ role: 'user', content: text })
      loading.value = true; scrollToBottom()
      try {
        const res = await api.aiChat({ message: text, conversationId })
        const reply = res?.reply || res?.data?.reply || '抱歉，AI 服务暂时不可用。'
        messages.value.push({ role: 'assistant', content: reply })
        if (res?.conversationId) conversationId = res.conversationId
      } catch (_) {
        messages.value.push({ role: 'assistant', content: '网络异常，请稍后重试。' })
      } finally { loading.value = false; scrollToBottom() }
    } else {
      uni.showToast({ title: '未识别到语音内容', icon: 'none' })
    }
  } catch (e) {
    uni.hideToast()
    console.warn('[Voice] ASR失败:', e)
    uni.showToast({ title: '语音识别失败，请重试', icon: 'none' })
  }
}

// ── Voice playback ──
async function playVoice(msg, i) {
  if (playingIdx.value >= 0) {
    if (playingIdx.value === i) { stopPlayback(); return }
    stopPlayback()
  }
  playingIdx.value = i
  try {
    const r = await api.aiVoiceSynthesize({ text: msg.content })
    if (r?.audioUrl) { msg._audioUrl = r.audioUrl; playAudioUrl(r.audioUrl, i); return }
  } catch (_) {}
  // Fallback: browser SpeechSynthesis
  if (window.speechSynthesis) {
    window.speechSynthesis.cancel()
    const u = new SpeechSynthesisUtterance(msg.content)
    u.lang = 'zh-CN'; u.rate = 1.0; u.pitch = 1.1
    u.onend = () => { playingIdx.value = -1 }
    u.onerror = () => { playingIdx.value = -1 }
    window.speechSynthesis.speak(u)
  } else { playingIdx.value = -1; uni.showToast({ title: '语音播报不可用', icon: 'none' }) }
}

function playAudioUrl(url, i) {
  try {
    currentAudio = new Audio(url)
    playingIdx.value = i
    currentAudio.onended = () => { playingIdx.value = -1; currentAudio = null }
    currentAudio.onerror = () => { playingIdx.value = -1; currentAudio = null }
    currentAudio.play()
  } catch (_) { playingIdx.value = -1 }
}

function stopPlayback() {
  if (currentAudio) { currentAudio.pause(); currentAudio = null }
  window.speechSynthesis?.cancel()
  playingIdx.value = -1
}

function scrollToBottom() {
  nextTick(() => { setTimeout(() => { scrollTop.value = 999999 }, 150) })
}

function request(opts) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('ops_token') || ''
    uni.request({
      url: '/api' + opts.url, method: opts.method || 'GET', data: opts.data,
      header: token ? { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' },
      success(r) { if (r.data?.code === 200) resolve(r.data.data); else reject(r.data) },
      fail: reject
    })
  })
}
</script>

<style scoped>
.ai-chat-page{
  display:flex; flex-direction:column; height:100%; overflow:hidden;
  background:#f0f2f5;
}
/* welcome */
.welcome-card{
  display:flex; flex-direction:column; align-items:center;
  padding:64rpx 48rpx 48rpx; margin:24rpx; background:#fff;
  border-radius:24rpx; box-shadow:0 2rpx 16rpx rgba(0,0,0,.06);
}
.wc-avatar{margin-bottom:20rpx}
.wc-avatar-img{width:120rpx;height:120rpx;border-radius:50%;border:4rpx solid #FF6B35}
.wc-title{font-size:38rpx;color:#333;font-weight:700;margin-bottom:8rpx}
.wc-desc{font-size:26rpx;color:#999;margin-bottom:24rpx}
.wc-features{display:flex;flex-wrap:wrap;gap:12rpx;justify-content:center}
.wc-feat{display:flex;align-items:center;gap:6rpx;padding:10rpx 20rpx;background:#FFF3EE;border-radius:12rpx;font-size:24rpx;color:#FF6B35}
/* chat area — flex:1 with height:0 is the key trick for uni-app H5 */
.chat-body{flex:1;height:0;padding:16rpx 24rpx;overflow-y:auto}
.msg-item{display:flex;align-items:flex-start;margin-bottom:24rpx}
.msg-left{flex-direction:row}.msg-right{flex-direction:row-reverse}
.msg-avatar{width:72rpx;height:72rpx;border-radius:50%;flex-shrink:0;background:#fff;box-shadow:0 2rpx 8rpx rgba(0,0,0,.08)}
.msg-left .msg-avatar{margin-right:16rpx}.msg-right .msg-avatar{margin-left:16rpx}
.msg-main{display:flex;flex-direction:column;max-width:72%}
.msg-bubble{padding:20rpx 24rpx;border-radius:16rpx;box-shadow:0 2rpx 8rpx rgba(0,0,0,.04)}
.msg-text{font-size:28rpx;line-height:1.6;word-break:break-all;white-space:pre-wrap}
.bubble-ai{background:#fff;color:#333;border-radius:4rpx 16rpx 16rpx 16rpx}
.bubble-user{background:linear-gradient(135deg,#FF6B35,#FF8C5A);color:#fff;border-radius:16rpx 4rpx 16rpx 16rpx}
.msg-actions{display:flex;padding-top:8rpx}
.voice-btn-sm{width:48rpx;height:48rpx;display:flex;align-items:center;justify-content:center;font-size:28rpx;opacity:.6}
.voice-btn-sm:active{opacity:1;transform:scale(1.15)}
.typing-bubble{padding:24rpx 36rpx}.typing-dots{display:flex;gap:8rpx}
.dot{width:14rpx;height:14rpx;background:#ccc;border-radius:50%;animation:bounce 1.4s infinite both}
.dot:nth-child(2){animation-delay:.2s}.dot:nth-child(3){animation-delay:.4s}
@keyframes bounce{0%,80%,100%{transform:scale(.6);opacity:.4}40%{transform:scale(1);opacity:1}}
/* quick bar */
.quick-bar{flex-shrink:0;padding:16rpx 24rpx;background:transparent}
.quick-scroll{white-space:nowrap;display:flex;gap:12rpx}
.quick-chip{display:inline-flex;padding:14rpx 24rpx;background:#fff;border-radius:32rpx;font-size:26rpx;color:#FF6B35;box-shadow:0 2rpx 8rpx rgba(0,0,0,.05);transition:all .2s}
.quick-chip:active{background:#FF6B35;color:#fff;transform:scale(.96)}
/* recording */
.recording-overlay{position:fixed;inset:0;background:rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center;z-index:999}
.recording-popup{background:#fff;border-radius:24rpx;padding:48rpx 64rpx;display:flex;flex-direction:column;align-items:center;gap:24rpx;box-shadow:0 16rpx 48rpx rgba(0,0,0,.15)}
.recording-wave{display:flex;align-items:flex-end;gap:6rpx;height:80rpx}
.wave-bar{width:8rpx;background:linear-gradient(180deg,#FF6B35,#FF8C5A);border-radius:4rpx;animation:wave .8s infinite alternate}
@keyframes wave{0%{height:12rpx}100%{height:52rpx}}
.recording-hint{font-size:24rpx;color:#999}
/* input bar */
.chat-input-bar{display:flex;align-items:center;padding:10rpx 16rpx;background:#fff;gap:10rpx;border-top:2rpx solid #f0f0f0;box-shadow:0 -2rpx 8rpx rgba(0,0,0,.04);flex-shrink:0}
.voice-main-btn{width:64rpx;height:64rpx;display:flex;align-items:center;justify-content:center;font-size:32rpx;border-radius:50%;background:#f5f5f5;transition:all .2s;flex-shrink:0}
.voice-main-btn:active,.voice-main-btn.active{background:#FF6B35;transform:scale(.92)}
.input-wrap{flex:1}
.chat-input{width:100%;height:64rpx;padding:0 20rpx;background:#f5f5f5;border-radius:32rpx;font-size:28rpx}
.send-main-btn{width:64rpx;height:64rpx;display:flex;align-items:center;justify-content:center;border-radius:50%;background:linear-gradient(135deg,#FF6B35,#FF8C5A);transition:all .2s;flex-shrink:0}
.send-main-btn:active{transform:scale(.92)}
.send-main-btn.disabled{background:#e0e0e0}
.send-arrow{color:#fff;font-size:28rpx;font-weight:bold}
</style>
