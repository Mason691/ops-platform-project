<template>
  <view ref="container" class="mp-launcher" :style="wrapStyle">
    <slot />
  </view>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'

const props = defineProps({
  finderUsername: { type: String, required: true },
  width: { type: String, default: '' },
  height: { type: String, default: '' }
})

const emit = defineEmits(['launch', 'error'])

const container = ref(null)
const appid = 'wx095b91409f8ecbfe'

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

function renderTag() {
  if (!isWechat() || !container.value) return

  const el = container.value.$el || container.value
  const html = '<wx-open-launch-weapp'
    + ' appid="' + appid + '"'
    + ' path="' + mpPath.value + '"'
    + ' style="position:absolute;top:0;left:0;width:100%;height:100%;z-index:1"'
    + '><script type="text/wxtag-template"><style>.mp-tag-btn{width:100%;height:100%;opacity:0}</style><div class="mp-tag-btn"></div><\/script><\/wx-open-launch-weapp>'

  // 移除旧的标签（如果有）
  const old = el.querySelector('wx-open-launch-weapp')
  if (old) old.remove()

  // 用原生 DOM 注入，绕过 Vue 编译器保留 <script> 标签
  const temp = document.createElement('div')
  temp.innerHTML = html
  const tag = temp.firstChild
  if (tag) {
    tag.addEventListener('launch', onLaunch)
    tag.addEventListener('error', onError)
    el.appendChild(tag)
  }
}

onMounted(() => {
  renderTag()
})

watch(() => props.finderUsername, () => {
  renderTag()
})
</script>

<style scoped>
.mp-launcher { position: relative; display: inline-block; }
</style>
