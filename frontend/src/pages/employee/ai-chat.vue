<template>
  <view class="ai-chat-page">
    <!-- Messages -->
    <scroll-view class="chat-body" scroll-y :scroll-top="scrollTop"
                 :scroll-with-animation="true" :enhanced="true"
                 :show-scrollbar="false">
      <!-- Welcome card (employee-specific) -->
      <view class="welcome-card" v-if="messages.length===0 && !loading">
        <view class="wc-avatar">
          <image class="wc-avatar-img" :src="aiAvatar" mode="aspectFill"/>
        </view>
        <text class="wc-title">{{ aiNickname }}</text>
        <text class="wc-desc">你的业务数据AI助手</text>
        <view class="wc-company" v-if="empCorpName">
          <text>{{ empCorpName }} · {{ empRoleName || '员工' }}</text>
        </view>
        <view class="wc-features">
          <view class="wc-feat"><text>📊</text><text>业绩查询</text></view>
          <view class="wc-feat"><text>💰</text><text>提成明细</text></view>
          <view class="wc-feat"><text>📋</text><text>品类分析</text></view>
          <view class="wc-feat"><text>📈</text><text>趋势报告</text></view>
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
        </view>
      </view>

      <view style="height:12rpx"></view>
    </scroll-view>

    <!-- Recording indicator -->
    <view class="recording-bar" v-if="recording">
      <view class="rec-wave-bars">
        <view class="rw-bar" v-for="b in 5" :key="b"
              :style="{ animationDelay: (b * 0.15) + 's' }"></view>
      </view>
      <text class="rec-text">{{ recordingHint }}</text>
    </view>

    <!-- Quick questions -->
    <view class="quick-bar" v-if="quickQuestions.length && messages.length <= 2">
      <scroll-view scroll-x="true" :show-scrollbar="false" class="quick-scroll">
        <view class="quick-chip" v-for="(q, i) in quickQuestions" :key="i"
              @tap="sendQuick(q)">{{ q }}</view>
      </scroll-view>
    </view>

    <!-- Input bar -->
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
               placeholder="输入业务问题..."
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

    <!-- Employee bottom bar (no tab bar since this is a navigateTo page) -->
    <view class="bottom-bar">
      <view class="bar-item" @click="goBusinessData">
        <text class="bar-icon">📊</text>
        <text class="bar-label">业务数据</text>
      </view>
      <view class="bar-item active">
        <text class="bar-icon">🤖</text>
        <text class="bar-label">AI助手</text>
      </view>
      <view class="bar-item" @click="goBackMember">
        <text class="bar-icon">👤</text>
        <text class="bar-label">返回会员端</text>
      </view>
    </view>

    <!-- No-company fallback -->
    <view class="no-company-overlay" v-if="!hasCompany && !checking">
      <view class="nc-card">
        <text class="nc-icon">🏢</text>
        <text class="nc-title">未关联公司</text>
        <text class="nc-desc">请先选择关联的公司</text>
        <view class="nc-btn" @click="goCompanySelect">去选择公司</view>
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
const scrollTop     = ref(0)
const recording     = ref(false)
const recordingHint = ref('松开发送，上滑取消')
const playingIdx    = ref(-1)
const voiceEnabled  = ref(true)
const quickQuestions = ref([
  '本月销售额多少？', '我的提成明细',
  '各品类销售情况', '帮我分析业务趋势',
])

// Employee context
const empCorpId   = ref(null)
const empCorpName = ref('')
const empRoleName = ref('')
const empRoleType = ref('')
const hasCompany  = ref(false)
const checking    = ref(true)

let conversationId = null
let holdTimer = null
let cancelled = false
let mediaRecorder = null
let audioStream = null
let audioChunks = []
let currentAudio = null

onShow(async () => {
  // Load user avatar
  const savedUser = uni.getStorageSync('ops_member') || uni.getStorageSync('ops_user')
  if (savedUser) {
    try { const su = JSON.parse(savedUser); if (su?.avatar) userAvatar.value = su.avatar } catch (_) {}
  }
  const token = uni.getStorageSync('ops_token')
  if (token) {
    try { const me = await api.getMe(); if (me?.avatar) userAvatar.value = me.avatar } catch (_) {}
  }
})

