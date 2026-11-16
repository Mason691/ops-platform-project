<template>
  <text class="animated-amount" :class="{ bump }">{{ prefix }}{{ display }}</text>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'

/**
 * 金额数字滚动动效：数值变化时用 requestAnimationFrame 在 260ms 内
 * 平滑过渡到新值，并轻微放大提示。非 H5 环境退化为直接赋值。
 */
const props = defineProps({
  value: { type: [Number, String], default: 0 },
  prefix: { type: String, default: '¥' },
  duration: { type: Number, default: 260 }
})

const display = ref(format(props.value))
const bump = ref(false)
let frame = null
let timer = null
let current = Number(props.value) || 0

watch(() => props.value, value => {
  const target = Number(value) || 0
  if (typeof requestAnimationFrame !== 'function' || target === current) {
    current = target
    display.value = format(target)
    return
  }
  const from = current
  const start = Date.now()
  cancelAnimationFrame(frame)
  const tick = () => {
    const progress = Math.min(1, (Date.now() - start) / props.duration)
    const eased = 1 - Math.pow(1 - progress, 3)
    current = from + (target - from) * eased
    display.value = format(current)
    if (progress < 1) frame = requestAnimationFrame(tick)
    else {
      current = target
      display.value = format(target)
    }
  }
  frame = requestAnimationFrame(tick)
  bump.value = true
  clearTimeout(timer)
  timer = setTimeout(() => { bump.value = false }, 220)
})

onBeforeUnmount(() => {
  if (frame && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(frame)
  clearTimeout(timer)
})

function format(value) {
  const n = Number(value)
  return (Number.isFinite(n) ? n : 0).toFixed(2)
}
</script>

<style scoped>
.animated-amount {
  font-variant-numeric: tabular-nums;
  transition: transform .18s ease;
  display: inline-block;
}
.animated-amount.bump { transform: scale(1.06); }
</style>
