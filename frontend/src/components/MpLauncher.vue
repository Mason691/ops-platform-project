<template>
  <view class="mp-launcher" :style="wrapStyle">
    <slot />
    <wx-open-launch-weapp
      v-if="isWechat"
      :appid="appid"
      :path="mpPath"
      class="mp-overlay"
      @launch="onLaunch"
      @error="onError"
    >
      <script type="text/wxtag-template">
        <style>.mp-tag-btn{width:100%;height:100%;opacity:0}</style>
        <div class="mp-tag-btn"></div>
      </script>
    </wx-open-launch-weapp>
  </view>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  finderUsername: { type: String, required: true },
  width: { type: String, default: '' },
  height: { type: String, default: '' }
})

const emit = defineEmits(['launch', 'error'])

const appid = 'wx095b91409f8ecbfe'

const isWechat = computed(() => {
  // #ifdef H5
  return /micromessenger/i.test(navigator.userAgent)
  // #endif
  return false
})

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
</script>

<style scoped>
.mp-launcher { position: relative; display: inline-block; }
.mp-overlay { position: absolute; top: 0; left: 0; width: 100%; height: 100%; z-index: 1; }
</style>