onMounted(async () => {
  // Load employee company info
  loadEmployeeContext()

  const token = uni.getStorageSync('ops_token')
  if (!token) { checking.value = false; return }

  // Fetch employee AI config
  try {
    const cfg = await api.employeeAiConfig()
    if (cfg?.nickname) aiNickname.value = cfg.nickname
    if (cfg?.voiceEnabled !== undefined) voiceEnabled.value = cfg.voiceEnabled
    if (cfg?.quickQuestions) quickQuestions.value = cfg.quickQuestions
  } catch (_) {}

  // Fetch employee AI profile (personalized greeting)
  try {
    const r = await api.employeeAiProfile()
    if (r?.greeting) messages.value.push({ role: 'assistant', content: r.greeting })
    else messages.value.push({ role: 'assistant', content: '您好！我是您的业务数据AI助手～\n可以帮您查询业绩、分析数据、了解公司政策，有什么需要？' })
    if (r?.corpName) empCorpName.value = r.corpName
    if (r?.roleName) empRoleName.value = r.roleName
  } catch (_) {}

  // Refresh user avatar
  try {
    if (token) { const me = await api.getMe(); if (me?.avatar) userAvatar.value = me.avatar }
  } catch (_) {}
})

function loadEmployeeContext() {
  const active = uni.getStorageSync('ops_employee_active_company')
  if (active) {
    try {
      const c = JSON.parse(active)
      empCorpId.value = c.corpId
      empCorpName.value = c.corpName || ''
      empRoleName.value = c.roleName || ''
      empRoleType.value = c.roleType || ''
      hasCompany.value = !!c.corpId
    } catch (e) {
      hasCompany.value = false
    }
  }
  checking.value = false
}

async function sendMsg() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  if (!hasCompany.value) {
    uni.showToast({ title: '请先选择关联公司', icon: 'none' })
    return
  }

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true; scrollToBottom()

  // Stop any currently playing audio
  stopPlayback()

  try {
    const res = await api.employeeAiChat({
      message: text,
      conversationId,
      employeeContext: {
        corpId: empCorpId.value,
        corpName: empCorpName.value,
        roleName: empRoleName.value,
        roleType: empRoleType.value,
      }
    })
    const reply = res?.reply || res?.data?.reply
    if (reply) {
      const idx = messages.value.length
      messages.value.push({ role: 'assistant', content: reply })
      if (res?.conversationId) conversationId = res.conversationId

      // Auto-play TTS
      if (voiceEnabled.value) autoPlayReply(reply, idx)
    } else {
      messages.value.push({ role: 'assistant', content: '抱歉，AI 助手暂时无响应，请稍后重试。' })
    }
  } catch (e) {
    console.warn('[EmployeeAI] chat失败:', e?.msg || e)
    const errMsg = e?.msg || '请求超时，请检查网络后重试'
    messages.value.push({ role: 'assistant', content: errMsg })
  } finally { loading.value = false; scrollToBottom() }
}

/** Auto-play TTS for assistant reply. */
async function autoPlayReply(text, msgIdx) {
  try {
    const r = await api.aiVoiceSynthesize({ text })
    if (r?.audioUrl) {
      stopPlayback()
      currentAudio = new Audio(r.audioUrl)
      playingIdx.value = msgIdx
      currentAudio.onended = () => { playingIdx.value = -1; currentAudio = null }
      currentAudio.onerror = () => { playingIdx.value = -1; currentAudio = null }
      currentAudio.play().catch(() => { playingIdx.value = -1; currentAudio = null })
      return
    }
  } catch (_) {}
  playingIdx.value = -1
}

function sendQuick(q) { inputText.value = q; sendMsg() }

// ── Voice input ──

let recordingMode = 'media'

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
    try { wxStopRecordAndUpload().catch(() => {}) } catch (_) {}
  }
  if (audioStream) { audioStream.getTracks().forEach(t => t.stop()); audioStream = null }
}

function startRecording() {
  console.log('[EmployeeVoice] 开始录音...')

  if (isWechat() && isWxRecordAvailable()) {
    recordingMode = 'wx'
    console.log('[EmployeeVoice] 使用微信 JSSDK 录音')
    wxStartRecord()
      .then(() => { console.log('[EmployeeVoice] JSSDK 录音已启动') })
      .catch(err => {
        console.warn('[EmployeeVoice] JSSDK 录音启动失败，回退到 getUserMedia:', err)
        recordingMode = 'media'
        startMediaRecording()
      })
    return
  }

  recordingMode = 'media'
  startMediaRecording()
}

function startMediaRecording() {
  console.log('[EmployeeVoice] 使用 getUserMedia 录音')
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
        console.error('[EmployeeVoice] getUserMedia 失败:', err)
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
    console.error('[EmployeeVoice] startMediaRecording 异常:', e)
  }
}

