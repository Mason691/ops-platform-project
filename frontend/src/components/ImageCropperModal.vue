<template>
  <!-- #ifdef H5 -->
  <view class="cropper-overlay" v-if="visible" @click.self="handleCancel" @touchmove.prevent.stop>
    <view class="cropper-dialog">
      <!-- ═══ 头部 ═══ -->
      <view class="cropper-header">
        <view class="header-left">
          <text class="cropper-title">✂️ 裁剪封面图</text>
          <text class="cropper-badge">800 × 800</text>
        </view>
        <view class="cropper-close" @click="handleCancel">
          <text>✕</text>
        </view>
      </view>

      <!-- ═══ 主体：左裁剪 + 右预览 ═══ -->
      <view class="cropper-body">
        <!-- 左侧：裁剪操作区 -->
        <view class="cropper-main">
          <view class="cropper-wrapper" ref="wrapperRef">
            <img
              ref="cropperImageRef"
              :src="imageUrl"
              alt="封面原图"
              crossorigin="anonymous"
              @load="initCropper"
              @error="handleImageError"
            />
          </view>
        </view>

        <!-- 右侧：实时预览 -->
        <view class="cropper-preview">
          <text class="preview-title">实时预览</text>
          <view class="preview-card">
            <view class="preview-box preview-box--round">
              <view class="preview-canvas" id="previewRound"></view>
            </view>
            <text class="preview-label">圆形</text>
          </view>
          <view class="preview-card">
            <view class="preview-box preview-box--square">
              <view class="preview-canvas" id="previewSquare"></view>
            </view>
            <text class="preview-label">方形</text>
          </view>
        </view>
      </view>

      <!-- ═══ 底部：工具栏 + 操作按钮 ═══ -->
      <view class="cropper-footer">
        <view class="toolbar">
          <view class="toolbar-group">
            <button
              class="tool-btn tool-btn--icon"
              :disabled="zoomLevel <= 0.5"
              @click="zoomOut"
            >−</button>
            <text class="zoom-text">{{ Math.round(zoomLevel * 100) }}%</text>
            <button
              class="tool-btn tool-btn--icon"
              :disabled="zoomLevel >= 3"
              @click="zoomIn"
            >+</button>
          </view>
          <view class="toolbar-divider"></view>
          <view class="toolbar-group">
            <button class="tool-btn tool-btn--text" @click="rotateLeft">↺ 左转</button>
            <button class="tool-btn tool-btn--text" @click="rotateRight">↻ 右转</button>
            <button class="tool-btn tool-btn--text" @click="resetCrop">↩ 重置</button>
          </view>
        </view>
        <view class="action-group">
          <button class="action-btn action-btn--cancel" @click="handleCancel">取消</button>
          <button class="action-btn action-btn--confirm" @click="handleConfirm">确认裁剪</button>
        </view>
      </view>
    </view>
  </view>
  <!-- #endif -->

  <!-- #ifndef H5 -->
  <view v-if="visible">
    <text>裁剪功能仅支持 PC 浏览器</text>
  </view>
  <!-- #endif -->
</template>

<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'
import Cropper from 'cropperjs'
import 'cropperjs/dist/cropper.css'

// ==================== Props & Emits ====================
const props = defineProps({
  visible: { type: Boolean, default: false },
  imageUrl: { type: String, default: '' },
})

const emit = defineEmits(['close', 'confirm', 'error'])

// ==================== Refs ====================
const cropperImageRef = ref(null)
// preview refs — use id selectors (uni-view custom elements)
//   see updatePreview() below

// ==================== State ====================
let cropper = null
let initialized = false
const zoomLevel = ref(1)

// ==================== Preview ====================
function updatePreview() {
  if (!cropper || !initialized) return

  try {
    const roundCanvas = cropper.getCroppedCanvas({ width: 150, height: 150 })
    const squareCanvas = cropper.getCroppedCanvas({ width: 150, height: 150 })

    const roundEl = document.getElementById("previewRound")
    const squareEl = document.getElementById("previewSquare")

    if (roundEl) {
      roundEl.innerHTML = ''
      if (roundCanvas) roundEl.appendChild(roundCanvas)
    }
    if (squareEl) {
      squareEl.innerHTML = ''
      if (squareCanvas) squareEl.appendChild(squareCanvas)
    }
  } catch { /* silently skip preview errors */ }
}

