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
        <navigator url="/pages/ai/settings" class="settings-link">⚙️ 个性化设置</navigator>
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
            <view class="voice-btn-sm" :class="{ 'playing': playingIdx === i }" @tap="toggleVoice(m, i)">
              <text class="voice-icon">{{ playingIdx === i ? '⏹' : '🔊' }}</text>
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
          <text class="progress-text" v-if="progressMsg">{{ progressMsg }}</text>
        </view>
      </view>

      <view style="height:12rpx"></view>
    </scroll-view>

    <!-- Recording indicator — small floating bar, NOT a full-screen overlay -->
    <view class="recording-bar" v-if="recording">
      <view class="rec-wave-bars">
        <view class="rw-bar" v-for="b in 5" :key="b"
              :style="{ animationDelay: (b * 0.15) + 's' }"></view>
      </view>
      <text class="rec-text">{{ recordingHint }}</text>
    </view>

    <view class="quick-bar" v-if="quickQuestions.length && messages.length <= 2">
      <scroll-view scroll-x="true" :show-scrollbar="false" class="quick-scroll">
        <view class="quick-chip" v-for="(q, i) in quickQuestions" :key="i"
              @tap="sendQuick(q)">{{ q }}</view>
      </scroll-view>
    </view>

    <view class="chat-input-bar">
      <view class="voice-main-btn" :class="{ active: recording }"
            @touchstart.prevent="onMicDown" @touchend.prevent="onMicUp"
            @mousedown.prevent="onMicDown" @mouseup.prevent="onMicUp"
            @touchcancel="onMicCancel">
        <svg class="mic-svg" viewBox="0 0 24 24" width="44" height="44">
          <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" fill="#666"/>
          <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z" fill="#666"/>
        </svg>
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
import { isWechat, isWxRecordAvailable, wxStartRecord, wxStopRecordAndUpload } from '@/utils/jssdk.js'

const aiNickname    = ref('小金')
const aiAvatar      = ref('/static/ai-avatar.png')
const userAvatar    = ref('/static/default-avatar.png')
const messages      = ref([])
const inputText     = ref('')
const loading       = ref(false)
const progressMsg   = ref('')
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
  // Fast: check locally stored avatar first (updated by mine page after upload)
  // Read avatar from local storage (ops_member is canonical; fallback to legacy ops_user)
  const savedUser = uni.getStorageSync('ops_member') || uni.getStorageSync('ops_user')
  if (savedUser) {
    try { const su = JSON.parse(savedUser); if (su?.avatar) userAvatar.value = su.avatar } catch (_) {}
  }
  if (token) {
    try { const me = await api.getMe(); if (me?.avatar) userAvatar.value = me.avatar } catch (_) {}
  }
})