function stopRecording() {
  if (recordingMode === 'media') {
    if (mediaRecorder && mediaRecorder.state === 'recording') { mediaRecorder.stop() }
    else { cancelled = true; recording.value = false }
  } else if (recordingMode === 'wx') {
    cancelled = false
    wxStopRecordAndUpload()
      .then(res => {
        recording.value = false
        console.log('[EmployeeVoice] JSSDK serverId:', res.serverId)
        processWechatVoice(res.serverId)
      })
      .catch(err => {
        console.error('[EmployeeVoice] JSSDK 录音停止失败:', err)
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
    console.error('[EmployeeVoice] ASR 失败:', e)
    const errMsg = e?.msg || '请重试'
    uni.showToast({ title: '语音识别失败：' + errMsg, icon: 'none', duration: 3000 })
  }
}

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
    console.error('[EmployeeVoice] ASR(WeChat) 失败:', e)
    const errMsg = e?.msg || '请重试'
    uni.showToast({ title: '语音识别失败：' + errMsg, icon: 'none', duration: 3000 })
  }
}

// ── Voice toggle ──
async function toggleVoice(msg, i) {
  if (playingIdx.value === i) {
    stopPlayback()
    return
  }
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

// ── Navigation ──
function goBusinessData() {
  uni.navigateTo({ url: '/pages/employee/business-data' })
}
function goBackMember() {
  uni.switchTab({ url: '/pages/index/index' })
}
function goCompanySelect() {
  uni.navigateTo({ url: '/pages/employee/company-select' })
}
</script>

<style scoped>
/* ── Root — full screen, no tab bar ── */
.ai-chat-page{
  position:fixed; top:0; right:0; bottom:0; left:0;
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
.wc-desc{font-size:26rpx;color:#999;margin-bottom:8rpx}
.wc-company{font-size:24rpx;color:#FF6B35;background:#FFF3EE;padding:6rpx 20rpx;border-radius:12rpx;margin-bottom:20rpx}
.wc-company text{color:#FF6B35}
.wc-features{display:flex;flex-wrap:wrap;gap:12rpx;justify-content:center}
.wc-feat{display:flex;align-items:center;gap:6rpx;padding:10rpx 20rpx;background:#FFF3EE;border-radius:12rpx;font-size:24rpx;color:#FF6B35}

/* chat area — leave room for input bar + bottom bar */
.chat-body{flex:1;height:0;padding:16rpx 24rpx 16rpx;overflow-y:auto}
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
@keyframes bounce{0%,80%,100%{transform:scale(.6);opacity:.4}40%{transform:scale(1);opacity:1}}

/* quick bar */
.quick-bar{flex-shrink:0;padding:16rpx 24rpx;background:transparent}
.quick-scroll{white-space:nowrap;display:flex;gap:12rpx}
.quick-chip{display:inline-flex;padding:14rpx 24rpx;background:#fff;border-radius:32rpx;font-size:26rpx;color:#FF6B35;box-shadow:0 2rpx 8rpx rgba(0,0,0,.05);transition:all .2s}
.quick-chip:active{background:#FF6B35;color:#fff;transform:scale(.96)}

/* Recording bar */
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

/* input bar */
.chat-input-bar{display:flex;align-items:center;padding:12rpx 20rpx;background:#fff;gap:12rpx;border-top:2rpx solid #f0f0f0;flex-shrink:0}
.voice-main-btn{
  width:88rpx;height:88rpx;display:flex;align-items:center;justify-content:center;
  border-radius:50%;background:#f5f5f5;transition:all .2s;flex-shrink:0;
  box-shadow:0 2rpx 8rpx rgba(0,0,0,.08);
}
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

/* Employee bottom bar */
.bottom-bar{
  flex-shrink:0; height:110rpx; background:#fff;
  display:flex; align-items:center; justify-content:space-around;
  border-top:1rpx solid #f0f0f0;
}
.bar-item{display:flex;flex-direction:column;align-items:center;gap:4rpx;padding:8rpx 24rpx}
.bar-icon{font-size:40rpx}
.bar-label{font-size:22rpx;color:#999}
.bar-item.active .bar-label{color:#FF6B35;font-weight:600}

/* No-company fallback overlay */
.no-company-overlay{
  position:fixed; top:0; left:0; right:0; bottom:0;
  background:rgba(0,0,0,.45); display:flex; align-items:center; justify-content:center;
  z-index:200;
}
.nc-card{
  background:#fff; border-radius:24rpx; padding:64rpx 48rpx;
  display:flex; flex-direction:column; align-items:center; gap:16rpx;
  margin:48rpx; width:520rpx; max-width:100%;
}
.nc-icon{font-size:80rpx}
.nc-title{font-size:34rpx;color:#333;font-weight:700}
.nc-desc{font-size:28rpx;color:#999}
.nc-btn{
  margin-top:16rpx; padding:20rpx 64rpx; background:linear-gradient(135deg,#FF6B35,#FF8C5A);
  color:#fff; border-radius:40rpx; font-size:30rpx; font-weight:600;
}
</style>