// ==================== Cropper Init ====================
function initCropper() {
  destroyCropper()

  const img = cropperImageRef.value
  if (!img) return

  nextTick(() => {
    try {
      cropper = new Cropper(img, {
        // ── Core constraints ──
        aspectRatio: 1,
        viewMode: 1,
        autoCropArea: 1,
        cropBoxMovable: false,
        cropBoxResizable: false,
        dragMode: 'move',

        // ── Visual ──
        guides: true,
        background: true,
        modal: true,
        center: true,
        highlight: false,

        // ── Interaction ──
        movable: true,
        zoomable: true,
        zoomOnWheel: true,
        zoomOnTouch: true,
        scalable: true,
        rotatable: true,
        toggleDragModeOnDblclick: false,
        responsive: true,
        restore: false,

        // ── Lifecycle ──
        ready() {
          cropper.zoomTo(2)
          zoomLevel.value = cropper.getImageData().width
            ? Math.round((cropper.getCanvasData().naturalWidth / cropper.getImageData().naturalWidth) * 100) / 100
            : cropper.getData().width > 0
              ? cropper.getData().width / 800
              : 2
          updatePreview()
        },

        crop() {
          updatePreview()
        },

        zoom(event) {
          zoomLevel.value = event.detail.ratio
        },
      })
      initialized = true
    } catch (e) {
      console.error('[ImageCropper] Init failed:', e)
      emit('error', e)
    }
  })
}

// ==================== Toolbar Actions ====================
function zoomIn() {
  if (!cropper) return
  cropper.zoom(0.1)
}

function zoomOut() {
  if (!cropper) return
  cropper.zoom(-0.1)
}

function rotateLeft() {
  if (!cropper) return
  cropper.rotate(-90)
}

function rotateRight() {
  if (!cropper) return
  cropper.rotate(90)
}

function resetCrop() {
  if (!cropper) return
  cropper.reset()
  cropper.zoomTo(2)
}

// ==================== Confirm / Cancel ====================
function handleConfirm() {
  if (!cropper || !initialized) {
    uni.showToast({ title: '裁剪未就绪，请稍候', icon: 'none' })
    return
  }
  try {
    const canvas = cropper.getCroppedCanvas({
      width: 800,
      height: 800,
      imageSmoothingEnabled: true,
      imageSmoothingQuality: 'high',
    })
    if (!canvas) {
      uni.showToast({ title: '生成图片失败', icon: 'none' })
      return
    }
    canvas.toBlob((blob) => {
      if (!blob) {
        uni.showToast({ title: '图片导出失败', icon: 'none' })
        return
      }
      emit('confirm', blob)
    }, 'image/jpeg', 0.92)
  } catch (e) {
    console.error('[ImageCropper] Crop failed:', e)
    uni.showToast({ title: '裁剪失败', icon: 'none' })
  }
}

function handleCancel() {
  emit('close')
}

function handleImageError() {
  uni.showToast({ title: '图片加载失败', icon: 'none' })
  emit('error', new Error('Image load failed'))
}

// ==================== Destroy ====================
function destroyCropper() {
  if (cropper) {
    try { cropper.destroy() } catch {}
    cropper = null
    initialized = false
    zoomLevel.value = 1
  }
}

// ==================== Watchers ====================
watch(() => props.visible, (v) => { if (!v) destroyCropper() })
watch(() => props.imageUrl, (n, o) => {
  if (n !== o && cropper && initialized) cropper.replace(n)
})

// ==================== Lifecycle ====================
onBeforeUnmount(() => destroyCropper())
</script>

<style scoped>
/* ═══════════════════════════════════════════
   OVERLAY
   ═══════════════════════════════════════════ */
.cropper-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  animation: fadeIn 0.2s ease;
  backdrop-filter: blur(4px);
}

@keyframes fadeIn {
  from { opacity: 0; }
  to   { opacity: 1; }
}

/* ═══════════════════════════════════════════
   DIALOG SHELL
   ═══════════════════════════════════════════ */
.cropper-dialog {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.18);
  width: 1060px;
  max-width: 97vw;
  max-height: 96vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: slideUp 0.25s ease;
}

@keyframes slideUp {
  from { transform: translateY(36px); opacity: 0; }
  to   { transform: translateY(0);    opacity: 1; }
}

/* ═══════════════════════════════════════════
   HEADER
   ═══════════════════════════════════════════ */
.cropper-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cropper-title {
  font-size: 17px;
  font-weight: 600;
  color: #1f2937;
}

.cropper-badge {
  font-size: 12px;
  font-weight: 500;
  color: #FF6B35;
  background: #FFF3EE;
  padding: 3px 10px;
  border-radius: 10px;
  border: 1px solid #ffe0d0;
}

.cropper-close {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #9ca3af;
  font-size: 17px;
  transition: all 0.15s;
}
.cropper-close:hover {
  background: #f3f4f6;
  color: #1f2937;
}

/* ═══════════════════════════════════════════
   BODY — left crop + right preview
   ═══════════════════════════════════════════ */
.cropper-body {
  flex: 1;
  display: flex;
  gap: 24px;
  padding: 24px;
  background: #fafbfc;
  overflow: hidden;
  min-height: 0;
}

