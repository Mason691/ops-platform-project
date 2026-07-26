<template>
  <view :id="uid" class="mp-launcher" :style="wrapStyle">
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

const appid = 'wx095b91409f8ecbfe'
const uid = 'mpl_' + Math.random().toString(36).slice(2, 8)
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

function injectTag() {
  if (!isWechat()) return

  // 用唯一 ID 精确定位宿主元素
  const el = document.getElementById(uid)
  if (!el) { console.warn('[MpLauncher] container #' + uid + ' not found'); return }

  // 清除旧标签
  if (tagEl && tagEl.parentNode) {
    tagEl.removeEventListener('launch', onLaunch)
    tagEl.removeEventListener('error', onError)
    tagEl.parentNode.removeChild(tagEl)
    tagEl = null
  }

  // 用 DOM API 构建完整标签树
  tagEl = document.createElement('wx-open-launch-weapp')
  tagEl.setAttribute('appid', appid)
  tagEl.setAttribute('path', mpPath.value)
  tagEl.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;z-index:10'

  const tmpl = document.createElement('script')
  tmpl.type = 'text/wxtag-template'
  tmpl.appendChild(document.createTextNode('<style>.mpx-btn{width:100%;height:100%;opacity:0;display:block}</style><div class="mpx-btn"></div>'))

  tagEl.appendChild(tmpl)
  tagEl.addEventListener('launch', onLaunch)
  tagEl.addEventListener('error', onError)

  el.appendChild(tagEl)
}

onMounted(() => {
  // 延迟确保 DOM 完全就绪
  setTimeout(injectTag, 200)
})

watch(() => props.finderUsername, () => {
  setTimeout(injectTag, 200)
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