onMounted(async () => {
  const token = uni.getStorageSync('ops_token')
  try {
    const cfg = await api.aiConfig()
    if (cfg?.nickname) aiNickname.value = cfg.nickname
    if (cfg?.avatar && cfg.avatar.startsWith('http')) aiAvatar.value = cfg.avatar
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

async function sendMsg() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true; progressMsg.value = ''; scrollToBottom()

  // Stop any currently playing audio
  stopPlayback()

  try {
    // 尝试异步模式：提交任务 → SSE/轮询 → 收到回复
    const res = await api.aiChatAsync({ message: text, conversationId })
    const taskId = res?.taskId
    if (taskId) {
      await listenForResult(taskId)
      return
    }
    // taskId 为空 = 服务端降级同步，res 中已含 reply
    if (res?.reply) {
      const idx = messages.value.length
      messages.value.push({ role: 'assistant', content: res.reply })
      if (res?.conversationId) conversationId = res.conversationId
      if (voiceEnabled.value) autoPlayReply(res.reply, idx)
    }
  } catch (e) {
    console.warn('[AI] async失败，回退同步:', e?.msg || e)
    // 回退到同步调用
    await sendSync(text)
  } finally { loading.value = false; progressMsg.value = ''; scrollToBottom() }
}

/** 同步回退：原有逻辑 */
async function sendSync(text) {
  try {
    const res = await api.aiChat({ message: text, conversationId })
    const reply = res?.reply || res?.data?.reply
    if (reply) {
      const idx = messages.value.length
      messages.value.push({ role: 'assistant', content: reply })
      if (res?.conversationId) conversationId = res.conversationId
      if (voiceEnabled.value) autoPlayReply(reply, idx)
    } else {
      messages.value.push({ role: 'assistant', content: '抱歉，AI 助手暂时无响应，请稍后重试。' })
    }
  } catch (e2) {
    console.warn('[AI] 同步也失败:', e2?.msg || e2)
    messages.value.push({ role: 'assistant', content: e2?.msg || '请求超时，请检查网络后重试' })
  }
}

/** 通过轮询监听异步任务结果（H5 下 EventSource 不支持自定义 Header，直接轮询更可靠） */
async function listenForResult(taskId) {
  // 轮询：最多 30 次 × 1s = 30s，足够 Kafka 管道完成
  for (let i = 0; i < 30; i++) {
    await new Promise(r => setTimeout(r, 1000))
    try {
      const status = await api.aiChatStatus(taskId)
      if (status.status === 'COMPLETED') {
        if (status.reply) {
          const idx = messages.value.length
          messages.value.push({ role: 'assistant', content: status.reply })
          if (status.conversationId) conversationId = status.conversationId
          if (voiceEnabled.value && status.reply) autoPlayReply(status.reply, idx)
        }
        return
      } else if (status.status === 'FAILED' || status.status === 'TIMEOUT') {
        messages.value.push({ role: 'assistant', content: status.reply || status.errorMsg || 'AI 处理失败，请稍后重试' })
        return
      }
      if (status.progressMsg) progressMsg.value = status.progressMsg
    } catch (_) { /* 网络抖动，继续轮询 */ }
  }
  // 轮询超时，回退同步
  loading.value = false; progressMsg.value = ''
  messages.value.push({ role: 'assistant', content: 'AI 服务处理超时，请简化问题后重试' })
}

/** Auto-play TTS for assistant reply. Must be called within a user-gesture
 *  chain (i.e., right after sendMsg) so WeChat allows autoplay. */
async function autoPlayReply(text, msgIdx) {
  try {
    const r = await api.aiVoiceSynthesize({ text })
    if (r?.audioUrl) {
      stopPlayback()
      currentAudio = new Audio(r.audioUrl)
      playingIdx.value = msgIdx
      currentAudio.onended = () => { playingIdx.value = -1; currentAudio = null }
      currentAudio.onerror = () => { playingIdx.value = -1; currentAudio = null }
      // .play() rejected → WeChat blocked autoplay; show speaker icon but don't error
      currentAudio.play().catch(() => { playingIdx.value = -1; currentAudio = null })
      return
    }
  } catch (_) {}
  // TTS failed or unavailable: fall through silently, user can tap speaker to retry
  playingIdx.value = -1
}

function sendQuick(q) { inputText.value = q; sendMsg() }

// ── Voice input ──

let recordingMode = 'media' // 'media' | 'wx' — 录音模式

function onMicDown(e) {
  e.preventDefault()
  cancelled = false
  recording.value = true
  recordingHint.value = '松开发送，上滑取消'
  holdTimer = setTimeout(() => { holdTimer = null; startRecording() }, 300)
}

function onMicUp(e) {
  e.preventDefault()
  if (holdTimer) { clearTimeout(holdTimer); holdTimer = null; recording.value = false; return }
  stopRecording()
}

function onMicCancel() {
  cancelled = true
  recording.value = false
  if (holdTimer) { clearTimeout(holdTimer); holdTimer = null }
  if (recordingMode === 'media' && mediaRecorder && mediaRecorder.state === 'recording') {
    mediaRecorder.stop()
  }
  if (recordingMode === 'wx') {
    // 微信 JSSDK 没有 cancel API，只能等待 stopRecord
    try { wxStopRecordAndUpload().catch(() => {}) } catch (_) {}
  }
  if (audioStream) { audioStream.getTracks().forEach(t => t.stop()); audioStream = null }
}

function startRecording() {
  console.log('[Voice] 开始录音...')

  // ── 微信环境优先使用 JSSDK 录音 ──
  if (isWechat() && isWxRecordAvailable()) {
    recordingMode = 'wx'
    console.log('[Voice] 使用微信 JSSDK 录音')
    wxStartRecord()
      .then(() => { console.log('[Voice] JSSDK 录音已启动') })
      .catch(err => {
        console.warn('[Voice] JSSDK 录音启动失败，回退到 getUserMedia:', err)
        recordingMode = 'media'
        startMediaRecording()
      })
    return
  }

  // ── 非微信环境使用 getUserMedia ──
  recordingMode = 'media'
  startMediaRecording()
}

function startMediaRecording() {
  console.log('[Voice] 使用 getUserMedia 录音')
  try {
    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
      navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
        if (cancelled) { stream.getTracks().forEach(t => t.stop()); return }
        audioStream = stream
        const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm'
        try { mediaRecorder = new MediaRecorder(stream, { mimeType: mime }) } catch (_) { mediaRecorder = new MediaRecorder(stream) }
        audioChunks = []
        mediaRecorder.ondataavailable = e => { if (e.data.size > 0) audioChunks.push(e.data) }
        mediaRecorder.onstop = () => {
          if (audioStream) { audioStream.getTracks().forEach(t => t.stop()); audioStream = null }
          if (!cancelled) { const blob = new Blob(audioChunks, { type: mediaRecorder.mimeType || 'audio/webm' }); processAudio(blob) }
        }
        mediaRecorder.onerror = () => { recording.value = false; uni.showToast({ title: '录音出错', icon: 'none' }) }
        mediaRecorder.start(250)
      }).catch(err => {
        console.error('[Voice] getUserMedia 失败:', err)
        recording.value = false
        const errMsg = String(err)
        if (errMsg.includes('NotAllowed') || errMsg.includes('Permission')) {
          uni.showToast({ title: '麦克风权限未开启，请在设置中允许', icon: 'none' })
        } else if (errMsg.includes('NotFound')) {
          uni.showToast({ title: '未检测到麦克风设备', icon: 'none' })
        } else {
          uni.showToast({ title: '录音启动失败，请重试', icon: 'none' })
        }
      })
    } else {
      recording.value = false
      uni.showToast({ title: '当前浏览器不支持录音，请使用微信小程序', icon: 'none', duration: 4000 })
    }
  } catch (e) {
    recording.value = false
    console.error('[Voice] startMediaRecording 异常:', e)
  }
}

