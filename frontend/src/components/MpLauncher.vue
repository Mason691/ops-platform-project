<template>
  <view ref="container" class="mp-launcher" :style="wrapStyle">
    <slot />
  </view>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'

const props = defineProps({
  finderUsername: { type: String, required: true },
  width: { type: String, default: '' },
  height: { type: String, default: '' }
})

const emit = defineEmits(['launch', 'error'])

const container = ref(null)
const appid = 'wx095b91409f8ecbfe'
let tagEl = null

const isWechat = () => {
  // #ifdef H5
  return /micromessenger/i.test(navigator.userAgent)
  // #endif
  return false
}

const mpPath = computed(() =>
  'pages/channels/live?finderUserName=' + encodeURIComponent(props.finderUsername)
)

const wrapStyle = computed(() => {
  const s = { position: 'relative', display: 'inline-block' }
  if (props.width) { s.width = props.width; s.display = 'block' }
  if (props.height) s.height = props.height
  return s
})

function onLaunch(e) { emit('launch', e) }
function onError(e) { console.error('[MpLauncher] error:', e); emit('error', e) }

function createTag() {
  if (!isWechat() || !container.value) return

  // 获取宿主 DOM 元素
  const el = container.value.$el || container.value
  if (!el || !el.appendChild) return

  // 移除旧标签
  if (tagEl && tagEl.parentNode) {
    tagEl.removeEventListener('launch', onLaunch)
    tagEl.removeEventListener('error', onError)
    tagEl.parentNode.removeChild(tagEl)
    tagEl = null
  }

  // 用 DOM API 创建元素（innerHTML 会丢弃 script 标签）
  tagEl = document.createElement('wx-open-launch-weapp')
  tagEl.setAttribute('appid', appid)
  tagEl.setAttribute('path', mpPath.value)
  tagEl.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;z-index:1'
  tagEl.addEventListener('launch', onLaunch)
  tagEl.addEventListener('error', onError)

  // 创建必需的 <script type="text/wxtag-template"> 子元素
  const script = document.createElement('script')
  script.type = 'text/wxtag-template'
  script.textContent = '<style>.mp-btn{width:100%;height:100%;opacity:0}</style><div class="mp-btn"></div>'
  tagEl.appendChild(script)

  el.appendChild(tagEl)
}

onMounted(() => {
  // 延迟一帧确保 DOM 就绪
  setTimeout(createTag, 100)
})

watch(() => props.finderUsername, () => {
  setTimeout(createTag, 100)
})

onBeforeUnmount(() => {
  if (tagEl) {
    tagEl.removeEventListener('launch', onLaunch)
    tagEl.removeEventListener('error', onError)
    if (tagEl.parentNode) tagEl.parentNode.removeChild(tagEl)
    tagEl = null
  }
})
</script>

<style scoped>
.mp-launcher { position: relative; display: inline-block; }
</style>