/* ── Left: crop area ── */
.cropper-main {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cropper-wrapper {
  width: 800px;
  height: 800px;
  background: #fff;
  border: 2px dashed #d1d5db;
  border-radius: 8px;
  overflow: hidden;
  flex-shrink: 0;
}

.cropper-wrapper :deep(img) {
  display: block;
  max-width: 100%;
  max-height: 100%;
}

/* ── Right: preview panel ── */
.cropper-preview {
  flex: none;
  width: 180px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding-top: 8px;
}

.preview-title {
  font-size: 13px;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.preview-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.preview-box {
  width: 140px;
  height: 140px;
  border: 2px solid #e5e7eb;
  background: #fff;
  overflow: hidden;
  transition: border-color 0.2s;
}
.preview-box:hover { border-color: #FF6B35; }

.preview-box--round    { border-radius: 50%; }
.preview-box--square   { border-radius: 8px;  }

.preview-canvas {
  width: 100%;
  height: 100%;
}
.preview-canvas :deep(canvas) {
  width: 100%;
  height: 100%;
}

.preview-label {
  font-size: 12px;
  color: #9ca3af;
}

/* ═══════════════════════════════════════════
   FOOTER — toolbar + action buttons
   ═══════════════════════════════════════════ */
.cropper-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  border-top: 1px solid #f0f0f0;
  background: #fff;
  flex-shrink: 0;
}

/* ── Toolbar ── */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-group {
  display: flex;
  align-items: center;
  gap: 4px;
}

.toolbar-divider {
  width: 1px;
  height: 24px;
  background: #e5e7eb;
}

.tool-btn {
  border: 1px solid #e5e7eb;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  font-family: inherit;
  transition: all 0.15s;
  outline: none;
  padding: 6px 0;
  color: #374151;
}
.tool-btn:hover  { background: #f9fafb; border-color: #d1d5db; }
.tool-btn:active { background: #f3f4f6; }

.tool-btn--icon {
  width: 34px;
  font-size: 18px;
  font-weight: 600;
  line-height: 1;
}

.tool-btn--text {
  padding: 6px 12px;
}

.tool-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.zoom-text {
  display: inline-block;
  min-width: 42px;
  text-align: center;
  font-size: 13px;
  font-weight: 600;
  color: #6b7280;
}

/* ── Action buttons ── */
.action-group {
  display: flex;
  gap: 10px;
}

.action-btn {
  padding: 9px 28px;
  font-size: 14px;
  font-weight: 500;
  border-radius: 6px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s;
  outline: none;
  font-family: inherit;
  line-height: 1.5;
}

.action-btn--cancel {
  background: #fff;
  color: #6b7280;
  border-color: #d1d5db;
}
.action-btn--cancel:hover {
  color: #FF6B35;
  border-color: #FF6B35;
  background: #fff8f5;
}

.action-btn--confirm {
  background: #FF6B35;
  color: #fff;
  border-color: #FF6B35;
}
.action-btn--confirm:hover {
  background: #e55a2b;
  border-color: #e55a2b;
}

/* ═══════════════════════════════════════════
   CROPPER INTERNAL STYLE OVERRIDES
   ═══════════════════════════════════════════ */
:deep(.cropper-crop-box) {
  outline: 2px dashed rgba(255, 255, 255, 0.9) !important;
}

:deep(.cropper-point) {
  display: none !important;
}

:deep(.cropper-dashed) {
  border-color: rgba(255, 255, 255, 0.45) !important;
}

:deep(.cropper-center) {
  display: block !important;
  opacity: 0.5;
}

/* ═══════════════════════════════════════════
   RESPONSIVE
   ═══════════════════════════════════════════ */
@media (max-width: 1100px) {
  .cropper-dialog { width: 97vw; }

  .cropper-body {
    flex-direction: column;
    align-items: center;
    overflow-y: auto;
  }

  .cropper-wrapper {
    width: 80vw;
    height: 80vw;
    max-width: 600px;
    max-height: 600px;
  }

  .cropper-preview {
    flex-direction: row;
    width: auto;
    gap: 24px;
    padding-top: 0;
  }

  .cropper-footer {
    flex-wrap: wrap;
    gap: 10px;
  }
}

@media (max-width: 640px) {
  .cropper-header { padding: 14px 16px; }
  .cropper-body   { padding: 16px; }
  .cropper-footer { padding: 12px 16px; }

  .cropper-wrapper {
    width: 88vw;
    height: 88vw;
    max-width: 360px;
    max-height: 360px;
  }

  .cropper-preview { gap: 16px; }
  .preview-box { width: 100px; height: 100px; }

  .toolbar { gap: 6px; }
  .toolbar-group { gap: 2px; }
  .action-btn { padding: 8px 18px; }
}
</style>