function stopRecording() {
  if (recordingMode === 'media') {
    if (mediaRecorder && mediaRecorder.state === 'recording') { mediaRecorder.stop() }
    else { cancelled = true; recording.value = false }
  } else if (recordingMode === 'wx') {
    cancelled = false // JSSDK 需要正常停止
    wxStopRecordAndUpload()
      .then(res => {
        recording.value = false
        console.log('[Voice] JSSDK serverId:', res.serverId)
        processWechatVoice(res.serverId)
      })
      .catch(err => {
        console.error('[Voice] JSSDK 录音停止失败:', err)
        recording.value = false
        uni.showToast({ title: '录音失败，请重试', icon: 'none' })
      })
  } else {
    cancelled = true; recording.value = false
  }
}

async function processAudio(blob) {
  recording.value = false
  uni.showToast({ title: '识别中...', icon: 'loading', duration: 5000 })
  try {
    const r = await api.aiVoiceRecognize(blob)
    uni.hideToast()
    const text = r?.text || ''
    if (text) {
      inputText.value = text
      sendMsg()
    } else { uni.showToast({ title: '未识别到语音内容', icon: 'none' }) }
  } catch (e) {
    uni.hideToast()
    console.error('[Voice] ASR 失败:', e)
    const errMsg = e?.msg || '请重试'
    uni.showToast({ title: '语音识别失败：' + errMsg, icon: 'none', duration: 3000 })
  }
}

/** 微信 JSSDK 录音 → serverId → 后端下载 → ASR */
async function processWechatVoice(serverId) {
  uni.showToast({ title: '识别中...', icon: 'loading', duration: 5000 })
  try {
    const r = await api.aiVoiceRecognizeWechat(serverId)
    uni.hideToast()
    const text = r?.text || ''
    if (text) {
      inputText.value = text
      sendMsg()
    } else { uni.showToast({ title: '未识别到语音内容', icon: 'none' }) }
  } catch (e) {
    uni.hideToast()
    console.error('[Voice] ASR(WeChat) 失败:', e)
    const errMsg = e?.msg || '请重试'
    uni.showToast({ title: '语音识别失败：' + errMsg, icon: 'none', duration: 3000 })
  }
}

// ── Voice toggle (stop active or replay this message) ──
async function toggleVoice(msg, i) {
  if (playingIdx.value === i) {
    // This message is the active one — stop it
    stopPlayback()
    return
  }
  // Stop current, then play this message
  stopPlayback()
  playingIdx.value = i
  try {
    const r = await api.aiVoiceSynthesize({ text: msg.content })
    if (r?.audioUrl) { msg._audioUrl = r.audioUrl; playAudioUrl(r.audioUrl, i); return }
  } catch (_) {}
  // Fallback SpeechSynthesis
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
/* ── Root — fill space above tab bar, adaptive ── */
.ai-chat-page{
  position:fixed; top:0; right:0; bottom:100rpx; left:0;
  display:flex; flex-direction:column;
  background:#f0f2f5;
}
/* welcome card */
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
.settings-link{display:block;text-align:center;margin-top:20rpx;font-size:24rpx;color:#999;text-decoration:none}
/* chat area */
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
.voice-btn-sm{width:48rpx;height:48rpx;display:flex;align-items:center;justify-content:center;font-size:28rpx;opacity:.6;transition:all .15s}
.voice-btn-sm:active{opacity:1;transform:scale(1.15)}
.voice-btn-sm.playing{opacity:1;animation:speaker-pulse .8s infinite}
.voice-btn-sm.playing .voice-icon{transform:scale(1.1)}
@keyframes speaker-pulse{0%,100%{color:#FF6B35}50%{color:#ff8c5a}}
.typing-bubble{padding:24rpx 36rpx}.typing-dots{display:flex;gap:8rpx}
.dot{width:14rpx;height:14rpx;background:#ccc;border-radius:50%;animation:bounce 1.4s infinite both}
.dot:nth-child(2){animation-delay:.2s}.dot:nth-child(3){animation-delay:.4s}
.progress-text{display:block;margin-top:12rpx;font-size:24rpx;color:#999;line-height:1.5}
@keyframes bounce{0%,80%,100%{transform:scale(.6);opacity:.4}40%{transform:scale(1);opacity:1}}
/* quick bar */
.quick-bar{flex-shrink:0;padding:16rpx 24rpx;background:transparent}
.quick-scroll{white-space:nowrap;display:flex;gap:12rpx}
.quick-chip{display:inline-flex;padding:14rpx 24rpx;background:#fff;border-radius:32rpx;font-size:26rpx;color:#FF6B35;box-shadow:0 2rpx 8rpx rgba(0,0,0,.05);transition:all .2s}
.quick-chip:active{background:#FF6B35;color:#fff;transform:scale(.96)}
/* ── Recording bar — compact inline, NO full-screen overlay ── */
.recording-bar{
  display:flex; align-items:center; justify-content:center;
  gap:12rpx; padding:16rpx 24rpx; flex-shrink:0;
  background:linear-gradient(90deg,#FFF3EE,#fff,#FFF3EE);
}
.rec-wave-bars{display:flex;align-items:flex-end;gap:4rpx;height:32rpx}
.rw-bar{
  width:6rpx; background:#FF6B35; border-radius:3rpx;
  animation:rw-wave .6s infinite alternate;
}
@keyframes rw-wave{0%{height:8rpx}100%{height:28rpx}}
.rec-text{font-size:24rpx;color:#FF6B35;font-weight:600}
/* input bar — 2x enlarged for easy tapping */
.chat-input-bar{display:flex;align-items:center;padding:12rpx 20rpx;background:#fff;gap:12rpx;border-top:2rpx solid #f0f0f0;flex-shrink:0}
.voice-main-btn{
  width:88rpx;height:88rpx;display:flex;align-items:center;justify-content:center;
  border-radius:50%;background:#f5f5f5;transition:all .2s;flex-shrink:0;
  box-shadow:0 2rpx 8rpx rgba(0,0,0,.08);
}
/* SVG mic icon — switches to white on active press */
.mic-svg{width:44rpx;height:44rpx}
.mic-svg path{transition:fill .2s}
.voice-main-btn:active,.voice-main-btn.active{background:#FF6B35}
.voice-main-btn:active .mic-svg path,.voice-main-btn.active .mic-svg path{fill:#fff}
.input-wrap{flex:1}
.chat-input{width:100%;height:80rpx;padding:0 28rpx;background:#f5f5f5;border-radius:40rpx;font-size:48rpx}
.send-main-btn{width:88rpx;height:88rpx;display:flex;align-items:center;justify-content:center;border-radius:50%;background:linear-gradient(135deg,#FF6B35,#FF8C5A);transition:all .2s;flex-shrink:0}
.send-main-btn:active{transform:scale(.92)}
.send-main-btn.disabled{background:#e0e0e0}
.send-arrow{color:#fff;font-size:44rpx;font-weight:bold}
</style>
